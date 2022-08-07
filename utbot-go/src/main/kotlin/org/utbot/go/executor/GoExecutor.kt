package org.utbot.go.executor

import mu.KotlinLogging
import org.utbot.common.pid
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.*
import org.utbot.fuzzer.FuzzedValue
import org.utbot.go.GoFunctionOrMethodNode
import org.utbot.go.codegen.GoSimpleCodeGenerator
import org.utbot.go.fuzzer.goRequiredImports
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths

object GoExecutor {

    object Constants {
        const val FILE_TO_EXECUTE_NAME = "ut_go_executor_tmp_file_test.go"

        // TODO: set up Go executor in general
        val GO_EXECUTOR_PATH = Paths.get("/home/gleb/go/go1.19rc1", "bin", "go").toString()
//        val GO_EXECUTOR_PATH = Paths.get(System.getenv("GOROOT"), "bin", "go").toString()
//        val GO_EXECUTOR_PATH = "go"

        // Note: codes must be correctly convertable into Regex (by .toRegex() method)
        const val NIL_VALUE_CODE = "%__go_exec_nil__%"
        const val DELIMITER_CODE = "%__go_exec_delim__%"
        const val NAN_VALUE_CODE = "%__go_exec_nan__%"
        const val POS_INF_VALUE_CODE = "%__go_exec_pos_inf__%"
        const val NEG_INF_VALUE_CODE = "%__go_exec_neg_inf__%"
        const val COMPLEX_PARTS_DELIMITER_CODE = "%__go_exec_complex_parts_delim__%"

        const val EXECUTION_RESULT_OUTPUT_FILE_NAME = "ut_go_executor_out_communication.temp"
        const val EXECUTION_RESULT_ERROR_FILE_NAME = "ut_go_executor_err_communication.temp"
    }

    private val logger = KotlinLogging.logger {}

    fun invokeFunctionOrMethod(
        functionOrMethodNode: GoFunctionOrMethodNode,
        fuzzedParametersValues: List<FuzzedValue>
    ): GoUtExecutionResult {

        val fileToExecute = createGoFileToExecute(functionOrMethodNode.containingFileNode.containingPackagePath)

        val packageDirectoryFile = File(functionOrMethodNode.containingFileNode.containingPackagePath).absoluteFile
        val executionOutputFile = packageDirectoryFile.resolve(Constants.EXECUTION_RESULT_OUTPUT_FILE_NAME)
        val executionErrorFile = packageDirectoryFile.resolve(Constants.EXECUTION_RESULT_ERROR_FILE_NAME)

        try {
            val fileToExecuteCode = generateInvokeFunctionOrMethodGoCode(functionOrMethodNode, fuzzedParametersValues)
            fileToExecute.writeText(fileToExecuteCode)

            // TODO: get correct module name and run tests for it; for now: locally from package directory
            val command = listOf(
                Constants.GO_EXECUTOR_PATH,
                "test",
                "-run",
                createTestFunctionName(functionOrMethodNode.name)
            )


            val executedProcess = runCatching {
                val process = ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectErrorStream(true)
                    .directory(packageDirectoryFile)
                    .start().also {
                        logger.debug("GoExecutor process started with PID=${it.pid}")
                    }
                process.waitFor()
                process
            }.getOrElse {
                throw RuntimeException(
                    "Execution of ${functionOrMethodNode.name} in child process failed with throwable: $it"
                )
            }
            val exitCode = executedProcess.exitValue()
            if (exitCode != 0) {
                val processOutput = InputStreamReader(executedProcess.inputStream).readText()
                throw RuntimeException(
                    "Execution of ${functionOrMethodNode.name} in child process failed with non-zero exit code = $exitCode:\n$processOutput"
                )
            }

            val functionOrMethodReturnOutput = executionOutputFile.readText()
            val functionOrMethodPanicOutput = executionErrorFile.readText()

            return mapToGoUtExecutionResult(
                functionOrMethodNode.returnTypes,
                functionOrMethodReturnOutput,
                functionOrMethodPanicOutput
            )

        } finally {
            fileToExecute.delete()
            executionErrorFile.delete()
            executionOutputFile.delete()
        }
    }

    private fun createGoFileToExecute(functionOrMethodContainingPackage: String): File {
        val file = Paths.get(functionOrMethodContainingPackage, Constants.FILE_TO_EXECUTE_NAME).toFile()
        file.createNewFile()
        return file
    }

    private fun createTestFunctionName(functionOrMethodName: String): String {
        return "Test${functionOrMethodName.capitalize()}ByUtGoExecutor"
    }

    // TODO: use more convenient code generation
    private fun generateInvokeFunctionOrMethodGoCode(
        functionOrMethodNode: GoFunctionOrMethodNode,
        fuzzedParametersValues: List<FuzzedValue>
    ): String {
        val fileBuilder = GoSimpleCodeGenerator.GoFileCodeBuilder()

        fileBuilder.setPackage(functionOrMethodNode.containingFileNode.containingPackageName)

        val imports = mutableSetOf("bufio", "fmt", "io", "math", "os", "testing", "reflect")
        fuzzedParametersValues.forEach {
            imports += it.goRequiredImports
        }
        fileBuilder.setImports(imports)

        val checkErrorFunctionDeclaration = """
            func __checkErrorAndExitToUtGoExecutor__(err error) {
            	if err != nil {
            		os.Exit(1)
            	}
            }
        """.trimIndent()

        val float64ToStringFunctionDeclaration = """
            func __float64ValueToStringForUtGoExecutor__(value float64) string {
            	const outputNaN = "${Constants.NAN_VALUE_CODE}"
            	const outputPosInf = "${Constants.POS_INF_VALUE_CODE}"
            	const outputNegInf = "${Constants.NEG_INF_VALUE_CODE}"
            	switch {
            	case math.IsNaN(value):
            		return fmt.Sprint(outputNaN)
            	case math.IsInf(value, 1):
            		return fmt.Sprint(outputPosInf)
            	case math.IsInf(value, -1):
            		return fmt.Sprint(outputNegInf)
            	default:
            		return fmt.Sprintf("%#v", value)
            	}
            }
        """.trimIndent()

        val float32ToStringFunctionDeclaration = """
            func __float32ValueToStringForUtGoExecutor__(value float32) string {
            	return __float64ValueToStringForUtGoExecutor__(float64(value))
            }
        """.trimIndent()

        // TODO: check "error" case more carefully
        val valueToStringFunctionDeclaration = """
            func __valueToStringForUtGoExecutor__(value any) string {
            	const outputComplexPartsDelimiter = "%__go_exec_complex_parts_delim__%"
            	switch typedValue := value.(type) {
            	case complex128:
            		realPartString := __float64ValueToStringForUtGoExecutor__(real(typedValue))
            		imagPartString := __float64ValueToStringForUtGoExecutor__(imag(typedValue))
            		return fmt.Sprintf("%v%v%v", realPartString, outputComplexPartsDelimiter, imagPartString)
            	case complex64:
            		realPartString := __float32ValueToStringForUtGoExecutor__(real(typedValue))
            		imagPartString := __float32ValueToStringForUtGoExecutor__(imag(typedValue))
            		return fmt.Sprintf("%v%v%v", realPartString, outputComplexPartsDelimiter, imagPartString)
            	case float64:
            		return __float64ValueToStringForUtGoExecutor__(typedValue)
            	case float32:
            		return __float32ValueToStringForUtGoExecutor__(typedValue)
            	case string:
            		return fmt.Sprintf("%#v", typedValue)
                case error:
                    return fmt.Sprintf("%#v", typedValue.Error())
            	default:
            		return fmt.Sprintf("%v", typedValue)
            	}
            }
        """.trimIndent()

        val printOrExitFunctionDeclaration = """
            func __printToUtGoExecutorOrExit__(writer io.Writer, value any) {
            	_, err := fmt.Fprint(writer, __valueToStringForUtGoExecutor__(value))
            	__checkErrorAndExitToUtGoExecutor__(err)
            }
        """.trimIndent()

        val printOutputCodeOrExitFunctionDeclaration = """
            func __printOutputCodeToUtGoExecutorOrExit__(writer io.Writer, outputCode string) {
            	_, err := fmt.Fprint(writer, outputCode)
            	__checkErrorAndExitToUtGoExecutor__(err)
            }
        """.trimIndent()

        val printValueFunctionDeclaration = """
            func __printValueToUtGoExecutor__(writer io.Writer, value any, printPostfixSeparator bool) {
                const outputNil = "${Constants.NIL_VALUE_CODE}"
                const outputDelimiter = "${Constants.DELIMITER_CODE}"
                if value == nil {
                    __printOutputCodeToUtGoExecutorOrExit__(writer, outputNil)
                } else {
                    __printToUtGoExecutorOrExit__(writer, value)
                }
                if printPostfixSeparator {
                    __printOutputCodeToUtGoExecutorOrExit__(writer, outputDelimiter)
                }
            }
        """.trimIndent()

        val createWriterFunctionDeclaration = """
            func __createWriterToUtGoExecutorFile__(fileName string) (*os.File, *bufio.Writer) {
            	file, err := os.Create(fileName)
            	__checkErrorAndExitToUtGoExecutor__(err)
            	return file, bufio.NewWriter(file)
            }
        """.trimIndent()

        val closeWriterFunctionDeclaration = """
            func __closeWriterToUtGoExecutorFile__(file *os.File, writer *bufio.Writer) {
                flushErr := writer.Flush()
                __checkErrorAndExitToUtGoExecutor__(flushErr)
            
                closeErr := file.Close()
                __checkErrorAndExitToUtGoExecutor__(closeErr)
            }
        """.trimIndent()

        // rv = return values
        val rvCount = functionOrMethodNode.returnTypes.size
        val rvVariablesNames = (1..rvCount).map { "rv$it" }

        // TODO: support methods
        val fuzzedFunctionCall = if (rvCount == 0) {
            GoSimpleCodeGenerator.generateFuzzedFunctionCall(functionOrMethodNode, fuzzedParametersValues)
        } else {
            GoSimpleCodeGenerator.generateFuzzedFunctionCallSavedToVariables(
                rvVariablesNames,
                functionOrMethodNode,
                fuzzedParametersValues
            )
        }

        val executionTestDeclarationSb = StringBuilder()
        executionTestDeclarationSb.append(
            """
            func ${createTestFunctionName(functionOrMethodNode.name)}(t *testing.T) {
                outFile, outWriter := __createWriterToUtGoExecutorFile__("${Constants.EXECUTION_RESULT_OUTPUT_FILE_NAME}")
                errFile, errWriter := __createWriterToUtGoExecutorFile__("${Constants.EXECUTION_RESULT_ERROR_FILE_NAME}")
        
                panicked := true
                defer func() {
                    panicMessage := recover()
                    __printValueToUtGoExecutor__(errWriter, panicked, true)
                    __printValueToUtGoExecutor__(errWriter, panicMessage, true)
                    __printValueToUtGoExecutor__(errWriter, reflect.TypeOf(panicMessage), false)
            
                    __closeWriterToUtGoExecutorFile__(outFile, outWriter)
                    __closeWriterToUtGoExecutorFile__(errFile, errWriter)
                }()
                
                $fuzzedFunctionCall
                panicked = false
               
        """.trimIndent()
        )

        rvVariablesNames.forEachIndexed { index, variableName ->
            val isLastPrintRvVariableCall = index == (rvVariablesNames.size - 1)
            executionTestDeclarationSb.append("\n\t__printValueToUtGoExecutor__(outWriter, $variableName, ${!isLastPrintRvVariableCall})")
        }
        executionTestDeclarationSb.append("\n}")

        fileBuilder.addTopLevelElements(
            checkErrorFunctionDeclaration,
            float64ToStringFunctionDeclaration,
            float32ToStringFunctionDeclaration,
            valueToStringFunctionDeclaration,
            printOrExitFunctionDeclaration,
            printOutputCodeOrExitFunctionDeclaration,
            printValueFunctionDeclaration,
            createWriterFunctionDeclaration,
            closeWriterFunctionDeclaration,
            executionTestDeclarationSb.toString()
        )

        return fileBuilder.buildCodeString()
    }

    private fun mapToGoUtExecutionResult(
        returnCommonTypes: List<GoTypeId>,
        returnRawValuesOutput: String,
        panicRawValuesOutput: String
    ): GoUtExecutionResult {

        val returnRawValues = if (returnRawValuesOutput.isEmpty()) {
            emptyList()
        } else {
            returnRawValuesOutput.split(Constants.DELIMITER_CODE.toRegex())
        }
        val panicRawValues = panicRawValuesOutput.split(Constants.DELIMITER_CODE.toRegex())

        if (panicRawValues.size != 3) {
            error("Panicked, panicMessage and panicMessageRawGoType are expected in stderr.")
        }
        val (panicked, panicMessage, panicMessageRawGoType) = panicRawValues
        if (panicked.toBoolean()) {
            if (panicMessage == Constants.NIL_VALUE_CODE) {
                return GoUtPanicFailure(GoUtNilModel(goAnyTypeId), goAnyTypeId)
            }
            val panicMessageTypeId = GoTypeId(panicMessageRawGoType)
            val panicMessageModel = if (panicMessageTypeId.isPrimitive) {
                createGoUtPrimitiveModelFromRawValue(panicMessage, panicMessageTypeId)
            } else {
                GoUtPrimitiveModel(panicMessage, goStringTypeId)
            }
            return GoUtPanicFailure(panicMessageModel, panicMessageTypeId)
        }

        if (returnRawValues.size != returnCommonTypes.size) {
            error("Function or method completed execution must have as many return values as return types.")
        }
        var executedWithNonNullErrorString = false
        val returnValues = returnRawValues.zip(returnCommonTypes).map { (returnRawValue, returnType) ->
            if (nonNullErrorReceived(returnRawValue, returnType)) {
                executedWithNonNullErrorString = true
            }
            if (returnRawValue == Constants.NIL_VALUE_CODE) {
                GoUtNilModel(returnType)
            } else {
                // TODO: support errors fairly, i. e. as structs; for now consider them as strings
                val nonNilModelTypeId = if (returnType.isErrorType) goStringTypeId else returnType
                // TODO: support compound types
                createGoUtPrimitiveModelFromRawValue(returnRawValue, nonNilModelTypeId)
            }
        }

        return if (executedWithNonNullErrorString) {
            GoUtExecutionWithNonNullError(returnValues)
        } else {
            GoUtExecutionSuccess(returnValues)
        }
    }

    private fun nonNullErrorReceived(rawValue: String, classId: GoTypeId): Boolean {
        return classId.isErrorType && rawValue != Constants.NIL_VALUE_CODE
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun createGoUtPrimitiveModelFromRawValue(rawValue: String, typeId: GoTypeId): GoUtPrimitiveModel {
        if (typeId == goFloat64TypeId || typeId == goFloat32TypeId) {
            return convertRawFloatValueToGoUtPrimitiveModel(rawValue, typeId)
        }
        if (typeId == goComplex128TypeId || typeId == goComplex64TypeId) {
            val correspondingFloatType = if (typeId == goComplex128TypeId) goFloat64TypeId else goFloat32TypeId
            val (realPartModel, imagPartModel) = rawValue.split(Constants.COMPLEX_PARTS_DELIMITER_CODE.toRegex()).map {
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
            Constants.NAN_VALUE_CODE -> GoUtFloatNaNModel(typeId)
            Constants.POS_INF_VALUE_CODE -> GoUtFloatInfModel(1, typeId)
            Constants.NEG_INF_VALUE_CODE -> GoUtFloatInfModel(-1, typeId)
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