package org.utbot.framework.codegen.model.constructor.tree

import fj.data.Either
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.context.CgContextOwner
import org.utbot.framework.codegen.model.constructor.util.CgComponents
import org.utbot.framework.codegen.model.constructor.util.CgStatementConstructor
import org.utbot.framework.codegen.model.constructor.util.ExpressionWithType
import org.utbot.framework.codegen.model.constructor.util.plus
import org.utbot.framework.codegen.model.tree.CgAnnotation
import org.utbot.framework.codegen.model.tree.CgAnonymousFunction
import org.utbot.framework.codegen.model.tree.CgComment
import org.utbot.framework.codegen.model.tree.CgDeclaration
import org.utbot.framework.codegen.model.tree.CgEmptyLine
import org.utbot.framework.codegen.model.tree.CgExpression
import org.utbot.framework.codegen.model.tree.CgForEachLoopBuilder
import org.utbot.framework.codegen.model.tree.CgForLoopBuilder
import org.utbot.framework.codegen.model.tree.CgIfStatement
import org.utbot.framework.codegen.model.tree.CgInnerBlock
import org.utbot.framework.codegen.model.tree.CgIsInstance
import org.utbot.framework.codegen.model.tree.CgLogicalAnd
import org.utbot.framework.codegen.model.tree.CgLogicalOr
import org.utbot.framework.codegen.model.tree.CgMultilineComment
import org.utbot.framework.codegen.model.tree.CgMultipleArgsAnnotation
import org.utbot.framework.codegen.model.tree.CgNamedAnnotationArgument
import org.utbot.framework.codegen.model.tree.CgParameterDeclaration
import org.utbot.framework.codegen.model.tree.CgReturnStatement
import org.utbot.framework.codegen.model.tree.CgSingleArgAnnotation
import org.utbot.framework.codegen.model.tree.CgSingleLineComment
import org.utbot.framework.codegen.model.tree.CgThrowStatement
import org.utbot.framework.codegen.model.tree.CgTryCatch
import org.utbot.framework.codegen.model.tree.CgVariable
import org.utbot.framework.codegen.model.tree.buildAssignment
import org.utbot.framework.codegen.model.tree.buildDeclaration
import org.utbot.framework.codegen.model.tree.buildDoWhileLoop
import org.utbot.framework.codegen.model.tree.buildForLoop
import org.utbot.framework.codegen.model.tree.buildTryCatch
import org.utbot.framework.codegen.model.tree.buildWhileLoop
import org.utbot.framework.codegen.model.util.buildExceptionHandler
import org.utbot.framework.codegen.model.util.resolve
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.UtModel

class JsCgStatementConstructorImpl(context: CgContext) :
    CgStatementConstructor,
    CgContextOwner by context,
    CgCallableAccessManager by CgComponents.getCallableAccessManagerBy(context) {

    private val nameGenerator = CgComponents.getNameGeneratorBy(context)

    override fun newVar(
        baseType: ClassId,
        model: UtModel?,
        baseName: String?,
        isMock: Boolean,
        isMutable: Boolean,
        init: () -> CgExpression
    ): CgVariable {
        val declarationOrVar: Either<CgDeclaration, CgVariable> =
            createDeclarationForNewVarAndUpdateVariableScopeOrGetExistingVariable(
                baseType,
                model,
                baseName,
                isMock,
                isMutable,
                init
            )

        return declarationOrVar.either(
            { declaration ->
                currentBlock += declaration

                declaration.variable
            },
            { variable -> variable }
        )
    }

    override fun createDeclarationForNewVarAndUpdateVariableScopeOrGetExistingVariable(
        baseType: ClassId,
        model: UtModel?,
        baseName: String?,
        isMock: Boolean,
        isMutableVar: Boolean,
        init: () -> CgExpression
    ): Either<CgDeclaration, CgVariable> {

        val baseExpr = init()

        val name = nameGenerator.variableName(baseType, baseName, isMock)

        // TODO SEVERE: here was import section for CgClassId. Implement it
//        importIfNeeded(baseType)
//        if ((baseType as JsClassId).name != "undefined") {
//            importedClasses += baseType
//        }

        val declaration = buildDeclaration {
            variableType = baseType
            variableName = name
            initializer = baseExpr
            isMutable = isMutableVar
        }

        updateVariableScope(declaration.variable, model)

        return Either.left(declaration)
    }

    override fun CgExpression.`=`(value: Any?) {
        currentBlock += buildAssignment {
            lValue = this@`=`
            rValue = value.resolve()
        }
    }

    override fun CgExpression.and(other: CgExpression): CgLogicalAnd =
        CgLogicalAnd(this, other)


    override fun CgExpression.or(other: CgExpression): CgLogicalOr =
        CgLogicalOr(this, other)

    override fun ifStatement(
        condition: CgExpression,
        trueBranch: () -> Unit,
        falseBranch: (() -> Unit)?
    ): CgIfStatement {
        val trueBranchBlock = block(trueBranch)
        val falseBranchBlock = falseBranch?.let { block(it) }
        return CgIfStatement(condition, trueBranchBlock, falseBranchBlock).also {
            currentBlock += it
        }
    }

    override fun forLoop(init: CgForLoopBuilder.() -> Unit) {
        currentBlock += buildForLoop(init)
    }

    override fun whileLoop(condition: CgExpression, statements: () -> Unit) {
        currentBlock += buildWhileLoop {
            this.condition = condition
            this.statements += block(statements)
        }
    }

    override fun doWhileLoop(condition: CgExpression, statements: () -> Unit) {
        currentBlock += buildDoWhileLoop {
            this.condition = condition
            this.statements += block(statements)
        }
    }

    override fun forEachLoop(init: CgForEachLoopBuilder.() -> Unit) {
        throw UnsupportedOperationException("JavaScript does not have forEach loops")
    }

    override fun tryBlock(init: () -> Unit): CgTryCatch = tryBlock(init, null)

    override fun tryBlock(init: () -> Unit, resources: List<CgDeclaration>?): CgTryCatch =
        buildTryCatch {
            statements = block(init)
            this.resources = resources
        }

    override fun CgTryCatch.catch(exception: ClassId, init: (CgVariable) -> Unit): CgTryCatch {
        val newHandler = buildExceptionHandler {
            val e = declareVariable(exception, nameGenerator.variableName(exception.simpleName.decapitalize()))
            this.exception = e
            this.statements = block { init(e) }
        }
        return this.copy(handlers = handlers + newHandler)
    }

    override fun CgTryCatch.finally(init: () -> Unit): CgTryCatch {
        val finallyBlock = block(init)
        return this.copy(finally = finallyBlock)
    }

    override fun CgExpression.isInstance(value: CgExpression): CgIsInstance {
        TODO("Not yet implemented")
    }

    // TODO MINOR: check whether js has inner blocks
    override fun innerBlock(init: () -> Unit): CgInnerBlock =
        CgInnerBlock(block(init)).also {
            currentBlock += it
        }

    override fun comment(text: String): CgComment =
        CgSingleLineComment(text).also {
            currentBlock += it
        }

    override fun comment(): CgComment =
        CgSingleLineComment("").also {
            currentBlock += it
        }

    override fun multilineComment(lines: List<String>): CgComment =
        CgMultilineComment(lines).also {
            currentBlock += it
        }

    override fun lambda(type: ClassId, vararg parameters: CgVariable, body: () -> Unit): CgAnonymousFunction {
        return withNameScope {
            for (parameter in parameters) {
                declareParameter(parameter.type, parameter.name)
            }
            val paramDeclarations = parameters.map { CgParameterDeclaration(it) }
            CgAnonymousFunction(type, paramDeclarations, block(body))
        }
    }

    override fun annotation(classId: ClassId, argument: Any?): CgAnnotation {
        val annotation = CgSingleArgAnnotation(classId, argument.resolve())
        addAnnotation(annotation)
        return annotation
    }

    override fun annotation(classId: ClassId, namedArguments: List<Pair<String, CgExpression>>): CgAnnotation {
        val annotation = CgMultipleArgsAnnotation(
            classId,
            namedArguments.mapTo(mutableListOf()) { (name, value) -> CgNamedAnnotationArgument(name, value) }
        )
        addAnnotation(annotation)
        return annotation
    }

    override fun annotation(
        classId: ClassId,
        buildArguments: MutableList<Pair<String, CgExpression>>.() -> Unit
    ): CgAnnotation {
        val arguments = mutableListOf<Pair<String, CgExpression>>()
            .apply(buildArguments)
            .map { (name, value) -> CgNamedAnnotationArgument(name, value) }
        val annotation = CgMultipleArgsAnnotation(classId, arguments.toMutableList())
        addAnnotation(annotation)
        return annotation
    }

    override fun returnStatement(expression: () -> CgExpression) {
        currentBlock += CgReturnStatement(expression())
    }

    override fun throwStatement(exception: () -> CgExpression): CgThrowStatement =
        CgThrowStatement(exception()).also { currentBlock += it }

    override fun emptyLine() {
        currentBlock += CgEmptyLine()
    }

    override fun emptyLineIfNeeded() {
        val lastStatement = currentBlock.lastOrNull() ?: return
        if (lastStatement is CgEmptyLine) return
        emptyLine()
    }

    override fun declareVariable(type: ClassId, name: String): CgVariable =
        CgVariable(name, type).also {
            updateVariableScope(it)
        }

    // TODO SEVERE: think about these 2 functions
    override fun guardExpression(baseType: ClassId, expression: CgExpression): ExpressionWithType = ExpressionWithType(baseType, expression)

    override fun wrapTypeIfRequired(baseType: ClassId): ClassId = baseType
}