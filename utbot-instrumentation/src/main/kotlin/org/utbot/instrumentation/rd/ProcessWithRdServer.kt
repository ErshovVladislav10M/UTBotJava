package org.utbot.instrumentation.rd

import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.base.static
import com.jetbrains.rd.framework.impl.RdSignal
import com.jetbrains.rd.framework.serverPort
import com.jetbrains.rd.framework.util.NetUtils
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isAlive
import org.utbot.common.utBotTempDirectory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

fun Process.withRdServer(
    parent: Lifetime? = null,
    serverFactory: (Lifetime) -> Protocol
): ProcessWithRdServer {
    return ProcessWithRdServerImpl(toLifetimedProcess(parent)) {
        serverFactory(it)
    }
}

fun LifetimedProcess.withRdServer(
    serverFactory: (Lifetime) -> Protocol
): ProcessWithRdServer {
    return ProcessWithRdServerImpl(this) {
        serverFactory(it)
    }
}

fun startProcessWithRdServer(
    cmd: List<String>,
    parent: Lifetime? = null,
    serverFactory: (Lifetime) -> Protocol
): ProcessWithRdServer {
    val child = startLifetimedProcess(cmd, parent)

    return ProcessWithRdServerImpl(child, serverFactory)
}

fun startProcessWithRdServer(
    processFactory: (Int) -> Process,
    parent: Lifetime? = null,
    serverFactory: (Int, Lifetime) -> Protocol
): ProcessWithRdServer {
    val port = NetUtils.findFreePort(0)

    return processFactory(port).withRdServer(parent) {
        serverFactory(port, it)
    }
}

fun startProcessWithRdServer2(
    cmd: (Int) -> List<String>,
    parent: Lifetime? = null,
    serverFactory: (Int, Lifetime) -> Protocol
): ProcessWithRdServer {
    return startProcessWithRdServer({ ProcessBuilder(cmd(it)).start() }, parent, serverFactory)
}

const val rdProcessDirName = "rdProcessSync"
val processSyncDirectory = File(utBotTempDirectory.toFile(), rdProcessDirName)

/**
 * Main goals of this class:
 * 1. start rd server protocol with child process
 * 2. protocol should be bound to process lifetime
 * 3. optionally wait until child process starts client protocol and connects
 *
 * To achieve step 3:
 * 1. child process should start client ASAP, preferably should be the first thing done when child starts
 * 2. serverFactory must create protocol with provided child process lifetime
 * 3. server and client protocol should choose same port,
 *      preferable way is to find open port in advance, provide it to child process via process arguments and
 *      have serverFactory use it
 */
interface ProcessWithRdServer : LifetimedProcess {
    val protocol: Protocol
    val port: Int
        get() = protocol.wire.serverPort
    val toProcess: RdSignal<ByteArray>
    val fromProcess: RdSignal<ByteArray>
}

class ProcessWithRdServerImpl(
    private val child: LifetimedProcess,
    serverFactory: (Lifetime) -> Protocol
) : ProcessWithRdServer, LifetimedProcess by child {
    override val protocol = serverFactory(lifetime)
    override val toProcess = RdSignal<ByteArray>().static(1).apply { async = true }
    override val fromProcess = RdSignal<ByteArray>().static(2).apply { async = true }

    init {
        lifetime.bracketIfAlive({
            lifetime.usingNested {
                val latch = CountDownLatch(1)

                it.onTermination {
                    latch.countDown()
                }
                protocol.wire.connected.advise(it) { isConnected ->
                    if (isConnected) {
                        latch.countDown()
                    }
                }
                latch.await()// todo coroutines
            }

            val bound = CountDownLatch(1)

            protocol.scheduler.invokeOrQueue {
                toProcess.bind(lifetime, protocol, "mainInputSignal")
                fromProcess.bind(lifetime, protocol, "mainOutputSignal")
                bound.countDown()
            }
            bound.await()
            processSyncDirectory.mkdirs()

            val syncFile = File(processSyncDirectory, "$pid.created")

            while (lifetime.isAlive) {
                if (Files.deleteIfExists(syncFile.toPath()))
                    break
                Thread.sleep(10)
            }
        }) {
            val syncFile = File(processSyncDirectory, "$pid.created")

            if (syncFile.exists()) {
                syncFile.delete()
            }
        }
    }
}