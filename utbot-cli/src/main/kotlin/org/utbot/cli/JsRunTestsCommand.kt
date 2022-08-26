package org.utbot.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import java.io.File
import mu.KotlinLogging
import utils.JsCmdExec

private val logger = KotlinLogging.logger {}

class JsRunTestsCommand : CliktCommand(name = "run_js", help = "Runs tests for the specified file or directory") {

    private val fileWithTests by option(
        "--fileOrDir", "-f",
        help = "Specifies a file or directory with tests"
    ).required()

    private val output by option(
        "-o", "--output",
        help = "Specifies an output .txt file for test framework result"
    ).check("Must end with .txt suffix") {
        it.endsWith(".txt")
    }

    private val testFramework by option("--test-framework", "-t", help = "Test framework to be used")
        .choice("mocha")
        .default("mocha")


    override fun run() {
        val dir = if (fileWithTests.endsWith(".js"))
            fileWithTests.substringBeforeLast(File.separator) else fileWithTests
        when (testFramework) {
            "mocha" -> {
                JsCmdExec.runCommand(
                    "npm i -l mocha",
                    dir,
                    true,
                    15
                )
                val (textReader, error) = JsCmdExec.runCommand(
                    "mocha $fileWithTests",
                    dir
                )
                val errorText = error.readText()
                if (errorText.isNotEmpty()) {
                    logger.error { "An error has occurred while running tests for $fileWithTests : $errorText" }
                } else {
                    val text = textReader.readText()
                    output?.let {
                        val file = File(it)
                        file.createNewFile()
                        file.writeText(text)
                    } ?: logger.info { "\n$text" }
                }
            }
        }
    }
}