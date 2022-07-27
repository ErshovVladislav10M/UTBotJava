package org.utbot.framework.plugin.api.impl

import org.utbot.framework.plugin.api.classId
import org.utbot.framework.plugin.api.util.field
import org.utbot.framework.plugin.api.util.id
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.FieldId
import soot.Scene
import soot.SootClass
import java.lang.reflect.Modifier

interface FieldIdStrategy {

    val isPublic: Boolean

    val isProtected: Boolean

    val isPrivate: Boolean

    val isPackagePrivate: Boolean
        get() = !(isPublic || isProtected || isPrivate)

    val isFinal: Boolean

    val isStatic: Boolean

    val isSynthetic: Boolean

    val type: ClassId
}

class FieldIdReflectionStrategy(val fieldId: FieldId) : FieldIdStrategy {

    override val isPublic: Boolean
        get() = Modifier.isPublic(fieldId.field.modifiers)

    override val isProtected: Boolean
        get() = Modifier.isProtected(fieldId.field.modifiers)

    override val isPrivate: Boolean
        get() = Modifier.isPrivate(fieldId.field.modifiers)

    override val isFinal: Boolean
        get() = Modifier.isFinal(fieldId.field.modifiers)

    override val isStatic: Boolean
        get() = Modifier.isStatic(fieldId.field.modifiers)

    override val isSynthetic: Boolean
        get() = fieldId.field.isSynthetic

    override val type: ClassId
        get() = fieldId.field.type.id
}

class FieldIdSootStrategy(val declaringClass: ClassId, val fieldId: FieldId) : FieldIdStrategy {

    private val declaringSootClass: SootClass
        get() = Scene.v().getSootClass(declaringClass.name)

    /**
     * For hidden field (fields with the same names but different types in one class) produces RuntimeException.
     * [SAT-315](JIRA:315)
     */
    private val modifiers: Int
        get() = declaringSootClass.getFieldByName(fieldId.name).modifiers


    override val isPublic: Boolean
        get() = soot.Modifier.isPublic(modifiers)

    override val isProtected: Boolean
        get() = soot.Modifier.isProtected(modifiers)

    override val isPrivate: Boolean
        get() = soot.Modifier.isPrivate(modifiers)

    override val isFinal: Boolean
        get() = soot.Modifier.isFinal(modifiers)

    override val isStatic: Boolean
        get() = soot.Modifier.isStatic(modifiers)

    override val isSynthetic: Boolean
        get() = soot.Modifier.isSynthetic(modifiers)

    override val type: ClassId
        get() = declaringSootClass.getFieldByName(fieldId.name).type.classId

}
