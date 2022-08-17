package utils

import org.utbot.framework.plugin.api.JsClassId

data class MethodTypes(
    val parameters: Lazy<List<JsClassId>>,
    val returnType: Lazy<JsClassId>,
)