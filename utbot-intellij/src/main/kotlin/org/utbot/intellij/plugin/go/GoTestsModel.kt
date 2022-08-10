package org.utbot.intellij.plugin.go

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.project.Project

class GoTestsModel(
    val project: Project,
    val functionsOrMethods: Set<GoFunctionOrMethodDeclaration>,
    val focusedFunctionOrMethod: GoFunctionOrMethodDeclaration?,
) {
    lateinit var selectedFunctionsOrMethods: Set<GoFunctionOrMethodDeclaration>
    lateinit var srcFiles: Set<GoFile>
}