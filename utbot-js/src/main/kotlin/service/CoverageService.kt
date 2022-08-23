package service

import java.io.File
import java.util.*
import org.apache.commons.io.FileUtils
import org.json.JSONObject
import utils.JsCmdExec

class CoverageService(
    private val context: ServiceContext,
    private val scriptText: String,
    private val id: Int,
    private val originalFileName: String,
    private val newFileName: String,
    private val basicCoverage: List<Int> = emptyList(),
) {

    init {
        with(context) {
            if (originalFileName == newFileName) {
                generateCoverageReport(projectPath, filePathToInference)
            } else {
                createTempScript("$projectPath${File.separator}$utbotDir")
                generateCoverageReport(projectPath, "$utbotDir${File.separator}$newFileName$id.js")
            }
        }
    }

    fun getCoveredLines(): List<Int> {
        val jsonText = with(context) {
            val file = File("$projectPath${File.separator}$utbotDir${File.separator}coverage$id${File.separator}coverage-final.json")
            file.readText()
        }
        val json = JSONObject(jsonText)
        val neededKey = json.keySet().find { it.contains(originalFileName) }
            json.getJSONObject(neededKey)
        val coveredStatements = json
            .getJSONObject(neededKey)
            .getJSONObject("s")
        val result = coveredStatements.keySet().flatMap {
            val count = coveredStatements.getInt(it)
            Collections.nCopies(count, it.toInt())
        }.toMutableList()
        basicCoverage.forEach {
            result.remove(it)
        }
        return result
    }

    fun removeTempFiles() {
        with(context) {
            FileUtils.deleteDirectory(File("$projectPath${File.separator}$utbotDir${File.separator}coverage$id"))
            File("$projectPath${File.separator}$utbotDir${File.separator}$newFileName$id.js").delete()
        }
    }

    private fun generateCoverageReport(workingDir: String, filePath: String) {
        val dir = File("$workingDir${File.separator}${context.utbotDir}${File.separator}coverage$id")
        dir.mkdir()
        val (_, error) = JsCmdExec.runCommand(
            "nyc --report-dir=\"$workingDir${File.separator}${context.utbotDir}${File.separator}coverage$id\" --reporter=\"json\" --temp-dir=\"${dir.absolutePath}${File.separator}cache$id\" node $filePath",
            workingDir,
            true,
        )
        val errText = error.readText()
        if (errText.isNotEmpty()) {
            println(errText)
            println("Also $id and ${dir.path}")
        }
    }

    private fun createTempScript(dir: String) {
        val file = File("$dir${File.separator}$newFileName$id.js")
        file.writeText(scriptText)
        file.createNewFile()
    }
}