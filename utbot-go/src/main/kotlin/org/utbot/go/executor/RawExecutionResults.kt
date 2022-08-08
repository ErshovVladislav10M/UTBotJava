package org.utbot.go.executor

internal data class PanicMessage(val rawValue: String?, val rawGoType: String, val implementsError: Boolean)

internal data class RawExecutionResult(
    val functionName: String,
    val resultRawValues: List<String?>,
    val panicMessage: PanicMessage?
)

internal data class RawExecutionResults(val results: List<RawExecutionResult>)