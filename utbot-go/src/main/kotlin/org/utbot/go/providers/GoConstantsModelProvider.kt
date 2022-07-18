package org.utbot.go.providers

import org.utbot.framework.plugin.api.GoClassId
import org.utbot.framework.plugin.api.GoUtPrimitiveModel
import org.utbot.framework.plugin.api.UtPrimitiveModel
import org.utbot.framework.plugin.api.util.isPrimitive
import org.utbot.fuzzer.*
import org.utbot.fuzzer.ModelProvider.Companion.yieldValue

// This class is a copy of ConstantsModelProvider up to GoClassId.isPrimitive and GoUtPrimitiveModel usage.
@Suppress("DuplicatedCode")
object GoConstantsModelProvider : ModelProvider {

    override fun generate(description: FuzzedMethodDescription): Sequence<FuzzedParameter> = sequence {
        description.concreteValues
            .asSequence()
            .filter { (classId, _, _) -> (classId as GoClassId).isPrimitive }
            .forEach { (_, value, op) ->
                sequenceOf(
                    GoUtPrimitiveModel(value).fuzzed { summary = "%var% = $value" },
                    modifyValue(value, op)
                )
                    .filterNotNull()
                    .forEach { m ->
                        description.parametersMap.getOrElse(m.model.classId) { emptyList() }.forEach { index ->
                            yieldValue(index, m)
                        }
                    }
            }
    }

    private fun modifyValue(value: Any, op: FuzzedOp): FuzzedValue? {
        if (!op.isComparisonOp()) return null
        val multiplier = if (op == FuzzedOp.LT || op == FuzzedOp.GE) -1 else 1
        return when (value) {
            is Boolean -> value.not()
            is Byte -> value + multiplier.toByte()
            is Char -> (value.toInt() + multiplier).toChar()
            is Short -> value + multiplier.toShort()
            is Int -> value + multiplier
            is Long -> value + multiplier.toLong()
            is Float -> value + multiplier.toDouble()
            is Double -> value + multiplier.toDouble()
            else -> null
        }?.let {
            UtPrimitiveModel(it).fuzzed {
                summary = "%var% ${
                    (if (op == FuzzedOp.EQ || op == FuzzedOp.LE || op == FuzzedOp.GE) {
                        op.reverseOrNull() ?: error("cannot find reverse operation for $op")
                    } else op).sign
                } $value"
            }
        }
    }
}