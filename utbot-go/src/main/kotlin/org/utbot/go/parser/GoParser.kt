package org.utbot.go.parser

import org.utbot.go.GoFunctionOrMethodNode
import java.io.File
import java.io.InputStreamReader
import com.beust.klaxon.Klaxon
import org.utbot.framework.plugin.api.GoTypeId
import org.utbot.go.GoBodyNode
import org.utbot.go.GoFileNode
import org.utbot.go.GoFunctionOrMethodParameterNode
import java.nio.file.Paths

object GoParser {

    object Constants {
        // TODO: set up Go executor in general
        val GO_EXECUTOR_PATH = Paths.get("/home/gleb/go/go1.19rc1", "bin", "go").toString()

        // TODO: find Go parser path by code
        const val GO_PARSER_SOURCE_DIRECTORY_PATH = "/home/gleb/tabs/UTBotJava/utbot-go/src/main/resources/go_parser/"
        val GO_PARSER_SOURCE_FILES_NAMES =
            listOf("main.go", "parser_core.go", "parsing_targets.go", "parsing_results.go")

        const val PARSING_TARGETS_FILE_NAME = "ut_go_parsing_targets.json"
        const val PARSING_RESULTS_FILE_NAME = "ut_go_parsing_results.json"
    }

    data class GoSourceFileParsingResult(
        val parsedFunctions: List<GoFunctionOrMethodNode>,
        val notSupportedFunctionsNames: List<String>,
        val notFoundFunctionsNames: List<String>,
    )

    /**
     * Takes map from paths of Go source files to names of their selected functions.
     * If list is empty, all containing functions are selected.
     *
     * Returns GoSourceFileParsingResult-s grouped by paths of their source files.
     */
    fun parseGoSourceFilesForFunctions(parsingTargets: Map<String, List<String>>): Map<String, GoSourceFileParsingResult> {
        val parsingTargetsStruct = ParsingTargets(
            parsingTargets.map { (filePath, selectedFunctionsNames) ->
                ParsingTarget(filePath, selectedFunctionsNames)
            }
        )
        val goParserSourceDir = File(Constants.GO_PARSER_SOURCE_DIRECTORY_PATH)
        val parsingTargetsFile = goParserSourceDir.resolve(Constants.PARSING_TARGETS_FILE_NAME)
        val parsingResultsFile = goParserSourceDir.resolve(Constants.PARSING_RESULTS_FILE_NAME)

        val goParserRunCommand = listOf(
            Constants.GO_EXECUTOR_PATH,
            "run"
        ) + Constants.GO_PARSER_SOURCE_FILES_NAMES + listOf(
            "-targets",
            Constants.PARSING_TARGETS_FILE_NAME,
            "-results",
            Constants.PARSING_RESULTS_FILE_NAME,
        )

        try {
            val parsingTargetsAsJson = Klaxon().toJsonString(parsingTargetsStruct)
            parsingTargetsFile.writeText(parsingTargetsAsJson)

            val executedProcess = runCatching {
                val process = ProcessBuilder(goParserRunCommand)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectErrorStream(true)
                    .directory(goParserSourceDir)
                    .start()
                process.waitFor()
                process
            }.getOrElse {
                throw RuntimeException(
                    "Execution of Go parser for $parsingTargetsStruct failed with throwable: $it"
                )
            }
            val exitCode = executedProcess.exitValue()
            if (exitCode != 0) {
                val processOutput = InputStreamReader(executedProcess.inputStream).readText()
                throw RuntimeException(
                    "Execution of Go parser for $parsingTargetsStruct failed with non-zero exit code = $exitCode:\n$processOutput"
                )
            }

            // TODO: maybe use Klaxon stream api to increase performance?
            val parsingResults = Klaxon().parse<ParsingResults>(parsingResultsFile)
            if (parsingResults == null) {
                val rawParsingResults = try {
                    parsingResultsFile.readText()
                } catch (exception: Exception) {
                    null
                }
                throw RuntimeException(
                    "Failed to deserialize parsing results: $rawParsingResults"
                )
            }

            return parsingResults.results.associateBy({ it.filePath }) { result ->
                val parsedFunctionsNodes = result.parsedFunctions.map { parsedFunction ->
                    fun ParsedType.toGoTypeId() = GoTypeId(this.name, isErrorType = this.implementsError)
                    val returnTypes = parsedFunction.resultTypes.map { it.toGoTypeId() }
                    val parameters = parsedFunction.parameters.map { parsedFunctionParameter ->
                        GoFunctionOrMethodParameterNode(
                            parsedFunctionParameter.name,
                            parsedFunctionParameter.type.toGoTypeId()
                        )
                    }
                    val containingFileNode = GoFileNode(
                        File(result.filePath).nameWithoutExtension,
                        result.packageName,
                        Paths.get(result.filePath).parent.toString()
                    )
                    GoFunctionOrMethodNode(
                        parsedFunction.name,
                        returnTypes,
                        parameters,
                        GoBodyNode("unused"),
                        containingFileNode
                    )
                }
                GoSourceFileParsingResult(
                    parsedFunctionsNodes,
                    result.notSupportedFunctionsNames,
                    result.notFoundFunctionsNames
                )
            }
        } finally {
            parsingTargetsFile.delete()
            parsingResultsFile.delete()
        }
    }
}