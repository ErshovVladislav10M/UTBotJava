package org.utbot.framework.codegen.model.constructor.tree

import kotlinx.collections.immutable.PersistentList
import org.utbot.framework.codegen.Junit5
import org.utbot.framework.codegen.TestNg
import org.utbot.framework.codegen.model.constructor.builtin.any
import org.utbot.framework.codegen.model.constructor.builtin.anyOfClass
import org.utbot.framework.codegen.model.constructor.builtin.arraysDeepEqualsMethodId
import org.utbot.framework.codegen.model.constructor.builtin.createArrayMethodId
import org.utbot.framework.codegen.model.constructor.builtin.createInstanceMethodId
import org.utbot.framework.codegen.model.constructor.builtin.deepEqualsMethodId
import org.utbot.framework.codegen.model.constructor.builtin.forName
import org.utbot.framework.codegen.model.constructor.builtin.getArrayLengthMethodId
import org.utbot.framework.codegen.model.constructor.builtin.getDeclaredConstructor
import org.utbot.framework.codegen.model.constructor.builtin.getDeclaredMethod
import org.utbot.framework.codegen.model.constructor.builtin.getEnumConstantByNameMethodId
import org.utbot.framework.codegen.model.constructor.builtin.getFieldValueMethodId
import org.utbot.framework.codegen.model.constructor.builtin.getStaticFieldValueMethodId
import org.utbot.framework.codegen.model.constructor.builtin.getTargetException
import org.utbot.framework.codegen.model.constructor.builtin.getUnsafeInstanceMethodId
import org.utbot.framework.codegen.model.constructor.builtin.hasCustomEqualsMethodId
import org.utbot.framework.codegen.model.constructor.builtin.invoke
import org.utbot.framework.codegen.model.constructor.builtin.iterablesDeepEqualsMethodId
import org.utbot.framework.codegen.model.constructor.builtin.mapsDeepEqualsMethodId
import org.utbot.framework.codegen.model.constructor.builtin.newInstance
import org.utbot.framework.codegen.model.constructor.builtin.setAccessible
import org.utbot.framework.codegen.model.constructor.builtin.setFieldMethodId
import org.utbot.framework.codegen.model.constructor.builtin.setStaticFieldMethodId
import org.utbot.framework.codegen.model.constructor.builtin.streamsDeepEqualsMethodId
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.context.CgContextOwner
import org.utbot.framework.codegen.model.constructor.util.CgComponents
import org.utbot.framework.codegen.model.constructor.util.getAmbiguousOverloadsOf
import org.utbot.framework.codegen.model.constructor.util.importIfNeeded
import org.utbot.framework.codegen.model.constructor.util.typeCast
import org.utbot.framework.codegen.model.tree.*
import org.utbot.framework.codegen.model.util.at
import org.utbot.framework.codegen.model.util.isAccessibleFrom
import org.utbot.framework.codegen.model.util.nullLiteral
import org.utbot.framework.codegen.model.util.resolve
import org.utbot.framework.plugin.api.BuiltinMethodId
import org.utbot.framework.plugin.api.ConstructorExecutableId
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.MethodExecutableId
import org.utbot.framework.plugin.api.UtExplicitlyThrownException
import org.utbot.framework.plugin.api.blockingIsSubtypeOf
import org.utbot.framework.plugin.api.isAbstract
import org.utbot.framework.plugin.api.isStatic
import org.utbot.framework.plugin.api.reflection
import org.utbot.framework.plugin.api.util.asExecutable
import org.utbot.framework.plugin.api.util.exceptions
import org.utbot.framework.plugin.api.util.id
import org.utbot.framework.plugin.api.util.isArray
import org.utbot.framework.plugin.api.util.objectArrayClassId
import org.utbot.framework.plugin.api.util.objectClassId
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.MethodId
import org.utbot.jcdb.api.ifArrayGetElementClass
import org.utbot.jcdb.api.isPrimitive
import java.lang.reflect.Constructor
import java.lang.reflect.Method

typealias Block = PersistentList<CgStatement>

class CgIncompleteMethodCall(val method: MethodExecutableId, val caller: CgExpression?)

/**
 * Provides DSL methods for method and field access elements creation
 *
 * Checks the accessibility of methods and fields and replaces
 * direct access with reflective access when needed
 */
interface CgCallableAccessManager {
    operator fun CgExpression?.get(methodId: MethodId): CgIncompleteMethodCall

    operator fun ClassId.get(staticMethodId: MethodId): CgIncompleteMethodCall

    operator fun ConstructorExecutableId.invoke(vararg args: Any?): CgExecutableCall

    operator fun CgIncompleteMethodCall.invoke(vararg args: Any?): CgMethodCall
}

internal class CgCallableAccessManagerImpl(val context: CgContext) : CgCallableAccessManager,
    CgContextOwner by context {

    private val statementConstructor by lazy { CgComponents.getStatementConstructorBy(context) }

    private val variableConstructor by lazy { CgComponents.getVariableConstructorBy(context) }

    override operator fun CgExpression?.get(methodId: MethodId): CgIncompleteMethodCall =
        CgIncompleteMethodCall(methodId.asExecutable() as MethodExecutableId, this)

    override operator fun ClassId.get(staticMethodId: MethodId): CgIncompleteMethodCall =
        CgIncompleteMethodCall(staticMethodId.asExecutable() as MethodExecutableId, null)

    override operator fun ConstructorExecutableId.invoke(vararg args: Any?): CgExecutableCall {
        val resolvedArgs = args.resolve()
        val constructorCall = if (this canBeCalledWith resolvedArgs) {
            CgConstructorCall(this, resolvedArgs.guardedForDirectCallOf(this))
        } else {
            callWithReflection(resolvedArgs)
        }
        newConstructorCall(this)
        return constructorCall
    }

    override operator fun CgIncompleteMethodCall.invoke(vararg args: Any?): CgMethodCall {
        val resolvedArgs = args.resolve()
        val methodCall = if (method.canBeCalledWith(caller, resolvedArgs)) {
            CgMethodCall(caller, method, resolvedArgs.guardedForDirectCallOf(method))
        } else {
            method.callWithReflection(caller, resolvedArgs)
        }
        newMethodCall(method)
        return methodCall
    }

    private fun newMethodCall(methodExecutable: MethodExecutableId) {
        if (methodExecutable.isUtil) requiredUtilMethods += methodExecutable
        importIfNeeded(methodExecutable)

        //Builtin methods does not have jClass, so [methodId.method] will crash on it,
        //so we need to collect required exceptions manually from source codes
        if (methodExecutable.methodId is BuiltinMethodId) {
            methodExecutable.findExceptionTypes().forEach { addExceptionIfNeeded(it) }
            return
        }

        if (methodExecutable.methodId == getTargetException) {
            addExceptionIfNeeded(Throwable::class.id)
        }

        val methodIsUnderTestAndThrowsExplicitly = methodExecutable == currentExecutable
                && currentExecution?.result is UtExplicitlyThrownException
        val frameworkSupportsAssertThrows = testFramework == Junit5 || testFramework == TestNg

        //If explicit exception is wrapped with assertThrows,
        // no "throws" in test method signature is required.
        if (methodIsUnderTestAndThrowsExplicitly && frameworkSupportsAssertThrows) {
            return
        }

        with(reflection) {
            methodExecutable.executable.exceptionTypes.forEach { addExceptionIfNeeded(it.id) }
        }
    }

    private fun newConstructorCall(constructorId: ConstructorExecutableId) {
        importIfNeeded(constructorId.classId)
        for (exception in constructorId.exceptions) {
            addExceptionIfNeeded(exception)
        }
    }

    //WARN: if you make changes in the following sets of exceptions,
    //don't forget to change them in hardcoded [UtilMethods] as well
    private fun MethodExecutableId.findExceptionTypes(): Set<ClassId> {
        if (!isUtil) return emptySet()

        with(outerMostTestClass) {
            return when (this@findExceptionTypes.methodId) {
                getEnumConstantByNameMethodId -> setOf(IllegalAccessException::class.id)
                getStaticFieldValueMethodId,
                getFieldValueMethodId,
                setStaticFieldMethodId,
                setFieldMethodId -> setOf(IllegalAccessException::class.id, NoSuchFieldException::class.id)
                createInstanceMethodId -> setOf(Exception::class.id)
                getUnsafeInstanceMethodId -> setOf(ClassNotFoundException::class.id, NoSuchFieldException::class.id, IllegalAccessException::class.id)
                createArrayMethodId -> setOf(ClassNotFoundException::class.id)
                deepEqualsMethodId,
                arraysDeepEqualsMethodId,
                iterablesDeepEqualsMethodId,
                streamsDeepEqualsMethodId,
                mapsDeepEqualsMethodId,
                hasCustomEqualsMethodId,
                getArrayLengthMethodId -> emptySet()
                else -> error("Unknown util method $this")
            }
        }
    }

    private infix fun CgExpression?.canBeReceiverOf(executable: MethodExecutableId): Boolean =
        when {
            // TODO: rewrite by using CgMethodId, etc.
            outerMostTestClass == executable.classId && this isThisInstanceOf outerMostTestClass -> true
            executable.methodId.isStatic -> true
            else -> this?.type?.classId?.blockingIsSubtypeOf(executable.classId) ?: false
        }

    private infix fun CgExpression.canBeArgOf(type: ClassId): Boolean {
        // TODO: SAT-1210 support generics so that we wouldn't need to check specific cases such as this one
        if (this is CgExecutableCall && (executableId.methodId == any || executableId.methodId == anyOfClass)) {
            return true
        }
        return this == nullLiteral() && type.isAccessibleFrom(testClassPackageName)
                || this.type.classId blockingIsSubtypeOf type
    }

    private infix fun CgExpression?.isThisInstanceOf(classId: ClassId): Boolean =
        this is CgThisInstance && this.type.classId == classId

    /**
     * Check whether @receiver (list of expressions) is a valid list of arguments for [executableId]
     *
     * First, we check all arguments except for the last one.
     * It is done to consider the last argument separately since it can be a vararg,
     * which requires some additional checks.
     *
     * For the last argument there can be several cases:
     * - Last argument is not of array type - then we simply check this argument as all the others
     * - Last argument is of array type:
     *     - Given arguments and parameters have the same size
     *         - Last argument is an array and it matches last parameter array type
     *         - Last argument is a single element of a vararg parameter - then we check
     *           if argument's type matches the vararg element's type
     *     - Given arguments and parameters have different size (last parameter is vararg) - then we
     *       check if all of the given arguments match the vararg element's type
     *
     */
    private infix fun List<CgExpression>.canBeArgsOf(executableId: ExecutableId): Boolean {
        val paramTypes = executableId.parameters

        // no arguments case
        if (paramTypes.isEmpty()) {
            return this.isEmpty()
        }

        val paramTypesExceptLast = paramTypes.dropLast(1)
        val lastParamType = paramTypes.last()

        // considering all arguments except the last one
        for ((arg, paramType) in (this zip paramTypesExceptLast)) {
            if (!(arg canBeArgOf paramType)) return false
        }

        // when the last parameter is not of array type
        if (!lastParamType.isArray) {
            val lastArg = this.last()
            return lastArg canBeArgOf lastParamType
        }

        // when arguments and parameters have equal size
        if (size == paramTypes.size) {
            val lastArg = this.last()
            return when {
                // last argument matches last param type
                lastArg canBeArgOf lastParamType -> true
                // last argument is a single element of a vararg parameter
                lastArg canBeArgOf lastParamType.ifArrayGetElementClass()!! -> true
                else -> false
            }
        }

        // when arguments size is greater than the parameters size
        // meaning that the last parameter is vararg
        return subList(paramTypes.size - 1, size).all {
            it canBeArgOf lastParamType.ifArrayGetElementClass()!!
        }
    }

    /**
     * @return true if a method can be called with the given arguments without reflection
     */
    private fun MethodExecutableId.canBeCalledWith(caller: CgExpression?, args: List<CgExpression>): Boolean =
        (isUtil || methodId.isAccessibleFrom(testClassPackageName))
                && caller canBeReceiverOf this
                && args canBeArgsOf this

    /**
     * @return true if a constructor can be called with the given arguments without reflection
     */
    private infix fun ConstructorExecutableId.canBeCalledWith(args: List<CgExpression>): Boolean =
        methodId.isAccessibleFrom(testClassPackageName) && !classId.isAbstract && args canBeArgsOf this

    private fun List<CgExpression>.guardedForDirectCallOf(executable: ExecutableId): List<CgExpression> {
        val ambiguousOverloads = executable.classId
            .getAmbiguousOverloadsOf(executable)
            .filterNot { it == executable }
            .toList()

        val isEmptyAmbiguousOverloads = ambiguousOverloads.isEmpty()

        return if (isEmptyAmbiguousOverloads) this else castAmbiguousArguments(executable, this, ambiguousOverloads)
    }

    private fun castAmbiguousArguments(
        executable: ExecutableId,
        args: List<CgExpression>,
        ambiguousOverloads: List<ExecutableId>
    ): List<CgExpression> =
        args.withIndex().map { (i ,arg) ->
            val targetType = executable.parameters[i]

            // always cast nulls
            if (arg == nullLiteral()) return@map typeCast(targetType, arg)

            // in case arg type exactly equals target type, do nothing
            if (arg.type.classId == targetType) return@map arg

            // arg type is subtype of target type
            // check other overloads for ambiguous types
            val typesInOverloadings = ambiguousOverloads.map { it.parameters[i] }
            val ancestors = typesInOverloadings.filter { arg.type.classId.blockingIsSubtypeOf(it) }

            if (ancestors.isNotEmpty()) typeCast(targetType, arg) else arg
        }

    private fun ExecutableId.toExecutableVariable(args: List<CgExpression>): CgVariable {
        val classType = type<Class<*>>(isNullable = false)
        val declaringClass = statementConstructor.newVar(classType) { classId[forName](classId.name) }
        val argTypes = (args zip parameters).map { (arg, paramType) ->
            val baseName = when (arg) {
                is CgVariable -> "${arg.name}Type"
                else -> "${paramType.simpleName.decapitalize()}Type"
            }
            statementConstructor.newVar(classType, baseName) {
                if (paramType.isPrimitive) {
                    CgGetJavaClass(paramType)
                } else {
                    Class::class.id[forName](paramType.name)
                }
            }
        }

        return when (this) {
            is MethodExecutableId -> {
                val name = this.name + "Method"
                statementConstructor.newVar(type<Method>(isNullable = false), name) {
                    declaringClass[getDeclaredMethod](this.name, *argTypes.toTypedArray())
                }
            }
            is ConstructorExecutableId -> {
                val name = this.classId.simpleName.decapitalize() + "Constructor"
                statementConstructor.newVar(type<Constructor<*>>(isNullable = false), name) {
                    declaringClass[getDeclaredConstructor](*argTypes.toTypedArray())
                }
            }
        }
    }

    /**
     * Receives a list of [CgExpression].
     * Transforms it into a list of [CgExpression] where:
     * - array and literal values are cast to [java.lang.Object]
     * - other values remain as they were
     *
     * @return a list of [CgExpression] where each expression can be
     * used as an argument of reflective call to a method or constructor
     */
    private fun List<CgExpression>.guardedForReflectiveCall(): List<CgExpression> =
        map {
            when {
                it is CgValue && it.type.classId.isArray -> typeCast(objectClassId, it)
                it == nullLiteral() -> typeCast(objectClassId, it)
                else -> it
            }
        }

    private fun MethodExecutableId.callWithReflection(caller: CgExpression?, args: List<CgExpression>): CgMethodCall {
        containsReflectiveCall = true
        val method = declaredExecutableRefs[this]
            ?: toExecutableVariable(args).also {
                declaredExecutableRefs = declaredExecutableRefs.put(this, it)
                +it[setAccessible](true)
            }

        val arguments = args.guardedForReflectiveCall().toTypedArray()
        val argumentsArrayVariable = convertVarargToArray(method, arguments)

        return method[invoke](caller, CgSpread(argumentsArrayVariable.type, argumentsArrayVariable))
    }

    private fun ConstructorExecutableId.callWithReflection(args: List<CgExpression>): CgExecutableCall {
        containsReflectiveCall = true
        val constructor = declaredExecutableRefs[this]
            ?: this.toExecutableVariable(args).also {
                declaredExecutableRefs = declaredExecutableRefs.put(this, it)
                +it[setAccessible](true)
            }

        val arguments = args.guardedForReflectiveCall().toTypedArray()
        val argumentsArrayVariable = convertVarargToArray(constructor, arguments)

        return constructor[newInstance](argumentsArrayVariable)
    }

    private fun convertVarargToArray(reflectionCallVariable: CgVariable, arguments: Array<CgExpression>): CgVariable {
        val argumentsArrayVariable = variableConstructor.newVar(
            baseType = objectArrayClassId.type(false),
            baseName = "${reflectionCallVariable.name}Arguments"
        ) {
            CgAllocateArray(
                type = CgClassType(objectArrayClassId),
                elementType = objectClassId,
                size = arguments.size
            )
        }

        for ((i, argument) in arguments.withIndex()) {
            +CgAssignment(argumentsArrayVariable.at(i), argument)
        }

        return argumentsArrayVariable
    }
}