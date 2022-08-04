package parser

import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.LexicalContext
import com.oracle.js.parser.ir.visitor.NodeVisitor

class JsClassAstVisitor(
    private val target: String
) : NodeVisitor<LexicalContext>(LexicalContext()) {

    lateinit var targetClassNode: ClassNode

    override fun enterClassNode(classNode: ClassNode?): Boolean {
        classNode?.let {
            if (it.ident.name.toString() == target) {
                targetClassNode = it
                return false
            }
        }
        return true
    }
}