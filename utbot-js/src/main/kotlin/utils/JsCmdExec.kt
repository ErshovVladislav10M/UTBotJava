package utils

import java.io.BufferedReader
import java.io.File

object JsCmdExec {

    fun runCommand(cmd: String, dir: String? = null): BufferedReader {
        val builder = ProcessBuilder("cmd.exe", "/c", cmd)
        dir?.let {
            builder.directory(File(it))
        }
        val process = builder.start()
        val lel = process.errorStream.bufferedReader().readText()
        return process.inputStream.bufferedReader()
    }
}