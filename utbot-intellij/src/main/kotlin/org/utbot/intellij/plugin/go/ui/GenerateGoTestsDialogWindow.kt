package org.utbot.intellij.plugin.go.ui

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.refactor.ui.GoDeclarationInfo
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.utbot.intellij.plugin.go.models.GenerateGoTestsModel
import javax.swing.JComponent

// This class is highly inspired by GenerateTestsDialogWindow.
class GenerateGoTestsDialogWindow(val model: GenerateGoTestsModel) : DialogWrapper(model.project) {

    private val targetInfos = model.targetFunctions.toInfos()
    private val targetFunctionsTable = GoFunctionsSelectionTable(targetInfos).apply {
        val height = this.rowHeight * (targetInfos.size.coerceAtMost(12) + 1)
        this.preferredScrollableViewportSize = JBUI.size(-1, height)
    }

    private lateinit var panel: DialogPanel

    init {
        title = "Generate tests with UtBot"
        setResizable(false)
        init()
    }

    override fun createCenterPanel(): JComponent {
        panel = panel {
            row("Test source root: near to source files") {}
            row("Generate test methods for:") {}
            row {
                scrollPane(targetFunctionsTable)
            }
        }
        updateFunctionsOrMethodsTable()
        return panel
    }

    override fun doOKAction() {
        model.selectedFunctions = targetFunctionsTable.selectedMemberInfos.fromInfos()
        super.doOKAction()
    }

    private fun updateFunctionsOrMethodsTable() {
        val focusedTargetFunctionsNames = model.focusedTargetFunctions.map { it.name }.toSet()
        val selectedInfos = targetInfos.filter {
            it.declaration.name in focusedTargetFunctionsNames
        }
        if (selectedInfos.isEmpty()) {
            checkInfos(targetInfos)
        } else {
            checkInfos(selectedInfos)
        }
        targetFunctionsTable.setMemberInfos(targetInfos)

        if (targetFunctionsTable.selectedMemberInfos.isEmpty()) {
            isOKActionEnabled = false
        }
    }

    private fun checkInfos(infos: Collection<GoDeclarationInfo>) {
        infos.forEach { it.isChecked = true }
    }

    private fun Collection<GoFunctionOrMethodDeclaration>.toInfos(): Set<GoDeclarationInfo> =
        this.map { GoDeclarationInfo(it) }.toSet()

    private fun Collection<GoDeclarationInfo>.fromInfos(): Set<GoFunctionOrMethodDeclaration> =
        this.map { it.declaration as GoFunctionOrMethodDeclaration }.toSet()

    @Suppress("DuplicatedCode") // Is cloned from GenerateTestsDialogWindow.
    override fun doValidate(): ValidationInfo? {
        targetFunctionsTable.tableHeader?.background = UIUtil.getTableBackground()
        targetFunctionsTable.background = UIUtil.getTableBackground()
        if (targetFunctionsTable.selectedMemberInfos.isEmpty()) {
            targetFunctionsTable.tableHeader?.background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
            targetFunctionsTable.background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
            return ValidationInfo(
                "Tick any methods to generate tests for", targetFunctionsTable
            )
        }
        return null
    }
}