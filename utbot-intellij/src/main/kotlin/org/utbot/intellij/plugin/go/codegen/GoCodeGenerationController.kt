package org.utbot.intellij.plugin.go.codegen

import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.command.executeCommand
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.util.application.invokeLater
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.go.GoFileNode
import org.utbot.go.GoFuzzedFunctionOrMethodTestCase
import org.utbot.go.codegen.GoSimpleCodeGenerator
import org.utbot.intellij.plugin.go.GoTestsModel
import org.utbot.intellij.plugin.ui.utils.showErrorDialogLater
import java.io.File
import java.nio.file.Paths

// TODO: get rid of IDEA plugin here and move to utbot-go module
// This class is highly inspired by CodeGenerationController
object GoCodeGenerationController {
    private enum class Target { THREAD_POOL, READ_ACTION, WRITE_ACTION, EDT_LATER }

    // May be used in the application separate from IDEA
    @Suppress("unused")
    fun generateTestsFilesAndCodeSimply(testCasesByFile: Map<GoFileNode, List<GoFuzzedFunctionOrMethodTestCase>>) {
        testCasesByFile.keys.forEach { fileNode ->
            val fileTestsString = GoSimpleCodeGenerator.generateTestFileCode(testCasesByFile[fileNode]!!)
            println("GENERATED TEST FOR FILE ${fileNode.name}:\n")
            println(fileTestsString) // TODO: create file and write each test into it
        }
    }

    fun generateTestsFilesAndCode(
        model: GoTestsModel,
        testCasesByFile: Map<String, List<GoFuzzedFunctionOrMethodTestCase>>
    ) {
        for (srcFilePath in testCasesByFile.keys) {
            val fileTestCases = testCasesByFile[srcFilePath] ?: continue
            val testFileName = createTestFileName(srcFilePath)
            try {
                val srcFile = findPsiFileByPath(model, srcFilePath)
                val testFile = createTestFileNearToSource(srcFile, testFileName) ?: continue
                runWriteCommandAction(model.project, "Generate Go tests with UtBot", null, {
                    try {
                        generateCode(testFile, fileTestCases)
                    } catch (e: IncorrectOperationException) {
                        showCreatingFileError(model.project, testFileName)
                    }
                })
            } catch (e: IncorrectOperationException) {
                showCreatingFileError(model.project, testFileName)
            }
        }
    }

    private fun findPsiFileByPath(model: GoTestsModel, srcFilePath: String): PsiFile {
        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(Paths.get(srcFilePath))!!
        return PsiManager.getInstance(model.project).findFile(virtualFile)!!
    }

    private fun createTestFileNearToSource(srcFile: PsiFile, testFileName: String): PsiFile? {
        val testFileNameWithExtension = testFileName + CodegenLanguage.GO.extension
        val srcFilePackage = srcFile.containingDirectory

        runWriteAction { srcFilePackage.findFile(testFileNameWithExtension)?.delete() }
        runWriteAction { srcFilePackage.createFile(testFileNameWithExtension) }

        return srcFilePackage.findFile(testFileNameWithExtension)
    }

    private fun createTestFileName(srcFilePath: String) = File(srcFilePath).nameWithoutExtension + "_go_ut_test"

    private fun generateCode(
        testFile: PsiFile,
        testCases: List<GoFuzzedFunctionOrMethodTestCase>,
    ) {
        val editor = CodeInsightUtil.positionCursor(testFile.project, testFile, testFile)
        //TODO: Use PsiDocumentManager.getInstance(model.project).getDocument(file)
        // if we don't want to open _all_ new files with tests in editor one-by-one
        run(Target.THREAD_POOL) {
            val generatedTestsCode = GoSimpleCodeGenerator.generateTestFileCode(testCases)
            run(Target.EDT_LATER) {
                run(Target.WRITE_ACTION) {
                    unblockDocument(testFile.project, editor.document)
                    // TODO: JIRA:1246 - display warnings if we rewrite the file
                    executeCommand(testFile.project, "Insert Generated Tests") {
                        editor.document.setText(generatedTestsCode)
                    }
                    unblockDocument(testFile.project, editor.document)
                }
            }
        }
    }

    private fun unblockDocument(project: Project, document: Document) {
        PsiDocumentManager.getInstance(project).apply {
            commitDocument(document)
            doPostponedOperationsAndUnblockDocument(document)
        }
    }

    private fun run(target: Target, runnable: Runnable) {
        when (target) {
            Target.THREAD_POOL -> AppExecutorUtil.getAppExecutorService().submit {
                runnable.run()
            }
            Target.READ_ACTION -> runReadAction { runnable.run() }
            Target.WRITE_ACTION -> runWriteAction { runnable.run() }
            Target.EDT_LATER -> invokeLater { runnable.run() }
        }
    }

    private fun showCreatingFileError(project: Project, testFileName: String) {
        showErrorDialogLater(
            project,
            message = "Cannot Create File '$testFileName'",
            title = "Failed to Create File"
        )
    }
}