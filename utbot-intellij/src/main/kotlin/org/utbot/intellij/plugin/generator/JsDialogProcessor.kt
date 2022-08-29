package org.utbot.intellij.plugin.generator

import api.JsTestGenerator
import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.lang.ecmascript6.psi.ES6Class
import com.intellij.lang.javascript.refactoring.util.JSMemberInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.file.PsiDirectoryFactory
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.util.application.invokeLater
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.konan.file.File
import org.utbot.common.runBlockingWithCancellationPredicate
import org.utbot.common.runIgnoringCancellationException
import org.utbot.intellij.plugin.models.JsTestsModel
import org.utbot.intellij.plugin.ui.JsDialogWindow
import org.utbot.intellij.plugin.ui.actions.JsActionMethods
import org.utbot.intellij.plugin.ui.utils.isBuildWithGradle
import org.utbot.intellij.plugin.ui.utils.showErrorDialogLater
import org.utbot.intellij.plugin.ui.utils.suitableTestSourceRoots
import org.utbot.intellij.plugin.ui.utils.testModules

object JsDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        containingFilePath: String,
        editor: Editor,
    ) {
        createDialog(project, srcModule, fileMethods, focusedMethod, containingFilePath)?.let { dialogProcessor ->
            if (!dialogProcessor.showAndGet()) return
            /*
                Since Tern.js accesses containing file, sync with file system required before test generation.
             */
            runWriteAction {
                with(FileDocumentManager.getInstance()) {
                    saveDocument(editor.document)
                }
            }
            createTests(dialogProcessor.model, containingFilePath, editor)
        }
    }

    private fun createDialog(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        filePath: String,
    ): JsDialogWindow? {
        val testModules = srcModule.testModules(project)

        if (project.isBuildWithGradle && testModules.flatMap { it.suitableTestSourceRoots() }.isEmpty()) {
            val errorMessage = """
                <html>No test source roots found in the project.<br>
                Please, <a href="https://www.jetbrains.com/help/idea/testing.html#add-test-root">create or configure</a> at least one test source root.
            """.trimIndent()
            showErrorDialogLater(project, errorMessage, "Test source roots not found")
            return null
        }

        return JsDialogWindow(
            JsTestsModel(
                project,
                srcModule,
                testModules,
                emptySet(),
                3L,
                fileMethods,
                if (focusedMethod != null) setOf(focusedMethod) else emptySet(),
            ).apply {
                containingFilePath = filePath
            }
        )
    }

    private fun createTests(model: JsTestsModel, containingFilePath: String, editor: Editor) {
        val normalizedContainingFilePath = containingFilePath.replace("/", "\\")
        (object : Task.Backgroundable(model.project, "Generate tests") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Generate tests: read classes"
                runIgnoringCancellationException {
                    runBlockingWithCancellationPredicate({ indicator.isCanceled }) {
                        val testDir = PsiDirectoryFactory.getInstance(project).createDirectory(
                            model.testSourceRoot!!
                        )
                        val testFileName = normalizedContainingFilePath.substringAfterLast(File.separator)
                            .replace(Regex(".js"), "Test.js")
                        val testGenerator = JsTestGenerator(
                            fileText = editor.document.text,
                            sourceFilePath = normalizedContainingFilePath,
                            projectPath = model.project.basePath?.replace("/", "\\")
                                ?: throw IllegalStateException("Can't access project path."),
                            selectedMethods = runReadAction {
                                model.selectedMethods.map {
                                    it.member.name!!
                                }
                            },
                            parentClassName = runReadAction {
                                val name = (model.selectedMethods.first().member.parent as ES6Class).name
                                if (name == "toplevelHack") null else name
                            },
                            outputFilePath = "${testDir.virtualFile.path}/$testFileName".replace("/", "\\"),
                            exportsManager = partial(::manageExports, editor, project),
                            timeout = model.timeout,
                        )

                        indicator.fraction = indicator.fraction.coerceAtLeast(0.9)
                        indicator.text = "Generate code for tests"

                        val generatedCode = testGenerator.run()
                        invokeLater {
                            runWriteAction {
                                val testPsiFile =
                                    testDir.findFile(testFileName) ?: PsiFileFactory.getInstance(project)
                                        .createFileFromText(testFileName, JsActionMethods.jsLanguage, generatedCode)
                                val testFileEditor =
                                    CodeInsightUtil.positionCursor(project, testPsiFile, testPsiFile)
                                CodeGenerationController.unblockDocument(project, testFileEditor.document)
                                testFileEditor.document.setText(generatedCode)
                                CodeGenerationController.unblockDocument(project, testFileEditor.document)
                                testDir.findFile(testFileName) ?: testDir.add(testPsiFile)
                            }
                        }

                    }
                }
            }
        }).queue()
    }

    private fun <A, B, C> partial(f: (A, B, C) -> Unit, a: A, b: B): (C) -> Unit {
        return { c: C -> f(a, b, c) }
    }

    private fun manageExports(editor: Editor, project: Project, exports: List<String>) {
        AppExecutorUtil.getAppExecutorService().submit {
            invokeLater {
                val startComment = "// Start of exports generated by UTBot"
                val endComment = "// End of exports generated by UTBot"
                val exportLine = exports.joinToString(", ")
                val fileText = editor.document.text
                when {
                    fileText.contains("module.exports = {$exportLine}") -> {}
                    fileText.contains(startComment) && !fileText.contains("module.exports = {$exportLine}") -> {
                        val regex = Regex("\n$startComment\n(.*)\n$endComment")
                        regex.find(fileText)?.groups?.get(1)?.value?.let {
                            val exportsRegex = Regex("\\{(.*)}")
                            val existingExportsLine = exportsRegex.find(it)!!.groupValues[1]
                            val existingExportsSet = existingExportsLine.filterNot {c -> c == ' ' }.split(',').toMutableSet()
                            existingExportsSet.addAll(exports)
                            val resLine = existingExportsSet.joinToString()
                            val swappedText = fileText.replace(it, "module.exports = {$resLine}")
                            runWriteAction {
                                with(editor.document) {
                                    CodeGenerationController.unblockDocument(project, this)
                                    setText(swappedText)
                                    CodeGenerationController.unblockDocument(project, this)
                                }
                                with(FileDocumentManager.getInstance()) {
                                    saveDocument(editor.document)
                                }
                            }
                        }
                    }

                    else -> {
                        val line = buildString {
                            append("\n$startComment")
                            append("\nmodule.exports = {$exportLine}")
                            append("\n$endComment")
                        }
                        runWriteAction {
                            with(editor.document) {
                                CodeGenerationController.unblockDocument(project, this)
                                setText(fileText + line)
                                CodeGenerationController.unblockDocument(project, this)
                            }
                            with(FileDocumentManager.getInstance()) {
                                saveDocument(editor.document)
                            }
                        }
                    }
                }
            }
        }
    }
}