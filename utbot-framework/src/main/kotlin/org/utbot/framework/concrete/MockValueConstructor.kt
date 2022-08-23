package org.utbot.framework.concrete

import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.objectweb.asm.Type
import org.utbot.common.invokeCatching
import org.utbot.common.withAccessibility
import org.utbot.framework.plugin.api.ConstructorExecutableId
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.FieldMockTarget
import org.utbot.framework.plugin.api.MethodExecutableId
import org.utbot.framework.plugin.api.MockId
import org.utbot.framework.plugin.api.MockInfo
import org.utbot.framework.plugin.api.MockTarget
import org.utbot.framework.plugin.api.ObjectMockTarget
import org.utbot.framework.plugin.api.ParameterMockTarget
import org.utbot.framework.plugin.api.UtArrayModel
import org.utbot.framework.plugin.api.UtAssembleModel
import org.utbot.framework.plugin.api.UtClassRefModel
import org.utbot.framework.plugin.api.UtCompositeModel
import org.utbot.framework.plugin.api.UtConcreteValue
import org.utbot.framework.plugin.api.UtDirectSetFieldModel
import org.utbot.framework.plugin.api.UtEnumConstantModel
import org.utbot.framework.plugin.api.UtExecutableCallModel
import org.utbot.framework.plugin.api.UtMockValue
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.UtNewInstanceInstrumentation
import org.utbot.framework.plugin.api.UtNullModel
import org.utbot.framework.plugin.api.UtPrimitiveModel
import org.utbot.framework.plugin.api.UtReferenceModel
import org.utbot.framework.plugin.api.UtStaticMethodInstrumentation
import org.utbot.framework.plugin.api.UtVoidModel
import org.utbot.framework.plugin.api.isMockModel
import org.utbot.framework.plugin.api.reflection
import org.utbot.framework.plugin.api.util.executableId
import org.utbot.framework.plugin.api.util.utContext
import org.utbot.framework.util.anyInstance
import org.utbot.jcdb.api.ArrayClassId
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.FieldId
import org.utbot.jcdb.api.ifArrayGetElementClass
import org.utbot.jcdb.api.jvmName
import java.io.Closeable
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import kotlin.reflect.KClass

/**
 * Constructs values (including mocks) from models.
 *
 * Uses model->constructed object reference-equality cache.
 *
 * This class is based on `ValueConstructor.kt`. The main difference is the ability to create mocked objects and mock
 * static methods.
 *
 * Note that `clearState` was deleted!
 */
// TODO: JIRA:1379 -- Refactor ValueConstructor and MockValueConstructor
class MockValueConstructor(
    private val instrumentationContext: InstrumentationContext
) : Closeable {
    private val classLoader: ClassLoader
        get() = utContext.classLoader

    val objectToModelCache: IdentityHashMap<Any, UtModel>
        get() {
            val objectToModel = IdentityHashMap<Any, UtModel>()
            constructedObjects.forEach { (model, obj) ->
                objectToModel[obj] = model
            }
            return objectToModel
        }

    // TODO: JIRA:1379 -- replace UtReferenceModel with Int
    private val constructedObjects = HashMap<UtReferenceModel, Any>()
    private val resultsCache = HashMap<UtReferenceModel, Any>()
    private val mockInfo = mutableListOf<MockInfo>()
    private var mockTarget: MockTarget? = null
    private var mockCounter = 0

    /**
     * Controllers contain info about mocked methods and have to be closed to restore initial state.
     */
    private val controllers = mutableListOf<MockController>()

    /**
     * Sets mock context (possible mock target) before block execution and restores previous one after block execution.
     */
    private inline fun <T> withMockTarget(target: MockTarget?, block: () -> T): T {
        val old = mockTarget
        try {
            mockTarget = target
            return block()
        } finally {
            mockTarget = old
        }
    }

    fun constructMethodParameters(models: List<UtModel>): List<UtConcreteValue<*>> =
        models.mapIndexed { index, model ->
            val target = mockTarget(model) { ParameterMockTarget(model.classId.name, index) }
            construct(model, target)
        }

    fun constructStatics(staticsBefore: Map<FieldId, UtModel>): Map<FieldId, UtConcreteValue<*>> =
        staticsBefore.mapValues { (field, model) -> // TODO: refactor this
            val target = FieldMockTarget(model.classId.name, field.classId.name, owner = null, field.name)
            construct(model, target)
        }

    /**
     * Main construction method.
     *
     * Takes care of nulls. Does not use cache, instead construct(Object/Array/List/FromAssembleModel) method
     * uses cache directly.
     *
     * Takes mock creation context (possible mock target) to create mock if required.
     */
    private fun construct(model: UtModel, target: MockTarget?): UtConcreteValue<*> = with(reflection) {
        withMockTarget(target) {
            when (model) {
                is UtNullModel -> UtConcreteValue(null, model.classId.javaClass)
                is UtPrimitiveModel -> UtConcreteValue(model.value, model.classId.javaClass)
                is UtEnumConstantModel -> UtConcreteValue(constructEnum(model))
                is UtClassRefModel -> UtConcreteValue(model.value)
                is UtCompositeModel -> UtConcreteValue(constructObject(model), model.classId.javaClass)
                is UtArrayModel -> UtConcreteValue(constructArray(model))
                is UtAssembleModel -> UtConcreteValue(constructFromAssembleModel(model), model.classId.javaClass)
                is UtVoidModel -> UtConcreteValue(Unit)
            }
        }
    }

    /**
     * Constructs an Enum<*> instance by model, uses reference-equality cache.
     */
    private fun constructEnum(model: UtEnumConstantModel): Any {
        constructedObjects[model]?.let { return it }
        constructedObjects[model] = model.value
        return model.value
    }

    /**
     * Constructs object by model, uses reference-equality cache.
     *
     * Returns null for mock cause cannot instantiate it.
     */
    private fun constructObject(model: UtCompositeModel): Any {
        constructedObjects[model]?.let { return it }

        this.mockTarget?.let { mockTarget ->
            model.mocks.forEach { (methodId, models) ->
                mockInfo += MockInfo(mockTarget, methodId, models.map { model ->
                    if (model.isMockModel()) {
                        val mockId = MockId("mock${++mockCounter}")
                        // Call to "construct" method still required to collect mock interaction
                        construct(model, ObjectMockTarget(model.classId.name, mockId))
                        UtMockValue(mockId, model.classId.name)
                    } else {
                        construct(model, target = null)
                    }
                })
            }
        }


        val javaClass = javaClass(model.classId)

        val classInstance = if (!model.isMock) {
            val notMockInstance = javaClass.anyInstance

            constructedObjects[model] = notMockInstance
            notMockInstance
        } else {
            val mockInstance = generateMockitoMock(javaClass, model.mocks)

            constructedObjects[model] = mockInstance
            mockInstance
        }

        model.fields.forEach { (fieldId, fieldModel) ->
            val declaredField = with(reflection) { fieldId.javaField }
            val accessible = declaredField.isAccessible
            declaredField.isAccessible = true

            val modifiersField = Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true

            val target = mockTarget(fieldModel) {
                FieldMockTarget(fieldModel.classId.name, model.classId.name, UtConcreteValue(classInstance), fieldId.name)
            }
            val value = construct(fieldModel, target).value
            val instance = if (Modifier.isStatic(declaredField.modifiers)) null else classInstance
            declaredField.set(instance, value)
            declaredField.isAccessible = accessible
        }

        return classInstance
    }

    private fun generateMockitoAnswer(methodToValues: Map<in ExecutableId, List<UtModel>>): Answer<*> {
        val pointers = methodToValues.mapValues { (_, _) -> 0 }.toMutableMap()
        val concreteValues = methodToValues.mapValues { (_, models) ->
            models.map { model ->
                val mockId = MockId("mock${++mockCounter}")
                val target = mockTarget(model) { ObjectMockTarget(model.classId.name, mockId) }
                construct(model, target).value.takeIf { it != Unit } // if it is unit, then null should be returned
                // This model has to be already constructed, so it is OK to pass null as a target
            }
        }
        return Answer { invocation ->
            with(invocation.method) {
                pointers[executableId].let { pointer ->
                    concreteValues[executableId].let { values ->
                        if (pointer != null && values != null && pointer < values.size) {
                            pointers[executableId] = pointer + 1
                            values[pointer]
                        } else {
                            invocation.callRealMethod()
                        }
                    }
                }
            }
        }
    }

    private fun generateMockitoMock(clazz: Class<*>, mocks: Map<ExecutableId, List<UtModel>>): Any {
        return Mockito.mock(clazz, generateMockitoAnswer(mocks))
    }

    private fun computeConcreteValuesForMethods(
        methodToValues: Map<ExecutableId, List<UtModel>>,
    ): Map<ExecutableId, List<Any?>> = methodToValues.mapValues { (_, models) ->
        models.map { mockAndGet(it) }
    }

    /**
     * Mocks methods on [instance] with supplied [methodToValues].
     *
     * Also add new controllers to [controllers]. Each controller corresponds to one method. If it is a static method, then the controller
     * must be closed. If it is a non-static method and you don't change the mocks behaviour on the passed instance,
     * then the controller doesn't have to be closed
     *
     * @param [instance] must be non-`null` for non-static methods.
     * @param [methodToValues] return values for methods.
     */
    private fun mockMethods(
        instance: Any?,
        methodToValues: Map<ExecutableId, List<UtModel>>,
    ) = with(reflection) {
        controllers += computeConcreteValuesForMethods(methodToValues).map { (method, values) ->
            if (method !is MethodExecutableId) {
                throw IllegalArgumentException("Expected MethodId, but got: $method")
            }
            MethodMockController(
                method.classId.javaClass,
                method.method,
                instance,
                values,
                instrumentationContext
            )
        }
    }

    /**
     * Mocks static methods according to instrumentations.
     */
    fun mockStaticMethods(
        instrumentations: List<UtStaticMethodInstrumentation>,
    ) {
        val methodToValues = instrumentations.associate { it.methodId as ExecutableId to it.values }
        mockMethods(null, methodToValues)
    }

    /**
     * Mocks new instances according to instrumentations
     */
    fun mockNewInstances(
        instrumentations: List<UtNewInstanceInstrumentation>,
    ) = with(reflection) {
        controllers += instrumentations.map { mock ->
            InstanceMockController(
                mock.classId,
                mock.instances.map { mockAndGet(it) },
                mock.callSites.map { Type.getType(it.javaClass).internalName }.toSet()
            )
        }
    }

    /**
     * Constructs array by model.
     *
     * Supports arrays of primitive, arrays of arrays and arrays of objects.
     *
     * Note: does not check isNull, but if isNull set returns empty array because for null array length set to 0.
     */
    private fun constructArray(model: UtArrayModel): Any {
        constructedObjects[model]?.let { return it }

        with(model) {
            val elementClassId = classId.ifArrayGetElementClass() ?: error(
                "Provided incorrect UtArrayModel without elementClassId. ClassId: ${model.classId}, model: $model"
            )
            return when (elementClassId.name.jvmName()) {
                "B" -> ByteArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "S" -> ShortArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "C" -> CharArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "I" -> IntArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "J" -> LongArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "F" -> FloatArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "D" -> DoubleArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                "Z" -> BooleanArray(length) { primitive(constModel) }.apply {
                    stores.forEach { (index, model) -> this[index] = primitive(model) }
                }.also { constructedObjects[model] = it }
                else -> {
                    val javaClass = javaClass(elementClassId)
                    val instance = java.lang.reflect.Array.newInstance(javaClass, length) as Array<*>
                    constructedObjects[model] = instance
                    for (i in instance.indices) {
                        val elementModel = stores[i] ?: constModel
                        val value = construct(elementModel, null).value
                        try {
                            java.lang.reflect.Array.set(instance, i, value)
                        } catch (iae:IllegalArgumentException) {
                            throw IllegalArgumentException(
                                iae.message + " array: ${instance.javaClass.name}; value: ${value?.javaClass?.name}" , iae
                            )
                        }
                    }
                    instance
                }
            }
        }
    }

    /**
     * Constructs object with [UtAssembleModel].
     */
    private fun constructFromAssembleModel(assembleModel: UtAssembleModel): Any {
        constructedObjects[assembleModel]?.let { return it }

        assembleModel.allStatementsChain.forEach { statementModel ->
            when (statementModel) {
                is UtExecutableCallModel -> updateWithExecutableCallModel(statementModel, assembleModel)
                is UtDirectSetFieldModel -> updateWithDirectSetFieldModel(statementModel)
            }
        }

        return resultsCache[assembleModel] ?: error("Can't assemble model: $assembleModel")
    }

    /**
     * Updates instance state with [UtExecutableCallModel] invocation.
     */
    private fun updateWithExecutableCallModel(
        callModel: UtExecutableCallModel,
        assembleModel: UtAssembleModel,
    ) {
        val executable = callModel.executable
        val instanceValue = resultsCache[callModel.instance]
        val params = callModel.params.map { value(it) }

        val result = when (executable) {
            is MethodExecutableId -> executable.call(params, instanceValue)
            is ConstructorExecutableId -> executable.call(params)
        }

        // Ignore result if returnId is null. Otherwise add it to instance cache.
        callModel.returnValue?.let {
            checkNotNull(result) { "Tracked instance can't be null for call $executable in model $assembleModel" }
            resultsCache[it] = result

            //If statement is final instantiating, add result to constructed objects cache
            if (callModel == assembleModel.finalInstantiationModel) {
                constructedObjects[assembleModel] = result
            }
        }
    }

    /**
     * Updates instance with [UtDirectSetFieldModel] execution.
     */
    private fun updateWithDirectSetFieldModel(directSetterModel: UtDirectSetFieldModel) {
        val instanceModel = directSetterModel.instance
        val instance = resultsCache[instanceModel] ?: error("Model $instanceModel is not instantiated")

        val instanceClassId = instanceModel.classId
        val fieldModel = directSetterModel.fieldModel

        val field = with(reflection) { directSetterModel.fieldId.javaField }
        val isAccessible = field.isAccessible

        try {
            //set field accessible to support protected or package-private direct setters
            field.isAccessible = true

            //prepare mockTarget for field if it is a mock
            val mockTarget = mockTarget(fieldModel) {
                FieldMockTarget(
                    fieldModel.classId.name,
                    instanceClassId.name,
                    UtConcreteValue(javaClass(instanceClassId).anyInstance),
                    field.name
                )
            }

            //construct and set the value
            val fieldValue = construct(fieldModel, mockTarget).value
            field.set(instance, fieldValue)
        } finally {
            //restore accessibility property of the field
            field.isAccessible = isAccessible
        }
    }

    /**
     * Constructs value from [UtModel].
     */
    private fun value(model: UtModel) = construct(model, null).value

    private fun mockAndGet(model: UtModel): Any? {
        val target = mockTarget(model) { // won't be called if model is not mockModel
            val mockId = MockId("mock${++mockCounter}")
            ObjectMockTarget(model.classId.name, mockId)
        }
        return construct(model, target).value
    }

    private fun MethodExecutableId.call(args: List<Any?>, instance: Any?): Any? = with(reflection) {
        method.run {
            withAccessibility {
                invokeCatching(obj = instance, args = args).getOrThrow()
            }
        }
    }

    private fun ConstructorExecutableId.call(args: List<Any?>): Any? = with(reflection) {
        constructor.run {
            withAccessibility {
                newInstance(*args.toTypedArray())
            }
        }
    }

    /**
     * Fetches primitive value from NutsModel to create array of primitives.
     */
    private inline fun <reified T> primitive(model: UtModel): T = (model as UtPrimitiveModel).value as T

    private fun javaClass(id: ClassId) = kClass(id).java

    private fun kClass(id: ClassId) =
        if (id is ArrayClassId) {
            arrayClassOf(id.elementClass)
        } else {
            when (id.name.jvmName()) {
                "B" -> Byte::class
                "S" -> Short::class
                "C" -> Char::class
                "I" -> Int::class
                "J" -> Long::class
                "F" -> Float::class
                "D" -> Double::class
                "Z" -> Boolean::class
                else -> classLoader.loadClass(id.name).kotlin
            }
        }

    private fun arrayClassOf(elementClassId: ClassId): KClass<*> =
        if (elementClassId is ArrayClassId) {
            val elementClass = arrayClassOf(elementClassId.elementClass)
            java.lang.reflect.Array.newInstance(elementClass.java, 0)::class
        } else {
            when (elementClassId.name.jvmName()) {
                "B" -> ByteArray::class
                "S" -> ShortArray::class
                "C" -> CharArray::class
                "I" -> IntArray::class
                "J" -> LongArray::class
                "F" -> FloatArray::class
                "D" -> DoubleArray::class
                "Z" -> BooleanArray::class
                else -> {
                    val elementClass = classLoader.loadClass(elementClassId.name)
                    java.lang.reflect.Array.newInstance(elementClass, 0)::class
                }
            }
        }

    override fun close() {
        controllers.forEach { it.close() }
    }
}

/**
 * Creates mock target using init lambda if model represents mock or null otherwise.
 */
private fun mockTarget(model: UtModel, init: () -> MockTarget): MockTarget? =
    if (model.isMockModel()) init() else null