package org.utbot.framework.codegen.model.constructor.tree

import org.utbot.framework.codegen.model.CodeGenerator
import org.utbot.framework.codegen.model.UtilClassKind
import org.utbot.framework.codegen.model.constructor.builtin.utUtilsClassId
import org.utbot.framework.codegen.model.tree.CgRegularClassFile
import org.utbot.framework.codegen.model.tree.CgSingleLineComment
import org.utbot.framework.codegen.model.tree.CgUtilMethod
import org.utbot.framework.codegen.model.tree.buildRegularClass
import org.utbot.framework.codegen.model.tree.buildRegularClassBody
import org.utbot.framework.codegen.model.tree.buildRegularClassFile

/**
 * This class is used to construct a file containing an util class UtUtils.
 * The util class is constructed when the argument `generateUtilClassFile` in the [CodeGenerator] is true.
 */
internal object CgUtilClassConstructor {
    fun constructUtilsClassFile(utilClassKind: UtilClassKind): CgRegularClassFile {
        val utilMethodProvider = utilClassKind.utilMethodProvider
        return buildRegularClassFile {
            // imports are empty, because we use fully qualified classes and static methods,
            // so they will be imported once IDEA reformatting action has worked
            declaredClass = buildRegularClass {
                id = utUtilsClassId
                body = buildRegularClassBody {
                    // TODO: get actual UTBot version and use it instead of the hardcoded one
                    content += CgSingleLineComment("UTBot version: 1.0-SNAPSHOT")
                    content += utilMethodProvider.utilMethodIds.map { CgUtilMethod(it) }
                }
            }
        }
    }
}