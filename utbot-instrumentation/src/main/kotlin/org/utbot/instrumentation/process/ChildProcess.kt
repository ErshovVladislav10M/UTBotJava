package org.utbot.instrumentation.process

import com.jetbrains.rd.framework.util.launchChild
import com.jetbrains.rd.util.ILoggerFactory
import com.jetbrains.rd.util.LogLevel
import com.jetbrains.rd.util.Logger
import com.jetbrains.rd.util.defaultLogFormat
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.plusAssign
import kotlinx.coroutines.*
import org.utbot.common.getCurrentProcessId
import org.utbot.common.scanForClasses
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.instrumentation.agent.Agent
import org.utbot.instrumentation.instrumentation.Instrumentation
import org.utbot.instrumentation.instrumentation.coverage.CoverageInstrumentation
import org.utbot.instrumentation.rd.childCreatedFileName
import org.utbot.instrumentation.rd.generated.CollectCoverageResult
import org.utbot.instrumentation.rd.generated.InvokeMethodCommandResult
import org.utbot.instrumentation.rd.obtainClientIO
import org.utbot.instrumentation.rd.processSyncDirectory
import org.utbot.instrumentation.rd.signalChildReady
import org.utbot.instrumentation.util.KryoHelper
import org.utbot.rd.UtRdUtil
import org.utbot.rd.UtSingleThreadScheduler
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * We use this ClassLoader to separate user's classes and our dependency classes.
 * Our classes won't be instrumented.
 */
internal object HandlerClassesLoader : URLClassLoader(emptyArray()) {
    fun addUrls(urls: Iterable<String>) {
        urls.forEach { super.addURL(File(it).toURI().toURL()) }
    }

    /**
     * System classloader can find org.slf4j thus when we want to mock something from org.slf4j
     * we also want this class will be loaded by [HandlerClassesLoader]
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("org.slf4j")) {
            return (findLoadedClass(name) ?: findClass(name)).apply {
                if (resolve) resolveClass(this)
            }
        }
        return super.loadClass(name, resolve)
    }
}

typealias ChildProcessLogLevel = LogLevel

private val logLevel = ChildProcessLogLevel.Trace

// Logging
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private fun log(level: ChildProcessLogLevel, any: () -> Any?) {
    if (level < logLevel)
        return

    System.err.println(LocalDateTime.now().format(dateFormatter) + " | ${any()}")
}

private fun logError(any: () -> Any?) {
    log(ChildProcessLogLevel.Error, any)
}

private fun logInfo(any: () -> Any?) {
    log(ChildProcessLogLevel.Info, any)
}

private fun logTrace(any: () -> Any?) {
    log(ChildProcessLogLevel.Trace, any)
}

private val executionStart = AtomicLong(1)
private val executionEnd = AtomicLong(0)
private val messageFromMainTimeoutMillis = 120 * 1000

/**
 * It should be compiled into separate jar file (child_process.jar) and be run with an agent (agent.jar) option.
 */
suspend fun main(args: Array<String>) {
    // 0 - auto port for server, should not be used here
    val port = args.find { it.startsWith(serverPortProcessArgumentTag) }
        ?.run { split("=").last().toInt().coerceIn(1..65535) }
        ?: throw IllegalArgumentException("No port provided")

    val pid = getCurrentProcessId()
    val def = LifetimeDefinition()

    GlobalScope.launchChild(Lifetime.Eternal, Dispatchers.Unconfined) {
        while (true) {
            val now = System.currentTimeMillis()
            val start = executionStart.get()
            val end = executionEnd.get()

            if (start > end) { // process is doing something
                delay(1000)
            } else { // process is waiting for message
                if (now - end > messageFromMainTimeoutMillis) {
                    logInfo { "terminating lifetime" }
                    def.terminate()
                    break
                } else {
                    delay(1000)
                }
            }
        }
    }

    def.usingNested { lifetime ->
        lifetime += { logInfo { "lifetime terminated" } }
        try {
            initiate(lifetime, port, pid.toInt())
        } finally {
            val syncFile = File(processSyncDirectory, childCreatedFileName(pid.toInt()))

            if (syncFile.exists()) {
                logInfo { "sync file existed" }
                syncFile.delete()
            }
        }
    }
}

fun <T> measureExecutionForTermination(block: () -> T): T {
    try {
        executionStart.set(System.currentTimeMillis())
        return block()
    }
    finally {
        executionEnd.set(System.currentTimeMillis())
    }
}

private lateinit var pathsToUserClasses: Set<String>
private lateinit var pathsToDependencyClasses: Set<String>
private lateinit var instrumentation: Instrumentation<*>

private suspend fun initiate(lifetime: Lifetime, port: Int, pid: Int) {
    // We don't want user code to litter the standard output, so we redirect it.
    val tmpStream = PrintStream(object : OutputStream() {
        override fun write(b: Int) {}
    })
    System.setOut(tmpStream)

    Logger.set(lifetime, object : ILoggerFactory {
        override fun getLogger(category: String) = object : Logger {
            override fun isEnabled(level: LogLevel): Boolean {
                return level >= logLevel
            }

            override fun log(level: LogLevel, message: Any?, throwable: Throwable?) {
                val msg = defaultLogFormat(category, level, message, throwable)

                log(logLevel) { msg }
            }

        }
    })

    val def = CompletableDeferred<Unit>()
    val kryoHelper = KryoHelper(lifetime)
    logInfo { "kryo created" }

    val clientProtocol = UtRdUtil.createUtClientProtocol(lifetime, port, UtSingleThreadScheduler { logInfo(it) })
    logInfo {
        "hearthbeatAlive - ${clientProtocol.wire.heartbeatAlive.value}, connected - ${
            clientProtocol.wire.connected.value
        }"
    }
    val (sync, protocolModel) = obtainClientIO(lifetime, clientProtocol, pid)
    protocolModel.warmup.set { _ ->
        measureExecutionForTermination {
            val time = measureTimeMillis {
                HandlerClassesLoader.scanForClasses("").toList() // here we transform classes
            }
            logInfo { "warmup finished in $time ms" }
        }
    }
    protocolModel.invokeMethodCommand.set { params ->
        measureExecutionForTermination {
            val clazz = HandlerClassesLoader.loadClass(params.classname)
            val res = instrumentation.invoke(
                clazz,
                params.signature,
                kryoHelper.readObject(params.arguments),
                kryoHelper.readObject(params.parameters)
            )

            logInfo { "sent cmd: $res" }
            InvokeMethodCommandResult(kryoHelper.writeObject(res))
        }
    }
    protocolModel.setInstrumentation.set { params ->
        measureExecutionForTermination {
            instrumentation = kryoHelper.readObject(params.instrumentation)
            Agent.dynamicClassTransformer.transformer = instrumentation // classTransformer is set
            Agent.dynamicClassTransformer.addUserPaths(pathsToUserClasses)
            instrumentation.init(pathsToUserClasses)
        }
    }
    protocolModel.addPaths.set { params ->
        measureExecutionForTermination {
            pathsToUserClasses = params.pathsToUserClasses.split(File.pathSeparatorChar).toSet()
            pathsToDependencyClasses = params.pathsToDependencyClasses.split(File.pathSeparatorChar).toSet()
            HandlerClassesLoader.addUrls(pathsToUserClasses)
            HandlerClassesLoader.addUrls(pathsToDependencyClasses)
            kryoHelper.setKryoClassLoader(HandlerClassesLoader) // Now kryo will use our classloader when it encounters unregistered class.

            logTrace { "User classes:" + pathsToUserClasses.joinToString() }

            UtContext.setUtContext(UtContext(HandlerClassesLoader))
        }
    }
    protocolModel.stopProcess.set { _ ->
        measureExecutionForTermination {
            def.complete(Unit)
        }
    }
    protocolModel.collectCoverage.set { params ->
        measureExecutionForTermination {
            val anyClass: Class<*> = kryoHelper.readObject(params.clazz)
            val result = (instrumentation as CoverageInstrumentation).collectCoverageInfo(anyClass)
            CollectCoverageResult(kryoHelper.writeObject(result))
        }
    }
    signalChildReady(pid)
    logInfo { "IO obtained" }

    val latch = CountDownLatch(1)
    sync.advise(lifetime) {
        if (it == "main") {
            sync.fire("child")
            latch.countDown()
        }
    }

    if (latch.await(messageFromMainTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
        logInfo { "starting instrumenting" }
        try {
            def.await()
        } catch (e: Throwable) {
            logError { "Terminating process because exception occured: ${e.stackTraceToString()}" }
        }
    }
}