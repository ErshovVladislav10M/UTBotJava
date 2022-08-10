package service

import java.io.File
import org.apache.commons.io.FileUtils
import org.json.JSONObject
import utils.JsCmdExec

class CoverageService(
    private val context: ServiceContext,
    private val scriptText: String,
    private val id: Long,
) {

    init {
        with(context) {
            createTempScript("$projectPath/$utbotDir")
            generateCoverageReport("$projectPath/$utbotDir", "temp$id.js")
        }
    }

    fun getCoveredLines(): Set<Int> {
        val jsonText = with(context) {
            val file = File("$projectPath/$utbotDir/coverage$id/coverage-final.json")
            file.readText()
        }
        removeTempFiles()
        val json = JSONObject(jsonText)
        val coveredStatements = json
            .getJSONObject(json.keys().next())
            .getJSONObject("s")
        return coveredStatements.keySet().mapNotNull {
            if (coveredStatements.getInt(it) == 1) it.toInt() else null
        }.toSet()
    }

    private fun removeTempFiles() {
        with(context) {
            FileUtils.deleteDirectory(File("$projectPath/$utbotDir/coverage$id"))
            File("$projectPath/$utbotDir/.c8$id.json").delete()
            File("$projectPath/$utbotDir/temp$id.js").delete()
        }
    }

    private fun generateCoverageReport(workingDir: String, filePath: String) {
        File("$workingDir/coverage$id").mkdir()
        JsCmdExec.runCommand(
            "c8 --report-dir=\"./coverage$id\" --reporter=\"json\" node $filePath",
            workingDir,
            true,
        )
    }

    private fun createTempScript(dir: String) {
        val file = File("$dir/temp$id.js")
        file.writeText(scriptText)
        file.createNewFile()
    }
}