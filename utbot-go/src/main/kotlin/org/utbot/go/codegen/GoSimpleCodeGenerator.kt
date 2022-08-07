@file:Suppress("MemberVisibilityCanBePrivate")

package org.utbot.go.codegen

import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.goFloat64TypeId
import org.utbot.framework.plugin.api.util.goStringTypeId
import org.utbot.framework.plugin.api.util.isPrimitive
import org.utbot.fuzzer.FuzzedValue
import org.utbot.go.GoFunctionOrMethodNode
import org.utbot.go.GoFuzzedFunctionOrMethodTestCase
import org.utbot.go.containsNaNOrInf
import org.utbot.go.doesNotContainNaNOrInf
import org.utbot.go.executor.GoUtExecutionCompleted
import org.utbot.go.executor.GoUtExecutionWithNonNullError
import org.utbot.go.executor.GoUtPanicFailure
import org.utbot.go.fuzzer.goRequiredImports

object GoSimpleCodeGenerator {

    class GoFileCodeBuilder {

        private var packageLine: String? = null
        private var importLines: String? = null
        private val topLevelElements: MutableList<String> = mutableListOf()

        fun buildCodeString(): String {
            return "$packageLine\n\n$importLines\n\n${topLevelElements.joinToString(separator = "\n\n")}"
        }

        fun setPackage(packageName: String) {
            packageLine = "package $packageName"
        }

        fun setImports(importNames: Set<String>) {
            val sortedImportNames = importNames.toList().sorted()
            if (sortedImportNames.isEmpty()) return
            if (sortedImportNames.size == 1) {
                importLines = "import ${sortedImportNames.first()}"
                return
            }
            importLines = sortedImportNames.joinToString(separator = "", prefix = "import(\n", postfix = ")") {
                "\t\"$it\"\n"
            }
        }

        fun addTopLevelElements(vararg originalElements: String) {
            topLevelElements.addAll(originalElements)
        }
    }

    fun generateFuzzedFunctionCallSavedToVariables(
        variablesNames: List<String>,
        functionOrMethodNode: GoFunctionOrMethodNode,
        fuzzedParametersValues: List<FuzzedValue>
    ): String = generateVariablesDeclarationTo(
        variablesNames,
        generateFuzzedFunctionCall(functionOrMethodNode, fuzzedParametersValues)
    )

    fun generateVariablesDeclarationTo(variablesNames: List<String>, expressionCode: String): String {
        val variables = variablesNames.joinToString(separator = ", ")
        return "$variables := $expressionCode"
    }

    fun generateFuzzedFunctionCall(
        functionOrMethodNode: GoFunctionOrMethodNode,
        fuzzedParametersValues: List<FuzzedValue>
    ): String {
        val fuzzedParameters =
            fuzzedParametersValues.joinToString(separator = ", ") {
                (it.model as GoUtPrimitiveModel).toString()
            }
        return "${functionOrMethodNode.name}($fuzzedParameters)"
    }

    fun generateCastIfNeed(toTypeId: GoTypeId, expressionType: GoTypeId, expressionCode: String): String {
        return if (expressionType != toTypeId) {
            "${toTypeId.name}($expressionCode)"
        } else {
            expressionCode
        }
    }

    fun generateTestFileCode(testCases: List<GoFuzzedFunctionOrMethodTestCase>): String {
        val fileBuilder = GoFileCodeBuilder()

        val packageName = testCases.first().containingPackageName
        if (testCases.any { it.containingPackageName != packageName }) {
            error("Test cases of the file corresponds to different packages.")
        }
        fileBuilder.setPackage(packageName)

        val imports = mutableSetOf("github.com/stretchr/testify/assert", "testing")
        testCases.forEach { testCase ->
            testCase.fuzzedParametersValues.forEach {
                imports += it.goRequiredImports
            }
            val executionResult = testCase.executionResult
            if (executionResult is GoUtExecutionCompleted) {
                executionResult.models.forEach {
                    imports += it.requiredImports
                }
            } else if (executionResult is GoUtPanicFailure) {
                imports += executionResult.panicMessageModel.requiredImports
            }
        }
        fileBuilder.setImports(imports)

        // TODO: support methods
        val testCasesByFunctionOrMethod = testCases.groupBy { it.functionOrMethodNode }
        testCasesByFunctionOrMethod.keys.forEach { functionOrMethodNode ->
            val functionOrMethodTestCases = testCasesByFunctionOrMethod[functionOrMethodNode]!!

            functionOrMethodTestCases.filter { !it.isPanicTestCase() }.forEachIndexed { testIndex, testCase ->
                val testFunctionCode = generateTestFunctionForCompletedExecutionTestCase(testCase, testIndex + 1)
                fileBuilder.addTopLevelElements(testFunctionCode)
            }
            functionOrMethodTestCases.filter { it.isPanicTestCase() }.forEachIndexed { testIndex, testCase ->
                val testFunctionCode = generateTestFunctionForPanicFailureTestCase(testCase, testIndex + 1)
                fileBuilder.addTopLevelElements(testFunctionCode)
            }
        }

        return fileBuilder.buildCodeString()
    }

    private fun GoFuzzedFunctionOrMethodTestCase.isPanicTestCase(): Boolean {
        return this.executionResult is GoUtPanicFailure
    }

    private fun generateTestFunctionForCompletedExecutionTestCase(
        testCase: GoFuzzedFunctionOrMethodTestCase,
        testIndex: Int
    ): String {
        val (functionOrMethodNode, fuzzedParametersValues, executionResult) = testCase

        val testFunctionExecutionType =
            if (executionResult is GoUtExecutionWithNonNullError) {
                "WithNonNilError"
            } else {
                ""
            }
        val testFunctionSignatureDeclaration =
            "func Test${functionOrMethodNode.name.capitalize()}${testFunctionExecutionType}ByUtGoFuzzer$testIndex(t *testing.T)"

        if (functionOrMethodNode.returnTypes.isEmpty()) {
            val actualFunctionCall = generateFuzzedFunctionCall(functionOrMethodNode, fuzzedParametersValues)
            val testFunctionBody = "\tassert.NotPanics(t, func() { $actualFunctionCall })\n"
            return "$testFunctionSignatureDeclaration {\n$testFunctionBody}"
        }

        val bodySb = StringBuilder()

        val returnTypes = functionOrMethodNode.returnTypes
        val isErrorReturnTypes = returnTypes.map { it.isErrorType }
        val actualRvVariablesNames = run {
            val errorVariablesTotal = returnTypes.count { it.isErrorType }
            val commonVariablesTotal = returnTypes.size - errorVariablesTotal

            var errorVariablesIndex = 0
            var commonVariablesIndex = 0
            isErrorReturnTypes.map { isErrorType ->
                if (isErrorType) {
                    "actualErr${if (errorVariablesTotal > 1) errorVariablesIndex++ else ""}"
                } else {
                    "actualVal${if (commonVariablesTotal > 1) commonVariablesIndex++ else ""}"
                }
            }
        }
        val actualFunctionCall = generateFuzzedFunctionCallSavedToVariables(
            actualRvVariablesNames,
            functionOrMethodNode,
            fuzzedParametersValues
        )
        bodySb.append("\t$actualFunctionCall\n\n")

        val expectedModels = (executionResult as GoUtExecutionCompleted).models
        val (assertName, tParameter) =
            if (expectedModels.size > 1 || expectedModels.any { it.isComplexModelAndNeedsSeparateAssertions() }) {
                bodySb.append("\tassertMultiple := assert.New(t)\n")
                "assertMultiple" to ""
            } else {
                "assert" to "t, "
            }
        actualRvVariablesNames.zip(expectedModels).zip(isErrorReturnTypes)
            .forEach { (variableAndModel, isErrorReturnType) ->
                val (actualRvVariableName, expectedModel) = variableAndModel

                val assertionMethodCalls = mutableListOf<String>()
                fun generateAssertionMethodCall(expectedModel_: GoUtModel, actualRvVariableNameCode: String) {
                    val code = generateAssertionMethodCall(
                        expectedModel_,
                        actualRvVariableNameCode,
                        isErrorReturnType,
                        tParameter
                    )
                    assertionMethodCalls.add(code)
                }

                if (expectedModel.isComplexModelAndNeedsSeparateAssertions()) {
                    val complexModel = expectedModel as GoUtComplexModel
                    generateAssertionMethodCall(complexModel.realValue, "real($actualRvVariableName)")
                    generateAssertionMethodCall(complexModel.imagValue, "imag($actualRvVariableName)")
                } else {
                    generateAssertionMethodCall(expectedModel, actualRvVariableName)
                }
                assertionMethodCalls.forEach { bodySb.append("\t$assertName.$it\n") }
            }
        val testFunctionBody = bodySb.toString()

        return "$testFunctionSignatureDeclaration {\n$testFunctionBody}"
    }

    private fun GoUtModel.isComplexModelAndNeedsSeparateAssertions(): Boolean =
        this is GoUtComplexModel && this.containsNaNOrInf()

    private fun generateTestFunctionForPanicFailureTestCase(
        testCase: GoFuzzedFunctionOrMethodTestCase,
        testIndex: Int
    ): String {
        val (functionOrMethodNode, fuzzedParametersValues, executionResult) = testCase

        val testFunctionSignatureDeclaration =
            "func Test${functionOrMethodNode.name.capitalize()}PanicsByUtGoFuzzer$testIndex(t *testing.T)"

        val actualFunctionCall = generateFuzzedFunctionCall(functionOrMethodNode, fuzzedParametersValues)
        val functionCallLambda = "func() { $actualFunctionCall }"
        val (expectedModel, originalGoType) = (executionResult as GoUtPanicFailure)

        val isPrimitiveWithOkEquals = originalGoType.isPrimitive && expectedModel.doesNotContainNaNOrInf()
        val testFunctionBody = if (isPrimitiveWithOkEquals || expectedModel is GoUtNilModel) {
            val expectedModelValueCode = if (expectedModel is GoUtNilModel) {
                "$expectedModel"
            } else {
                generateCastedValueGoCodeIfPossible(expectedModel as GoUtPrimitiveModel)
            }
            "\tassert.PanicsWithValue(t, $expectedModelValueCode, $functionCallLambda)"
        } else if (originalGoType.isErrorType) { // TODO: improve '== "error"' check
            "\tassert.PanicsWithError(t, $expectedModel, $functionCallLambda)"
        } else {
            "\tassert.Panics(t, $functionCallLambda)"
        }

        return "$testFunctionSignatureDeclaration {\n$testFunctionBody\n}"
    }

    private fun generateCastedValueGoCodeIfPossible(model: GoUtPrimitiveModel): String {
        return if (model.explicitCastMode == ExplicitCastMode.NEVER) {
            model.toValueGoCode()
        } else {
            model.toCastedValueGoCode()
        }
    }

    private fun generateAssertionMethodCall(
        expectedModel: GoUtModel,
        actualRvVariableNameCode: String,
        isErrorReturnType: Boolean,
        tParameter: String
    ): String {
        if (expectedModel is GoUtNilModel) {
            return "Nil($tParameter$actualRvVariableNameCode)"
        }
        if (isErrorReturnType && expectedModel.classId == goStringTypeId) {
            return "ErrorContains($tParameter$actualRvVariableNameCode, $expectedModel)"
        }
        if (expectedModel is GoUtFloatNaNModel) {
            val actualRvVariableCode =
                generateCastIfNeed(goFloat64TypeId, expectedModel.classId, actualRvVariableNameCode)
            return "True(${tParameter}math.IsNaN($actualRvVariableCode))"
        }
        if (expectedModel is GoUtFloatInfModel) {
            val actualRvVariableCode =
                generateCastIfNeed(goFloat64TypeId, expectedModel.classId, actualRvVariableNameCode)
            return "True(${tParameter}math.IsInf($actualRvVariableCode, ${expectedModel.sign}))"
        }
        val expectedModelValueCode =
            generateCastedValueGoCodeIfPossible(expectedModel as GoUtPrimitiveModel)
        return "Equal($tParameter$expectedModelValueCode, $actualRvVariableNameCode)"
    }
}