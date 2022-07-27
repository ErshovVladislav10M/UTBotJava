package org.utbot.framework.util

import org.utbot.engine.displayName
import org.utbot.framework.plugin.api.UtMethod

fun isKnownSyntheticMethod(method: UtMethod<*>): Boolean =
    if (method.clazz.java.isEnum)
        method.displayName.substringBefore('(') in KnownSyntheticMethodNames.enumSyntheticMethodNames
    else
        false

/**
 * Contains names of methods that are always autogenerated and thus it is unlikely that
 * one would want to generate tests for them.
 */
private object KnownSyntheticMethodNames {
    /** List with names of enum methods that are autogenerated */
    val enumSyntheticMethodNames = listOf("values", "valueOf")
}