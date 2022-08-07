package org.utbot.go.fuzzer

import org.utbot.fuzzer.FuzzedValue
import org.utbot.fuzzer.ModelProvider
import org.utbot.fuzzer.fuzz
import org.utbot.go.GoFunctionOrMethodNode
import org.utbot.go.providers.GoConstantsModelProvider
import org.utbot.go.providers.GoPrimitivesModelProvider
import org.utbot.go.providers.GoStringConstantModelProvider
import org.utbot.go.toFuzzedMethodDescription

fun goFuzzing(
    modelProvider: (ModelProvider) -> ModelProvider = { it },
    functionOrMethodNode: GoFunctionOrMethodNode
): Sequence<List<FuzzedValue>> {

    /* Unit test generation for functions or methods with no parameters can be useful:
    * one can fix panic behaviour or its absence */
    if (functionOrMethodNode.parameters.isEmpty()) {
        return sequenceOf(emptyList())
    }

    // find concrete values in function or method body
    functionOrMethodNode.body.accept(DummyGoAstVisitor)

    // TODO: add more ModelProvider-s
    val modelProviderWithFallback = modelProvider(
        ModelProvider.of(
            GoConstantsModelProvider,
            GoStringConstantModelProvider,
            GoPrimitivesModelProvider
        )
    )

    val fuzzedFunctionOrMethodDescription =
        functionOrMethodNode.toFuzzedMethodDescription(DummyGoAstVisitor.fuzzedConcreteValues)

    return fuzz(fuzzedFunctionOrMethodDescription, modelProviderWithFallback)
}