package org.utbot.intellij.plugin.go.models

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.project.Project

/**
 * Contains information about Go tests generation task required for intellij plugin logic.
 *
 * targetFunctions: all possible functions to generate tests for;
 * focusedTargetFunctions: such target functions that user is focused on while plugin execution;
 * selectedFunctions: finally selected functions to generate tests for.
 */
data class GenerateGoTestsModel(
    val project: Project,
    val targetFunctions: Set<GoFunctionOrMethodDeclaration>,
    val focusedTargetFunctions: Set<GoFunctionOrMethodDeclaration>,
) {
    lateinit var selectedFunctions: Set<GoFunctionOrMethodDeclaration>
}