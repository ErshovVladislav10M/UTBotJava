package org.utbot.python

import org.utbot.framework.plugin.api.UtExecution

object PythonTestCaseGenerator {
    lateinit var testSourceRoot: String
    lateinit var directoriesForSysPath: List<String>
    lateinit var moduleToImport: String
    lateinit var pythonPath: String
    lateinit var projectRoot: String
    lateinit var fileOfMethod: String

    fun init(
        testSourceRoot: String,
        directoriesForSysPath: List<String>,
        moduleToImport: String,
        pythonPath: String,
        projectRoot: String,
        fileOfMethod: String
    ) {
        this.testSourceRoot = testSourceRoot
        this.directoriesForSysPath = directoriesForSysPath
        this.moduleToImport = moduleToImport
        this.pythonPath = pythonPath
        this.projectRoot = projectRoot
        this.fileOfMethod = fileOfMethod
    }

    fun generate(method: PythonMethod): PythonTestSet {
        val engine = PythonEngine(
            method,
            testSourceRoot,
            directoriesForSysPath,
            moduleToImport,
            pythonPath,
            projectRoot,
            fileOfMethod
        )
        val executions = mutableListOf<PythonExecution>()
        val errors = mutableListOf<PythonError>()

        engine.fuzzing().forEach {
            when (it) {
                is PythonExecution -> executions += it
                is PythonError -> errors += it
            }
        }

        return PythonTestSet(method, executions, errors)
    }
}