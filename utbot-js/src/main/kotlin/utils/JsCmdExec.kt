package utils

import java.io.BufferedReader
import java.io.File

object JsCmdExec {

    private val cmdPrefix =
        if (System.getProperty("os.name").toLowerCase().contains("windows"))
            "cmd.exe" else "/bin/bash"
    private val cmdDelim = if (System.getProperty("os.name").toLowerCase().contains("windows"))
        "/c" else "-c"

    fun runCommand(cmd: String, dir: String? = null, shouldWait: Boolean = false): BufferedReader {
        val builder = ProcessBuilder(cmdPrefix, cmdDelim, cmd)
        dir?.let {
            builder.directory(File(it))
        }
        val process = builder.start()
        if (shouldWait) process.waitFor()
        val error = process.errorStream.bufferedReader().readText()
        return process.inputStream.bufferedReader()
    }
}