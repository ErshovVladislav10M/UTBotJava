package org.utbot.intellij.plugin.js.fuzzer

import com.oracle.js.parser.ir.FunctionNode
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.MethodId
import org.utbot.framework.plugin.api.util.jsUndefinedClassId
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.FuzzedValue
import org.utbot.fuzzer.ModelProvider
import org.utbot.fuzzer.fuzz
import org.utbot.intellij.plugin.js.fuzzer.providers.JsConstantsModelProvider
import org.utbot.intellij.plugin.js.fuzzer.providers.JsStringModelProvider
import org.utbot.intellij.plugin.js.fuzzer.providers.JsUndefinedModelProvider

fun jsFuzzing(
    modelProvider: (ModelProvider) -> ModelProvider = { it },
    method: FunctionNode
): Sequence<List<FuzzedValue>> {
    val execId = MethodId(
        JsClassId("debug"),
        method.name.toString(),
        jsUndefinedClassId,
        method.parameters.toList().map { jsUndefinedClassId }
    )
    method.body.accept(JsAstVisitor)
    val modelProviderWithFallback = modelProvider(
        ModelProvider.of(
            JsConstantsModelProvider,
            JsUndefinedModelProvider,
            JsStringModelProvider,
        )
    )
    val methodUnderTestDescription = FuzzedMethodDescription(execId, JsAstVisitor.fuzzedConcreteValues).apply {
        compilableName = method.name.toString()
        val names = method.parameters.map { it.name.toString() }
        parameterNameMap = { index -> names.getOrNull(index) }
    }
    return fuzz(methodUnderTestDescription, modelProviderWithFallback)
}