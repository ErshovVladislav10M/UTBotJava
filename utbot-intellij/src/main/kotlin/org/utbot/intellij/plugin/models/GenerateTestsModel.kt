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

class GenerateTestsModel(
    project: Project,
    srcModule: Module,
    testModule: Module,
    val jdkVersion: JavaSdkVersion,
    var srcClasses: Set<PsiClass>,
    var selectedMethods: Set<MemberInfo>?,
    var timeout: Long,
    var generateWarningsForStaticMocking: Boolean = false,
    var forceMockHappened: Boolean = false,
    var forceStaticMockHappened: Boolean = false,
    var hasTestFrameworkConflict: Boolean = false,
) : BaseTestsModel(
    project,
    srcModule,
    testModule
) {
    var testPackageName: String? = null
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
}

val PsiClass.packageName: String get() = this.containingFile.containingDirectory.getPackage()?.qualifiedName ?: ""