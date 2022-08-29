package org.utbot.intellij.plugin.ui.utils

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import org.utbot.intellij.plugin.models.packageName

open class BaseTestsModel(
    open val project: Project,
    open val srcModule: Module,
    val potentialTestModules: List<Module>,
    open var srcClasses: Set<PsiClass>,
    open var timeout: Long,
) {
    // GenerateTestsModel is supposed to be created with non-empty list of potentialTestModules.
    // Otherwise, the error window is supposed to be shown earlier.
    var testModule: Module = potentialTestModules.firstOrNull() ?: error("Empty list of test modules in model")
    var testSourceRoot: VirtualFile? = null
    var testPackageName: String? = null

    fun setSourceRootAndFindTestModule(newTestSourceRoot: VirtualFile?) {
        requireNotNull(newTestSourceRoot)
        testSourceRoot = newTestSourceRoot
        testModule = ModuleUtil.findModuleForFile(newTestSourceRoot, project)
            ?: error("Could not find module for $newTestSourceRoot")
    }

    val isMultiPackage: Boolean by lazy {
        srcClasses.map { it.packageName }.distinct().size != 1
    }

}