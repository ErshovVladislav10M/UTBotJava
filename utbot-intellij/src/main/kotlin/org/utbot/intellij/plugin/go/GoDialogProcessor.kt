package org.utbot.intellij.plugin.go

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.debugger.readAction
import org.utbot.go.fuzzer.generateTestCasesForGoFile
import org.utbot.go.parser.GoParser
import org.utbot.intellij.plugin.go.codegen.GoCodeGenerationController
import org.utbot.intellij.plugin.ui.utils.showWarningDialogLater
import org.utbot.intellij.plugin.ui.utils.testModule
import java.io.File

object GoDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        srcModule: Module,
        functionsOrMethod: Set<GoFunctionOrMethodDeclaration>,
        focusedFunctionOrMethod: GoFunctionOrMethodDeclaration?,
    ) {
        val dialogProcessor = createDialog(project, srcModule, functionsOrMethod, focusedFunctionOrMethod)
        if (!dialogProcessor.showAndGet()) return

        createTests(project, dialogProcessor.model)
    }

    private fun createDialog(
        project: Project,
        srcModule: Module,
        functionsOrMethod: Set<GoFunctionOrMethodDeclaration>,
        focusedFunctionOrMethod: GoFunctionOrMethodDeclaration?,
    ): GoDialogWindow {
        val testModel = srcModule.testModule(project)

        return GoDialogWindow(
            GoTestsModel(
                project,
                srcModule,
                testModel,
                functionsOrMethod,
                focusedFunctionOrMethod,
            )
        )
    }

    private object ProgressIndicatorConstants {
        const val START_FRACTION = 0.05 // is needed to prevent infinite indicator that appears for 0.0
        const val PARSE_FILES_FRACTION = 0.25
        const val GENERATE_CODE_FRACTION = 0.1
        const val GENERATE_TEST_CASES_FRACTION = 1.0 - PARSE_FILES_FRACTION - GENERATE_CODE_FRACTION
    }

    private fun createTests(project: Project, model: GoTestsModel) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generate Go tests") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Parse and analyze files"
                indicator.fraction = ProgressIndicatorConstants.START_FRACTION

                val parsingTargets = model.selectedFunctionsOrMethods.toParsingTargets()
                val parsingResults = GoParser.parseGoSourceFilesForFunctions(parsingTargets)
                indicator.fraction = indicator.fraction.coerceAtLeast(
                    ProgressIndicatorConstants.START_FRACTION
                        + ProgressIndicatorConstants.PARSE_FILES_FRACTION
                )
                acknowledgeUserAboutUnparsedSelectedFunctions(model, parsingResults)

                var processedFiles = 0
                val testCasesByFile = parsingResults.mapValues { (filePath, parsingResult) ->
                    val fileName = File(filePath).name
                    indicator.text = "Generate test cases for $fileName"
                    indicator.fraction = indicator.fraction.coerceAtLeast(
                        ProgressIndicatorConstants.START_FRACTION + ProgressIndicatorConstants.PARSE_FILES_FRACTION
                            + ProgressIndicatorConstants.GENERATE_TEST_CASES_FRACTION * processedFiles / parsingResults.size
                    )
                    indicator.checkCanceled() // allow user cancel unit test generation
                    generateTestCasesForGoFile(parsingResult.parsedFunctions).also { processedFiles++ }
                }

                indicator.fraction =
                    indicator.fraction.coerceAtLeast(
                        ProgressIndicatorConstants.PARSE_FILES_FRACTION +
                            ProgressIndicatorConstants.GENERATE_TEST_CASES_FRACTION
                    )
                indicator.text = "Generate code for tests"

                invokeLater {
                    GoCodeGenerationController.generateTestsFilesAndCode(model, testCasesByFile)
                }
            }
        })
    }

    private fun Set<GoFunctionOrMethodDeclaration>.toParsingTargets(): Map<String, List<String>> =
        readAction { // to read PSI-tree or else "Read access" exception {
            this.groupBy({ it.containingFile.virtualFile.canonicalPath!! }) { it.name!! }
        }

    private fun acknowledgeUserAboutUnparsedSelectedFunctions(
        model: GoTestsModel,
        parsingResults: Map<String, GoParser.GoSourceFileParsingResult>
    ) {
        val unparsedSelectedFunctionsListMessage = parsingResults.filter { (_, parsingResult) ->
            parsingResult.notSupportedFunctionsNames.isNotEmpty() || parsingResult.notFoundFunctionsNames.isNotEmpty()
        }.map { (filePath, parsingResult) ->
            val unsupportedFunctions = parsingResult.notSupportedFunctionsNames.joinToString(separator = ", ")
            val notFoundFunctions = parsingResult.notFoundFunctionsNames.joinToString(separator = ", ")
            val messageSb = StringBuilder()
            messageSb.append("File $filePath")
            if (unsupportedFunctions.isNotEmpty()) {
                messageSb.append("\n-- contains currently unsupported functions: $unsupportedFunctions")
            }
            if (notFoundFunctions.isNotEmpty()) {
                messageSb.append("\n-- does not contain functions: $notFoundFunctions")
            }
            messageSb.toString()
        }.joinToString(separator = "\n")

        if (unparsedSelectedFunctionsListMessage.isNotEmpty()) {
            val errorMessage = StringBuilder()
                .append("Some selected functions were skipped during source code analysis.\n\n")
                .append("$unparsedSelectedFunctionsListMessage\n\n")
                .append("Unit test generation for other selected functions will be performed as usual.")
                .toString()
            showWarningDialogLater(
                model.project,
                errorMessage,
                title = "Skipped some functions for unit tests generation"
            )
        }
    }
}