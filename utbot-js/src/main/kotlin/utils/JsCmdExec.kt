package utils

import java.io.BufferedReader
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import org.utbot.framework.plugin.api.TimeoutException

object JsCmdExec {

    private val cmdPrefix =
        if (System.getProperty("os.name").lowercase(Locale.getDefault()).contains("windows"))
            "cmd.exe" else "/bin/bash"
    private val cmdDelim = if (System.getProperty("os.name").lowercase(Locale.getDefault()).contains("windows"))
        "/c" else "-c"

    fun runCommand(cmd: String, dir: String? = null, shouldWait: Boolean = false, timeout: Long = 5): Pair<BufferedReader, BufferedReader> {
        val builder = ProcessBuilder(cmdPrefix, cmdDelim, cmd)
        dir?.let {
            builder.directory(File(it))
        }
        val process = builder.start()
        if (shouldWait) {
            if(!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.descendants().forEach {
                    it.destroy()
                }
                process.destroy()
                throw TimeoutException("")
            }
        }
        return process.inputStream.bufferedReader() to process.errorStream.bufferedReader()
    }
}