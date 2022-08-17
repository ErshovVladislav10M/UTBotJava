package org.utbot.framework.plugin.api.util

import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.JsClassId

val jsUndefinedClassId = JsClassId("undefined")
val jsNumberClassId = JsClassId("number")
val jsBooleanClassId = JsClassId("bool")
val jsDoubleClassId = JsClassId("double")
val jsStringClassId = JsClassId("string")


val jsPrimitives = setOf(
    jsNumberClassId,
    jsBooleanClassId,
    jsDoubleClassId,
)

val jsBasic = setOf(
    jsNumberClassId,
    jsBooleanClassId,
    jsDoubleClassId,
    jsUndefinedClassId,
    jsStringClassId,
)

fun ClassId.toJsClassId() =
    when {
        this == intClassId -> jsNumberClassId
        this == byteClassId -> jsNumberClassId
        this == shortClassId -> jsNumberClassId
        this == booleanClassId -> jsBooleanClassId
        this == doubleClassId -> jsDoubleClassId
        this == floatClassId -> jsDoubleClassId
        this.name == "string"  -> jsStringClassId
        this == longClassId -> jsNumberClassId
        else -> jsUndefinedClassId
    }

val JsClassId.isJsBasic: Boolean
    get() = this in jsBasic

val JsClassId.isJsPrimitive: Boolean
    get() = this in jsPrimitives

val JsClassId.isUndefined: Boolean
    get() = this == jsUndefinedClassId