package org.utbot.framework.codegen.model.visitor

import org.apache.commons.text.StringEscapeUtils
import org.utbot.common.WorkaroundReason
import org.utbot.common.workaround
import org.utbot.framework.codegen.RegularImport
import org.utbot.framework.codegen.StaticImport
import org.utbot.framework.codegen.isLanguageKeyword
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.tree.*
import org.utbot.framework.codegen.model.util.CgPrinter
import org.utbot.framework.codegen.model.util.CgPrinterImpl
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.framework.plugin.api.TypeParameters
import org.utbot.framework.plugin.api.WildcardTypeParameter
import org.utbot.framework.plugin.api.util.kClass

internal class CgPythonRenderer(context: CgContext, printer: CgPrinter = CgPrinterImpl()) :
    CgAbstractRenderer(context, printer) {
    override val regionStart: String = "# region"
    override val regionEnd: String = "# endregion"

    override val statementEnding: String = ""

    override val logicalAnd: String
        get() = "and"

    override val logicalOr: String
        get() = "or"

    override val language: CodegenLanguage = CodegenLanguage.PYTHON

    override val langPackage: String = "python"

    override fun visit(element: CgCommentedAnnotation) {
        print("#")
        element.annotation.accept(this)
    }

    override fun visit(element: CgSingleArgAnnotation) {
        print("")
    }

    override fun visit(element: CgMultipleArgsAnnotation) {
        print("")
    }

    override fun visit(element: CgSingleLineComment) {
        println("# ${element.comment}")
    }

    override fun visit(element: CgAbstractMultilineComment) {
        visit(element as CgElement)
    }

    override fun visit(element: CgTripleSlashMultilineComment) {
        for (line in element.lines) {
            println("# $line")
        }
    }

    override fun visit(element: CgMultilineComment) {
        val lines = element.lines
        if (lines.isEmpty()) return

        if (lines.size == 1) {
            print("# ${lines.first()}")
            return
        }

        // print lines saving indentation
        print("\"\"\"")
        println(lines.first())
        lines.subList(1, lines.lastIndex).forEach { println(it) }
        print(lines.last())
        println("\"\"\"")
    }

    override fun visit(element: CgDocumentationComment) {
        if (element.lines.all { it.isEmpty() }) return

        println("\"\"\"")
        for (line in element.lines) line.accept(this)
        println("\"\"\"")
    }

    override fun visit(element: CgErrorWrapper) {
        element.expression.accept(this)
    }

    override fun visit(element: CgTestClass) {
        print("class ")
        print(element.simpleName)
        if (element.superclass != null) {
            print("(${element.superclass.asString()})")
        }
        println(":")
        withIndent { element.body.accept(this) }
        println("")
    }

    override fun visit(element: CgTryCatch) {
        println("try")
        // TODO introduce CgBlock
        visit(element.statements)
        for ((exception, statements) in element.handlers) {
            print("except")
            renderExceptionCatchVariable(exception)
            println("")
            // TODO introduce CgBlock
            visit(statements, printNextLine = element.finally == null)
        }
        element.finally?.let {
            print("finally")
            // TODO introduce CgBlock
            visit(element.finally, printNextLine = true)
        }
    }

    override fun visit(element: CgArrayAnnotationArgument) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgAnonymousFunction) {
        print("lambda ")
        element.parameters.renderSeparated()
        print(": ")

        visit(element.body)
    }

    override fun visit(element: CgEqualTo) {
        element.left.accept(this)
        print(" == ")
        element.right.accept(this)
    }

    override fun visit(element: CgTypeCast) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgNotNullAssertion) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgAllocateArray) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgAllocateInitializedArray) {
        TODO("Not yet implemented")
        /*
        val arrayModel = element.model
        val elementsInLine = arrayElementsInLine(arrayModel.constModel)

        print("[")
        arrayModel.renderElements(element.size, elementsInLine)
        print("]")
         */
    }

    override fun visit(element: CgArrayInitializer) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgSwitchCaseLabel) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgSwitchCase) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgParameterDeclaration) {
        print(element.name.escapeNamePossibleKeyword())
        print(": ")
        print(element.type.asString())
    }

    override fun visit(element: CgGetLength) {
        print("len(")
        element.variable.accept(this)
        print(")")
    }

    override fun visit(element: CgGetJavaClass) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgGetKotlinClass) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgConstructorCall) {
        print(element.executableId.classId.asString())
        renderExecutableCallArguments(element)
    }

    override fun renderRegularImport(regularImport: RegularImport) {
        val escapedImport = getEscapedImportRendering(regularImport)  // ???
        print("import $escapedImport")
    }

    override fun renderStaticImport(staticImport: StaticImport) {
        TODO("Not yet implemented")
    }

    override fun renderMethodSignature(element: CgTestMethod) {
        print("def ")
        print(element.name)

        print("(")
        val newLinesNeeded = element.parameters.size > maxParametersAmountInOneLine
        element.parameters.renderSeparated(newLinesNeeded)
        print(")")
    }

    override fun renderMethodSignature(element: CgErrorTestMethod) {
        print("def ${element.name}()")
    }

    override fun renderMethodSignature(element: CgParameterizedTestDataProviderMethod) {
        TODO("Not yet implemented")
    }

    override fun visit(element: CgInnerBlock) {
        withIndent {
            for (statement in element.statements) {
                statement.accept(this)
            }
        }
    }

    override fun renderForLoopVarControl(element: CgForLoop) {
        println("for ??? in ???:")
    }

    override fun renderDeclarationLeftPart(element: CgDeclaration) {
        visit(element.variable)
//        print(": ")
//        print(element.variableType.asString())
    }

    override fun toStringConstantImpl(byte: Byte): String {
        return "str($byte)"
    }

    override fun toStringConstantImpl(short: Short): String {
        return "str($short)"
    }

    override fun toStringConstantImpl(int: Int): String {
        return "str($int)"
    }

    override fun toStringConstantImpl(long: Long): String {
        return "str($long)"
    }

    override fun toStringConstantImpl(float: Float): String {
        return "str($float)"
    }

    override fun renderAccess(caller: CgExpression) {
        print(".")
    }

    override fun renderTypeParameters(typeParameters: TypeParameters) {
        if (typeParameters.parameters.isNotEmpty()) {
            print("[")
            if (typeParameters is WildcardTypeParameter) {
                print("typing.Any")
            } else {
                print(typeParameters.parameters.joinToString { it.name })
            }
            print("]")
        }
    }

    override fun renderExecutableCallArguments(executableCall: CgExecutableCall) {
        print("(")
        executableCall.arguments.renderSeparated()
        print(")")
    }

    override fun renderExceptionCatchVariable(exception: CgVariable) {
        print(exception.name.escapeNamePossibleKeyword())
    }

    override fun escapeNamePossibleKeywordImpl(s: String): String = s

    override fun visit(block: List<CgStatement>, printNextLine: Boolean) {
        println(":")

        val isBlockTooLarge = workaround(WorkaroundReason.LONG_CODE_FRAGMENTS) { block.size > 120 }

        if (isBlockTooLarge) {
            print("\"\"\"")
            println(" This block of code is ${block.size} lines long and could lead to compilation error")
        }

        withIndent {
            for (statement in block) {
                statement.accept(this)
            }
        }

        if (isBlockTooLarge) println("\"\"\"")

//        print("}")

        if (printNextLine) println()
    }

    override fun String.escapeCharacters(): String =
        StringEscapeUtils.escapeJava(this)
        .replace("$", "\\$")
        .replace("\\f", "\\u000C")
        .replace("\\xxx", "\\\u0058\u0058\u0058")
}