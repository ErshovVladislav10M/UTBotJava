package utils

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import org.utbot.framework.plugin.api.TimeoutException

object JsCmdExec {

    private val cmdPrefix =
        if (System.getProperty("os.name").toLowerCase().contains("windows"))
            "cmd.exe" else "/bin/bash"
    private val cmdDelim = if (System.getProperty("os.name").toLowerCase().contains("windows"))
        "/c" else "-c"

    fun runCommand(cmd: String, dir: String? = null, shouldWait: Boolean = true, timeout: Long = 30_000): Pair<BufferedReader, BufferedReader> {
        val builder = ProcessBuilder(cmdPrefix, cmdDelim, cmd)
        dir?.let {
            builder.directory(File(it))
        }
        val process = builder.start()
        if (shouldWait) {
            if(!process.waitFor(timeout, TimeUnit.MILLISECONDS)) throw TimeoutException("")
        }
        return process.inputStream.bufferedReader() to process.errorStream.bufferedReader()
    }
}