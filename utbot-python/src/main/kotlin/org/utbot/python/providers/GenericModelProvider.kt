package org.utbot.python.providers

import org.utbot.framework.plugin.api.*
import org.utbot.fuzzer.*
import java.lang.Integer.min
import kotlin.random.Random

object GenericModelProvider: ModelProvider {
    val concreteTypesModelProvider = ModelProvider.of(
        ConstantModelProvider,
        DefaultValuesModelProvider,
        GenericModelProvider
    )

    override fun generate(description: FuzzedMethodDescription): Sequence<FuzzedParameter> = sequence {
        fun <T: UtModel> fuzzGeneric(parameters: List<PythonClassId>, modelConstructor: (List<List<FuzzedValue>>) -> T) = sequence {
            val syntheticGenericType = FuzzedMethodDescription(
                "${description.name}<syntheticGenericList>",
                pythonNoneClassId,
                parameters,
                description.concreteValues
            )
            fuzz(syntheticGenericType, concreteTypesModelProvider)
                .randomChunked()
                .map(modelConstructor)
                .forEach {
                    yield(FuzzedParameter(0, it.fuzzed()))
                }
        }

        fun parseList(matchResult: MatchResult): Sequence<FuzzedParameter> {
            val genericType = if (matchResult.groupValues.size >= 2) PythonClassId(matchResult.groupValues[1]) else pythonAnyClassId
            return fuzzGeneric(listOf(genericType)) { list ->
                PythonListModel(
                    list.size,
                    list.flatten().map { it.model }
                )
            }
        }

        fun parseDict(matchResult: MatchResult): Sequence<FuzzedParameter> {
            val genericKeyType = if (matchResult.groupValues.size >= 2) PythonClassId(matchResult.groupValues[1]) else pythonAnyClassId
            val genericValueType = if (matchResult.groupValues.size >= 3) PythonClassId(matchResult.groupValues[2]) else pythonAnyClassId
            return fuzzGeneric(listOf(genericKeyType, genericValueType)) { list ->
                    PythonDictModel(
                        list.size,
                        list.associate { pair ->
                            pair[0].model to pair[1].model
                        }
                    )
                }
        }

        fun parseSet(matchResult: MatchResult): Sequence<FuzzedParameter> {
            val genericType = if (matchResult.groupValues.size >= 2) PythonClassId(matchResult.groupValues[1]) else pythonAnyClassId
            return fuzzGeneric(listOf(genericType)) { list ->
                    PythonSetModel(
                        list.size,
                        list.flatten().map { it.model }.toSet(),
                    )
                }
        }

        val modelRegexMap = mapOf<Regex, (MatchResult) -> Sequence<FuzzedParameter>>(
            Regex("builtins.list\\[(.*)]") to { matchResult -> parseList(matchResult) },
            Regex("[Ll]ist\\[(.*)]") to { matchResult -> parseList(matchResult) },
            Regex("typing.List\\[(.*)]") to { matchResult -> parseList(matchResult) },

            Regex("builtins.dict\\[(.*), *(.*)]") to { matchResult -> parseDict(matchResult) },
            Regex("[Dd]ict\\[(.*), *(.*)]") to { matchResult -> parseDict(matchResult) },
            Regex("typing.Dict\\[(.*), *(.*)]") to { matchResult -> parseDict(matchResult) },

            Regex("builtins.set\\[(.*)]") to { matchResult -> parseSet(matchResult) },
            Regex("[Ss]et\\[(.*)]") to { matchResult -> parseSet(matchResult) },
            Regex("typing.Set\\[(.*)]") to { matchResult -> parseSet(matchResult) },
        )

        description.parametersMap.forEach { (classId, parameterIndices) ->
            val annotation = classId.name
            parameterIndices.forEach { _ ->
                modelRegexMap.entries.forEach { (regex, action) ->
                    val result = regex.matchEntire(annotation)
                    if (result != null) {
                        yieldAll(action(result).take(10))
                    }
                }
            }
        }
    }
}

fun Sequence<List<FuzzedValue>>.randomChunked(): Sequence<List<List<FuzzedValue>>> {
    val seq = this
    val maxSize = 15
    val itemsToGenerateFrom = seq.take(20).toList()
    return sequenceOf(emptyList<List<FuzzedValue>>()) + generateSequence {
        if (itemsToGenerateFrom.isEmpty())
            return@generateSequence null
        val size = Random.nextInt(1, min(maxSize, itemsToGenerateFrom.size) + 1)
        (0 until size).map {
            val index = Random.nextInt(0, itemsToGenerateFrom.size)
            itemsToGenerateFrom[index]
        }
    }
}
