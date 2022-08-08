package utils

import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.FunctionNode
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsConstructorId
import org.utbot.framework.plugin.api.JsMethodId

fun JsClassId.constructClass(classNode: ClassNode? = null, functions: List<FunctionNode> = emptyList()): JsClassId {
    val methods = classNode?.classElements?.map {
        val funcNode = it.value as FunctionNode
        val types = TernService.processMethod(name, funcNode.name.toString())
        JsMethodId(
            JsClassId(name),
            funcNode.name.toString(),
            types.returnType,
            types.parameters
        )
    }?.asSequence() ?:
        // used for toplevel functions
        functions.map { funcNode ->
            val types = TernService.processMethod(name, funcNode.name.toString(), true)
            JsMethodId(
                JsClassId(name),
                funcNode.name.toString(),
                types.returnType,
                types.parameters
            )
        }.asSequence()

    val constructor = classNode?.let {
        JsConstructorId(
            JsClassId(name),
            TernService.processConstructor(name),
        )
    }
    val newClassId = JsClassId(
        name,
        methods,
        constructor,
        TernService.projectPath,
        TernService.filePathToInference,
    )
    methods.forEach {
        it.classId = newClassId
    }
    constructor?.classId = newClassId
    return newClassId
}