package org.utbot.go.fuzzer

import org.utbot.go.GoFunctionOrMethodNode
import org.utbot.go.GoFuzzedFunction
import org.utbot.go.GoFuzzedFunctionOrMethodTestCase
import org.utbot.go.executor.GoExecutor

fun generateTestCasesForGoFile(fileFunctions: List<GoFunctionOrMethodNode>): List<GoFuzzedFunctionOrMethodTestCase> {
    val fileFuzzedFunctions = fileFunctions.map { functionNode ->
        goFuzzing(functionOrMethodNode = functionNode).map { fuzzedParametersValues ->
            GoFuzzedFunction(functionNode, fuzzedParametersValues)
        }.toList()
    }.flatten()

    return GoExecutor.executeGoFileFuzzedFunctions(fileFuzzedFunctions).map { (fuzzedFunction, executionResult) ->
        GoFuzzedFunctionOrMethodTestCase(
            fuzzedFunction.functionNode,
            fuzzedFunction.fuzzedParametersValues,
            executionResult
        )
    }
}