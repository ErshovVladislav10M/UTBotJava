package org.utbot.python

import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.functionStmts.FunctionDef
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.framework.plugin.api.*

data class PythonArgument(val name: String, val annotation: String?)

interface PythonMethod {
    val name: String
    val returnAnnotation: String?
    val arguments: List<PythonArgument>
    fun asString(): String
    fun ast(): FunctionDef
}

sealed class PythonResult(val parameters: List<UtModel>)

class PythonError(val utError: UtError, parameters: List<UtModel>): PythonResult(parameters)
class PythonExecution(val utExecution: UtExecution, parameters: List<UtModel>): PythonResult(parameters)

data class PythonTestSet(
    val method: PythonMethod,
    val executions: List<PythonExecution>,
    val errors: List<PythonError>
)