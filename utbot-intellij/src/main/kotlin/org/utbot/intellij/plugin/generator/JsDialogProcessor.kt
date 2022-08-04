package org.utbot.intellij.plugin.generator

import api.JsUtModelConstructor
import codegen.JsCodeGenerator
import com.intellij.lang.ecmascript6.psi.ES6Class
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.refactoring.util.JSMemberInfo
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import com.oracle.js.parser.ir.FunctionNode
import fuzzer.JsFuzzer.jsFuzzing
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.EnvironmentModels
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsPrimitiveModel
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.framework.plugin.api.UtExecutionSuccess
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.FuzzedValue
import org.utbot.intellij.plugin.ui.JsDialogWindow
import org.utbot.intellij.plugin.models.JsTestsModel
import org.utbot.intellij.plugin.ui.utils.testModule
import parser.JsFunctionAstVisitor
import parser.JsFuzzerAstVisitor
import parser.JsParserUtils
import utils.TernService
import utils.constructClass
import utils.toAny
import kotlin.random.Random

object JsDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        containingFilePath: String,
    ) {
        val dialogProcessor = createDialog(project, srcModule, fileMethods, focusedMethod)
        if (!dialogProcessor.showAndGet()) return

        createTests(dialogProcessor.model, containingFilePath)
    }

    private fun createDialog(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
    ): JsDialogWindow {
        val testModel = srcModule.testModule(project)

        return JsDialogWindow(
            JsTestsModel(
                project,
                srcModule,
                testModel,
                fileMethods,
                if (focusedMethod != null) setOf(focusedMethod) else null,
            )
        )
    }

    private fun createTests(model: JsTestsModel, containingFilePath: String) {
        TernService.filePathToInference = containingFilePath
        TernService.projectPath = model.project.basePath ?: throw IllegalStateException("Can't access project path.")
        TernService.run()
        model.selectedMethods?.forEach { jsMemberInfo ->
            var parentPsi = PsiTreeUtil.getParentOfType(jsMemberInfo.member, ES6Class::class.java)
            // "toplevelHack" is from JsActionMethods
            if (parentPsi?.name == null || parentPsi.name == "toplevelHack") {
                parentPsi = null
            }
            val funcNode = getFunctionNode(jsMemberInfo, parentPsi?.name)
            val classId = parentPsi?.let {
                val classNode = JsParserUtils.searchForClassDecl(it.name!!, containingFilePath)
                JsClassId(it.name!!).constructClass(classNode)
            } ?: JsClassId("undefined").constructClass(functions = listOf(funcNode))
            val execId = classId.allMethods.find {
                it.name == funcNode.name.toString()
            } ?: throw IllegalStateException()
            funcNode.body.accept(JsFuzzerAstVisitor)
            val methodUnderTestDescription = FuzzedMethodDescription(execId, JsFuzzerAstVisitor.fuzzedConcreteValues).apply {
                compilableName = funcNode.name.toString()
                val names = funcNode.parameters.map { it.name.toString() }
                parameterNameMap = { index -> names.getOrNull(index) }
            }
            val fuzzedValues =
                jsFuzzing(methodUnderTestDescription = methodUnderTestDescription).toList()
            // For dev purposes only random set of fuzzed values is picked. TODO SEVERE: patch this later
            val randomParams = getRandomNumFuzzedValues(fuzzedValues)
            val testsForGenerator = mutableListOf<UtExecution>()
            randomParams.forEach { param ->
                // Hack: Should create one file with all functions to run? TODO MINOR: think
                val utConstructor = JsUtModelConstructor()
                val (returnValue, valueClassId) = runJs(param, funcNode, jsMemberInfo.member.text).toAny()
                val result = utConstructor.construct(returnValue, valueClassId)
                val initEnv = EnvironmentModels(null, param.map { it.model }, mapOf())
                testsForGenerator.add(
                    UtExecution(
                        stateBefore = initEnv,
                        stateAfter = initEnv,
                        result = UtExecutionSuccess(result),
                        instrumentation = emptyList(),
                        path = mutableListOf(),
                        fullPath = emptyList(),
                    )
                )
            }
            val testSet = CgMethodTestSet(
                execId,
                listOf(testsForGenerator.first())
            )
            val codeGen = JsCodeGenerator(
                classId,
                mutableMapOf(execId to funcNode.parameters.map { it.name.toString() }),
            )
            val opachki = codeGen.generateAsStringWithTestReport(listOf(testSet))
            println(opachki.generatedCode)
            val NIHUYA = 1
        }
    }

    private fun getRandomNumFuzzedValues(fuzzedValues: List<List<FuzzedValue>>): List<List<FuzzedValue>> {
        val newFuzzedValues = mutableListOf<List<FuzzedValue>>()
        for (i in 0..10) {
            newFuzzedValues.add(fuzzedValues[Random.nextInt(fuzzedValues.size)])
        }
        return newFuzzedValues
    }

    private fun runJs(fuzzedValues: List<FuzzedValue>, method: FunctionNode, funcString: String): Value {
        val context = Context.newBuilder("js").build()
        val str = makeStringForRunJs(fuzzedValues, method, funcString)
        return context.eval("js", str)
    }

    private fun makeStringForRunJs(fuzzedValue: List<FuzzedValue>, method: FunctionNode, funcString: String): String {
        val callString = makeCallFunctionString(fuzzedValue, method)
        return """function $funcString
                  $callString""".trimIndent()
    }

    private fun makeCallFunctionString(fuzzedValue: List<FuzzedValue>, method: FunctionNode): String {
        var callString = "${method.name}("
        fuzzedValue.forEach { value ->
            // Explicit string wrap with "" is needed.
            callString += when ((value.model as JsPrimitiveModel).value) {
                is String -> "\"${(value.model as JsPrimitiveModel).value}\","
                else -> "${(value.model as JsPrimitiveModel).value},"
            }
        }
        callString = callString.dropLast(1)
        callString += ')'
        return callString
    }

    private fun getFunctionNode(focusedMethod: JSMemberInfo, parentClassName: String?): FunctionNode {
        val psiFile = PsiTreeUtil.getParentOfType(focusedMethod.member, JSFile::class.java) ?: throw IllegalStateException()
        Thread.currentThread().contextClassLoader = Context::class.java.classLoader
        val parser = Parser(
            ScriptEnvironment.builder().build(),
            Source.sourceFor("jsFile", psiFile.text),
            ErrorManager.ThrowErrorManager()
        )
        val fileNode = parser.parse()
        val visitor = JsFunctionAstVisitor(focusedMethod.member.name!!, parentClassName ?: "toplevelHack")
        fileNode.accept(visitor)
        return visitor.targetFunctionNode
    }

}