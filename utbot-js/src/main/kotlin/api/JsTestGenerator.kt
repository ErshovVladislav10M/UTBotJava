package api

import codegen.JsCodeGenerator
import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.truffle.api.strings.TruffleString
import fuzzer.JsFuzzer
import fuzzer.providers.JsObjectModelProvider
import java.io.File
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.EnvironmentModels
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsMethodId
import org.utbot.framework.plugin.api.JsMultipleClassId
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
import org.utbot.fuzzer.SimpleIdGenerator
import parser.JsClassAstVisitor
import parser.JsFunctionAstVisitor
import parser.JsFuzzerAstVisitor
import parser.JsParserUtils
import parser.JsToplevelFunctionAstVisitor
import service.CoverageService
import service.ServiceContext
import service.TernService
import utils.JsCmdExec
import utils.PathResolver
import utils.constructClass
import utils.toAny


class JsTestGenerator(
    private val fileText: String,
    private val sourceFilePath: String,
    private val projectPath: String = sourceFilePath.replaceAfterLast(File.separator, ""),
    private val selectedMethods: List<String>? = null,
    private val parentClassName: String? = null,
    private val outputFilePath: String?
) {

    private val _exports = mutableSetOf<String>()

    val exports
        get() = _exports.toList()

    private lateinit var parsedFile: FunctionNode

    companion object {
        const val utbotDir = "utbotJs"
    }

    /**
     * Returns String representation of generated tests.
     */
    fun run(): String {
        val trimmedFileText = trimText(fileText)
        parsedFile = runParser(trimmedFileText)
        val context = ServiceContext(
            utbotDir = utbotDir,
            projectPath = projectPath,
            filePathToInference = sourceFilePath.replace("\\", "/"),
            trimmedFileText = trimmedFileText,
            fileText = fileText,
        )
        // TODO MINOR: do not create existing files
        val ternService = TernService(context)
        ternService.run()
        val paramNames = mutableMapOf<ExecutableId, List<String>>()
        val testSets = mutableListOf<CgMethodTestSet>()
        val classNode = parentClassName?.let {
            JsParserUtils.searchForClassDecl(
                parentClassName,
                trimmedFileText
            )
        }
        val classId = classNode?.let {
            JsClassId(parentClassName!!).constructClass(ternService, classNode)
        } ?: JsClassId("undefined").constructClass(
            ternService,
            functions = extractToplevelFunctions()
        )
        val methods = selectedMethods?.map {
            getFunctionNode(
                it,
                parentClassName,
                trimmedFileText
            )
        } ?: getMethodsToTest()
        if (methods.isEmpty()) throw IllegalArgumentException("No methods to test were found!")
        methods.forEach { funcNode ->

            val execId = classId.allMethods.find {
                it.name == funcNode.name.toString()
            } ?: throw IllegalStateException()

            val obligatoryExport = (classNode?.ident?.name ?: funcNode.ident.name).toString()
            val collectedExports = collectExports(execId)
            _exports += (collectedExports + obligatoryExport)
            val fuzzerVisitor = JsFuzzerAstVisitor()
            funcNode.body.accept(fuzzerVisitor)
            val methodUnderTestDescription =
                FuzzedMethodDescription(execId, fuzzerVisitor.fuzzedConcreteValues).apply {
                    compilableName = funcNode.name.toString()
                    val names = funcNode.parameters.map { it.name.toString() }
                    parameterNameMap = { index -> names.getOrNull(index) }
                }
            val fuzzedValues =
                JsFuzzer.jsFuzzing(methodUnderTestDescription = methodUnderTestDescription).toList()
                    .shuffled()
                    .take(500)
            val coveredBranchesArray = Array<Set<Int>>(fuzzedValues.size) { emptySet() }
            val idGenerator = SimpleIdGenerator()
            fuzzedValues.indices.toList().parallelStream().forEach {
                val scriptText =
                    makeStringForRunJs(
                        fuzzedValues[it],
                        execId,
                        classNode?.ident?.name,
                        trimmedFileText
                    )
                val id = idGenerator.asInt
                val coverageService = CoverageService(context, scriptText, id.toLong())
                coveredBranchesArray[it] = coverageService.getCoveredLines()
            }
            val testsForGenerator = mutableListOf<UtExecution>()
            val resultRegex = Regex("Utbot result: (.*)")


            analyzeCoverage(coveredBranchesArray.toList()).forEach { paramIndex ->
                val param = fuzzedValues[paramIndex]
                val utConstructor = JsUtModelConstructor()
                val scriptText =
                    makeStringForRunJs(param, execId, classNode?.ident?.name, trimmedFileText)
                val returnText = runJs(
                    scriptText,
                    sourceFilePath.replaceAfterLast(File.separator, ""),
                )
                val unparsedValue =
                    resultRegex.findAll(returnText).last().groups[1]?.value ?: throw IllegalStateException()
                val (returnValue, valueClassId) = unparsedValue.toAny(execId.returnType)
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
                                fuzzerVisitor.fuzzedConcreteValues
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
            testSets += testSet
            paramNames[execId] = funcNode.parameters.map { it.name.toString() }
        }
        val importPrefix = outputFilePath?.let {
            PathResolver.getRelativePath(it.substringBeforeLast(File.separator), sourceFilePath.substringBeforeLast(File.separator))
        } ?: ""
        val codeGen = JsCodeGenerator(
            classId,
            paramNames,
            importPrefix = importPrefix
        )
        return codeGen.generateAsStringWithTestReport(testSets).generatedCode
    }

    // TODO SEVERE: make a copy of a file so that it doesn't contain any global invocations besides generated one.
    //  Also general trimming required, for example "export" keyword, etc.
    private fun trimText(fileText: String): String {
        val regex = Regex("module.exports = \\{.*}|export")
        return fileText.replace(regex, "")
    }

    private fun runParser(fileText: String): FunctionNode {
        val parser = Parser(
            ScriptEnvironment.builder().build(),
            Source.sourceFor("jsFile", fileText),
            ErrorManager.ThrowErrorManager()
        )
        return parser.parse()
    }

    private fun extractToplevelFunctions(): List<FunctionNode> {
        val visitor = JsToplevelFunctionAstVisitor()
        parsedFile.body.accept(visitor)
        return visitor.extractedMethods
    }

    private fun collectExports(methodId: JsMethodId): List<String> {
        val res = mutableListOf<String>()
        methodId.parameters.forEach {
            if (!(it.isJsBasic || it is JsMultipleClassId)) {
                res += it.name
            }
        }
        if (!(methodId.returnType.isJsBasic || methodId.returnType is JsMultipleClassId)) res += methodId.returnType.name
        return res
    }

    private fun runJs(scriptText: String, workDir: String): String {
        val tempFile = File("$workDir${File.separator}tempScriptUtbotJs.js")
        tempFile.writeText(scriptText)
        tempFile.createNewFile()
        val (reader, _) = JsCmdExec.runCommand("node ${tempFile.path}", dir = workDir, true)
        tempFile.delete()
        return reader.readText()
    }

    private fun makeStringForRunJs(
        fuzzedValue: List<FuzzedValue>,
        method: JsMethodId,
        containingClass: TruffleString?,
        fileText: String
    ): String {
        val callString = makeCallFunctionString(fuzzedValue, method, containingClass)
        val prefix = "Utbot result:"
        val temp = "console.log(`$prefix \"\${res}\"`)"
        val res = buildString {
            append(fileText)
            append("\n")
            append(
                """
                {
                    let prefix = "$prefix"
                    let res = $callString
                    if (typeof res == "string") $temp
                    else console.log(prefix, res)
                }
            """.trimIndent()
            )
        }
        return res
    }

    // TODO MINOR: my eyes...
    private fun makeCallFunctionString(
        fuzzedValue: List<FuzzedValue>,
        method: JsMethodId,
        containingClass: TruffleString?
    ): String {
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

    private fun getFunctionNode(focusedMethodName: String, parentClassName: String?, fileText: String): FunctionNode {
        val parser = Parser(
            ScriptEnvironment.builder().build(),
            Source.sourceFor("jsFile", fileText),
            ErrorManager.ThrowErrorManager()
        )
        val fileNode = parser.parse()
        val visitor = JsFunctionAstVisitor(
            focusedMethodName,
            if (parentClassName != "toplevelHack") parentClassName else null
        )
        fileNode.accept(visitor)
        return visitor.targetFunctionNode
    }

    private fun getMethodsToTest() =
        parentClassName?.let {
            getClassMethods(it)
        } ?: extractToplevelFunctions()

    private fun getClassMethods(className: String): List<FunctionNode> {
        val visitor = JsClassAstVisitor(className)
        parsedFile.body.accept(visitor)
        val classNode = visitor.targetClassNode
        return classNode.classElements.filter {
            it.value is FunctionNode
        }.map {it.value as FunctionNode}
    }
}