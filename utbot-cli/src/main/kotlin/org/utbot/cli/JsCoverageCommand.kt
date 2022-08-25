package org.utbot.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import mu.KotlinLogging
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import utils.JsCmdExec
import org.w3c.dom.Element

private val logger = KotlinLogging.logger {}

class JsCoverageCommand : CliktCommand(name = "coverage_js", help = "Get tests coverage for the specified file") {

    private val testFile by option(
        "-s", "--source",
        help = "Target test file path"
    ).required()
        .check("Must exist and ends with .js suffix") {
        it.endsWith(".js") && Files.exists(Paths.get(it))
    }

    private val output by option("-o", "--output",
        help = "Specifies output .json file for generated tests"
    ).check("Must end with .json suffix") {
            it.endsWith(".json")
        }

    override fun run() {
        val workingDir = testFile.substringBeforeLast(File.separator)
        val coverageDataPath = "$workingDir${File.separator}coverage"
        JsCmdExec.runCommand(
            "nyc " +
                    "--report-dir=\"$coverageDataPath\" " +
                    "--reporter=\"clover\" " +
                    "--temp-dir=\"${workingDir}${File.separator}cache\" " +
                    "mocha $testFile",
            workingDir,
            true,
        )
        val coveredList = mutableListOf<Int>()
        val partiallyCoveredList = mutableListOf<Int>()
        val uncoveredList = mutableListOf<Int>()
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val xmlFile = File("$coverageDataPath${File.separator}clover.xml")
        val doc = db.parse(xmlFile)
        buildCoverageLists(
            coveredList,
            partiallyCoveredList,
            uncoveredList,
            doc,
        )
        val json = createJson(
            coveredList,
            partiallyCoveredList,
            uncoveredList,
        )
        processResult(json, output)
    }

    private fun buildCoverageLists(
        coveredList: MutableList<Int>,
        partiallyCoveredList: MutableList<Int>,
        uncoveredList: MutableList<Int>,
        doc: Document,
    ) {
        doc.documentElement.normalize()
        val lineList = (((doc.getElementsByTagName("project").item(0) as Element)
            .getElementsByTagName("package").item(0) as Element)
            .getElementsByTagName("file").item(0) as Element)
            .getElementsByTagName("line")
        for (i in 0 until lineList.length) {
            val lineInfo = lineList.item(i) as Element
            val num = lineInfo.getAttribute("num").toInt()
            val count = lineInfo.getAttribute("count").toInt()
            when(lineInfo.getAttribute("type")) {
                "stmt" -> {
                    if (count > 0) coveredList += num
                    else uncoveredList += num
                }
                "cond" -> {
                    val trueCount = lineInfo.getAttribute("truecount").toInt()
                    val falseCount = lineInfo.getAttribute("falsecount").toInt()
                    when {
                        trueCount == 2 && falseCount == 0 -> coveredList += num
                        trueCount == 1 && falseCount == 1 -> partiallyCoveredList += num
                        trueCount == 0 && falseCount == 2 -> uncoveredList += num
                    }
                }
            }
        }
    }

    private fun createJson(
        coveredList: List<Int>,
        partiallyCoveredList: List<Int>,
        uncoveredList: List<Int>,
    ): JSONObject {
        val coveredArray = JSONArray()
        coveredList.forEach {
            val obj = JSONObject()
            obj.put("start", it)
            obj.put("end", it)
            coveredArray.put(obj)
        }
        val partiallyCoveredArray = JSONArray()
        partiallyCoveredList.forEach {
            val obj = JSONObject()
            obj.put("start", it)
            obj.put("end", it)
            partiallyCoveredArray.put(obj)
        }
        val uncoveredArray = JSONArray()
        uncoveredList.forEach {
            val obj = JSONObject()
            obj.put("start", it)
            obj.put("end", it)
            uncoveredArray.put(obj)
        }
        val json = JSONObject()
        json.put("covered", coveredArray)
        json.put("notCovered", uncoveredArray)
        json.put("partlyCovered", partiallyCoveredArray)
        return json
    }

    private fun processResult(json: JSONObject, output: String?) {
        output?.let { fileName ->
            val file = File(fileName)
            file.createNewFile()
            file.writeText(json.toString())
        } ?: logger.info { json.toString() }
    }
}