package org.utbot.intellij.plugin.go

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.utbot.framework.plugin.api.UtMethodTestSet
import org.utbot.intellij.plugin.ui.utils.testModule

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

    private fun createTests(project: Project, model: GoTestsModel) {
        println("Generate tests for:")
        model.selectedFunctionsOrMethods.forEach { println("${it.name}") }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generate Go tests") {
            override fun run(indicator: ProgressIndicator) {

                // TODO: fix "Read access is allowed from event dispatch thread or inside read-action only"
                //  to enable operations with indicator
//                indicator.isIndeterminate = false
//                indicator.text = "Generate tests: read classes"

//                val goFunctionsOrMethods = findSelectedGoFunctionsOrMethods(model) // TODO
                val goFunctionsOrMethods = model.selectedFunctionsOrMethods

                val testSourceRoot = model.testSourceRoot!!.path

                val testSetsByFile = mutableMapOf<GoFile, MutableList<UtMethodTestSet>>()

                for (goFunctionOrMethod in goFunctionsOrMethods) {
//                    indicator.text = "Generate test cases for ${goFunctionOrMethod.name}"
//                    indicator.fraction =
//                        indicator.fraction.coerceAtLeast(0.9 * processedFunctionsOrMethods / totalFunctionsOrMethods)

                    val testSets = generateTestSets(goFunctionOrMethod, testSourceRoot)

                    val file = goFunctionOrMethod.containingFile
                    testSetsByFile.putIfAbsent(file, mutableListOf())
                    testSetsByFile[file]!!.addAll(testSets)
                }

//                indicator.fraction = indicator.fraction.coerceAtLeast(0.9)
//                indicator.text = "Generate code for tests"
                // Commented out to generate tests for collected executions even if action was canceled.
                // indicator.checkCanceled()

                invokeLater {
                    generateTestsCode(model, testSetsByFile)
                }
            }
        })
    }

    @Suppress("UNUSED_PARAMETER")
    private fun generateTestSets(
        functionOrMethod: GoFunctionOrMethodDeclaration,
        testSourceRoot: String
    ): List<UtMethodTestSet> {
        // TODO
        println("Generate test sets")
        return emptyList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun generateTestsCode(model: GoTestsModel, testSetsByFile: Map<GoFile, List<UtMethodTestSet>>) {
        // TODO
        println("Generate code for tests")
    }
}