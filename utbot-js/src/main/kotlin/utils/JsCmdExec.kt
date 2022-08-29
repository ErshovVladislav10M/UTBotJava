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

    fun runCommand(
        cmd: String,
        dir: String? = null,
        shouldWait: Boolean = false,
        timeout: Long = 5,
        isPathAbsolute: Boolean = true
    ): Pair<BufferedReader, BufferedReader> {
        val builder = ProcessBuilder(cmdPrefix, cmdDelim, cmd)
        dir?.let {
            if (!isPathAbsolute) {
                val currDir = builder.directory() ?: File(System.getProperty("user.dir"))
                val newFile = File("${currDir.absolutePath}${File.separator}$dir")
                val newDir = if (newFile.isFile) File(newFile.absolutePath.substringBeforeLast(File.separator)) else newFile
                builder.directory(newDir)
            } else builder.directory(File(it))
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