package org.utbot.intellij.plugin.go.generator

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.utbot.intellij.plugin.go.models.GenerateGoTestsModel
import org.utbot.intellij.plugin.go.ui.GenerateGoTestsDialogWindow

object GoUtTestsDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        targetFunctions: Set<GoFunctionOrMethodDeclaration>,
        focusedTargetFunctions: Set<GoFunctionOrMethodDeclaration>,
    ) {
        val dialogProcessor = createDialog(project, targetFunctions, focusedTargetFunctions)
        if (!dialogProcessor.showAndGet()) return

        createTests(dialogProcessor.model)
    }

    private fun createDialog(
        project: Project,
        targetFunctions: Set<GoFunctionOrMethodDeclaration>,
        focusedTargetFunctions: Set<GoFunctionOrMethodDeclaration>,
    ): GenerateGoTestsDialogWindow {
        return GenerateGoTestsDialogWindow(
            GenerateGoTestsModel(
                project,
                targetFunctions,
                focusedTargetFunctions,
            )
        )
    }

    private fun createTests(model: GenerateGoTestsModel) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(model.project, "Generate Go tests") {
            override fun run(indicator: ProgressIndicator) {

                // readAction is required to read PSI-tree or else "Read access" exception occurs.
                val selectedFunctionsNamesBySourceFiles = runReadAction {
                    model.selectedFunctions.groupBy({ it.containingFile.virtualFile.canonicalPath!! }) { it.name!! }
                }

                IntellijGoUtTestsGenerationController(model, indicator).generateTests(
                    selectedFunctionsNamesBySourceFiles
                )
            }
        })
    }
}