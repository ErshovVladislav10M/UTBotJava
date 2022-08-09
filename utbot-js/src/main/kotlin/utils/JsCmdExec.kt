package utils

import java.io.BufferedReader
import java.io.File

object JsCmdExec {

    fun runCommand(cmd: String, dir: String? = null, shouldWait: Boolean = false): BufferedReader {
        val builder = ProcessBuilder("cmd.exe", "/c", cmd)
        dir?.let {
            builder.directory(File(it))
        }
        val process = builder.start()
        if (shouldWait) process.waitFor()
        return process.inputStream.bufferedReader()
    }
}