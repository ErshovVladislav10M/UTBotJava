package service

import java.io.File
import org.apache.commons.io.FileUtils
import org.json.JSONObject
import utils.JsCmdExec

class CoverageService(
    private val context: ServiceContext,
    private val scriptText: String,
    private val id: Int,
    private val originalFileName: String
) {

    init {
        with(context) {
            createTempScript("$projectPath${File.separator}$utbotDir")
            generateCoverageReport(projectPath, "$utbotDir${File.separator}temp$id.js")
        }
    }

    fun getCoveredLines(): Set<Int> {
        val jsonText = with(context) {
            val file = File("$projectPath${File.separator}$utbotDir${File.separator}coverage$id${File.separator}coverage-final.json")
            file.readText()
        }
        val json = JSONObject(jsonText)
        val neededKey = json.keySet().find { it.contains(originalFileName) }
        try {
            json.getJSONObject(neededKey)
        } catch (t: Throwable) {
            println("Json text: $jsonText")
            throw t
        }
        val coveredStatements = json
            .getJSONObject(neededKey)
            .getJSONObject("s")
//        removeTempFiles()
        return coveredStatements.keySet().mapNotNull {
            if (coveredStatements.getInt(it) > 0) it.toInt() else null
        }.toSet()
    }

    private fun removeTempFiles() {
        with(context) {
            FileUtils.deleteDirectory(File("$projectPath${File.separator}$utbotDir${File.separator}coverage$id"))
            File("$projectPath${File.separator}$utbotDir${File.separator}temp$id.js").delete()
        }
    }

    private fun generateCoverageReport(workingDir: String, filePath: String) {
        val dir = File("$workingDir${File.separator}${context.utbotDir}${File.separator}coverage$id")
        dir.mkdir()
        val (_, error) = JsCmdExec.runCommand(
            "nyc --report-dir=\"$workingDir${File.separator}${context.utbotDir}${File.separator}coverage$id\" --reporter=\"json\" node $filePath",
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
        val file = File("$dir${File.separator}temp$id.js")
        file.writeText(scriptText)
        file.createNewFile()
    }
}