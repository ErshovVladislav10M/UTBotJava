package org.utbot.framework.codegen.model.util

import kotlinx.coroutines.runBlocking
import org.utbot.framework.plugin.api.isAccessibleFrom
import org.utbot.framework.plugin.api.packageName
import org.utbot.jcdb.api.ClassId
import org.utbot.jcdb.api.ifArrayGetElementClass
import org.utbot.jcdb.api.isLocal
import org.utbot.jcdb.api.isPackagePrivate
import org.utbot.jcdb.api.isProtected
import org.utbot.jcdb.api.isPublic
import org.utbot.jcdb.api.isSynthetic

/**
 * For now we will count class accessible if it is:
 * - Public or package-private within package [packageName].
 * - It's outer class (if exists) is accessible too.
 * NOTE: local and synthetic classes are considered as inaccessible.
 * NOTE: protected classes cannot be accessed because test class does not extend any classes.
 *
 * @param packageName name of the package we check accessibility from
 */
infix fun ClassId.isAccessibleFrom(packageName: String): Boolean = runBlocking{

    if (isLocal() || isSynthetic()) {
        return@runBlocking false
    }

    val outerClassId = outerClass()
    if (outerClassId != null && !outerClassId.isAccessibleFrom(packageName)) {
        return@runBlocking false
    }
    val elementClassId = ifArrayGetElementClass()
    if (elementClassId != null) {
        elementClassId.isAccessibleFrom(packageName)
    } else {
        val classPackage =this@isAccessibleFrom.packageName
        isPublic() || (classPackage == packageName && (isPackagePrivate() || isProtected()))
    }
}