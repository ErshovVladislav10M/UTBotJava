package org.utbot.intellij.plugin.models

import com.intellij.lang.javascript.refactoring.util.JSMemberInfo
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.utbot.framework.codegen.TestFramework
import org.utbot.intellij.plugin.ui.utils.BaseTestsModel

class JsTestsModel(
    project: Project,
    srcModule: Module,
    potentialTestModules: List<Module>,
    timeout: Long,
    val fileMethods: Set<JSMemberInfo>,
    var selectedMethods: Set<JSMemberInfo>,
) : BaseTestsModel(
    project,
    srcModule,
    potentialTestModules,
    timeout
) {
    lateinit var testFramework: TestFramework
    lateinit var containingFilePath: String
}