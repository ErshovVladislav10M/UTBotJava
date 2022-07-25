package org.utbot.python.code

import io.github.danielnaczo.python3parser.model.Identifier
import io.github.danielnaczo.python3parser.model.expr.Expression
import io.github.danielnaczo.python3parser.model.expr.atoms.Atom
import io.github.danielnaczo.python3parser.model.expr.atoms.Name
import io.github.danielnaczo.python3parser.model.expr.atoms.Str
import io.github.danielnaczo.python3parser.model.expr.atoms.trailers.Attribute
import io.github.danielnaczo.python3parser.model.expr.atoms.trailers.arguments.Arguments
import io.github.danielnaczo.python3parser.model.expr.atoms.trailers.arguments.Keyword
import io.github.danielnaczo.python3parser.model.expr.operators.binaryops.comparisons.Eq
import io.github.danielnaczo.python3parser.model.mods.Module
import io.github.danielnaczo.python3parser.model.stmts.Body
import io.github.danielnaczo.python3parser.model.stmts.Statement
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.functionStmts.FunctionDef
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.tryExceptStmts.ExceptHandler
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.tryExceptStmts.Try
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.withStmts.With
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.withStmts.WithItem
import io.github.danielnaczo.python3parser.model.stmts.importStmts.Alias
import io.github.danielnaczo.python3parser.model.stmts.importStmts.Import
import io.github.danielnaczo.python3parser.model.stmts.importStmts.ImportFrom
import io.github.danielnaczo.python3parser.model.stmts.smallStmts.Assert
import io.github.danielnaczo.python3parser.model.stmts.smallStmts.assignStmts.Assign
import io.github.danielnaczo.python3parser.visitors.prettyprint.IndentationPrettyPrint
import io.github.danielnaczo.python3parser.visitors.prettyprint.ModulePrettyPrintVisitor
import org.utbot.framework.plugin.api.UtModel
import org.utbot.python.*
import java.io.File


object PythonCodeGenerator {
    private fun toString(module: Module): String {
        val modulePrettyPrintVisitor = ModulePrettyPrintVisitor()
        return modulePrettyPrintVisitor.visitModule(module, IndentationPrettyPrint(0))
    }

    fun generateTestCode(testCase: PythonTestSet, directoriesForSysPath: List<String>, moduleToImport: String): String {
        val importFunction = generateImportFunctionCode(
            moduleToImport,
            directoriesForSysPath
        )
        val testCaseCodes = (testCase.executions + testCase.errors).mapIndexed { index, utExecution ->
            generateTestCode(testCase.method, utExecution, index)
        }
        return toString(Module(importFunction)) + testCaseCodes.joinToString("")
    }

    fun generateTestCode(method: PythonMethod, result: PythonResult, number: Int) =
        when (result) {
            is PythonExecution -> generateExecutionCode(method, result, number)
            is PythonError -> generateErrorCode(method, result, number)
        }

    private fun functionWithParameters(
        testFunctionName: String,
        method: PythonMethod,
        parameterValues: List<UtModel>,
        returnValueName: String
    ): FunctionDef {
        val testFunction = FunctionDef(testFunctionName)
        val parameters = parameterValues.zip(method.arguments).map { (model, argument) ->
            Assign(
                listOf(Name(argument.name)),
                Name(model.toString())
            )
        }
        parameters.forEach {
            testFunction.addStatement(it)
        }

        val keywords = method.arguments.map {
            Keyword(Name(it.name), Name(it.name))
        }
        val functionCall = Assign(
            listOf(Name(returnValueName)),
            Atom(
                Name(method.name),
                listOf(createArguments(emptyList(), keywords))
            )
        )
        testFunction.addStatement(functionCall)

        return testFunction
    }

    private fun generateErrorCode(method: PythonMethod, error: PythonError, number: Int): String {
        val testFunctionName = "test_$number"
        val testFunction = functionWithParameters(testFunctionName, method, error.parameters, "returnValue")
        testFunction.addStatement(Name("# throws ${error.utError.description}"))
        return PythonMethodBody(testFunction).asString()
    }

    private fun generateExecutionCode(method: PythonMethod, pyExecution: PythonExecution, number: Int): String {
        val execution = pyExecution.utExecution
        val testFunctionName = "${execution.testMethodName?.camelToSnakeCase() ?: "test"}_$number"
        val actualName = "actual"
        val testFunction = functionWithParameters(testFunctionName, method, pyExecution.parameters, actualName)

        val correctResultName = "correct_result"
        val correctResult = Assign(
            listOf(Name(correctResultName)),
            Name(execution.result.toString())
        )
        testFunction.addStatement(correctResult)

        val assertLine = Assert(
            Eq(
                Name(correctResultName),
                Name(actualName)
            )
        )
        testFunction.addStatement(assertLine)

        return PythonMethodBody(testFunction).asString()
    }

    private fun createOutputBlock(outputName: String, outputFilename: String, outputFileAlias: String): With {
        return With(
            listOf(
                WithItem(
                Atom(
                    Name("open"),
                    listOf(
                        createArguments(
                            listOf(
                                Str(outputFilename),
                                Str("w")
                            )
                        )
                    )
                ),
                Name(outputFileAlias)
            )
            ),
            Body(listOf(
                Assign(
                    listOf(Name("out")),
                    Name("dict()")
                ),
                Assign(
                    listOf(Name("out['output']")),
                    Atom(Name("repr"), listOf(createArguments(listOf(Name(outputName)))))
                ),
                Assign(
                    listOf(Name("out['type']")),
                    Atom(
                        Atom(
                            Name("type"),
                            listOf(createArguments(listOf(Name(outputName))))
                        ),
                        listOf(Attribute(Identifier("__name__")))
                    )
                ),
                Atom(
                    Name("print"),
                    listOf(
                        createArguments(
                            listOf(Name("json.dumps(out)")),
                            listOf(
                                Keyword(Name("file"), Name(outputFileAlias)),
                                Keyword(Name("end"), Str(""))
                            ),
                        )
                    )
                )
            ))
        )
    }

    private fun createArguments(
        args: List<Expression> = emptyList(),
        keywords: List<Keyword> = emptyList(),
        starredArgs: List<Expression> = emptyList(),
        doubleStarredArgs: List<Keyword> = emptyList()
    ): Arguments {
        return Arguments(args, keywords, starredArgs, doubleStarredArgs)
    }

    private fun generateImportFunctionCode(functionPath: String, directoriesForSysPath: List<String>): List<Statement> {
        val systemImport = Import(listOf(Alias("sys"), Alias("json")))
        val systemCalls = directoriesForSysPath.map { path ->
            Atom(
                Name("sys.path.append"),
                listOf(
                    createArguments(
                        listOf(Str(path))
                    )
                )
            )
        }
        val import = ImportFrom(functionPath, listOf(Alias("*")))
        return listOf(systemImport) + systemCalls + listOf(import)
    }

    fun generateRunFunctionCode(
        method: PythonMethod,
        methodArguments: List<UtModel>,
        outputFilename: String,
        errorFilename: String,
        codeFilename: String,
        directoriesForSysPath: List<String>,
        moduleToImport: String
    ): File {

        val importStatements = generateImportFunctionCode(
            moduleToImport,
            directoriesForSysPath
        )

        val testFunctionName = "__run_${method.name}"
        val testFunction = FunctionDef(testFunctionName)

        val parameters = methodArguments.zip(method.arguments).map { (model, argument) ->
            Assign(
                listOf(Name(argument.name)),
                Name(model.toString())
            )
        }
        val resultName = "result"
        val keywords = method.arguments.map {
            Keyword(Name(it.name), Name(it.name))
        }
        val functionCall = Assign(
            listOf(Name(resultName)),
            Atom(
                Name(method.name),
                listOf(
                    createArguments(emptyList(), keywords)
                )
            )
        )

        val outputFileAlias = "fout"
        val withOpenResultFile = createOutputBlock(
            resultName,
            outputFilename,
            outputFileAlias
        )

        val errorFileAlias = "ferr"
        val exceptionName = "e"

        val withOpenErrorFile = createOutputBlock(
            exceptionName,
            errorFilename,
            errorFileAlias
        )

        val tryBody = Body(parameters + listOf(functionCall, withOpenResultFile))
        val tryHandler = ExceptHandler("Exception", exceptionName)
        val tryBlock = Try(tryBody, listOf(tryHandler), listOf(withOpenErrorFile))

        testFunction.addStatement(
            tryBlock
        )

        val runFunction = Atom(
            Name(testFunctionName),
            listOf(createArguments())
        )

        val functionCode = toString(
            Module(
                importStatements + listOf(testFunction, runFunction)
            )
        )

        return saveToFile(codeFilename, functionCode)
    }

    fun saveToFile(filePath: String, code: String): File {
        val file = File(filePath)
        file.writeText(code)
        file.createNewFile()
        return file
    }

//    fun generateStubFile(
//        method: PythonMethod,
//        annotations: List<String>,
//    ): String {
//        val parameters = method.arguments.zip(annotations).map { (argument, annotation) ->
//            Parameter(argument.name, Name(annotation))
//        }
//        val sourceCodeFile = File(method.sourceCodePath.toString().split(".").first() + ".py")
//        val sourceCode = sourceCodeFile.readText()
//
//        val functionName = "__mypy_test_${method.name}"
//        val functionDef = FunctionDef(
//            functionName,
//            Parameters(parameters),
//            method.ast().body
//        )
//        return sourceCode + "\n" + PythonMethodBody(functionDef).asString()
//    }
}

fun String.camelToSnakeCase(): String {
    val camelRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()
    return camelRegex.replace(this) {
        "_${it.value}"
    }.toLowerCase()
}