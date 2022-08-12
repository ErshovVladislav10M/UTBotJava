package org.utbot.framework.plugin.api

import org.utbot.common.WorkaroundReason
import org.utbot.common.heuristic
import org.utbot.common.unreachableBranch
import org.utbot.common.withAccessibility
import org.utbot.framework.plugin.api.util.executable
import org.utbot.framework.plugin.api.util.id
import org.utbot.framework.plugin.api.util.isSubtypeOf
import org.utbot.framework.plugin.api.util.kClass
import org.utbot.framework.plugin.api.util.objectClassId
import sun.reflect.generics.parser.SignatureParser
import sun.reflect.generics.tree.ArrayTypeSignature
import sun.reflect.generics.tree.ClassTypeSignature
import sun.reflect.generics.tree.SimpleClassTypeSignature
import sun.reflect.generics.tree.TypeArgument
import sun.reflect.generics.tree.TypeTree
import sun.reflect.generics.tree.TypeVariableSignature
import java.lang.reflect.Constructor
import java.lang.reflect.GenericSignatureFormatError
import java.lang.reflect.Method
import kotlin.reflect.KCallable
import kotlin.reflect.KType
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.kotlinFunction


fun processGenerics(result: UtResult, callable: KCallable<*>): UtResult {
    return when (result) {
        is UtExecution -> {
            processGenericsForEnvironmentModels(result.stateBefore, callable)
            processGenericsForEnvironmentModels(result.stateAfter, callable)

            (result.result as? UtExecutionSuccess)?.model?.let { model ->
                processGenericsForModel(model, callable.returnType)
            }

            result
        }
        else -> result
    }
}

fun processGenericsForEnvironmentModels(models: EnvironmentModels, callable: KCallable<*>) {
    if (models is MissingState) return

    // If MUT is static, no instance parameter is used
    val paramOffset = if (callable.instanceParameter != null) 1 else 0

    require(models.parameters.size + paramOffset == callable.parameters.size)
    for (i in models.parameters.indices) {
        processGenericsForModel(models.parameters[i], (callable.parameters[i + paramOffset].type))
    }

    // set Any? for all statics
    for ((field, model) in models.statics) {
        fillGenericsAsObjectsForModel(model)

        field.fixedType.typeParameters.parameters =
            List(field.fixedType.kClass.typeParameters.size) { WildcardTypeParameter }
    }
}

fun processGenericsForModel(model: UtModel, type: KType) {
    when (model) {
        is UtAssembleModel -> model.processGenerics(type)
        is UtCompositeModel -> model.processGenerics(type)
        else -> return
    }
}

fun fillGenericsAsObjectsForModel(model: UtModel) {
    when (model) {
        is UtAssembleModel -> model.fillGenericsAsObjects()
        else -> return
    }
}

fun UtAssembleModel.processGenerics(type: KType) {
    classId.typeParameters.fromType(type)

    // TODO might cause problems with type params when program synthesis comes
    // assume that last statement is constructor call
    instantiationChain.lastOrNull()?.let inst@ { lastStatement ->
        (lastStatement as? UtExecutableCallModel)?.let { executableModel ->
            when (val executable = executableModel.executable) {
                is ConstructorId -> executable.typeParameters.copyFromClassId(classId)
                is MethodId -> executable.typeParameters.copyFromClassId(classId)
            }

            try {
                val function = when (val executable = executableModel.executable.executable) {
                    is Constructor<*> -> executable.kotlinFunction
                    is Method -> executable.kotlinFunction
                    else -> unreachableBranch("this executable does not exist $executable")
                }

                executableModel.params.mapIndexed { i, param ->
                    function?.parameters?.getOrNull(i)?.type?.let { it -> processGenericsForModel(param, it) }
                }
            } catch (e: Error) {
                // KotlinReflectionInternalError can't be imported, but it is assumed here
                // it can be thrown here because, i.e., Int(Int) constructor does not exist in Kotlin
            }

            heuristic(WorkaroundReason.COLLECTION_CONSTRUCTOR_FROM_COLLECTION) {
                val propagateFromReturnTypeToParameter = { id: Int ->
                    ((executableModel.params[id] as? UtAssembleModel)?.instantiationChain?.get(0) as? UtExecutableCallModel)
                        ?.executable?.typeParameters?.copyFromClassId(classId)
                }

                when (val executable = executableModel.executable.executable) {
                    is Constructor<*> -> {
                        // Can't parse signature here, since constructors return void
                        // This part only works for cases like Collection<T>(collection: Collection<T>)
                        if (executableModel.executable is ConstructorId) {
                            if (executableModel.executable.classId.isSubtypeOf(Collection::class.id)) {
                                if (executableModel.executable.parameters.size == 1 &&
                                    executableModel.executable.parameters[0].isSubtypeOf(Collection::class.id)) {
                                    propagateFromReturnTypeToParameter(0)
                                }
                            }
                        }
                    }
                    is Method -> {
                        try {
                            val f = Method::class.java.getDeclaredField("signature")
                            val signature = f.withAccessibility {
                                f.get(executable) as? String ?: return@inst
                            }
                            val parsedSignature = SignatureParser.make().parseMethodSig(signature)

                            // check if parameter types are equal to return types
                            // e.g. <T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)Ljava/util/List<TT;>;
                            parsedSignature.parameterTypes.forEachIndexed { paramId, param ->
                                parsedSignature as? TypeArgument ?: error("Only TypeArgument is expected")
                                if (param.cmp(parsedSignature)) {
                                    propagateFromReturnTypeToParameter(paramId)
                                }
                            }
                        } catch (e: GenericSignatureFormatError) {
                            // TODO log
                        }
                    }
                    else -> unreachableBranch("this executable does not exist $executable")
                }
            }
        }
    }

    for (model in modificationsChain) {
        if (model is UtExecutableCallModel) {
            model.params.mapIndexed { i, param ->
                heuristic(WorkaroundReason.MODIFICATION_CHAIN_GENERICS_FROM_CLASS) {
                    type.arguments.getOrNull(i)?.type?.let { it -> processGenericsForModel(param, it) }
                }
            }
        }
    }
}

fun UtAssembleModel.fillGenericsAsObjects() {
    // TODO might cause problems with type params when program synthesis comes
    // assume that last statement is constructor call
    instantiationChain.lastOrNull()?.let { lastStatement ->
        (lastStatement as? UtExecutableCallModel)?.let {
            try {
                val function = when (val executable = it.executable.executable) {
                    is Constructor<*> -> executable.kotlinFunction
                    is Method -> executable.kotlinFunction
                    else -> unreachableBranch("this executable does not exist $executable")
                }
                function?.let { f ->
                    classId.typeParameters.parameters = List(f.typeParameters.size) { objectClassId }
                }

                it.params.map { param -> fillGenericsAsObjectsForModel(param) }
            } catch (e: Error) {
                // KotlinReflectionInternalError can't be imported, but it is assumed here
                // it can be thrown here because, i.e., Int(Int) constructor does not exist in Kotlin
            }
        }
    }

    for (model in modificationsChain) {
        if (model is UtExecutableCallModel) {
            val function = when (val executable = model.executable.executable) {
                is Constructor<*> -> executable.kotlinFunction
                is Method -> executable.kotlinFunction
                else -> unreachableBranch("this executable does not exist $executable")
            }
            function?.let { f ->
                model.executable.classId.typeParameters.parameters = List(f.typeParameters.size) { objectClassId }
            }

            model.params.map { fillGenericsAsObjectsForModel(it) }
        }
    }
}

fun UtCompositeModel.processGenerics(type: KType) {
    classId.typeParameters.fromType(type)

    // TODO propagate generics into fields and mocks if required
}

private fun TypeTree.cmp(other: TypeTree): Boolean {
    if (this::class != other::class) return false

    when (this) {
        is TypeVariableSignature -> return identifier == (other as TypeVariableSignature).identifier
        is ClassTypeSignature -> {
            val otherPath = (other as ClassTypeSignature).path
            return path.foldIndexed(true) { i, prev, it ->
                prev && (otherPath.getOrNull(i)?.cmp(it) ?: false)
            }
        }
        is SimpleClassTypeSignature -> {
            val otherTypeArgs = (other as SimpleClassTypeSignature).typeArguments
            return typeArguments.foldIndexed(true) { i, prev, it ->
                prev && (otherTypeArgs.getOrNull(i)?.cmp(it) ?: false)
            }
        }
        is ArrayTypeSignature -> return componentType.cmp((other as ArrayTypeSignature).componentType)
        // other cases are trivial and handled by class comparison
        else -> return true
    }
}