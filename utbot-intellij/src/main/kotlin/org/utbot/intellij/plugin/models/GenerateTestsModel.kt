package org.utbot.intellij.plugin.models

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.psi.PsiClass
import com.intellij.refactoring.util.classMembers.MemberInfo
import org.jetbrains.kotlin.idea.core.getPackage
import org.utbot.framework.codegen.*
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.framework.plugin.api.MockFramework
import org.utbot.framework.plugin.api.MockStrategyApi
import org.utbot.framework.util.ConflictTriggers
import org.utbot.intellij.plugin.ui.utils.BaseTestsModel
import org.utbot.intellij.plugin.ui.utils.jdkVersion

class GenerateTestsModel(
    override val project: Project,
    override val srcModule: Module,
    potentialTestModules: List<Module>,
    override var srcClasses: Set<PsiClass>,
    var selectedMethods: Set<MemberInfo>?,
    override var timeout: Long,
    var generateWarningsForStaticMocking: Boolean = false,
    var fuzzingValue: Double = 0.05,
) : BaseTestsModel(
    project,
    srcModule,
    potentialTestModules,
    srcClasses,
    timeout,
) {

    lateinit var testFramework: TestFramework
    lateinit var mockStrategy: MockStrategyApi
    var mockFramework: MockFramework? = null
    lateinit var staticsMocking: StaticsMocking
    lateinit var parametrizedTestSource: ParametrizedTestSource
    lateinit var codegenLanguage: CodegenLanguage
    lateinit var runtimeExceptionTestsBehaviour: RuntimeExceptionTestsBehaviour
    lateinit var hangingTestsTimeout: HangingTestsTimeout
    lateinit var forceStaticMocking: ForceStaticMocking
    lateinit var chosenClassesToMockAlways: Set<ClassId>

    val conflictTriggers: ConflictTriggers = ConflictTriggers()


    var runGeneratedTestsWithCoverage : Boolean = false

    val jdkVersion: JavaSdkVersion?
        get() = try {
            testModule.jdkVersion()
        } catch (e: IllegalStateException) {
            // Just ignore it here, notification will be shown in org.utbot.intellij.plugin.ui.utils.ModuleUtilsKt.jdkVersionBy
            null
        }
}

val PsiClass.packageName: String get() = this.containingFile.containingDirectory.getPackage()?.qualifiedName ?: ""