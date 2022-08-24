package org.utbot.intellij.plugin.ui.utils

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

open class BaseTestsModel(
    open val project: Project,
    open val srcModule: Module,
    open val potentialTestModules: List<Module>,
) {
    // GenerateTestsModel is supposed to be created with non-empty list of potentialTestModules.
    // Otherwise, the error window is supposed to be shown earlier.
    var testModule: Module = potentialTestModules.firstOrNull() ?: error("Empty list of test modules in model")
    var testSourceRoot: VirtualFile? = null

    fun setSourceRootAndFindTestModule(newTestSourceRoot: VirtualFile?) {
        requireNotNull(newTestSourceRoot)
        testSourceRoot = newTestSourceRoot
        testModule = ModuleUtil.findModuleForFile(newTestSourceRoot, project)
            ?: error("Could not find module for $newTestSourceRoot")
    }
}