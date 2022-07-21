package org.utbot.instrumentation.rd

import com.jetbrains.rd.framework.util.RdCoroutineScope
import com.jetbrains.rd.framework.util.asCoroutineDispatcher
import com.jetbrains.rd.util.lifetime.Lifetime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger("UtRdCoroutineScope")

class UtRdCoroutineScope(lifetime: Lifetime) : RdCoroutineScope(lifetime) {
    companion object {
        private val dispatcher = UtSingleThreadScheduler("UtRdCoroutineScope dispatcher") { logger.info(it) }.asCoroutineDispatcher
        val current = UtRdCoroutineScope(Lifetime.Eternal) // TODO normal lifetime
    }

    init {
        lifetime.bracketIfAlive({
            override(lifetime, this)
            logger.info { "UtRdCoroutineScope overridden" }
        }, {
            logger.info { "UtRdCoroutineScope has been reset" }
        })
        lifetime.onTermination {
            logger.info("UtRdCoroutineScope disposed")
        }
    }

    override val defaultDispatcher = dispatcher

    override fun shutdown() {
        try {
            runBlocking {
                coroutineContext[Job]!!.cancelAndJoin()
            }
        } catch (e: CancellationException) {
            // nothing
        } catch (e: Throwable) {
            logger.error { e }
        }
    }

    override fun onException(throwable: Throwable) {
        if (throwable !is CancellationException) {
            logger.error("Unhandled coroutine throwable", throwable)
        }
    }
}