package org.utbot.go.executor

import org.utbot.framework.plugin.api.GoTypeId
import org.utbot.framework.plugin.api.GoUtModel

interface GoUtExecutionResult

interface GoUtExecutionCompleted : GoUtExecutionResult {
    val models: List<GoUtModel>
}

data class GoUtExecutionSuccess(override val models: List<GoUtModel>) : GoUtExecutionCompleted

data class GoUtExecutionWithNonNilError(override val models: List<GoUtModel>) : GoUtExecutionCompleted

data class GoUtPanicFailure(
    val panicMessageModel: GoUtModel,
    val panicMessageOriginalGoType: GoTypeId
) : GoUtExecutionResult