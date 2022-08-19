package org.utbot.instrumentation.rd

import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.base.static
import com.jetbrains.rd.framework.impl.RdSignal
import com.jetbrains.rd.util.lifetime.Lifetime
import org.utbot.common.utBotTempDirectory
import org.utbot.instrumentation.rd.generated.ProtocolModel
import org.utbot.instrumentation.rd.generated.protocolModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val rdProcessDirName = "rdProcessSync"
val processSyncDirectory = File(utBotTempDirectory.toFile(), rdProcessDirName)
private val awaitTimeoutMillis: Long = 120 * 1000

internal fun obtainClientIO(lifetime: Lifetime, protocol: Protocol, pid: Int): Pair<RdSignal<String>, ProtocolModel> {
    val latch = CountDownLatch(2)
    val sync = RdSignal<String>().static(1).init(lifetime, protocol, latch)

    protocol.scheduler.invokeOrQueue {
        protocol.protocolModel
        latch.countDown()
    }

    if (!latch.await(awaitTimeoutMillis, TimeUnit.MILLISECONDS))
        throw IllegalStateException("Cannot bind signals")

    return sync to protocol.protocolModel
}

internal fun childCreatedFileName(pid: Int): String {
    return "$pid.created"
}

internal fun signalChildReady(pid: Int) {
    processSyncDirectory.mkdirs()

    val signalFile = File(processSyncDirectory, childCreatedFileName(pid))

    if (signalFile.exists()) {
        signalFile.delete()
    }

    val created = signalFile.createNewFile()

    if (!created) {
        throw IllegalStateException("cannot create signal file")
    }
}

private fun <T> RdSignal<T>.init(lifetime: Lifetime, protocol: Protocol, latch: CountDownLatch): RdSignal<T> {
    return this.apply {
        async = true
        protocol.scheduler.invokeOrQueue {
            this.bind(lifetime, protocol, rdid.toString())
            latch.countDown()
        }
    }
}