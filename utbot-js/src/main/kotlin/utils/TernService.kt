package utils

import java.io.File
import java.nio.charset.Charset
import org.json.JSONException
import org.json.JSONObject
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.JsMultipleClassId
import org.utbot.framework.plugin.api.util.isJsBasic
import org.utbot.framework.plugin.api.util.jsUndefinedClassId
import parser.JsParserUtils

/*
    NOTE: this approach is quite bad, but we failed to implement alternatives.
    TODO: 1. MINOR: Find a better solution after the first stable version.
          2. SEVERE: Load all necessary .js files in Tern.js since functions can be exported and used in other files.
 */

/**
 * Installs and sets up scripts for running Tern.js type inferencer.
 */
object TernService {

    var projectPath = ""

    var filePathToInference = ""

    private val utbotDir = "utbotJs"

    private fun ternScriptCode() = """
// @ts-ignore        
import * as tern from "tern/lib/tern.js";
// @ts-ignore
import * as condense from "tern/lib/condense.js";
// @ts-ignore
import * as util from "tern/test/util.js";
// @ts-ignore
import * as fs from "fs";
// @ts-ignore
import * as path from "path";
import * as angular from "tern/plugin/angular.js";
import * as node from "tern/plugin/node.js";

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

test("$filePathToInference")
    """

    private const val packageJsonCode = """
{
    "name": "utbotTern",
    "version": "1.0.0",
    "type": "module",
    "dependencies": {
        "tern": "^0.24.3"
    }
}
    """

    private lateinit var json: JSONObject

    fun run() {
        setupTernEnv("$projectPath/$utbotDir")
        installDeps("$projectPath/$utbotDir")
        runTypeInferencer()
    }

    private fun installDeps(path: String) {
        JsCmdExec.runCommand("npm install tern -l", path)
    }

    private fun setupTernEnv(path: String) {
        File(path).mkdirs()
        val ternScriptFile = File("$path/ternScript.js")
        ternScriptFile.writeText(ternScriptCode(), Charset.defaultCharset())
        ternScriptFile.createNewFile()
        val packageJsonFile = File("$path/package.json")
        packageJsonFile.writeText(packageJsonCode, Charset.defaultCharset())
        packageJsonFile.createNewFile()
    }

    private fun runTypeInferencer() {
        val reader = JsCmdExec.runCommand("node $projectPath/$utbotDir/ternScript.js", "$projectPath/$utbotDir/")
        json = JSONObject(reader.readText())
    }

    fun processConstructor(className: String): List<JsClassId> {
        val classJson = json.getJSONObject(className)
        val constructorFunc = classJson.getString("!type")
            .filterNot { setOf(' ', '+', '!').contains(it) }
        return extractParameters(constructorFunc)
    }

    private fun extractParameters(line: String): List<JsClassId> {
        val parametersRegex = Regex("fn[(](.+)[)]")
        return parametersRegex.find(line)?.groups?.get(1)?.let { matchResult ->
            val value = matchResult.value
            val paramList = value.split(',')
            paramList.map { param ->
                val paramReg = Regex(":(.*)")
                makeClassId(paramReg.find(param)?.groups?.get(1)?.value
                    ?: throw IllegalStateException()
                )
            }
        } ?: emptyList()
    }

    private fun extractReturnType(line: String): JsClassId {
        val returnTypeRegex = Regex("->(.*)")
        return returnTypeRegex.find(line)?.groups?.get(1)?.let { matchResult ->
            val value = matchResult.value
            makeClassId(value)
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
        val parametersList = extractParameters(typesString)
        val returnType = extractReturnType(typesString)

        return MethodTypes(parametersList, returnType)
    }

    //TODO SEVERE: move to appropriate place (JsIdUtil or JsClassId constructor)
    private fun makeClassId(name: String): JsClassId {
        val classId = when {
            name == "?" -> jsUndefinedClassId
            name.contains('|') -> JsMultipleClassId(name.toLowerCase())
            else -> JsClassId(name.toLowerCase())
        }
       return when {
            classId.isJsBasic || classId is JsMultipleClassId -> classId
            else -> {
                val classNode = JsParserUtils.searchForClassDecl(name, filePathToInference)
                JsClassId(name).constructClass(classNode)
            }
        }
    }
}