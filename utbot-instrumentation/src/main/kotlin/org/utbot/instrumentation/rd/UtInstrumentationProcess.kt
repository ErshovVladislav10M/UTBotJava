package org.utbot.instrumentation.rd

import com.jetbrains.rd.framework.base.static
import com.jetbrains.rd.framework.impl.RdSignal
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isAlive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.utbot.common.pid
import org.utbot.instrumentation.instrumentation.Instrumentation
import org.utbot.instrumentation.process.ChildProcessRunner
import org.utbot.instrumentation.rd.generated.AddPathsParams
import org.utbot.instrumentation.rd.generated.ProtocolModel
import org.utbot.instrumentation.rd.generated.SetInstrumentationParams
import org.utbot.instrumentation.rd.generated.protocolModel
import org.utbot.instrumentation.util.KryoHelper
import org.utbot.rd.ProcessWithRdServer
import org.utbot.rd.UtRdUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger{}
private const val fileWaitTimeoutMillis = 10L

/**
 * Main goals of this class:
 * 1. prepare started child process for execution - initializing rd, sending paths and instrumentation
 * 2. communicate with child process, i.e. send and receive messages
 *
 * facts
 * 1. process lifetime - helps indicate that child died
 * 2. operation lifetime - terminates either by process lifetime or coroutine scope cancellation, i.e. timeout
 *      also removes orphaned deferred on termination
 * 3. if process is dead - it always throws CancellationException on any operation
 * do not allow to obtain dead process, return newly restarted instance it if terminated
 * 4. wait until child process starts client protocol, advised all callbacks and connects
 */
class UtInstrumentationProcess private constructor(
    private val classLoader: ClassLoader?,
    private val rdProcess: ProcessWithRdServer
) : ProcessWithRdServer by rdProcess {
    private val sync = RdSignal<String>().static(1).apply { async = true }
    val kryoHelper = KryoHelper(lifetime.createNested()).apply {
        classLoader?.let { setKryoClassLoader(it) }
    }
    val protocolModel: ProtocolModel
        get() = protocol.protocolModel

    private suspend fun init(): UtInstrumentationProcess {
        lifetime.usingNested { operation ->
            val bound = CompletableDeferred<Boolean>()

            protocol.scheduler.invokeOrQueue {
                sync.bind(lifetime, protocol, sync.rdid.toString())
                protocol.protocolModel
                bound.complete(true)
            }
            operation.onTermination { bound.cancel() }
            bound.await()
        }
        processSyncDirectory.mkdirs()

        val syncFile = File(processSyncDirectory, childCreatedFileName(process.pid.toInt()))

        while (lifetime.isAlive) {
            if (Files.deleteIfExists(syncFile.toPath())) {
                logger.trace { "process ${process.pid}: signal file deleted connecting" }
                break
            }

            delay(fileWaitTimeoutMillis)
        }

        lifetime.usingNested { syncLifetime ->
            val childReady = AtomicBoolean(false)
            sync.advise(syncLifetime) {
                if (it == "child") {
                    childReady.set(true)
                }
            }

            while (!childReady.get()) {
                sync.fire("main")
                delay(10)
            }
        }

        lifetime.onTermination {
            if (syncFile.exists()) {
                logger.trace{ "process ${process.pid}: on terminating syncFile existed" }
                syncFile.delete()
            }
        }

        return this
    }

    companion object {
        suspend operator fun <TIResult, TInstrumentation : Instrumentation<TIResult>> invoke(
            parent: Lifetime,
            childProcessRunner: ChildProcessRunner,
            instrumentation: TInstrumentation,
            pathsToUserClasses: String,
            pathsToDependencyClasses: String,
            classLoader: ClassLoader?
        ): UtInstrumentationProcess {
            val rdProcess: ProcessWithRdServer = UtRdUtil.startUtProcessWithRdServer(
                parent = parent
            ) {
                childProcessRunner.start(it)
            }
            logger.trace("rd process started")
            val proc = UtInstrumentationProcess(
                classLoader,
                rdProcess
            ).init()

            proc.lifetime.onTermination {
                logger.trace { "process is terminating" }
            }

            logger.trace("sending add paths")
            proc.protocolModel.addPaths.startSuspending(proc.lifetime, AddPathsParams(
                pathsToUserClasses,
                pathsToDependencyClasses))

            logger.trace("sending instrumentation")
            proc.protocolModel.setInstrumentation.startSuspending(proc.lifetime, SetInstrumentationParams(
                proc.kryoHelper.writeObject(instrumentation)))
            logger.trace("start commands sent")

            return proc
        }
    }
}