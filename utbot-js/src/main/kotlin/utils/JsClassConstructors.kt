package utils

import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.FunctionNode
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsConstructorId
import org.utbot.framework.plugin.api.JsMethodId
import service.TernService

fun JsClassId.constructClass(ternService: TernService, classNode: ClassNode? = null, functions: List<FunctionNode> = emptyList()): JsClassId {
    val methods = classNode?.classElements?.map {
        val funcNode = it.value as FunctionNode
        val types = ternService.processMethod(name, funcNode.name.toString())
        JsMethodId(
            JsClassId(name),
            funcNode.name.toString(),
            types.returnType,
            types.parameters,
            it.isStatic,
        )
    }?.asSequence() ?:
        // used for toplevel functions
        functions.map { funcNode ->
            val types = ternService.processMethod(name, funcNode.name.toString(), true)
            JsMethodId(
                JsClassId(name),
                funcNode.name.toString(),
                types.returnType,
                types.parameters,
                true
            )
        }.asSequence()

    val constructor = classNode?.let {
        JsConstructorId(
            JsClassId(name),
            ternService.processConstructor(it),
        )
    }
    val newClassId = JsClassId(
        name,
        methods,
        constructor,
        ternService.context.projectPath,
        ternService.context.filePathToInference,
    )
    methods.forEach {
        it.classId = newClassId
    }
    constructor?.classId = newClassId
    return newClassId
}