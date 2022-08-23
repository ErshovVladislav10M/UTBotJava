package org.utbot.framework.codegen.model.constructor

import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.executableId
import org.utbot.framework.plugin.api.util.objectClassId
import org.utbot.framework.plugin.api.util.voidClassId
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.FieldId
import soot.jimple.JimpleBody

data class CgMethodTestSet private constructor(
    val executableId: ExecutableId,
    val jimpleBody: JimpleBody? = null,
    val errors: Map<String, Int> = emptyMap(),
    val clustersInfo: List<Pair<UtClusterInfo?, IntRange>>,
) {
    var executions: List<UtExecution> = emptyList()
        private set

    constructor(from: UtMethodTestSet) : this(
        from.method.callable.executableId,
        from.jimpleBody,
        from.errors,
        from.clustersInfo
    ) {
        executions = from.executions
    }

    /**
     * Splits [CgMethodTestSet] into separate test sets having
     * unique result model [ClassId] in each subset.
     */
    fun splitExecutionsByResult() : List<CgMethodTestSet> {
        val successfulExecutions = executions.filter { it.result is UtExecutionSuccess }
        val failureExecutions = executions.filter { it.result is UtExecutionFailure }

        val executionsByResult: MutableMap<ClassId, List<UtExecution>> =
            successfulExecutions
                .groupBy { (it.result as UtExecutionSuccess).model.classId }.toMutableMap()

        // if we have failure executions, we add them to the first successful executions group
        val groupClassId = executionsByResult.keys.firstOrNull()
        if (groupClassId != null) {
            executionsByResult[groupClassId] = executionsByResult[groupClassId]!! + failureExecutions
        } else {
            executionsByResult[objectClassId] = failureExecutions
        }

        return executionsByResult.map{ (_, executions) -> substituteExecutions(executions) }
    }

    /**
     * Splits [CgMethodTestSet] test sets by affected static fields statics.
     *
     * A separate test set is created for each combination of modified statics.
     */
    fun splitExecutionsByChangedStatics(): List<CgMethodTestSet> {
        val executionsByStaticsUsage: Map<Set<FieldId>, List<UtExecution>> =
            executions.groupBy { it.stateBefore.statics.keys }

        return executionsByStaticsUsage.map { (_, executions) -> substituteExecutions(executions) }
    }

    /**
     * Finds a [ClassId] of all result models in executions.
     *
     * Tries to find a unique result type in testSets or
     * gets executable return type.
     */
    fun resultType(): ClassId {
        return when (executableId.returnType) {
            voidClassId -> executableId.returnType
            else -> {
                val successfulExecutions = executions.filter { it.result is UtExecutionSuccess }
                if (successfulExecutions.isNotEmpty()) {
                    successfulExecutions
                        .map { (it.result as UtExecutionSuccess).model.classId }
                        .distinct()
                        .singleOrNull()
                        ?: executableId.returnType
                } else {
                    executableId.returnType
                }
            }
        }
    }

    private fun substituteExecutions(newExecutions: List<UtExecution>): CgMethodTestSet =
        copy().apply { executions = newExecutions }
}