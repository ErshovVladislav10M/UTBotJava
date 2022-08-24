package org.utbot.fuzzer.providers

import org.utbot.framework.plugin.api.UtPrimitiveModel
import org.utbot.framework.plugin.api.util.charClassId
import org.utbot.framework.plugin.api.util.stringClassId
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.FuzzedParameter
import org.utbot.fuzzer.ModelProvider
import org.utbot.fuzzer.ModelProvider.Companion.yieldAllValues
import org.utbot.fuzzer.ModelProvider.Companion.yieldValue

object StringConstantModelProvider : ModelProvider {

    override fun generate(description: FuzzedMethodDescription): Sequence<FuzzedParameter> = sequence {
        description.concreteValues
            .asSequence()
            .filter { (classId, _) -> classId == stringClassId }
            .forEach { (_, value, _) ->
                description.parametersMap.getOrElse(stringClassId) { emptyList() }.forEach { index ->
                    yieldValue(index, UtPrimitiveModel(value).fuzzed { summary = "%var% = string" })
                }
            }
        val charsAsStrings = description.concreteValues
            .asSequence()
            .filter { (classId, _) -> classId == charClassId }
            .map { (_, value, _) ->
                UtPrimitiveModel((value as Char).toString()).fuzzed {
                    summary = "%var% = $value"
                }
            }
        yieldAllValues(description.parametersMap.getOrElse(stringClassId) { emptyList() }, charsAsStrings)
    }

    fun mutate(random: Random, value: String?, op: FuzzedOp): String? {
        if (value == null || value.isEmpty() || op != FuzzedOp.CH) return null
        val indexOfMutation = random.nextInt(value.length)
        return value.replaceRange(indexOfMutation, indexOfMutation + 1, SingleCharacterSequence(value[indexOfMutation] - random.nextInt(1, 128)))
    }

    class SingleCharacterSequence(private val character: Char) : CharSequence {
        override val length: Int
            get() = 1

        override fun get(index: Int): Char = character

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            throw UnsupportedOperationException()
        }

    }
}