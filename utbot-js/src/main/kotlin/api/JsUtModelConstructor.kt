package api

import fuzzer.providers.JsObjectModelProvider
import org.utbot.framework.concrete.UtModelConstructorInterface
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsEmptyClassId
import org.utbot.framework.plugin.api.JsNullModel
import org.utbot.framework.plugin.api.JsPrimitiveModel
import org.utbot.framework.plugin.api.JsUndefinedModel
import org.utbot.framework.plugin.api.JsUtModel
import org.utbot.framework.plugin.api.UtAssembleModel
import org.utbot.framework.plugin.api.UtExecutableCallModel
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.UtStatementModel
import org.utbot.framework.plugin.api.util.jsUndefinedClassId

class JsUtModelConstructor: UtModelConstructorInterface {

    // TODO SEVERE: This is a very dirty prototype version. Expand!
    @Suppress("NAME_SHADOWING")
    override fun construct(value: Any?, classId: ClassId): UtModel {
        val classId = classId as JsClassId
        when (classId) {
            jsUndefinedClassId -> return JsUndefinedModel(classId)
        }
        return when (value) {
            null -> JsNullModel(classId)
            is Byte,
            is Short,
            is Char,
            is Int,
            is Long,
            is Float,
            is Double,
            is String,
            is Boolean -> JsPrimitiveModel(value)
            is Map<*, *> -> {
                val constructor = classId.allConstructors.first()
                val values = (value as Map<String, Any>).values.map {
                    construct(it, JsEmptyClassId())
                }
                val id = JsObjectModelProvider.idGenerator.asInt
                val instantiationChain = mutableListOf<UtStatementModel>()
                UtAssembleModel(
                    id,
                    constructor.classId,
                    "${constructor.classId.name}${constructor.parameters}#" + id.toString(16),
                    instantiationChain = instantiationChain,
                    modificationsChain = mutableListOf()
                ).apply {
                    instantiationChain += UtExecutableCallModel(null, constructor, values, this)
                }
            }
            else -> JsUndefinedModel(classId)
        }
    }
}