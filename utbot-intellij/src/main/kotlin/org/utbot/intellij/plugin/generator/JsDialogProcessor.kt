package org.utbot.intellij.plugin.generator

import api.JsUtModelConstructor
import codegen.JsCodeGenerator
import com.intellij.lang.ecmascript6.psi.ES6Class
import com.intellij.lang.javascript.refactoring.util.JSMemberInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.truffle.api.strings.TruffleString
import fuzzer.JsFuzzer.jsFuzzing
import fuzzer.providers.JsObjectModelProvider
import java.io.File
import java.nio.file.Paths
import javafx.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.jetbrains.kotlin.idea.util.application.invokeLater
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.utbot.common.runBlockingWithCancellationPredicate
import org.utbot.common.runIgnoringCancellationException
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.EnvironmentModels
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsMethodId
import org.utbot.framework.plugin.api.JsPrimitiveModel
import org.utbot.framework.plugin.api.UtAssembleModel
import org.utbot.framework.plugin.api.UtExecutableCallModel
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.framework.plugin.api.UtExecutionSuccess
import org.utbot.framework.plugin.api.UtStatementModel
import org.utbot.framework.plugin.api.util.isJsBasic
import org.utbot.framework.plugin.api.util.voidClassId
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.FuzzedValue
import org.utbot.intellij.plugin.models.JsTestsModel
import org.utbot.intellij.plugin.ui.JsDialogWindow
import org.utbot.intellij.plugin.ui.utils.testModule
import parser.JsFunctionAstVisitor
import parser.JsFuzzerAstVisitor
import parser.JsParserUtils
import service.CoverageService
import service.ServiceContext
import service.TernService
import utils.constructClass
import utils.toAny

object JsDialogProcessor {

    fun createDialogAndGenerateTests(
        project: Project,
        srcModule: Module,
        fileMethods: Set<JSMemberInfo>,
        focusedMethod: JSMemberInfo?,
        containingFilePath: String,
        editor: Editor
    ) {
        val dialogProcessor = createDialog(project, srcModule, fileMethods, focusedMethod, containingFilePath)
        if (!dialogProcessor.showAndGet()) return

        createTests(dialogProcessor.model, containingFilePath, editor)
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

    private fun createTests(model: JsTestsModel, containingFilePath: String, editor: Editor) {
        (object : Task.Backgroundable(model.project, "Generate tests") {
            override fun run(indicator: ProgressIndicator) {
                Thread.currentThread().contextClassLoader = Context::class.java.classLoader
                runIgnoringCancellationException {
                    runBlockingWithCancellationPredicate({ indicator.isCanceled }) {
                        val text = File(containingFilePath).readText()
                        indicator.text = "Generate tests"
                        val fileText = text
                        val regex = Regex("module.exports = \\{.*}")
                        // TODO SEVERE: make a copy of a file so that it doesn't contain any global invocations besides generated one.
                        //  Also general trimming required, for example "export" keyword, etc.
                        val trimmedFileText = fileText.replace(regex, "")
                        val context = ServiceContext(
                            utbotDir = "utbotJs",
                            projectPath = model.project.basePath
                                ?: throw IllegalStateException("Can't access project path."),
                            filePathToInference = containingFilePath,
                            trimmedFileText = trimmedFileText,
                            fileText = fileText,
                        )
                        // TODO MINOR: do not create existing files
                        val ternService = TernService(context)
                        ternService.run()
                        val exports = mutableSetOf<String>()
                        model.selectedMethods?.forEach { jsMemberInfo ->
                            // TODO SEVERE: remove psi
                            var parentPsi = runReadAction { PsiTreeUtil.getParentOfType(jsMemberInfo.member, ES6Class::class.java) }
                            // "toplevelHack" is from JsActionMethods
                            val psiName = runReadAction { parentPsi?.name }
                            if (psiName == null || psiName == "toplevelHack") {
                                parentPsi = null
                            }
                            val funcNode = getFunctionNode(
                                runReadAction { jsMemberInfo.member.name!! },
                                psiName,
                                trimmedFileText
                            )
                            val classNode = if (parentPsi != null) JsParserUtils.searchForClassDecl(
                                psiName!!,
                                trimmedFileText
                            ) else null
                            val classId = parentPsi?.let {
                                JsClassId(psiName!!).constructClass(ternService, classNode)
                            } ?: JsClassId("undefined").constructClass(ternService, functions = listOf(funcNode))
                            val execId = classId.allMethods.find {
                                it.name == funcNode.name.toString()
                            } ?: throw IllegalStateException()

                            val obligatoryExport = (classNode?.ident?.name ?: funcNode.ident.name).toString()
                            val collectedExports = collectExports(execId)
                            exports += (collectedExports + obligatoryExport)
                            funcNode.body.accept(JsFuzzerAstVisitor)
                            val methodUnderTestDescription =
                                FuzzedMethodDescription(execId, JsFuzzerAstVisitor.fuzzedConcreteValues).apply {
                                    compilableName = funcNode.name.toString()
                                    val names = funcNode.parameters.map { it.name.toString() }
                                    parameterNameMap = { index -> names.getOrNull(index) }
                                }
                            val fuzzedValues =
                                jsFuzzing(methodUnderTestDescription = methodUnderTestDescription).toList()
                                    .shuffled()
                                    .take(500)
                            val coveredBranchesArray = Array<Set<Int>>(fuzzedValues.size) { emptySet() }
                            fuzzedValues.indices.toList().parallelStream().forEach {
                                val scriptText =
                                    makeStringForRunJs(
                                        fuzzedValues[it],
                                        execId,
                                        classNode?.ident?.name,
                                        trimmedFileText
                                    )
                                val id = Thread.currentThread().id
                                val coverageService = CoverageService(context, scriptText, id)
                                coveredBranchesArray[it] = coverageService.getCoveredLines()
                            }
                            val testsForGenerator = mutableListOf<UtExecution>()
                            analyzeCoverage(coveredBranchesArray.toList()).forEach { paramIndex ->
                                val param = fuzzedValues[paramIndex]
                                val utConstructor = JsUtModelConstructor()
                                val scriptText =
                                    makeStringForRunJs(param, execId, classNode?.ident?.name, trimmedFileText)
                                val (returnValue, valueClassId) = runJs(
                                    scriptText,
                                    containingFilePath.replaceAfterLast("/", "")
                                ).toAny(execId.returnType)
                                val result = utConstructor.construct(returnValue, valueClassId)
                                val thisInstance = when {
                                    execId.isStatic -> null
                                    classId.allConstructors.first().parameters.isEmpty() -> {
                                        val id = JsObjectModelProvider.idGenerator.asInt
                                        val constructor = classId.allConstructors.first()
                                        val instantiationChain = mutableListOf<UtStatementModel>()
                                        UtAssembleModel(
                                            id,
                                            constructor.classId,
                                            "${constructor.classId.name}${constructor.parameters}#" + id.toString(16),
                                            instantiationChain = instantiationChain
                                        ).apply {
                                            instantiationChain += UtExecutableCallModel(
                                                null,
                                                constructor,
                                                emptyList(),
                                                this
                                            )
                                        }
                                    }

                                    else -> {
                                        JsObjectModelProvider.generate(
                                            FuzzedMethodDescription(
                                                "thisInstance",
                                                voidClassId,
                                                listOf(classId),
                                                JsFuzzerAstVisitor.fuzzedConcreteValues
                                            )
                                        ).take(10).toList()
                                            .shuffled().map { it.value.model }.first()
                                    }
                                }
                                val initEnv = EnvironmentModels(thisInstance, param.map { it.model }, mapOf())
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
                            val generatedCode = codeGen.generateAsStringWithTestReport(listOf(testSet)).generatedCode
                            val fileName = containingFilePath.substringAfterLast("/").replace(Regex(".js"), "Test.js")
                            val testFile = File("${containingFilePath.replaceAfterLast("/", "")}$fileName")
                            testFile.writeText(generatedCode)
                            testFile.createNewFile()
                        }
                        AppExecutorUtil.getAppExecutorService().submit {
                            invokeLater {
                                manageExports(exports.toList(), editor, project)
                            }
                        }
                    }
                }
            }
        }).queue()
    }

    private fun collectExports(methodId: JsMethodId): List<String> {
        val res = mutableListOf<String>()
        methodId.parameters.forEach {
            if (!it.isJsBasic) {
                res += it.name
            }
        }
        if (!methodId.returnType.isJsBasic) res += methodId.returnType.name
        return res
    }

    private fun manageExports(exports: List<String>, editor: Editor, project: Project) {
        val startComment = "// Start of exports generated by UTBot"
        val endComment = "// End of exports generated by UTBot"
        val exportLine = exports.joinToString(", ")
        val fileText = editor.document.text
        val docManager = PsiDocumentManager.getInstance(project)
        when {
            fileText.contains("module.exports = {$exportLine}") -> {
            }
            fileText.contains(startComment) && !fileText.contains("module.exports = {$exportLine}") -> {
                val regex = Regex("\n$startComment\n(.*)\n$endComment")
                regex.find(fileText)?.groups?.get(1)?.value?.let {
                    val swappedText = fileText.replace(it, "module.exports = {$exportLine}")
                    runWriteAction {
                        with(editor.document) {
                            CodeGenerationController.unblockDocument(project, this)
                            setText(swappedText)
                            CodeGenerationController.unblockDocument(project, this)
                        }
                    }
                }
            }
            else -> {
                val line = buildString {
                    append("\n$startComment")
                    append("\nmodule.exports = {$exportLine}")
                    append("\n$endComment")
                }
                runWriteAction {
                    with(editor.document) {
                        CodeGenerationController.unblockDocument(project, this)
                        setText(fileText + line)
                        CodeGenerationController.unblockDocument(project, this)
                    }
                }
            }
        }
    }

    private fun runJs(scriptText: String, workDir: String): Value {
        val context = Context.newBuilder("js")
            .allowIO(true)
            .currentWorkingDirectory(Paths.get(workDir))
            .option("engine.WarnInterpreterOnly", "false")
            .build()
        val source = org.graalvm.polyglot.Source.newBuilder("js", scriptText, "script")
            .mimeType("application/javascript+module").build()
        return context.eval(source)
    }

    private fun makeStringForRunJs(fuzzedValue: List<FuzzedValue>, method: JsMethodId, containingClass: TruffleString?, fileText: String): String {
        val callString = makeCallFunctionString(fuzzedValue, method, containingClass)
        val res = buildString {
            append(fileText)
            append("\n")
            append(callString)
        }
        return res
    }

    private fun makeCallFunctionString(fuzzedValue: List<FuzzedValue>, method: JsMethodId, containingClass: TruffleString?): String {
        val initClass = containingClass?.let {
            if (!method.isStatic) {
                "new ${it}()."
            } else "$it."
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

    private fun getFunctionNode(focusedMethodName: String, parentClassName: String?, fileText: String): FunctionNode {
        val parser = Parser(
            ScriptEnvironment.builder().build(),
            Source.sourceFor("jsFile", fileText),
            ErrorManager.ThrowErrorManager()
        )
        val fileNode = parser.parse()
        val visitor = JsFunctionAstVisitor(focusedMethodName,
            if (parentClassName != "toplevelHack") parentClassName else null
        )
        fileNode.accept(visitor)
        return visitor.targetFunctionNode
    }

    private fun analyzeCoverage(coverageList: List<Set<Int>>): List<Int> {
        val allCoveredBranches = mutableSetOf<Int>()
        allCoveredBranches.addAll(coverageList.first())
        val resultList = mutableListOf(0)
        coverageList.forEachIndexed { index, it ->
            if (!allCoveredBranches.containsAll(it)) {
                resultList += index
                allCoveredBranches.addAll(it)
            }
        }
        return resultList
    }
}