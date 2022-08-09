package utils

import org.graalvm.polyglot.Value
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.util.jsBooleanClassId
import org.utbot.framework.plugin.api.util.jsNumberClassId
import org.utbot.framework.plugin.api.util.jsStringClassId

fun Value.toAny(returnType: JsClassId): Pair<Any?, JsClassId> {
    return when {
        isBoolean -> asBoolean() to jsBooleanClassId
        isString -> asString() to jsStringClassId
        isNumber -> {
            val str = toString()
            if (str.contains('.')) {
                asDouble() to jsNumberClassId
            } else {
                (str.toByteOrNull() ?:
                str.toShortOrNull() ?:
                str.toIntOrNull() ?:
                str.toLongOrNull() ?:
                // TODO SEVERE: extend this
                throw IllegalStateException("Number too big")) to jsNumberClassId
            }
        }
        isNull -> null to JsClassId("null")
        else -> this.`as`(Map::class.java) to returnType
    }
}