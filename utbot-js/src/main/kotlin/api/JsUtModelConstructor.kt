package api

import org.utbot.framework.concrete.UtModelConstructorInterface
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsNullModel
import org.utbot.framework.plugin.api.JsPrimitiveModel
import org.utbot.framework.plugin.api.JsUndefinedModel
import org.utbot.framework.plugin.api.JsUtModel
import org.utbot.framework.plugin.api.util.jsUndefinedClassId

class JsUtModelConstructor: UtModelConstructorInterface {

    // TODO SEVERE: This is a very dirty prototype version. Expand!
    @Suppress("NAME_SHADOWING")
    override fun construct(value: Any?, classId: ClassId): JsUtModel {
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
            else -> throw Exception("Not implemented yet")
        }
    }
}