package utils

import org.graalvm.polyglot.Value
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.util.jsBooleanClassId
import org.utbot.framework.plugin.api.util.jsNumberClassId
import org.utbot.framework.plugin.api.util.jsStringClassId

fun Value.toAny(): Pair<Any?, JsClassId> {
    return when {
        isBoolean -> asBoolean() to jsBooleanClassId
        isString -> asString() to jsStringClassId
        isNumber -> {
            when {
                fitsInByte() -> asByte()
                fitsInShort() -> asShort()
                fitsInInt() -> asInt()
                fitsInLong() -> asLong()
                fitsInFloat() -> asFloat()
                fitsInDouble() -> asDouble()
                // TODO: Don't forget about infinities, NaN, etc.
                else -> throw Exception("Not implemented yet")
            } to jsNumberClassId
        }
        isNull -> null to JsClassId("null")
        else -> throw Exception("Not implemented yet")
    }
}