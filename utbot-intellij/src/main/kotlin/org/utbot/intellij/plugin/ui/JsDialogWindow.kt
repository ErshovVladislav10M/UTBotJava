package org.utbot.intellij.plugin.ui

import com.intellij.lang.javascript.refactoring.ui.JSMemberSelectionTable
import com.intellij.lang.javascript.refactoring.util.JSMemberInfo
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.Panel
import com.intellij.ui.layout.Cell
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBUI
import org.utbot.framework.codegen.Mocha
import org.utbot.framework.codegen.TestFramework
import org.utbot.framework.plugin.api.CodeGenerationSettingItem
import org.utbot.intellij.plugin.models.JsTestsModel
import org.utbot.intellij.plugin.ui.components.TestFolderComboWithBrowseButton
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import org.jetbrains.kotlin.idea.core.util.toVirtualFile
import org.utbot.framework.UtSettings
import utils.JsCmdExec
import kotlin.concurrent.thread

private const val MINIMUM_TIMEOUT_VALUE_IN_SECONDS = 1
class JsDialogWindow(val model: JsTestsModel) : DialogWrapper(model.project) {

    private val items = model.fileMethods

    private val functionsTable = JSMemberSelectionTable(items, null, null).apply {
        val height = this.rowHeight * (items.size.coerceAtMost(12) + 1)
        this.preferredScrollableViewportSize = JBUI.size(-1, height)
    }

    private val testSourceFolderField = TestFolderComboWithBrowseButton(model)
    private val testFrameworks: ComboBox<TestFramework> = ComboBox(DefaultComboBoxModel(arrayOf(Mocha)))
    private val timeoutSpinner =
        JBIntSpinner(
            TimeUnit.MILLISECONDS.toSeconds(UtSettings.utBotGenerationTimeoutInMillis).toInt(),
            MINIMUM_TIMEOUT_VALUE_IN_SECONDS,
            Int.MAX_VALUE,
            MINIMUM_TIMEOUT_VALUE_IN_SECONDS
        )
    private var initTestFrameworkPresenceThread: Thread

    private lateinit var panel: DialogPanel

    init {
        if (model.testSourceRoot is FakeVirtualFile || model.testSourceRoot == null) {
            val file = File(model.project.basePath + "/utbot_tests/")
            file.mkdir()
            model.testSourceRoot = file.toVirtualFile()!!
        }
        title = "Generate tests with UtBot"
        initTestFrameworkPresenceThread = thread(start = true) {
            TestFramework.allJsItems.forEach {
                it.isInstalled = findFrameworkLibrary(it.displayName.toLowerCase())
            }
        }
        setResizable(false)
        init()
    }

    @Suppress("UNCHECKED_CAST")
    override fun createCenterPanel(): JComponent {
        panel = panel {
            row("Test source root:") {
                component(testSourceFolderField)
            }
            row("Test framework:") {
                component(
                    Panel().apply {
                        add(testFrameworks as ComboBox<CodeGenerationSettingItem>, BorderLayout.LINE_START)
                    }
                )
            }
            row("Generate test methods for:") {}
            row {
                scrollPane(functionsTable)
            }
            row("Timeout for class:") {
                panelWithHelpTooltip("The execution timeout") {
                    component(timeoutSpinner)
                    component(JBLabel("sec"))
                }

            }
        }
        updateMembersTable()
        return panel
    }

    private inline fun Cell.panelWithHelpTooltip(tooltipText: String?, crossinline init: Cell.() -> Unit): Cell {
        init()
        tooltipText?.let { component(ContextHelpLabel.create(it)) }
        return this
    }
    override fun doOKAction() {
        val selected = functionsTable.selectedMemberInfos.toSet()
        model.selectedMethods = if (selected.any()) selected else emptySet()
        model.testFramework = testFrameworks.item
        model.timeout = TimeUnit.SECONDS.toMillis(timeoutSpinner.number.toLong())
        configureTestFrameworkIfRequired()
        super.doOKAction()
    }

    private fun updateMembersTable() {
        if (items.isEmpty()) isOKActionEnabled = false
        val focusedNames = model.selectedMethods.map { it.member.name }
        val selectedMethods = items.filter {
            focusedNames.contains(it.member.name)
        }
        if (selectedMethods.isEmpty()) {
            checkMembers(items)
        } else {
            checkMembers(selectedMethods)
        }
    }

    private fun configureTestFramework() {
        val selectedTestFramework = testFrameworks.item
        selectedTestFramework.isInstalled = true
        // TODO SEVERE: move version to TestFramework. Here is a hardcode for mocha
        JsCmdExec.runCommand("npm install -l ${selectedTestFramework.displayName.toLowerCase()}@8.0.0",
            model.project.basePath!!
        )
    }

    private fun configureTestFrameworkIfRequired() {
        initTestFrameworkPresenceThread.join()
        val frameworkNotInstalled = !testFrameworks.item.isInstalled
        if (frameworkNotInstalled) {
            if (createTestFrameworkNotificationDialog() == Messages.YES) {
                (object : Task.Backgroundable(model.project, "Install test framework package") {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = "Installing ${testFrameworks.item.displayName} npm package"
                        configureTestFramework()
                    }
                }).queue()
            }
        }
    }

    private fun createTestFrameworkNotificationDialog() = Messages.showYesNoDialog(
        """Selected test framework ${testFrameworks.item.displayName} is not installed into current module. 
            |Would you like to install it now?""".trimMargin(),
        title,
        "Yes",
        "No",
        Messages.getQuestionIcon(),
    )

    private fun findFrameworkLibrary(npmPackageName: String): Boolean {
        val (bufferedReader, _) = JsCmdExec.runCommand("npm list -l", model.project.basePath!!)
        val checkForPackageText = bufferedReader.readText()
        bufferedReader.close()
        if (checkForPackageText == "") {
            Messages.showErrorDialog(
                model.project,
                "Node.js is not installed",
                title,
            )
            return false
        }
        return checkForPackageText.contains(npmPackageName)
    }

    private fun checkMembers(members: Collection<JSMemberInfo>) = members.forEach { it.isChecked = true }
}