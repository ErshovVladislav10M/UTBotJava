package org.utbot.intellij.plugin.csharp

object CSharpActionMethods {
    const val csharpID = "CSharp"

    /*data class Targets(
        val functions: Set<PyFunction>,
        val containingClass: PyClass?,
        val focusedFunction: PyFunction?,
        val file: PyFile
    )

    fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (functions, containingClass, focusedFunction, file) = getPsiTargets(e) ?: return

        PythonDialogProcessor.createDialogAndGenerateTests(
            project,
            functions,
            containingClass,
            focusedFunction,
            file
        )
    }

    fun update(e: AnActionEvent) {
        e.presentation.isEnabled = getPsiTargets(e) != null
    }

    private fun getPsiTargets(e: AnActionEvent): Targets? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.PSI_FILE) as? PyFile ?: return null
        val element = findPsiElement(file, editor) ?: return null

        val containingFunction = IterationUtils.getContainingElement<PyFunction>(element)
        val containingClass = IterationUtils.getContainingElement<PyClass>(element)

        if (containingClass == null) {
            val functions = file.topLevelFunctions
            if (functions.isEmpty())
                return null

            val focusedFunction = if (functions.contains(containingFunction)) containingFunction else null
            return Targets(functions.toSet(), null, focusedFunction, file)
        }

        val functions = containingClass.methods
        if (functions.isEmpty())
            return null

        val focusedFunction = if (functions.any {it.name == containingFunction?.name}) containingFunction else null
        return Targets(functions.toSet(), containingClass, focusedFunction, file)
    }

    // this method is copy-paste from GenerateTestsActions.kt
    private fun findPsiElement(file: PsiFile, editor: Editor): PsiElement? {
        val offset = editor.caretModel.offset
        var element = file.findElementAt(offset)
        if (element == null && offset == file.textLength) {
            element = file.findElementAt(offset - 1)
        }

        return element
    }*/
}