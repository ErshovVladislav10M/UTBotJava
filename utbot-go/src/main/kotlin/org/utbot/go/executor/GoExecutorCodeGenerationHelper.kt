package org.utbot.go.executor

import org.utbot.go.GoFuzzedFunction
import org.utbot.go.codegen.GoSimpleCodeGenerator
import org.utbot.go.fuzzer.goRequiredImports

internal object GoExecutorCodeGenerationHelper {

    private val alwaysRequiredImports = setOf("encoding/json", "fmt", "math", "os", "testing", "reflect")

    fun generateExecutorTestGoCode(
        executorTestFunctionName: String,
        rawExecutionResultsFileName: String,
        fileFuzzedFunctions: List<GoFuzzedFunction>
    ): String {
        val fileNode = fileFuzzedFunctions.first().functionNode.containingFileNode
        val fileCodeBuilder = GoSimpleCodeGenerator.GoFileCodeBuilder()

        fileCodeBuilder.setPackage(fileNode.containingPackageName)

        val additionalImports = mutableSetOf<String>()
        fileFuzzedFunctions.forEach { (_, fuzzedParametersValues) ->
            fuzzedParametersValues.forEach { additionalImports += it.goRequiredImports }
        }
        fileCodeBuilder.setImports(alwaysRequiredImports + additionalImports)

        val executorTestFunctionCode =
            generateExecutorTestFunctionCode(executorTestFunctionName, rawExecutionResultsFileName, fileFuzzedFunctions)
        fileCodeBuilder.addTopLevelElements(
            CodeTemplates.topLevelHelperStructsAndFunctions + listOf(executorTestFunctionCode)
        )

        return fileCodeBuilder.buildCodeString()
    }

    // TODO: use more convenient code generation
    private fun generateExecutorTestFunctionCode(
        executorTestFunctionName: String,
        rawExecutionResultsFileName: String,
        fuzzedFunctions: List<GoFuzzedFunction>
    ): String {
        val codeSb = StringBuilder()
        codeSb.append("func $executorTestFunctionName(t *testing.T) {")
        codeSb.append("\n\texecutionResults := __UtBotGoExecutorRawExecutionResults__{Results: []__UtBotGoExecutorRawExecutionResult__{")

        fuzzedFunctions.forEach { (functionNode, fuzzedParametersValues) ->
            val fuzzedFunctionCall =
                GoSimpleCodeGenerator.generateFuzzedFunctionCall(functionNode, fuzzedParametersValues)
            codeSb.append("\n\t\t__executeFunctionForUtBotGoExecutor__(\"${functionNode.name}\", func() []*string {")
            if (functionNode.returnTypes.isEmpty()) {
                codeSb.append("\n\t\t\t$fuzzedFunctionCall")
                codeSb.append("\n\t\t\treturn []*string{}")
            } else {
                codeSb.append("\n\t\t\treturn __wrapResultValuesForUtBotGoExecutor__($fuzzedFunctionCall)")
            }
            codeSb.append("\n\t\t}),")
        }

        codeSb.append("\n")
        codeSb.append(
            """
                }}
                
            	jsonBytes, toJsonErr := json.MarshalIndent(executionResults, "", "  ")
            	__checkErrorAndExitToUtBotGoExecutor__(toJsonErr)

            	const resultsFilePath = "$rawExecutionResultsFileName"
            	writeErr := os.WriteFile(resultsFilePath, jsonBytes, os.ModePerm)
            	__checkErrorAndExitToUtBotGoExecutor__(writeErr)
            }
            
        """.trimIndent()
        )

        return codeSb.toString()
    }

    private object CodeTemplates {

        private val panicMessageStruct = """
            type __UtBotGoExecutorPanicMessage__ struct {
            	RawValue        *string `json:"rawValue"`
            	RawGoType       string  `json:"rawGoType"`
            	ImplementsError bool    `json:"implementsError"`
            }
        """.trimIndent()

        private val rawExecutionResultStruct = """
            type __UtBotGoExecutorRawExecutionResult__ struct {
            	FunctionName    string                       `json:"functionName"`
            	ResultRawValues []*string                    `json:"resultRawValues"`
            	PanicMessage    *__UtBotGoExecutorPanicMessage__ `json:"panicMessage"`
            }
        """.trimIndent()

        private val rawExecutionResultsStruct = """
            type __UtBotGoExecutorRawExecutionResults__ struct {
            	Results []__UtBotGoExecutorRawExecutionResult__ `json:"results"`
            }
        """.trimIndent()

        private val checkErrorFunction = """
            func __checkErrorAndExitToUtBotGoExecutor__(err error) {
            	if err != nil {
            		os.Exit(1)
            	}
            }
        """.trimIndent()

        private val convertFloat64ValueToStringFunction = """
            func __convertFloat64ValueToStringForUtBotGoExecutor__(value float64) string {
            	const outputNaN = "NaN"
            	const outputPosInf = "+Inf"
            	const outputNegInf = "-Inf"
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

        private val convertFloat32ValueToStringFunction = """
            func __convertFloat32ValueToStringForUtBotGoExecutor__(value float32) string {
            	return __convertFloat64ValueToStringForUtBotGoExecutor__(float64(value))
            }
        """.trimIndent()

        private val convertValueToStringFunction = """
            func __convertValueToStringForUtBotGoExecutor__(value any) string {
            	if typedValue, ok := value.(error); ok {
            		return fmt.Sprintf("%#v", typedValue.Error())
            	}
            	const outputComplexPartsDelimiter = "@"
            	switch typedValue := value.(type) {
            	case complex128:
            		realPartString := __convertFloat64ValueToStringForUtBotGoExecutor__(real(typedValue))
            		imagPartString := __convertFloat64ValueToStringForUtBotGoExecutor__(imag(typedValue))
            		return fmt.Sprintf("%v%v%v", realPartString, outputComplexPartsDelimiter, imagPartString)
            	case complex64:
            		realPartString := __convertFloat32ValueToStringForUtBotGoExecutor__(real(typedValue))
            		imagPartString := __convertFloat32ValueToStringForUtBotGoExecutor__(imag(typedValue))
            		return fmt.Sprintf("%v%v%v", realPartString, outputComplexPartsDelimiter, imagPartString)
            	case float64:
            		return __convertFloat64ValueToStringForUtBotGoExecutor__(typedValue)
            	case float32:
            		return __convertFloat32ValueToStringForUtBotGoExecutor__(typedValue)
            	case string:
            		return fmt.Sprintf("%#v", typedValue)
            	default:
            		return fmt.Sprintf("%v", typedValue)
            	}
            }
        """.trimIndent()

        private val convertValueToRawValueFunction = """
            func __convertValueToRawValueForUtBotGoExecutor__(value any) *string {
            	if value == nil {
            		return nil
            	} else {
            		rawValue := __convertValueToStringForUtBotGoExecutor__(value)
            		return &rawValue
            	}
            }
        """.trimIndent()

        private val getValueRawGoTypeFunction = """
            func __getValueRawGoTypeForUtBotGoExecutor__(value any) string {
            	return __convertValueToStringForUtBotGoExecutor__(reflect.TypeOf(value))
            }
        """.trimIndent()

        private val executeFunctionFunction = """
            func __executeFunctionForUtBotGoExecutor__(functionName string, wrappedFunction func() []*string) (
            	executionResult __UtBotGoExecutorRawExecutionResult__,
            ) {
            	executionResult.FunctionName = functionName
            	executionResult.ResultRawValues = []*string{}
            	panicked := true
            	defer func() {
            		panicMessage := recover()
            		if panicked {
            			_, implementsError := panicMessage.(error)
            			executionResult.PanicMessage = &__UtBotGoExecutorPanicMessage__{
            				RawValue:        __convertValueToRawValueForUtBotGoExecutor__(panicMessage),
            				RawGoType:       __getValueRawGoTypeForUtBotGoExecutor__(panicMessage),
            				ImplementsError: implementsError,
            			}
            		} else {
            			executionResult.PanicMessage = nil
            		}
            	}()

            	rawResultValues := wrappedFunction()
            	executionResult.ResultRawValues = rawResultValues
            	panicked = false

            	return executionResult
            }
        """.trimIndent()

        private val wrapResultValuesFunction = """
            //goland:noinspection GoPreferNilSlice
            func __wrapResultValuesForUtBotGoExecutor__(values ...any) []*string {
            	rawValues := []*string{}
            	for _, value := range values {
            		rawValues = append(rawValues, __convertValueToRawValueForUtBotGoExecutor__(value))
            	}
            	return rawValues
            }
        """.trimIndent()

        val topLevelHelperStructsAndFunctions = listOf(
            panicMessageStruct,
            rawExecutionResultStruct,
            rawExecutionResultsStruct,
            checkErrorFunction,
            convertFloat64ValueToStringFunction,
            convertFloat32ValueToStringFunction,
            convertValueToStringFunction,
            convertValueToRawValueFunction,
            getValueRawGoTypeFunction,
            executeFunctionFunction,
            wrapResultValuesFunction,
        )
    }
}