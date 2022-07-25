package org.utbot.framework.codegen.model.constructor.util

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.coroutines.runBlocking
import org.utbot.framework.codegen.RegularImport
import org.utbot.framework.codegen.StaticImport
import org.utbot.framework.codegen.model.constructor.builtin.setArrayElement
import org.utbot.framework.codegen.model.constructor.context.CgContextOwner
import org.utbot.framework.codegen.model.constructor.tree.CgCallableAccessManager
import org.utbot.framework.codegen.model.tree.CgAllocateInitializedArray
import org.utbot.framework.codegen.model.tree.CgArrayInitializer
import org.utbot.framework.codegen.model.tree.CgClassType
import org.utbot.framework.codegen.model.tree.CgExpression
import org.utbot.framework.codegen.model.tree.CgGetClass
import org.utbot.framework.codegen.model.tree.CgGetJavaClass
import org.utbot.framework.codegen.model.tree.CgTypeCast
import org.utbot.framework.codegen.model.tree.CgValue
import org.utbot.framework.codegen.model.tree.CgVariable
import org.utbot.framework.codegen.model.util.at
import org.utbot.framework.codegen.model.util.isAccessibleFrom
import org.utbot.framework.fields.ArrayElementAccess
import org.utbot.framework.fields.FieldAccess
import org.utbot.framework.fields.FieldPath
import org.utbot.framework.plugin.api.BuiltinClassId
import org.utbot.framework.plugin.api.BuiltinMethodId
import org.utbot.framework.plugin.api.ConstructorExecutableId
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.MethodExecutableId
import org.utbot.framework.plugin.api.UtArrayModel
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.UtNullModel
import org.utbot.framework.plugin.api.UtPrimitiveModel
import org.utbot.framework.plugin.api.builtInClass
import org.utbot.framework.plugin.api.isAnonymous
import org.utbot.framework.plugin.api.isInDefaultPackage
import org.utbot.framework.plugin.api.isNested
import org.utbot.framework.plugin.api.isStatic
import org.utbot.framework.plugin.api.packageName
import org.utbot.framework.plugin.api.parameters
import org.utbot.framework.plugin.api.util.asExecutable
import org.utbot.framework.plugin.api.util.booleanClassId
import org.utbot.framework.plugin.api.util.byteClassId
import org.utbot.framework.plugin.api.util.charClassId
import org.utbot.framework.plugin.api.util.doubleClassId
import org.utbot.framework.plugin.api.util.enclosingClass
import org.utbot.framework.plugin.api.util.findClass
import org.utbot.framework.plugin.api.util.findMethod
import org.utbot.framework.plugin.api.util.floatClassId
import org.utbot.framework.plugin.api.util.id
import org.utbot.framework.plugin.api.util.intClassId
import org.utbot.framework.plugin.api.util.isRefType
import org.utbot.framework.plugin.api.util.longClassId
import org.utbot.framework.plugin.api.util.newBuiltinStaticMethodId
import org.utbot.framework.plugin.api.util.objectArrayClassId
import org.utbot.framework.plugin.api.util.objectClassId
import org.utbot.framework.plugin.api.util.shortClassId
import org.utbot.framework.plugin.api.util.underlyingType
import org.utbot.framework.plugin.api.util.utContext
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.MethodId
import org.utbot.jcdb.api.allConstructors
import org.utbot.jcdb.api.ext.findClass
import org.utbot.jcdb.api.isConstructor
import org.utbot.jcdb.api.isSubtypeOf

internal data class EnvironmentFieldStateCache(
    val thisInstance: FieldStateCache,
    val arguments: Array<FieldStateCache>,
    val classesWithStaticFields: MutableMap<ClassId, FieldStateCache>
) {
    companion object {
        fun emptyCacheFor(execution: UtExecution): EnvironmentFieldStateCache {
            val argumentsCache = Array(execution.stateBefore.parameters.size) { FieldStateCache() }

            val staticFields = execution.stateBefore.statics.keys
            val classesWithStaticFields = staticFields.groupBy { it.classId }.keys
            val staticFieldsCache = mutableMapOf<ClassId, FieldStateCache>().apply {
                for (classId in classesWithStaticFields) {
                    put(classId, FieldStateCache())
                }
            }

            return EnvironmentFieldStateCache(
                thisInstance = FieldStateCache(),
                arguments = argumentsCache,
                classesWithStaticFields = staticFieldsCache
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnvironmentFieldStateCache

        if (thisInstance != other.thisInstance) return false
        if (!arguments.contentEquals(other.arguments)) return false
        if (classesWithStaticFields != other.classesWithStaticFields) return false

        return true
    }

    override fun hashCode(): Int {
        var result = thisInstance.hashCode()
        result = 31 * result + arguments.contentHashCode()
        result = 31 * result + classesWithStaticFields.hashCode()
        return result
    }
}

internal class FieldStateCache {
    val before: MutableMap<FieldPath, CgFieldState> = mutableMapOf()
    val after: MutableMap<FieldPath, CgFieldState> = mutableMapOf()

    val paths: List<FieldPath>
        get() = (before.keys union after.keys).toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FieldStateCache

        if (before != other.before) return false
        if (after != other.after) return false

        return true
    }

    override fun hashCode(): Int {
        var result = before.hashCode()
        result = 31 * result + after.hashCode()
        return result
    }
}

internal data class CgFieldState(val variable: CgVariable, val model: UtModel)

data class ExpressionWithType(val type: CgClassType, val expression: CgExpression)

/**
 * A [MethodId] to add an item into [ArrayList].
 */
internal val addToListMethodId: MethodId
    get() = runBlocking {
        findClass<ArrayList<Any>>()
            .findMethod("add", returnType = booleanClassId, listOf(objectClassId))
    }

/**
 * A [ClassId] of class `org.junit.jupiter.params.provider.Arguments`
 */
internal val argumentsClassId: BuiltinClassId
    get() = builtInClass("org.junit.jupiter.params.provider.Arguments")

/**
 * A [MethodId] to call JUnit Arguments method.
 */
internal val argumentsMethodId: BuiltinMethodId
    get() = argumentsClassId.newBuiltinStaticMethodId("arguments",
        argumentsClassId,
        // vararg of Objects
        listOf(objectArrayClassId)
    )

internal fun getStaticFieldVariableName(owner: ClassId, path: FieldPath): String {
    val elements = mutableListOf<String>()
    elements += owner.simpleName.decapitalize()
    // TODO: check how capitalize() works with numeric strings e.g. "0"
    elements += path.toStringList().map { it.capitalize() }
    return elements.joinToString("")
}

internal fun getFieldVariableName(owner: CgValue, path: FieldPath): String {
    val elements = mutableListOf<String>()
    if (owner is CgVariable) {
        elements += owner.name
    }
    // TODO: check how capitalize() works with numeric strings e.g. "0"
    elements += path.toStringList().map { it.capitalize() }
    if (elements.size > 0) {
        elements[0] = elements[0].decapitalize()
    }
    return elements.joinToString("")
}

private fun FieldPath.toStringList(): List<String> =
    elements.map {
        when (it) {
            is FieldAccess -> it.field.name
            is ArrayElementAccess -> it.index.toString()
        }
    }

internal fun infiniteInts(): Sequence<Int> =
    generateSequence(1) { it + 1 }

internal const val MAX_ARRAY_INITIALIZER_SIZE = 10

/**
 * Checks if we have already imported a class with such simple name.
 * If so, we cannot import [type] (because it will be used with simple name and will be clashed with already imported)
 * and should use its fully qualified name instead.
 */
private fun CgContextOwner.doesNotHaveSimpleNameClash(type: ClassId): Boolean =
    importedClasses.none { it.simpleName == type.simpleName }

internal fun CgContextOwner.importIfNeeded(type: CgClassType) = importIfNeeded(type.classId)

internal fun CgContextOwner.importIfNeeded(type: ClassId) {
    // TODO: for now we consider that tests are generated in the same package as CUT, but this may change
    val underlyingType = type.underlyingType

    underlyingType
        .takeIf { (it.isRefType && it.packageName != testClassPackageName && it.packageName != "java.lang") || it.isNested }
        // we cannot import inaccessible classes (builtin classes like JUnit are accessible here because they are public)
        ?.takeIf { it.isAccessibleFrom(testClassPackageName) }
        // don't import classes from default package
        ?.takeIf { !it.isInDefaultPackage }
        // cannot import anonymous classes
        ?.takeIf { !it.isAnonymous }
        // do not import if there is a simple name clash
        ?.takeIf { doesNotHaveSimpleNameClash(it) }
        ?.let {
            importedClasses += it
            collectedImports += it.toImport()
        }

    // for nested classes we need to import enclosing class
    if (underlyingType.isNested) {
        importIfNeeded(underlyingType.enclosingClass!!)
    }
}

internal fun CgContextOwner.importIfNeeded(method: MethodExecutableId) {
    val name = method.name
    val packageName = method.classId.packageName
    method.takeIf { it.methodId.isStatic && packageName != testClassPackageName && packageName != "java.lang" }
        .takeIf { importedStaticMethods.none { it.name == name } }
        // do not import method under test in order to specify the declaring class directly for its calls
        .takeIf { currentExecutable != method }
        ?.let {
            importedStaticMethods += method
            collectedImports += StaticImport(method.classId.name, method.name)
        }
}

/**
 * Casts [expression] to [targetType].
 *
 * @param isSafetyCast shows if we should render "as?" instead of "as" in Kotlin
 */
internal fun CgContextOwner.typeCast(
    targetType: ClassId,
    expression: CgExpression,
    isSafetyCast: Boolean = false
) = typeCast(CgClassType(targetType), expression, isSafetyCast)

internal fun CgContextOwner.typeCast(
    targetType: CgClassType,
    expression: CgExpression,
    isSafetyCast: Boolean = false
): CgTypeCast {
    if (targetType.classId.simpleName.isEmpty()) {
        error("Cannot cast an expression to the anonymous type $targetType")
    }
    importIfNeeded(targetType)
    return CgTypeCast(targetType, expression, isSafetyCast)
}

/**
 * Sets an element of arguments array in parameterized test,
 * if test framework represents arguments as array.
 */
internal fun <T> T.setArgumentsArrayElement(
    array: CgVariable,
    index: Int,
    value: CgExpression,
    constructor: CgStatementConstructor
) where T : CgContextOwner, T: CgCallableAccessManager {
    when (array.type.classId) {
        objectClassId -> {
            +java.lang.reflect.Array::class.id[setArrayElement](array, index, value)
        }
        else -> with(constructor) { array.at(index) `=` value }
    }
}

@Suppress("unused")
internal fun newArrayOf(elementType: ClassId, values: List<CgExpression>): CgAllocateInitializedArray {
    val arrayType = arrayTypeOf(elementType)
    return CgAllocateInitializedArray(arrayInitializer(arrayType, elementType, values))
}

internal fun arrayInitializer(arrayType: ClassId, elementType: ClassId, values: List<CgExpression>): CgArrayInitializer =
    CgArrayInitializer(arrayType, elementType, values)


/**
 * For a given [elementType] returns a [ClassId] of an array with elements of this type.
 * For example, for an id of `int` the result will be an id of `int[]`.
 *
 * @param elementType the element type of the returned array class id
 * @param isNullable a flag whether returned array is nullable or not
 */
internal fun arrayTypeOf(elementType: ClassId, isNullable: Boolean = false): ClassId = runBlocking {
    utContext.classpath.findClass(elementType.name + "[]")
}

@Suppress("unused")
internal fun CgContextOwner.getJavaClass(classId: ClassId): CgGetClass {
    importIfNeeded(CgClassType(classId))
    return CgGetJavaClass(classId)
}

internal suspend fun ClassId.overridesEquals(): Boolean =
    when {
        // Object does not override equals
        name == Any::class.java.name -> false
        this isSubtypeOf Map::class.id -> true
        this isSubtypeOf Collection::class.id -> true
        else -> methods().any {
            it.name == "equals" && it.parameters().let {
                it.size == 1 && it.first() == objectClassId
            }
        }
    }

// NOTE: this function does not consider executable return type because it is not important in our case
internal fun ClassId.getAmbiguousOverloadsOf(executableId: ExecutableId): Sequence<ExecutableId> = runBlocking {
    val methodsOrConstructors = when (executableId) {
        is MethodExecutableId -> methods().filter { !it.isConstructor }
        is ConstructorExecutableId -> allConstructors()
    }

    methodsOrConstructors.filter {
        it.name == executableId.name && it.parameters.size == executableId.parameters.size && it.classId == executableId.classId
    }.map { it.asExecutable() }.asSequence()
}


internal infix fun ClassId.hasAmbiguousOverloadsOf(executableId: ExecutableId): Boolean {
    // TODO: for now we assume that builtin classes do not have ambiguous overloads
    if (this is BuiltinClassId) return false

    return getAmbiguousOverloadsOf(executableId).toList().size > 1
}

private val defaultByPrimitiveType: Map<ClassId, Any> = mapOf(
    booleanClassId to false,
    byteClassId to 0.toByte(),
    charClassId to '\u0000',
    shortClassId to 0.toShort(),
    intClassId to 0,
    longClassId to 0L,
    floatClassId to 0.0f,
    doubleClassId to 0.0
)

/**
 * By 'default' here we mean a value that is used for a type in one of the two cases:
 * - When we allocate an array of some type in the following manner: `new int[10]`,
 * the array is filled with some value. In case of `int` this value is `0`, for `boolean`
 * it is `false`, etc.
 * - When we allocate an instance of some reference type e.g. `new A()` and the class `A` has field `int a`.
 * If the constructor we use does not initialize `a` directly and `a` is not assigned to some value
 * at the declaration site, then `a` will be assigned to some `default` value, e.g. `0` for `int`.
 *
 * Here we do not consider default values of nested arrays of multidimensional arrays,
 * because they depend on the way the outer array is allocated:
 * - An array allocated using `new int[10][]` will contain 10 `null` elements.
 * - An array allocated using `new int[10][5]` will contain 10 arrays of 5 elements,
 *   where each element is `0`.
 */
internal infix fun UtModel.isDefaultValueOf(type: ClassId): Boolean =
    when (this) {
        is UtNullModel -> type.isRefType // null is default for ref types
        is UtPrimitiveModel -> value == defaultByPrimitiveType[type]
        else -> false
    }

internal infix fun UtModel.isNotDefaultValueOf(type: ClassId): Boolean = !isDefaultValueOf(type)

/**
 * If the model contains a store for the given [index], return the model of this store.
 * Otherwise, return a [UtArrayModel.constModel] of this array model.
 */
internal operator fun UtArrayModel.get(index: Int): UtModel = stores[index] ?: constModel


fun ClassId.toImport(): RegularImport = runBlocking {
    val outerClass = outerClass()
    val name = when {
        outerClass != null -> outerClass.simpleName + "." + simpleName
        else -> simpleName
    }
    RegularImport(packageName, name)
}

// Immutable collections utils

internal operator fun <T> PersistentList<T>.plus(element: T): PersistentList<T> =
    this.add(element)

internal operator fun <T> PersistentList<T>.plus(other: PersistentList<T>): PersistentList<T> =
    this.addAll(other)

internal operator fun <T> PersistentSet<T>.plus(element: T): PersistentSet<T> =
    this.add(element)

internal operator fun <T> PersistentSet<T>.plus(other: PersistentSet<T>): PersistentSet<T> =
    this.addAll(other)
