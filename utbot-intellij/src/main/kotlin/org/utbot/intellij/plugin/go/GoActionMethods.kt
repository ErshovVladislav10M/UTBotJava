package org.utbot.intellij.plugin.go

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoPointerType
import com.goide.psi.GoStructType
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

object GoActionMethods {

    private const val goId = "go"
    val goLanguage: Language = Language.findLanguageByID(goId) ?: error("Go language wasn't found")

    private data class PsiTargets(
        val functionsOrMethods: Set<GoFunctionOrMethodDeclaration>,
        val focusedFunctionOrMethod: GoFunctionOrMethodDeclaration?,
    )

    fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (functionsOrMethods, focusedFunctionOrMethod) = getPsiTargets(e) ?: return
        GoDialogProcessor.createDialogAndGenerateTests(
            project,
            functionsOrMethods,
            focusedFunctionOrMethod,
        )
    }

    fun update(e: AnActionEvent) {
        e.presentation.isEnabled = getPsiTargets(e) != null
    }

    private fun getPsiTargets(e: AnActionEvent): PsiTargets? {
        e.project ?: return null

        //The action is being called from editor or return (TODO)
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null

        val file = e.getData(CommonDataKeys.PSI_FILE) as? GoFile ?: return null
        val element = findPsiElement(file, editor) ?: return null

        val containingFunctionOrMethod = getContainingFunctionOrMethod(element)
        val targetFunctionsOrMethods = extractTargetFunctionsOrMethods(file)

        return PsiTargets(
            targetFunctionsOrMethods,
            containingFunctionOrMethod,
        )
    }

    // TODO: logic can be modified
    // for example, maybe suggest methods of the containing struct if present
    private fun extractTargetFunctionsOrMethods(file: GoFile): Set<GoFunctionOrMethodDeclaration> {
        // suggest file functions and methods
        return file.functions.toSet() union file.methods.toSet()
    }

    private fun getContainingFunctionOrMethod(element: PsiElement): GoFunctionOrMethodDeclaration? {
        if (element is GoFunctionOrMethodDeclaration)
            return element

        val parent = element.parent ?: return null
        return getContainingFunctionOrMethod(parent)
    }

    // unused for now, but may be used for more complicated extract logic in future
    @Suppress("unused")
    private fun getContainingStruct(element: PsiElement): GoStructType? =
        PsiTreeUtil.getParentOfType(element, GoStructType::class.java, false)

    // unused for now, but may be used to access all methods of receiver's struct
    @Suppress("unused")
    private fun getMethodReceiverStruct(method: GoMethodDeclaration): GoStructType {
        val receiverType = method.receiverType
        if (receiverType is GoPointerType) {
            return receiverType.type as GoStructType
        }
        return receiverType as GoStructType
    }

    // this method is copy-paste from GenerateTestsActions.kt
    private fun findPsiElement(file: PsiFile, editor: Editor): PsiElement? {
        val offset = editor.caretModel.offset
        var element = file.findElementAt(offset)
        if (element == null && offset == file.textLength) {
            element = file.findElementAt(offset - 1)
        }

        return element
    }
}