package org.utbot.python.typing

import org.utbot.framework.plugin.api.ClassId
import org.utbot.python.utils.FileManager
import org.utbot.python.PythonMethod
import org.utbot.python.code.PythonCodeGenerator.generateMypyCheckCode
import org.utbot.python.utils.runCommand
import java.io.File


object MypyAnnotations {
    private const val mypyVersion = "0.971"

    fun getCheckedByMypyAnnotations(
        method: PythonMethod,
        functionArgAnnotations: Map<String, List<String>>,
        moduleToImport: String,
        directoriesForSysPath: List<String>,
        pythonPath: String,
        isCancelled: () -> Boolean
    ) = sequence {
        val fileWithCode = FileManager.assignTemporaryFile(tag = "mypy")
        val codeWithoutAnnotations = generateMypyCheckCode(
            method,
            emptyMap(),
            directoriesForSysPath,
            moduleToImport.split(".").last(),
        )
        FileManager.writeToAssignedFile(fileWithCode, codeWithoutAnnotations)

        startMypyDaemon(pythonPath, directoriesForSysPath)
        val defaultOutput = runMypy(pythonPath, fileWithCode)
        val defaultErrorNum = getErrorNumber(defaultOutput)

        val candidates = functionArgAnnotations.entries.map { (key, value) ->
            value.map {
                Pair(key, it)
            }
        }
        if (candidates.any { it.isEmpty() })
            return@sequence

        val annotationCandidates = PriorityCartesianProduct(candidates).getSequence()
        annotationCandidates.forEach checkAnnotations@{
            if (isCancelled())
                return@checkAnnotations

            val annotationMap = it.toMap()
            val codeWithAnnotations = generateMypyCheckCode(
                method,
                annotationMap,
                directoriesForSysPath,
                moduleToImport.split(".").last(),
            )
            FileManager.writeToAssignedFile(fileWithCode, codeWithAnnotations)
            val mypyOutput = runMypy(pythonPath, fileWithCode)
            val errorNum = getErrorNumber(mypyOutput)
            if (errorNum <= defaultErrorNum) {
                yield(annotationMap.mapValues { entry ->
                    ClassId(entry.value)
                })
            }
        }

        fileWithCode.delete()
    }

    private const val configFilename = "mypy.ini"
    private val configFile = FileManager.assignTemporaryFile(configFilename)

    private fun runMypy(
        pythonPath: String,
        fileWithCode: File
    ): String {
        val result = runCommand(listOf(
            pythonPath,
            "-m",
            "mypy.dmypy",
            "run",
            fileWithCode.path,
            "--",
            "--config-file",
            configFile.path
        ))
        return result.stdout
    }

    private fun startMypyDaemon(
        pythonPath: String,
        directoriesForSysPath: List<String>
    ): String {
        val configContent = "[mypy]\nmypy_path = ${directoriesForSysPath.joinToString(separator = ":")}"
        FileManager.writeToAssignedFile(configFile, configContent)

        val result = runCommand(listOf(pythonPath, "-m", "mypy.dmypy", "start"))
        return result.stdout
    }

    private fun getErrorNumber(mypyOutput: String): Int {
        val regex = Regex("Found ([0-9]*) error")
        val match = regex.find(mypyOutput)
        return match?.groupValues?.getOrNull(1)?.toInt() ?: 0
    }

    fun mypyInstalled(pythonPath: String): Boolean {
        val result = runCommand(listOf(pythonPath, "-m", "pip", "show", "mypy"))
        if (result.exitValue != 0)
            return false
        val regex = Regex("Version: ([0-9.]*)")
        val version = regex.find(result.stdout)?.groupValues?.getOrNull(1) ?: return false
        return version == mypyVersion
    }

    fun installMypy(pythonPath: String): Int {
        val result = runCommand(listOf(pythonPath, "-m", "pip", "install", "mypy==" + mypyVersion))
        return result.exitValue
    }
}
