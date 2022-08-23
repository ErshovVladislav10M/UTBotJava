package org.utbot.framework.codegen.model.tree

import kotlinx.coroutines.runBlocking
import org.utbot.framework.codegen.Import
import org.utbot.framework.codegen.model.constructor.tree.TestsGenerationReport
import org.utbot.framework.codegen.model.util.CgExceptionHandler
import org.utbot.framework.codegen.model.visitor.CgVisitor
import org.utbot.framework.plugin.api.ConstructorExecutableId
import org.utbot.framework.plugin.api.DocClassLinkStmt
import org.utbot.framework.plugin.api.DocCodeStmt
import org.utbot.framework.plugin.api.DocMethodLinkStmt
import org.utbot.framework.plugin.api.DocPreTagStatement
import org.utbot.framework.plugin.api.DocRegularStmt
import org.utbot.framework.plugin.api.DocStatement
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.MethodExecutableId
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.packageName
import org.utbot.framework.plugin.api.util.booleanClassId
import org.utbot.framework.plugin.api.util.id
import org.utbot.framework.plugin.api.util.intClassId
import org.utbot.framework.plugin.api.util.isNullable
import org.utbot.framework.plugin.api.util.objectArrayClassId
import org.utbot.framework.plugin.api.util.objectClassId
import org.utbot.framework.plugin.api.util.voidClassId
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.FieldId
import org.utbot.jcdb.api.MethodId
import org.utbot.jcdb.api.ifArrayGetElementClass
import org.utbot.jcdb.api.isNullable

interface CgElement {
    // TODO: order of cases is important here due to inheritance between some of the element types
    fun <R> accept(visitor: CgVisitor<R>): R = visitor.run {
        when (val element = this@CgElement) {
            is CgTestClassFile -> visit(element)
            is CgTestClass -> visit(element)
            is CgTestClassBody -> visit(element)
            is CgStaticsRegion -> visit(element)
            is CgSimpleRegion<*> -> visit(element)
            is CgTestMethodCluster -> visit(element)
            is CgExecutableUnderTestCluster -> visit(element)
            is CgUtilMethod -> visit(element)
            is CgTestMethod -> visit(element)
            is CgErrorTestMethod -> visit(element)
            is CgParameterizedTestDataProviderMethod -> visit(element)
            is CgCommentedAnnotation -> visit(element)
            is CgSingleArgAnnotation -> visit(element)
            is CgMultipleArgsAnnotation -> visit(element)
            is CgArrayAnnotationArgument -> visit(element)
            is CgNamedAnnotationArgument -> visit(element)
            is CgSingleLineComment -> visit(element)
            is CgTripleSlashMultilineComment -> visit(element)
            is CgMultilineComment -> visit(element)
            is CgDocumentationComment -> visit(element)
            is CgDocPreTagStatement -> visit(element)
            is CgDocCodeStmt -> visit(element)
            is CgDocRegularStmt -> visit(element)
            is CgDocMethodLinkStmt -> visit(element)
            is CgDocClassLinkStmt -> visit(element)
            is CgAnonymousFunction -> visit(element)
            is CgReturnStatement -> visit(element)
            is CgArrayElementAccess -> visit(element)
            is CgSpread -> visit(element)
            is CgLessThan -> visit(element)
            is CgGreaterThan -> visit(element)
            is CgEqualTo -> visit(element)
            is CgIncrement -> visit(element)
            is CgDecrement -> visit(element)
            is CgTryCatch -> visit(element)
            is CgInnerBlock -> visit(element)
            is CgForLoop -> visit(element)
            is CgWhileLoop -> visit(element)
            is CgDoWhileLoop -> visit(element)
            is CgBreakStatement -> visit(element)
            is CgContinueStatement -> visit(element)
            is CgDeclaration -> visit(element)
            is CgAssignment -> visit(element)
            is CgTypeCast -> visit(element)
            is CgIsInstance -> visit(element)
            is CgThisInstance -> visit(element)
            is CgNotNullAssertion -> visit(element)
            is CgVariable -> visit(element)
            is CgParameterDeclaration -> visit(element)
            is CgLiteral -> visit(element)
            is CgNonStaticRunnable -> visit(element)
            is CgStaticRunnable -> visit(element)
            is CgAllocateInitializedArray -> visit(element)
            is CgArrayInitializer -> visit(element)
            is CgAllocateArray -> visit(element)
            is CgEnumConstantAccess -> visit(element)
            is CgFieldAccess -> visit(element)
            is CgStaticFieldAccess -> visit(element)
            is CgIfStatement -> visit(element)
            is CgSwitchCaseLabel -> visit(element)
            is CgSwitchCase -> visit(element)
            is CgLogicalAnd -> visit(element)
            is CgLogicalOr -> visit(element)
            is CgGetLength -> visit(element)
            is CgGetJavaClass -> visit(element)
            is CgGetKotlinClass -> visit(element)
            is CgStatementExecutableCall -> visit(element)
            is CgConstructorCall -> visit(element)
            is CgMethodCall -> visit(element)
            is CgThrowStatement -> visit(element)
            is CgErrorWrapper -> visit(element)
            is CgEmptyLine -> visit(element)
            else -> throw IllegalArgumentException("Can not visit element of type ${element::class}")
        }
    }
}

// Code entities

data class CgTestClassFile(
    val imports: List<Import>,
    val testClass: CgTestClass,
    val testsGenerationReport: TestsGenerationReport
) : CgElement

data class CgTestClass(
    val id: ClassId,
    val annotations: List<CgAnnotation>,
    val superclass: ClassId?,
    val interfaces: List<ClassId>,
    val body: CgTestClassBody,
    val isStatic: Boolean,
    val isNested: Boolean
) : CgElement {
    val packageName = id.packageName
    val simpleName = id.simpleName
}

data class CgTestClassBody(
    val testMethodRegions: List<CgExecutableUnderTestCluster>,
    val utilsRegion: List<CgRegion<CgElement>>,
    val nestedClassRegions: List<CgRegion<CgTestClass>>
) : CgElement {
    val regions: List<CgRegion<*>>
        get() = testMethodRegions
}

/**
 * A class representing the IntelliJ IDEA's regions.
 * A region is a part of code between the special starting and ending comments.
 *
 * [header] The header of the region.
 */
sealed class CgRegion<out T : CgElement> : CgElement {
    abstract val header: String?
    abstract val content: List<T>
}

open class CgSimpleRegion<T : CgElement>(
    override val header: String?,
    override val content: List<T>
) : CgRegion<T>()

/**
 * Stores data providers for parametrized tests and util methods
 */
class CgStaticsRegion(
    override val header: String?,
    override val content: List<CgElement>
) : CgSimpleRegion<CgElement>(header, content)

data class CgTestMethodCluster(
    override val header: String?,
    val description: CgTripleSlashMultilineComment?,
    override val content: List<CgTestMethod>
) : CgRegion<CgTestMethod>()

/**
 * Stores all clusters (ERROR, successful, timeouts, etc.) for executable under test.
 */
data class CgExecutableUnderTestCluster(
    override val header: String?,
    override val content: List<CgRegion<CgMethod>>
) : CgRegion<CgRegion<CgMethod>>()

/**
 * This class does not inherit from [CgMethod], because it only needs an [id],
 * and it does not need to have info about all the other properties of [CgMethod].
 * This is because util methods are hardcoded. On the rendering stage their text
 * is retrieved by their [MethodId].
 *
 * [id] identifier of the util method.
 */
data class CgUtilMethod(val id: MethodId) : CgElement

// Methods

sealed class CgMethod(open val isStatic: Boolean) : CgElement {
    abstract val name: String
    abstract val returnType: CgClassType
    abstract val parameters: List<CgParameterDeclaration>
    abstract val statements: List<CgStatement>
    abstract val exceptions: Set<ClassId>
    abstract val annotations: List<CgAnnotation>
    abstract val documentation: CgDocumentationComment
    abstract val requiredFields: List<CgParameterDeclaration>
}

class CgTestMethod(
    override val name: String,
    override val returnType: CgClassType,
    override val parameters: List<CgParameterDeclaration>,
    override val statements: List<CgStatement>,
    override val exceptions: Set<ClassId>,
    override val annotations: List<CgAnnotation>,
    val type: CgTestMethodType,
    override val documentation: CgDocumentationComment = CgDocumentationComment(emptyList()),
    override val requiredFields: List<CgParameterDeclaration> = emptyList(),
) : CgMethod(false)

class CgErrorTestMethod(
    override val name: String,
    override val statements: List<CgStatement>,
    override val documentation: CgDocumentationComment = CgDocumentationComment(emptyList())
) : CgMethod(false) {
    override val exceptions: Set<ClassId> = emptySet()
    override val returnType: CgClassType = voidClassId.type(false)
    override val parameters: List<CgParameterDeclaration> = emptyList()
    override val annotations: List<CgAnnotation> = emptyList()
    override val requiredFields: List<CgParameterDeclaration> = emptyList()
}

class CgParameterizedTestDataProviderMethod(
    override val name: String,
    override val statements: List<CgStatement>,
    override val returnType: CgClassType,
    override val annotations: List<CgAnnotation>,
    override val exceptions: Set<ClassId>,
) : CgMethod(isStatic = true) {
    override val parameters: List<CgParameterDeclaration> = emptyList()
    override val documentation: CgDocumentationComment = CgDocumentationComment(emptyList())
    override val requiredFields: List<CgParameterDeclaration> = emptyList()
}

enum class CgTestMethodType(val displayName: String) {
    SUCCESSFUL("Successful tests"),
    FAILING("Failing tests (with exceptions)"),
    TIMEOUT("Failing tests (with timeout)"),
    CRASH("Possibly crashing tests"),
    PARAMETRIZED("Parametrized tests");

    override fun toString(): String = displayName
}

// Annotations

abstract class CgAnnotation : CgElement {
    abstract val classId: ClassId
}

class CgCommentedAnnotation(val annotation: CgAnnotation) : CgAnnotation() {
    override val classId: ClassId = annotation.classId
}

class CgSingleArgAnnotation(
    override val classId: ClassId,
    val argument: CgExpression
) : CgAnnotation()

class CgMultipleArgsAnnotation(
    override val classId: ClassId,
    val arguments: MutableList<CgNamedAnnotationArgument>
) : CgAnnotation()

class CgArrayAnnotationArgument(
    val values: List<CgExpression>
) : CgExpression {
    override val type = CgClassType(objectArrayClassId, isNullable = false) // TODO: is this type correct?
}

class CgNamedAnnotationArgument(
    val name: String,
    val value: CgExpression
) : CgElement

// Statements

interface CgStatement : CgElement

// Comments

sealed class CgComment : CgStatement

class CgSingleLineComment(val comment: String) : CgComment()

/**
 * A comment that consists of multiple lines.
 * The appearance of such comment may vary depending
 * on the [CgAbstractMultilineComment] inheritor being used.
 * Each inheritor is rendered differently.
 */
sealed class CgAbstractMultilineComment : CgComment() {
    abstract val lines: List<String>
}

/**
 * Multiline comment where each line starts with ///
 */
class CgTripleSlashMultilineComment(override val lines: List<String>) : CgAbstractMultilineComment()

/**
 * Classic Java multiline comment starting with &#47;* and ending with *&#47;
 */
class CgMultilineComment(override val lines: List<String>) : CgAbstractMultilineComment() {
    constructor(line: String) : this(listOf(line))
}

//class CgDocumentationComment(val lines: List<String>) : CgComment {
//    constructor(text: String?) : this(text?.split("\n") ?: listOf())
//}
class CgDocumentationComment(val lines: List<CgDocStatement>) : CgComment() {
    constructor(text: String?) : this(text?.split("\n")?.map { CgDocRegularStmt(it) }?.toList() ?: listOf())

    override fun equals(other: Any?): Boolean =
        if (other is CgDocumentationComment) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int = lines.hashCode()
}

sealed class CgDocStatement : CgStatement { //todo is it really CgStatement or maybe something else?
    abstract fun isEmpty(): Boolean
}

sealed class CgDocTagStatement(val content: List<CgDocStatement>) : CgDocStatement() {
    override fun isEmpty(): Boolean = content.all { it.isEmpty() }
}

class CgDocPreTagStatement(content: List<CgDocStatement>) : CgDocTagStatement(content) {
    override fun equals(other: Any?): Boolean =
        if (other is CgDocPreTagStatement) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int = content.hashCode()
}

class CgDocCodeStmt(val stmt: String) : CgDocStatement() {
    override fun isEmpty(): Boolean = stmt.isEmpty()

    override fun equals(other: Any?): Boolean =
        if (other is CgDocCodeStmt) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int = stmt.hashCode()
}

class CgDocRegularStmt(val stmt: String) : CgDocStatement() {
    override fun isEmpty(): Boolean = stmt.isEmpty()

    override fun equals(other: Any?): Boolean =
        if (other is CgDocCodeStmt) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int = stmt.hashCode()
}

open class CgDocClassLinkStmt(val className: String) : CgDocStatement() {
    override fun isEmpty(): Boolean = className.isEmpty()

    override fun equals(other: Any?): Boolean =
        if (other is CgDocClassLinkStmt) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int = className.hashCode()
}

class CgDocMethodLinkStmt(val methodName: String, stmt: String) : CgDocClassLinkStmt(stmt) {
    override fun equals(other: Any?): Boolean =
        if (other is CgDocMethodLinkStmt) this.hashCode() == other.hashCode() else false

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + methodName.hashCode()
        return result
    }
}

fun convertDocToCg(stmt: DocStatement): CgDocStatement {
    return when (stmt) {
        is DocPreTagStatement -> {
            val stmts = stmt.content.map { convertDocToCg(it) }
            CgDocPreTagStatement(content = stmts)
        }
        is DocRegularStmt -> CgDocRegularStmt(stmt = stmt.stmt)
        is DocMethodLinkStmt -> CgDocMethodLinkStmt(methodName = stmt.methodName, stmt = stmt.className)
        is DocClassLinkStmt -> CgDocClassLinkStmt(className = stmt.className)
        is DocCodeStmt -> CgDocCodeStmt(stmt = stmt.stmt)
    }
}

// Anonymous function (lambda)

class CgAnonymousFunction(
    override val type: CgClassType,
    val parameters: List<CgParameterDeclaration>,
    val body: List<CgStatement>
) : CgExpression

// Return statement

class CgReturnStatement(val expression: CgExpression) : CgStatement

// Array element access

// TODO: check nested array element access expressions e.g. a[0][1][2]
// TODO in general it is not CgReferenceExpression because array element can have a primitive type
class CgArrayElementAccess(val array: CgExpression, val index: CgExpression) : CgReferenceExpression {
    override val type: CgClassType =
        CgClassType(array.type.classId.ifArrayGetElementClass() ?: objectClassId, isNullable = true)
}

// Loop conditions
sealed class CgComparison : CgExpression {
    abstract val left: CgExpression
    abstract val right: CgExpression

    override val type: CgClassType = CgClassType(booleanClassId, isNullable = false)
}

class CgLessThan(
    override val left: CgExpression,
    override val right: CgExpression
) : CgComparison()

class CgGreaterThan(
    override val left: CgExpression,
    override val right: CgExpression
) : CgComparison()

class CgEqualTo(
    override val left: CgExpression,
    override val right: CgExpression
) : CgComparison()

// Increment and decrement

class CgIncrement(val variable: CgVariable) : CgStatement

class CgDecrement(val variable: CgVariable) : CgStatement

// Inner block in method (keeps parent method fields visible)

class CgInnerBlock(val statements: List<CgStatement>) : CgStatement

// Try-catch

// for now finally clause is not supported
data class CgTryCatch(
    val statements: List<CgStatement>,
    val handlers: List<CgExceptionHandler>,
    val finally: List<CgStatement>?,
    val resources: List<CgDeclaration>? = null
) : CgStatement

// ?: error("")

data class CgErrorWrapper(
    val message: String,
    val expression: CgExpression,
) : CgExpression {
    override val type: CgClassType
        get() = expression.type
}

// Loops

sealed class CgLoop : CgStatement {
    abstract val condition: CgExpression // TODO: how to represent conditions
    abstract val statements: List<CgStatement>
}

class CgForLoop(
    val initialization: CgDeclaration,
    override val condition: CgExpression,
    val update: CgStatement,
    override val statements: List<CgStatement>
) : CgLoop()

class CgWhileLoop(
    override val condition: CgExpression,
    override val statements: List<CgStatement>
) : CgLoop()

class CgDoWhileLoop(
    override val condition: CgExpression,
    override val statements: List<CgStatement>
) : CgLoop()

/**
 * @property condition represents variable in foreach loop
 * @property iterable represents iterable in foreach loop
 * @property statements represents statements in foreach loop
 */
class CgForEachLoop(
    override val condition: CgExpression,
    override val statements: List<CgStatement>,
    val iterable: CgReferenceExpression,
) : CgLoop()

// Control statements

object CgBreakStatement : CgStatement
object CgContinueStatement : CgStatement

// Variable declaration

class CgDeclaration(
    val variableType: CgClassType,
    val variableName: String,
    val initializer: CgExpression?,
    val isMutable: Boolean = false,
) : CgStatement {
    val variable: CgVariable
        get() = CgVariable(variableName, variableType)
}

// Variable assignment

class CgAssignment(
    val lValue: CgExpression,
    val rValue: CgExpression
) : CgStatement

// Expressions

interface CgExpression : CgStatement {
    val type: CgClassType
    val isNullable get() = type.isNullable
    val typeClassId get() = type.classId
}

// marker interface representing expressions returning reference
// TODO: it seems that not all [CgValue] implementations are reference expressions
interface CgReferenceExpression : CgExpression

/**
 * Type cast model
 *
 * @property isSafetyCast shows if we should use "as?" instead of "as" in Kotlin code
 */
class CgTypeCast(
    val targetType: CgClassType,
    val expression: CgExpression,
    val isSafetyCast: Boolean = false,
) : CgExpression {
    override val type: CgClassType = targetType
}

/**
 * Represents [java.lang.Class.isInstance] method.
 */
class CgIsInstance(
    val classExpression: CgExpression,
    val value: CgExpression,
): CgExpression {
    override val type: CgClassType = booleanClassId.type(isNullable = false)
}

// Value

// TODO in general CgLiteral is not CgReferenceExpression because it can hold primitive values
interface CgValue : CgReferenceExpression

// This instance
class CgThisInstance(type: ClassId) : CgValue {

    override val type: CgClassType = CgClassType(type)

    override val isNullable: Boolean
        get() = false
}

// Variables

open class CgVariable(
    val name: String,
    override val type: CgClassType,
) : CgValue {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CgVariable

        if (name != other.name) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    override fun toString(): String {
        return "${type.classId.simpleName} $name"
    }
}

/**
 * A variable with explicit not null annotation if this is required in language.
 *
 * Note:
 * - in Java it is an equivalent of [CgVariable]
 * - in Kotlin the difference is in addition of "!!" to the name
 */
class CgNotNullAssertion(val expression: CgExpression) : CgValue {
    override val type: CgClassType = CgClassType(expression.type.classId, expression.type.typeParameters, false)

    override val isNullable: Boolean
        get() = false
}

/**
 * Method parameters declaration
 *
 * @property isReferenceType is used for rendering nullable types in Kotlin codegen.
 */
data class CgParameterDeclaration(
    val parameter: CgVariable,
    val isVararg: Boolean = false,
    val isReferenceType: Boolean = false
) : CgElement {
    constructor(name: String, type: ClassId, isReferenceType: Boolean = false) : this(
        CgVariable(name, CgClassType(type)),
        isReferenceType = isReferenceType
    )

    val name: String
        get() = parameter.name

    val type: CgClassType
        get() = parameter.type
}

/**
 * Test method parameter can be one of the following types:
 * - this instance for method under test (MUT)
 * - argument of MUT with a certain index
 * - result expected from MUT with the given arguments
 * - exception expected from MUT with the given arguments
 */
sealed class CgParameterKind {
    object ThisInstance : CgParameterKind()
    data class Argument(val index: Int) : CgParameterKind()
    data class Statics(val model: UtModel) : CgParameterKind()
    object ExpectedResult : CgParameterKind()
    object ExpectedException : CgParameterKind()
}


// Primitive and String literals

class CgLiteral(override val type: CgClassType, val value: Any?) : CgValue {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CgLiteral

        if (type != other.type) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        return result
    }
}

// Runnable like this::toString or (new Object())::toString (non-static) or Random::nextRandomInt (static) etc
abstract class CgRunnable(override val type: CgClassType, val methodId: MethodId) : CgValue

/**
 * [referenceExpression] is "this" in this::toString or (new Object()) in (new Object())::toString (non-static)
 */
class CgNonStaticRunnable(
    type: ClassId,
    val referenceExpression: CgReferenceExpression,
    methodId: MethodId
) : CgRunnable(CgClassType(type, isNullable = false), methodId)

/**
 * [classId] is Random is Random::nextRandomInt (static) etc
 */
class CgStaticRunnable(type: ClassId, val classId: ClassId, methodId: MethodId) :
    CgRunnable(CgClassType(type, isNullable = false), methodId)

// Array allocation

open class CgAllocateArray(override val type: CgClassType, val elementType: ClassId, val size: Int) :
    CgReferenceExpression

/**
 * Allocation of an array with initialization. For example: `new String[] {"a", "b", null}`.
 */
class CgAllocateInitializedArray(val initializer: CgArrayInitializer) :
    CgAllocateArray(CgClassType(initializer.arrayType, isNullable = false), initializer.elementType, initializer.size)

class CgArrayInitializer(val arrayType: ClassId, val elementType: ClassId, val values: List<CgExpression>) :
    CgExpression {
    val size: Int
        get() = values.size

    override val type: CgClassType
        get() = CgClassType(arrayType, isNullable = false)
}


// Spread operator (for Kotlin, empty for Java)

class CgSpread(override val type: CgClassType, val array: CgExpression) : CgExpression

// Enum constant

data class CgEnumConstantAccess(
    val enumClass: ClassId,
    val name: String
) : CgReferenceExpression {
    override val type: CgClassType = CgClassType(enumClass, isNullable = false)
}

// Property access

// TODO in general it is not CgReferenceExpression because field can have a primitive type
abstract class CgAbstractFieldAccess : CgReferenceExpression {
    abstract val fieldId: FieldId

    override val type: CgClassType by lazy {
        runBlocking {
            CgClassType(fieldId.type(), isNullable = fieldId.isNullable())
        }
    }

}

class CgFieldAccess(
    val caller: CgExpression,
    override val fieldId: FieldId
) : CgAbstractFieldAccess()

class CgStaticFieldAccess(
    override val fieldId: FieldId
) : CgAbstractFieldAccess() {
    val declaringClass: ClassId = fieldId.classId
    val fieldName: String = fieldId.name
}

// Conditional statements

class CgIfStatement(
    val condition: CgExpression,
    val trueBranch: List<CgStatement>,
    val falseBranch: List<CgStatement>? = null // false branch may be absent
) : CgStatement

data class CgSwitchCaseLabel(
    val label: CgLiteral? = null, // have to be compile time constant (null for default label)
    val statements: MutableList<CgStatement>
) : CgStatement

data class CgSwitchCase(
    val value: CgExpression, // TODO required: 'char, byte, short, int, Character, Byte, Short, Integer, String, or an enum'
    val labels: List<CgSwitchCaseLabel>,
    val defaultLabel: CgSwitchCaseLabel? = null
) : CgStatement

// Binary logical operators

class CgLogicalAnd(
    val left: CgExpression,
    val right: CgExpression
) : CgExpression {
    override val type: CgClassType = CgClassType(booleanClassId, isNullable = false)
}

class CgLogicalOr(
    val left: CgExpression,
    val right: CgExpression
) : CgExpression {
    override val type: CgClassType = CgClassType(booleanClassId, isNullable = false)
}

// Acquisition of array length, e.g. args.length

/**
 * @param variable represents an array variable
 */
class CgGetLength(val variable: CgVariable) : CgExpression {
    override val type: CgClassType = CgClassType(intClassId, isNullable = false)

}

// Acquisition of java or kotlin class, e.g. MyClass.class in Java, MyClass::class.java in Kotlin or MyClass::class for Kotlin classes

sealed class CgGetClass(val classId: ClassId) : CgReferenceExpression {
    override val type: CgClassType = CgClassType(Class::class.id, isNullable = false)
    override val isNullable: Boolean
        get() = false

}

class CgGetJavaClass(classId: ClassId) : CgGetClass(classId)

class CgGetKotlinClass(classId: ClassId) : CgGetClass(classId)

// Executable calls

class CgStatementExecutableCall(val call: CgExecutableCall) : CgStatement

// TODO in general it is not CgReferenceExpression because returned value can have a primitive type
//  (or no value can be returned)
abstract class CgExecutableCall : CgReferenceExpression {
    abstract val executableId: ExecutableId
    abstract val arguments: List<CgExpression>
    abstract val typeParameters: TypeParameters

    override val isNullable: Boolean get() = executableId.methodId.isNullable

}

class CgConstructorCall(
    override val executableId: ConstructorExecutableId,
    override val arguments: List<CgExpression>,
    override val typeParameters: TypeParameters = TypeParameters()
) : CgExecutableCall() {
    override val type = CgClassType(executableId.classId, isNullable = false)
    override val isNullable: Boolean
        get() = false
}

class CgMethodCall(
    val caller: CgExpression?,
    override val executableId: MethodExecutableId,
    override val arguments: List<CgExpression>,
    override val typeParameters: TypeParameters = TypeParameters()
) : CgExecutableCall() {
    override val type = CgClassType(executableId.returnType, isNullable = executableId.methodId.isNullable)
}

fun CgExecutableCall.toStatement(): CgStatementExecutableCall = CgStatementExecutableCall(this)

// Throw statement

class CgThrowStatement(
    val exception: CgExpression
) : CgStatement

// Empty line

class CgEmptyLine : CgStatement

open class TypeParameters(val parameters: List<CgClassType> = emptyList()) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypeParameters

        if (parameters != other.parameters) return false

        return true
    }

    override fun hashCode(): Int {
        return parameters.hashCode()
    }
}

object WildcardTypeParameter : TypeParameters(emptyList())


class CgClassType(
    val classId: ClassId,
    val typeParameters: TypeParameters = TypeParameters(),
    val isNullable: Boolean = true
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CgClassType

        if (classId != other.classId) return false
        if (typeParameters != other.typeParameters) return false
        if (isNullable != other.isNullable) return false

        return true
    }

    override fun hashCode(): Int {
        var result = classId.hashCode()
        result = 31 * result + typeParameters.hashCode()
        result = 31 * result + isNullable.hashCode()
        return result
    }
}


fun ClassId.type(isNullable: Boolean = true, parameters: TypeParameters = TypeParameters()) =
    CgClassType(this, parameters, isNullable)

inline fun <reified T> type(isNullable: Boolean = true, parameters: TypeParameters = TypeParameters()) =
    CgClassType(T::class.java.id, parameters, isNullable)

fun MethodId.type() = runBlocking {
    CgClassType(returnType(), isNullable = isNullable())
}
