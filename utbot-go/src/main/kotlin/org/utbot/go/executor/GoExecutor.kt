package org.utbot.go.executor

import com.beust.klaxon.Klaxon
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.*
import org.utbot.go.*
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths

object GoExecutor {

    fun executeGoFileFuzzedFunctions(fileFuzzedFunctions: List<GoFuzzedFunction>): Map<GoFuzzedFunction, GoUtExecutionResult> {
        val fileNode = fileFuzzedFunctions.first().functionNode.containingFileNode
        val fileToExecuteName = createFileToExecuteName(fileNode.name)
        val rawExecutionResultsFileName = createRawExecutionResultsFileName(fileNode.name)

        val goSourceFileDir = File(fileNode.containingPackagePath)
        val fileToExecute = goSourceFileDir.resolve(fileToExecuteName)
        val rawExecutionResultsFile = goSourceFileDir.resolve(rawExecutionResultsFileName)

        val executorTestFunctionName = createExecutorTestFunctionName()
        val runGeneratedGoExecutorTestCommand = listOf(
            getGoExecutablePath(),
            "test",
            "-run",
            executorTestFunctionName
        )

        try {
            val fileToExecuteGoCode = GoExecutorCodeGenerationHelper.generateExecutorTestGoCode(
                executorTestFunctionName,
                rawExecutionResultsFileName,
                fileFuzzedFunctions
            )
            fileToExecute.writeText(fileToExecuteGoCode)

            val executedProcess = runCatching {
                val process = ProcessBuilder(runGeneratedGoExecutorTestCommand)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectErrorStream(true)
                    .directory(goSourceFileDir)
                    .start()
                process.waitFor()
                process
            }.getOrElse {
                throw RuntimeException(
                    "Execution of functions from ${fileNode.containingPackagePath} in child process failed with throwable: $it"
                )
            }
            val exitCode = executedProcess.exitValue()
            if (exitCode != 0) {
                val processOutput = InputStreamReader(executedProcess.inputStream).readText()
                throw RuntimeException(
                    "Execution of functions from ${fileNode.containingPackagePath} in child process failed with non-zero exit code = $exitCode:\n$processOutput"
                )
            }

            val rawExecutionResults = Klaxon().parse<RawExecutionResults>(rawExecutionResultsFile)
            if (rawExecutionResults == null) {
                val rawExecutionResultsFileContent = try {
                    rawExecutionResultsFile.readText()
                } catch (exception: Exception) {
                    null
                }
                throw RuntimeException(
                    "Failed to deserialize raw execution results:\n$rawExecutionResultsFileContent"
                )
            }

            return fileFuzzedFunctions.zip(rawExecutionResults.results)
                .associate { (fuzzedFunction, rawExecutionResult) ->
                    val executionResult = convertRawExecutionResultToExecutionResult(
                        rawExecutionResult,
                        fuzzedFunction.functionNode.returnTypes
                    )
                    fuzzedFunction to executionResult
                }

        } finally {
            fileToExecute.delete()
            rawExecutionResultsFile.delete()
        }
    }

    private fun createFileToExecuteName(sourceFileName: String): String {
        return "utbot_go_executor_${sourceFileName}_test.go"
    }

    private fun createRawExecutionResultsFileName(sourceFileName: String): String {
        return "utbot_go_executor_${sourceFileName}_test_results.json"
    }

    // TODO: find in general
    private fun getGoExecutablePath(): String {
        return Paths.get("/home/gleb/go/go1.19rc1", "bin", "go").toString()
    }

    private fun createExecutorTestFunctionName(): String {
        return "TestGoFileFuzzedFunctionsByUtGoExecutor"
    }

    private object RawValuesCodes {
        const val NAN_VALUE = "NaN"
        const val POS_INF_VALUE = "+Inf"
        const val NEG_INF_VALUE = "-Inf"
        const val COMPLEX_PARTS_DELIMITER = "@"
    }

    private fun convertRawExecutionResultToExecutionResult(
        rawExecutionResult: RawExecutionResult,
        functionResultTypes: List<GoTypeId>
    ): GoUtExecutionResult {
        if (rawExecutionResult.panicMessage != null) {
            val (rawValue, rawGoType, implementsError) = rawExecutionResult.panicMessage
            if (rawValue == null) {
                return GoUtPanicFailure(GoUtNilModel(goAnyTypeId), goAnyTypeId)
            }
            val goTypeId = GoTypeId(rawGoType, isErrorType = implementsError)
            val messageModel = if (goTypeId.isPrimitive) {
                createGoUtPrimitiveModelFromRawValue(rawValue, goTypeId)
            } else {
                GoUtPrimitiveModel(rawValue, goStringTypeId)
            }
            return GoUtPanicFailure(messageModel, goTypeId)
        }

        if (rawExecutionResult.resultRawValues.size != functionResultTypes.size) {
            error("Function completed execution must have as many result raw values as result types.")
        }
        var executedWithNonNilErrorString = false
        val resultValues =
            rawExecutionResult.resultRawValues.zip(functionResultTypes).map { (resultRawValue, resultType) ->
                if (resultType.isErrorType && resultRawValue != null) {
                    executedWithNonNilErrorString = true
                }
                if (resultRawValue == null) {
                    GoUtNilModel(resultType)
                } else {
                    // TODO: support errors fairly, i. e. as structs; for now consider them as strings
                    val nonNilModelTypeId = if (resultType.isErrorType) goStringTypeId else resultType
                    createGoUtPrimitiveModelFromRawValue(resultRawValue, nonNilModelTypeId)
                }
            }
        return if (executedWithNonNilErrorString) {
            GoUtExecutionWithNonNilError(resultValues)
        } else {
            GoUtExecutionSuccess(resultValues)
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun createGoUtPrimitiveModelFromRawValue(rawValue: String, typeId: GoTypeId): GoUtPrimitiveModel {
        if (typeId == goFloat64TypeId || typeId == goFloat32TypeId) {
            return convertRawFloatValueToGoUtPrimitiveModel(rawValue, typeId)
        }
        if (typeId == goComplex128TypeId || typeId == goComplex64TypeId) {
            val correspondingFloatType = if (typeId == goComplex128TypeId) goFloat64TypeId else goFloat32TypeId
            val (realPartModel, imagPartModel) = rawValue.split(RawValuesCodes.COMPLEX_PARTS_DELIMITER).map {
                convertRawFloatValueToGoUtPrimitiveModel(it, correspondingFloatType, typeId == goComplex64TypeId)
            }
            return GoUtComplexModel(realPartModel, imagPartModel, typeId)
        }
        val value = when (typeId.correspondingKClass) {
            Boolean::class -> rawValue.toBoolean()
            Byte::class -> rawValue.toByte()
            UByte::class -> rawValue.toUByte()
            Char::class -> rawValue.toCharArray().firstOrNull() ?: rawValue
            Float::class -> rawValue.toFloat()
            Double::class -> rawValue.toDouble()
            Short::class -> rawValue.toShort()
            UShort::class -> rawValue.toUShort()
            Int::class -> rawValue.toInt()
            UInt::class -> rawValue.toUInt()
            Long::class -> rawValue.toLong()
            ULong::class -> rawValue.toULong()
            else -> rawValue
        }
        return GoUtPrimitiveModel(value, typeId)
    }

    private fun convertRawFloatValueToGoUtPrimitiveModel(
        rawValue: String,
        typeId: GoTypeId,
        explicitCastRequired: Boolean = false
    ): GoUtPrimitiveModel {
        return when (rawValue) {
            RawValuesCodes.NAN_VALUE -> GoUtFloatNaNModel(typeId)
            RawValuesCodes.POS_INF_VALUE -> GoUtFloatInfModel(1, typeId)
            RawValuesCodes.NEG_INF_VALUE -> GoUtFloatInfModel(-1, typeId)
            else -> {
                val typedValue = if (typeId == goFloat64TypeId) rawValue.toDouble() else rawValue.toFloat()
                if (explicitCastRequired) {
                    GoUtPrimitiveModel(typedValue, typeId, explicitCastMode = ExplicitCastMode.REQUIRED)
                } else {
                    GoUtPrimitiveModel(typedValue, typeId)
                }
            }
        }
    }
}