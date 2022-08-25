package utils

import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.FunctionNode
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsConstructorId
import org.utbot.framework.plugin.api.JsMethodId
import org.utbot.framework.plugin.api.util.jsUndefinedClassId
import service.TernService

fun JsClassId.constructClass(ternService: TernService, classNode: ClassNode? = null, functions: List<FunctionNode> = emptyList()): JsClassId {
    val className = classNode?.ident?.name?.toString()
    val methods = classNode?.classElements?.map {
        val funcNode = it.value as FunctionNode
        val types = ternService.processMethod(className, funcNode)
        JsMethodId(
            JsClassId(name),
            funcNode.name.toString(),
            jsUndefinedClassId,
            emptyList(),
            it.isStatic,
            lazyReturnType = types.returnType,
            lazyParameters = types.parameters,
        )
    }?.asSequence() ?:
        // used for toplevel functions
        functions.map { funcNode ->
            val types = ternService.processMethod(className, funcNode, true)
            JsMethodId(
                JsClassId(name),
                funcNode.name.toString(),
                jsUndefinedClassId,
                emptyList(),
                true,
                lazyReturnType = types.returnType,
                lazyParameters = types.parameters,
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