package org.utbot.python

import org.utbot.fuzzer.CartesianProduct
import org.utbot.python.code.PythonCodeGenerator.generateMypyCheckCode
import org.utbot.python.code.StubFileStructures


object MypyAnnotations {
    fun mypyCheckAnnotations(
        method: PythonMethod,
        functionArgAnnotations: Map<String, List<StubFileStructures.PythonInfoType>>,
        testSourcePath: String,
        moduleToImport: String,
        directoriesForSysPath: List<String>,
        pythonPath: String
    ) = sequence {
        val codeFilename = "${testSourcePath}/__${method.name}__mypy_types.py"

        generateMypyCheckCode(
            method,
            emptyMap(),
            codeFilename,
            directoriesForSysPath,
            moduleToImport.split(".").last(),
        )
        startMypyDaemon(pythonPath)
        val defaultOutput = runMypy(pythonPath, codeFilename, testSourcePath)

        val annotations = CartesianProduct(
            functionArgAnnotations.entries.map { (key, value) ->
                value.map {
                    Pair(key, it)
                }
            }).asSequence()

        annotations.forEach {
            val annotationMap = it.toMap()
            val functionFile = generateMypyCheckCode(
                method,
                annotationMap,
                codeFilename,
                directoriesForSysPath,
                moduleToImport.split(".").last(),
            )
            val mypyOutput = runMypy(pythonPath, codeFilename, testSourcePath)
            if (mypyOutput == defaultOutput) {
                yield(annotationMap)
            }
            functionFile.deleteOnExit()
        }
    }

    private fun runMypy(
        pythonPath: String,
        codeFilename: String,
        testSourcePath: String,
    ): String {
        val command = "$pythonPath -m mypy.dmypy run $codeFilename -- --config-file $testSourcePath/mypy.ini"
//        val command = "$pythonPath -m mypy.dmypy run $codeFilename"
//        val command = "$pythonPath -m mypy.dmypy start"
        val process = Runtime.getRuntime().exec(
            command
        )
        process.waitFor()
        return process.inputStream.readBytes().decodeToString()
    }

    private fun startMypyDaemon(
        pythonPath: String,
    ): String {
        val command = "$pythonPath -m mypy.dmypy start"
        val process = Runtime.getRuntime().exec(
            command
        )
        process.waitFor()
        return process.inputStream.readBytes().decodeToString()
    }
}

