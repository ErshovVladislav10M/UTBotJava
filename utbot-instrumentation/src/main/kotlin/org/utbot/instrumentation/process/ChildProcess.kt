package org.utbot.instrumentation.process

import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.plusAssign
import kotlinx.coroutines.delay
import org.utbot.common.getCurrentProcessId
import org.utbot.common.scanForClasses
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.instrumentation.agent.Agent
import org.utbot.instrumentation.instrumentation.Instrumentation
import org.utbot.instrumentation.rd.*
import org.utbot.instrumentation.util.KryoHelper
import org.utbot.instrumentation.util.Protocol
import org.utbot.instrumentation.util.UnexpectedCommand
import org.utbot.rd.UtRdCoroutineScope
import org.utbot.rd.UtRdUtil
import org.utbot.rd.UtSingleThreadScheduler
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

private enum class CUSTOM_LOG_LEVEL(val value: Int) {
    ERROR(0),
    INFO(1),
    TRACE(2)
}

private val logLevel = CUSTOM_LOG_LEVEL.ERROR

// Logging
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private fun log(any: () -> Any?, level: CUSTOM_LOG_LEVEL) {
    if (level.value > logLevel.value)
        return

    System.err.println(LocalDateTime.now().format(dateFormatter) + " | ${any()}")
}

private fun logError(any: () -> Any?) {
    log(any, CUSTOM_LOG_LEVEL.ERROR)
}

private fun logInfo(any: () -> Any?) {
    log(any, CUSTOM_LOG_LEVEL.INFO)
}

private fun logTrace(any: () -> Any?) {
    log(any, CUSTOM_LOG_LEVEL.TRACE)
}

private val counter = AtomicLong(0)

/**
 * It should be compiled into separate jar file (child_process.jar) and be run with an agent (agent.jar) option.
 * CHILD PROCESS INVARIANT:
 * Parent process should live longer than child
 * if parent dies before child does - child will be orphan
 */
fun main(args: Array<String>): Unit {
    // 0 - auto port for server, should not be used here
    val port = args.find { it.startsWith(serverPortProcessArgumentTag) }
        ?.run { split("=").last().toInt().coerceIn(1..65535) }
        ?: throw IllegalArgumentException("No port provided")

    val pid = getCurrentProcessId()
    val def = LifetimeDefinition()

    counter.set(System.currentTimeMillis())

    UtRdCoroutineScope.current.launch(Lifetime.Eternal) {
        while(true) {
            val now = System.currentTimeMillis()
            if (now - counter.get() > 60 * 1000) {
                def.terminate()
                break
            } else {
                delay(1000)
            }
        }
    }

    def.usingNested { lifetime ->
        lifetime += { logInfo { "terminating lifetime" } }
        initiate(lifetime, port, pid.toInt())
    }
}

private fun initiate(lifetime: Lifetime, port: Int, pid: Int) = lifetime.bracketIfAlive({
    // We don't want user code to litter the standard output, so we redirect it.
    // it is import to set output before creating protocol because rd has its own logging to stdout
    val tmpStream = PrintStream(object : OutputStream() {
        override fun write(b: Int) {}
    })
    System.setOut(tmpStream)

    val clientProtocol = UtRdUtil.createUtClientProtocol(lifetime, port, UtSingleThreadScheduler { logInfo(it) })
    val (mainToProcess, processToMain) = obtainClientIO(lifetime, clientProtocol, pid)
    val kryoHelper =
        KryoHelper(lifetime, mainToProcess, processToMain)
        { logTrace(it) } // this generates a lot of logs - comment if needed


    logInfo { "starting instrumenting" }
    startInstrumenting(kryoHelper)
}) {
    val syncFile = File(processSyncDirectory, childCreatedFileName(pid))

    if (syncFile.exists()) {
        syncFile.delete()
    }
}

private fun startInstrumenting(kryoHelper: KryoHelper) {
    val classPaths = readClasspath(kryoHelper) ?: return
    logTrace { "classpaths read $classPaths" }
    val pathsToUserClasses = classPaths.pathsToUserClasses.split(File.pathSeparatorChar).toSet()
    val pathsToDependencyClasses = classPaths.pathsToDependencyClasses.split(File.pathSeparatorChar).toSet()
    HandlerClassesLoader.addUrls(pathsToUserClasses)
    HandlerClassesLoader.addUrls(pathsToDependencyClasses)
    kryoHelper.setKryoClassLoader(HandlerClassesLoader) // Now kryo will use our classloader when it encounters unregistered class.

    logTrace { "User classes:" + pathsToUserClasses.joinToString() }

    UtContext.setUtContext(UtContext(HandlerClassesLoader)).use {
        getInstrumentation(kryoHelper)?.let { instrumentation ->
            Agent.dynamicClassTransformer.transformer = instrumentation // classTransformer is set
            Agent.dynamicClassTransformer.addUserPaths(pathsToUserClasses)
            instrumentation.init(pathsToUserClasses)

            try {
                loop(kryoHelper, instrumentation)
            } catch (e: Throwable) {
                logError { "Terminating process because exception occured: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }
}

private fun send(kryoHelper: KryoHelper, cmdId: Long, cmd: Protocol.Command) {
    try {
        kryoHelper.writeCommand(cmdId, cmd)
        logInfo { "Send << $cmdId" }
    } catch (e: Exception) {
        logError { "Failed to serialize << $cmdId with exception: ${e.stackTraceToString()}" }
        logInfo { "Writing it to kryo..." }
        kryoHelper.writeCommand(cmdId, Protocol.ExceptionInChildProcess(e))
        logInfo { "Successfuly wrote." }
    }
}

private fun read(kryoHelper: KryoHelper): KryoHelper.ReceivedCommand {
    try {
        val cmd = kryoHelper.readCommand()
        logInfo { "Received :> $cmd" }
        return cmd
    } catch (e: Exception) {
        logError { "Failed to read :> ${e.stackTraceToString()}" }
        throw e
    }
}

/**
 * Main loop. Processes incoming commands.
 */
private fun loop(kryoHelper: KryoHelper, instrumentation: Instrumentation<*>) {
    while (true) {
        val (id, cmd) = try {
            read(kryoHelper)
        } catch (e: Exception) {
            logInfo { "error while trying read: $e" }
            kryoHelper.discard()
            continue
        }

        logInfo { "read cmd: $cmd" }

        when (cmd) {
            is Protocol.WarmupCommand -> {
                val time = measureTimeMillis {
                    HandlerClassesLoader.scanForClasses("").toList() // here we transform classes
                }
                logInfo { "warmup finished in $time ms" }
            }
            is Protocol.InvokeMethodCommand -> {
                val resultCmd = try {
                    val clazz = HandlerClassesLoader.loadClass(cmd.className)
                    val res = instrumentation.invoke(
                        clazz,
                        cmd.signature,
                        cmd.arguments,
                        cmd.parameters
                    )
                    Protocol.InvocationResultCommand(res)
                } catch (e: Throwable) {
                    logInfo { "error in invokeMethod: ${e.stackTraceToString()}" }
                    Protocol.ExceptionInChildProcess(e)
                }

                send(kryoHelper, id, resultCmd)
                logInfo { "sent cmd: $resultCmd" }
            }
            is Protocol.StopProcessCommand -> {
                break
            }
            is Protocol.InstrumentationCommand -> {
                val result = instrumentation.handle(cmd)
                result?.let {
                    send(kryoHelper, id, it)
                }
            }
            else -> {
                logInfo { "unexpected command: $cmd" }
                send(kryoHelper, id, Protocol.ExceptionInChildProcess(UnexpectedCommand(cmd)))
            }
        }

        logInfo { "cmd executed" }
    }
}

/**
 * Retrieves the actual instrumentation. It is passed from the main process during
 * [org.utbot.instrumentation.ConcreteExecutor] instantiation.
 */
private fun getInstrumentation(kryoHelper: KryoHelper): Instrumentation<*>? {
    val (id, cmd) = kryoHelper.readCommand()
    return when (cmd) {
        is Protocol.SetInstrumentationCommand<*> -> {
            cmd.instrumentation
        }
        is Protocol.StopProcessCommand -> {
            null
        }
        else -> {
            send(kryoHelper, id, Protocol.ExceptionInChildProcess(UnexpectedCommand(cmd)))
            error("No instrumentation!")
        }
    }
}

private fun readClasspath(kryoHelper: KryoHelper): Protocol.AddPathsCommand? {
    val (id, cmd) = kryoHelper.readCommand()
    return when (cmd) {
        is Protocol.AddPathsCommand -> {
            cmd
        }
        is Protocol.StopProcessCommand -> {
            null
        }
        else -> {
            send(kryoHelper, id, Protocol.ExceptionInChildProcess(UnexpectedCommand(cmd)))
            error("No classpath!")
        }
    }
}