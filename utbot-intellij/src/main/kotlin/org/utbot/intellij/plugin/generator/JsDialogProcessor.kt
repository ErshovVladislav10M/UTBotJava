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
import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.truffle.api.strings.TruffleString
import fuzzer.JsFuzzer.jsFuzzing
import java.io.File
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.EnvironmentModels
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsPrimitiveModel
import org.utbot.framework.plugin.api.UtAssembleModel
import org.utbot.framework.plugin.api.UtExecutableCallModel
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
import java.io.RandomAccessFile
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

object JsDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        containingFilePath: String,
    ) {
        val dialogProcessor = createDialog(project, srcModule, fileMethods, focusedMethod, containingFilePath)
        if (!dialogProcessor.showAndGet()) return

        createTests(dialogProcessor.model, containingFilePath)
    }

    private fun createDialog(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        filePath: String,
    ): JsDialogWindow {
        val testModel = srcModule.testModule(project)

        return JsDialogWindow(
            JsTestsModel(
                project,
                srcModule,
                testModel,
                fileMethods,
                if (focusedMethod != null) setOf(focusedMethod) else null,
            ).apply {
                containingFilePath = filePath
            }
        )
    }

    private fun createTests(model: JsTestsModel, containingFilePath: String) {
        val fileText = File(containingFilePath).readText()
        val regex = Regex("export")
        // TODO SEVERE: make a copy of a file so that it doesn't contain any global invocations besides generated one.
        //  Also general trimming required, for example "export" keyword, etc.
        val trimmedFileText = fileText.replace(regex, "")
        TernService.filePathToInference = containingFilePath
        TernService.projectPath = model.project.basePath ?: throw IllegalStateException("Can't access project path.")
        TernService.trimmedFileText = trimmedFileText
        TernService.run()
        model.selectedMethods?.forEach { jsMemberInfo ->
            var parentPsi = PsiTreeUtil.getParentOfType(jsMemberInfo.member, ES6Class::class.java)
            // "toplevelHack" is from JsActionMethods
            if (parentPsi?.name == null || parentPsi.name == "toplevelHack") {
                parentPsi = null
            }
            val funcNode = getFunctionNode(jsMemberInfo, parentPsi?.name, trimmedFileText)
            val classNode = if (parentPsi != null) JsParserUtils.searchForClassDecl(parentPsi.name!!, trimmedFileText) else null

            val file = File(containingFilePath)
            manageExports(file, classNode, funcNode)

            val classId = parentPsi?.let {
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
                val (returnValue, valueClassId) = runJs(param, funcNode, classNode?.ident?.name, trimmedFileText,
                    containingFilePath.replaceAfterLast("/", "")
                ).toAny()
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
                testsForGenerator
            )
            val codeGen = JsCodeGenerator(
                classId,
                mutableMapOf(execId to funcNode.parameters.map { it.name.toString() }),
            )
            val opachki = codeGen.generateAsStringWithTestReport(listOf(testSet))
            val fileName = containingFilePath.substringAfterLast("/").replace(Regex(".js"), "Test.js")
            val testFile = File("${containingFilePath.replaceAfterLast("/", "")}$fileName")
            testFile.writeText(opachki.generatedCode)
            testFile.createNewFile()
        }
    }

    private fun manageExports(file: File, classNode: ClassNode?, funcNode: FunctionNode) {
        val startComment = "// Start of exports generated by UTBot"
        val endComment = "// End of exports generated by UTBot"
        val exportName = classNode?.ident?.name ?: funcNode.ident.name
        val fileText = file.readText()
        when {
            fileText.contains("export {$exportName}") -> {
                return
            }
            fileText.contains(startComment) && !fileText.contains("export {$exportName}") -> {
                val regex = Regex("$startComment\n(.*)\n$endComment")
                regex.find(fileText)?.groups?.get(1)?.value?.let {
                    val swappedText = fileText.replace(it, "export{$exportName}")
                    runWriteAction {
                        file.writeText(swappedText)
                    }
                }
                return
            }
            else -> {
                runWriteAction {
                    file.appendText("\n$startComment")
                    file.appendText("\nexport {$exportName}")
                    file.appendText("\n$endComment")
                }
            }
        }
        if (file.readText().contains("// Start of exports generated by UTBot"))
            if (!file.readText().contains("// Start of exports generated by UTBot")) {
                // TODO SEVERE: add check if object has already been imported
                runWriteAction {
                    file.appendText("\n// Start of exports generated by UTBot")
                    file.appendText(
                        "\n" +
                                "export {${classNode?.ident?.name ?: funcNode.ident.name}}"
                    )
                    file.appendText("\n// End of exports generated by UTBot")
                }
            }
    }

    private fun getRandomNumFuzzedValues(fuzzedValues: List<List<FuzzedValue>>): List<List<FuzzedValue>> {
        val newFuzzedValues = mutableListOf<List<FuzzedValue>>()
        for (i in 0..minOf(10, fuzzedValues.size)) {
            newFuzzedValues.add(fuzzedValues[Random.nextInt(fuzzedValues.size)])
        }
        return newFuzzedValues
    }

    private fun runJs(fuzzedValues: List<FuzzedValue>, method: FunctionNode, containingClass: TruffleString?, fileText: String, workDir: String): Value {
        val context = Context.newBuilder("js")
            .allowIO(true)
            .currentWorkingDirectory(Paths.get(workDir))
            .build()
        val str = makeStringForRunJs(fuzzedValues, method, containingClass, fileText)
        val source = org.graalvm.polyglot.Source.newBuilder("js", str, "script")
            .mimeType("application/javascript+module").build()
        return context.eval(source)
    }

    private fun makeStringForRunJs(fuzzedValue: List<FuzzedValue>, method: FunctionNode, containingClass: TruffleString?, fileText: String): String {
        val callString = makeCallFunctionString(fuzzedValue, method, containingClass)
        val res = buildString {
            append(fileText)
            append("\n")
            append(callString)
        }
        return res
    }

    private fun makeCallFunctionString(fuzzedValue: List<FuzzedValue>, method: FunctionNode, containingClass: TruffleString?): String {
        val initClass = containingClass?.let {
            "new ${it}()."
        } ?: ""
        var callString = "$initClass${method.name}("
        fuzzedValue.forEach { value ->
            // Explicit string wrap with "" is needed.
            if (value.model is UtAssembleModel) {
                val model = value.model as UtAssembleModel
                callString += "new ${model.classId.name}("
                (model.instantiationChain.first() as UtExecutableCallModel).params.forEach {
                    callString += when ((it as JsPrimitiveModel).value) {
                        is String -> "\"${(it).value}\","
                        else -> "${(it).value},"
                    }
                }
                callString = callString.dropLast(1)
                callString += "),"
            } else {
                callString += when ((value.model as JsPrimitiveModel).value) {
                    is String -> "\"${(value.model as JsPrimitiveModel).value}\","
                    else -> "${(value.model as JsPrimitiveModel).value},"
                }
            }
        }
        callString = callString.dropLast(1)
        callString += ')'
        return callString
    }

    private fun getFunctionNode(focusedMethod: JSMemberInfo, parentClassName: String?, fileText: String): FunctionNode {
        Thread.currentThread().contextClassLoader = Context::class.java.classLoader
        val parser = Parser(
            ScriptEnvironment.builder().build(),
            Source.sourceFor("jsFile", fileText),
            ErrorManager.ThrowErrorManager()
        )
        val fileNode = parser.parse()
        val visitor = JsFunctionAstVisitor(focusedMethod.member.name!!, parentClassName)
        fileNode.accept(visitor)
        return visitor.targetFunctionNode
    }
}