package service

import com.oracle.js.parser.ir.ClassNode
import com.oracle.js.parser.ir.FunctionNode
import java.io.File
import java.nio.charset.Charset
import org.json.JSONException
import org.json.JSONObject
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsMultipleClassId
import org.utbot.framework.plugin.api.util.jsUndefinedClassId
import parser.JsParserUtils
import utils.JsCmdExec
import utils.MethodTypes
import utils.constructClass

/*
    NOTE: this approach is quite bad, but we failed to implement alternatives.
    TODO: 1. MINOR: Find a better solution after the first stable version.
          2. SEVERE: Load all necessary .js files in Tern.js since functions can be exported and used in other files.
 */

/**
 * Installs and sets up scripts for running Tern.js type inferencer.
 */
class TernService(val context: ServiceContext) {


    private val packageJsonCode = """
{
    "name": "utbotTern",
    "version": "1.0.0",
    "dependencies": {
        "tern": "^0.24.3"
    }
}
    """

    private fun ternScriptCode() = """
const tern = require("tern/lib/tern")
const condense = require("tern/lib/condense.js")
const util = require("tern/test/util.js")
const fs = require("fs")
const path = require("path")

var condenseDir = "";

function runTest(options) {

    var server = new tern.Server({
        projectDir: util.resolve(condenseDir),
        defs: [util.ecmascript],
        plugins: options.plugins,
        getFile: function(name) {
            return fs.readFileSync(path.resolve(condenseDir, name), "utf8");
        }
    });
    options.load.forEach(function(file) {
        server.addFile(file)
    });
    server.flush(function() {
        var origins = options.include || options.load;
        var condensed = condense.condense(origins, null, {sortOutput: true});
        var out = JSON.stringify(condensed, null, 2);
        console.log(out)
    });
}

function test(options) {
    if (typeof options == "string") options = {load: [options]};
    runTest(options);
}

test("${context.filePathToInference}")
    """

    private lateinit var json: JSONObject

    fun run() {
        with(context) {
            setupTernEnv("$projectPath${File.separator}$utbotDir")
            installDeps("$projectPath${File.separator}$utbotDir")
            runTypeInferencer()
        }
    }

    private fun installDeps(path: String) {
        JsCmdExec.runCommand(
            "npm install tern -l",
            path,
            true,
        )
    }

    private fun setupTernEnv(path: String) {
        File(path).mkdirs()
        val ternScriptFile = File("$path${File.separator}ternScript.js")
        ternScriptFile.writeText(ternScriptCode(), Charset.defaultCharset())
        ternScriptFile.createNewFile()
        val packageJsonFile = File("$path${File.separator}package.json")
        packageJsonFile.writeText(packageJsonCode, Charset.defaultCharset())
        packageJsonFile.createNewFile()
    }

    private fun runTypeInferencer() {
        with(context) {
            val (reader, _) = JsCmdExec.runCommand(
                "node ${projectPath}${File.separator}$utbotDir${File.separator}ternScript.js",
                "$projectPath${File.separator}$utbotDir${File.separator}",
                true,
                15_000
            )
            val text = reader.readText().replaceAfterLast("}", "")
            json = JSONObject(text)
        }
    }

    fun processConstructor(classNode: ClassNode): List<JsClassId> {
        val classJson = json.getJSONObject(classNode.ident.name.toString())
        return try {
            val constructorFunc = classJson.getString("!type")
                .filterNot { setOf(' ', '+', '!').contains(it) }
            extractParameters(constructorFunc)
        } catch (e: JSONException) {
            (classNode.constructor.value as FunctionNode).parameters.map { jsUndefinedClassId }
        }
    }

    private fun extractParameters(line: String): List<JsClassId> {
        val parametersRegex = Regex("fn[(](.+)[)]")
        return parametersRegex.find(line)?.groups?.get(1)?.let { matchResult ->
            val value = matchResult.value
            val paramList = value.split(',')
            paramList.map { param ->
                val paramReg = Regex(":(.*)")
                try {
                    makeClassId(
                        paramReg.find(param)?.groups?.get(1)?.value
                            ?: throw IllegalStateException()
                    )
                } catch (t: Throwable) {
                    jsUndefinedClassId
                }
            }
        } ?: emptyList()
    }

    private fun extractReturnType(line: String): JsClassId {
        val returnTypeRegex = Regex("->(.*)")
        return returnTypeRegex.find(line)?.groups?.get(1)?.let { matchResult ->
            val value = matchResult.value
            try {
                makeClassId(value)
            } catch (t: Throwable){
                jsUndefinedClassId
            }
        } ?: jsUndefinedClassId
    }

    fun processMethod(className: String?, methodName: String, isToplevel: Boolean = false): MethodTypes {
        // Js doesn't support nested classes, so if the function is not top-level, then we can check for only one parent class.
        var scope = className?.let {
            if (!isToplevel) json.getJSONObject(it) else json
        } ?: json
        try {
            scope.getJSONObject(methodName)
        } catch (e: JSONException) {
            scope = scope.getJSONObject("prototype")
        }
        val methodJson = scope.getJSONObject(methodName)
        val typesString = methodJson.getString("!type")
            .filterNot { setOf(' ', '+', '!').contains(it) }
        val parametersList = lazy { extractParameters(typesString) }
        val returnType = lazy { extractReturnType(typesString) }

        return MethodTypes(parametersList, returnType)
    }

    //TODO MINOR: move to appropriate place (JsIdUtil or JsClassId constructor)
    private fun makeClassId(name: String): JsClassId {
        val classId = when {
            // TODO SEVERE: I don't know why Tern sometimes says that type is "0"
            name == "?" || name == "0" -> jsUndefinedClassId
            Regex("\\[(.*)]").matches(name) -> {
                val arrType = Regex("\\[(.*)]").find(name)?.groups?.get(1)?.value ?: throw IllegalStateException()
                JsClassId(
                    "array",
                    elementClassId = makeClassId(arrType)
                )
            }
            name.contains('|') -> JsMultipleClassId(name.toLowerCase())
            else -> JsClassId(name.toLowerCase())
        }

        return try {
            val classNode = JsParserUtils.searchForClassDecl(name, context.fileText ?: context.trimmedFileText)
            JsClassId(name).constructClass(this, classNode)
        } catch (e: Exception) {
            classId
        }
    }
}