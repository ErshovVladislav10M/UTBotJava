package org.utbot.python

import io.github.danielnaczo.python3parser.Python3Lexer
import io.github.danielnaczo.python3parser.Python3Parser
import io.github.danielnaczo.python3parser.model.AST
import io.github.danielnaczo.python3parser.model.expr.Expression
import io.github.danielnaczo.python3parser.model.expr.atoms.Name
import io.github.danielnaczo.python3parser.model.expr.atoms.Num
import io.github.danielnaczo.python3parser.model.expr.atoms.Str
import io.github.danielnaczo.python3parser.model.mods.Module
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.ClassDef
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.functionStmts.FunctionDef
import io.github.danielnaczo.python3parser.model.stmts.compoundStmts.functionStmts.parameters.Parameter
import io.github.danielnaczo.python3parser.visitors.ast.ModuleVisitor
import io.github.danielnaczo.python3parser.visitors.modifier.ModifierVisitor
import io.github.danielnaczo.python3parser.visitors.prettyprint.IndentationPrettyPrint
import io.github.danielnaczo.python3parser.visitors.prettyprint.ModulePrettyPrintVisitor
import org.antlr.v4.runtime.CharStreams.fromString
import org.antlr.v4.runtime.CommonTokenStream
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.PythonIntModel
import org.utbot.framework.plugin.api.PythonStrModel
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.framework.plugin.api.util.doubleClassId
import org.utbot.framework.plugin.api.util.intClassId
import org.utbot.framework.plugin.api.util.longClassId
import org.utbot.framework.plugin.api.util.stringClassId
import org.utbot.fuzzer.FuzzedConcreteValue
import java.util.*
import java.util.function.Consumer
import javax.xml.bind.DatatypeConverter.parseLong


class PythonCode(private val body: Module) {
    fun getToplevelFunctions(): List<PythonMethodBody> =
        body.statements.mapNotNull { statement ->
            (statement as? FunctionDef)?.let { functionDef: FunctionDef ->
                PythonMethodBody(functionDef)
            }
        }

    fun getToplevelClasses(): List<PythonClass> =
        body.statements.mapNotNull { statement ->
            (statement as? ClassDef)?.let { classDef: ClassDef ->
                PythonClass(classDef)
            }
        }

    companion object {
        fun getFromString(code: String): PythonCode {
            val lexer = Python3Lexer(fromString(code))
            val tokens = CommonTokenStream(lexer)
            val parser = Python3Parser(tokens)
            val moduleVisitor = ModuleVisitor()
            val ast = moduleVisitor.visit(parser.file_input()) as Module

            return PythonCode(ast)
        }
    }
}

class PythonClass(private val ast: ClassDef) {
    val name: String
        get() = ast.name.name

    val methods: List<PythonMethodBody>
        get() = ast.functionDefs.map { PythonMethodBody(it) }
}

class PythonMethodBody(private val ast: FunctionDef): PythonMethod {
    override val name: String
        get() = ast.name.name

    private val returnTypeAsString: String?
        get() = annotationToString(ast.returns)

    override val returnType: ClassId?
        get() = returnTypeAsString?.let { typeAsStringToClassId(it) }

    // TODO: consider cases of default and named arguments
    private val getParams: List<Parameter> =
        if (ast.parameters.isPresent) ast.parameters.get().params else emptyList()

    override val arguments: List<PythonArgument>
        get() = getParams.map { param ->
            PythonArgument(
                param.parameterName.name,
                annotationToString(param.annotation)?.let { typeAsStringToClassId(it) }
            )
        }

    override fun asString(): String {
        val modulePrettyPrintVisitor = ModulePrettyPrintVisitor()
        return modulePrettyPrintVisitor.visitModule(Module(listOf(ast)), IndentationPrettyPrint(0))
    }

    override fun getConcreteValues(): List<FuzzedConcreteValue> {
        val visitor = ConcreteValuesVisitor()
        val res = mutableListOf<FuzzedConcreteValue>()
        visitor.visitFunctionDef(ast, res)
        return res
    }

    private class ConcreteValuesVisitor: ModifierVisitor<MutableList<FuzzedConcreteValue>>() {
        override fun visitNum(num: Num, res: MutableList<FuzzedConcreteValue>): AST {
            res += (FuzzedConcreteValue(longClassId, parseLong(num.n)))
            return super.visitNum(num, res)
        }

        override fun visitStr(str: Str, res: MutableList<FuzzedConcreteValue>): AST {
            res += FuzzedConcreteValue(
                stringClassId,
                str.s.removeSurrounding("\"", "\"").removeSurrounding("'", "'")
            )
            return super.visitStr(str, res)
        }
    }

    companion object {
        fun typeAsStringToClassId(typeAsString: String): ClassId? =
            when (typeAsString) {
                "int" -> PythonIntModel.classId
                "str" -> PythonStrModel.classId
                else -> null
            }

        fun annotationToString(annotation: Optional<Expression>): String? =
            if (annotation.isPresent) (annotation.get() as? Name)?.id?.name else null
    }
}