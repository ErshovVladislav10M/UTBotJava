package org.utbot.intellij.plugin.python

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyClass
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.idea.util.projectStructure.sdk
import org.utbot.common.PathUtil.toPath
import org.utbot.framework.codegen.model.CodeGenerator
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.framework.plugin.api.util.withUtContext
import org.utbot.framework.UtSettings
import org.utbot.intellij.plugin.ui.utils.showErrorDialogLater
import org.utbot.intellij.plugin.ui.utils.testModule
import org.utbot.python.code.PythonCode
import org.utbot.python.code.PythonCode.Companion.getFromString
import org.utbot.python.PythonMethod
import org.utbot.python.PythonTestCaseGenerator
import org.utbot.python.code.PythonCodeGenerator.generateTestCode
import org.utbot.python.typing.MypyAnnotations
import org.utbot.python.typing.PythonTypesStorage
import org.utbot.python.typing.StubFileFinder
import org.utbot.python.utils.FileManager
import org.utbot.python.utils.getLineOfFunction
import java.io.File
import javax.swing.SwingUtilities.invokeLater


object PythonDialogProcessor {
    fun createDialogAndGenerateTests(
        project: Project,
        functionsToShow: Set<PyFunction>,
        containingClass: PyClass?,
        focusedMethod: PyFunction?,
        file: PyFile
    ) {
        val dialog = createDialog(project, functionsToShow, containingClass, focusedMethod, file)
        if (!dialog.showAndGet()) {
            return
        }

        createTests(project, dialog.model)
    }

    private fun createDialog(
        project: Project,
        functionsToShow: Set<PyFunction>,
        containingClass: PyClass?,
        focusedMethod: PyFunction?,
        file: PyFile
    ): PythonDialogWindow {
        val srcModule = findSrcModule(functionsToShow)
        val testModule = srcModule.testModule(project)

        return PythonDialogWindow(
            PythonTestsModel(
                project,
                srcModule,
                testModule,
                functionsToShow,
                containingClass,
                if (focusedMethod != null) setOf(focusedMethod) else null,
                file,
                getDefaultModuleToImport(file),
                UtSettings.utBotGenerationTimeoutInMillis
            )
        )
    }

    private fun findSelectedPythonMethods(model: PythonTestsModel): List<PythonMethod> {
        val code = getPyCodeFromPyFile(model.file)

        val shownFunctions: Set<PythonMethod> =
            if (model.containingClass == null) {
                code.getToplevelFunctions().toSet()
            } else {
                val classes = code.getToplevelClasses()
                val myClass = classes.find { it.name == model.containingClass.name }
                    ?: error("Didn't find containing class")
                myClass.methods.toSet()
            }

        return model.selectedFunctions.map { pyFunction ->
            shownFunctions.find { pythonMethod ->
                pythonMethod.name == pyFunction.name
            } ?: error("Didn't find PythonMethod ${pyFunction.name}")
        }
    }

    private fun createTests(project: Project, model: PythonTestsModel) {
        ProgressManager.getInstance().run(object : Backgroundable(project, "Generate python tests") {
            override fun run(indicator: ProgressIndicator) {
                val startTime = System.currentTimeMillis()

                val pythonPath = model.srcModule.sdk?.homePath ?: error("Couldn't find Python interpreter")
                val testSourceRoot = model.testSourceRoot!!.path
                val filePath = model.file.virtualFile.path
                FileManager.assignTestSourceRoot(testSourceRoot)

                if (!MypyAnnotations.mypyInstalled(pythonPath) && !indicator.isCanceled) {
                    indicator.text = "Installing mypy"
                    MypyAnnotations.installMypy(pythonPath)
                    if (!MypyAnnotations.mypyInstalled(pythonPath))
                        error("Something wrong with mypy")
                }

                if (!indicator.isCanceled) {
                    indicator.text = "Loading information about Python types"

                    // PythonCodeCollector.refreshProjectClassesList(model.project.basePath!!)
                    PythonTypesStorage.pythonPath = pythonPath
                    PythonTypesStorage.refreshProjectClassesList(
                        filePath,
                        model.project.basePath!!,
                        model.directoriesForSysPath
                    )

                    while (!StubFileFinder.isInitialized);

                    indicator.text = "Generating tests"
                }

                val pythonMethods = findSelectedPythonMethods(model)

                val testCaseGenerator = PythonTestCaseGenerator.apply {
                    init(
                        model.directoriesForSysPath,
                        model.moduleToImport,
                        pythonPath,
                        model.project.basePath!!,
                        filePath
                    ) { indicator.isCanceled || (System.currentTimeMillis() - startTime) > model.timeout }
                }

                val tests = pythonMethods.map { method ->
                    testCaseGenerator.generate(method)
                }

                val notEmptyTests = tests.filter { it.executions.isNotEmpty() || it.errors.isNotEmpty() }
                val emptyTestSets = tests.filter { it.executions.isEmpty() && it.errors.isEmpty() }

                if (emptyTestSets.isNotEmpty() && !indicator.isCanceled) {
                    val functionNames = emptyTestSets.map { it.method.name }
                    showErrorDialogLater(
                        project,
                        message = "Cannot create tests for the following functions: " + functionNames.joinToString(),
                        title = "Python test generation error"
                    )
                }

                val files = mutableListOf<File>()
                val codeAsString = getContentFromPyFile(model.file)
                notEmptyTests.forEach { testSet ->
                    val lineOfFunction = getLineOfFunction(codeAsString, testSet.method.name)
                    val message =
                        if (testSet.mypyReport.isNotEmpty())
                            "MYPY REPORT\n${
                                testSet.mypyReport.joinToString(separator = "") {
                                    if (lineOfFunction != null && it.line >= 0)
                                        ":${it.line + lineOfFunction}: ${it.type}: ${it.message}"
                                    else
                                        "${it.type}: ${it.message}"
                                }
                            }"
                        else
                            null

                    val testCode = generateTestCode(testSet, model.directoriesForSysPath, model.moduleToImport, message)
                    val fileName = "test_${testSet.method.name}.py"
                    val testFile = FileManager.createPermanentFile(fileName, testCode)
                    files.add(testFile)
                }

                if (files.size == 1) {
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(files[0])
                    if (virtualFile != null) {
                        invokeLater {
                            OpenFileDescriptor(model.project, virtualFile).navigate(true)
                        }
                    }
                }
//                val context = UtContext(this::class.java.classLoader)
//
//                val classId = PythonClassId("src.a.a.__ivtdjvrdkgbmpmsclaro__")
//                val methods = notEmptyTests.map {
//                    PythonMethodId(
//                        classId,
//                        it.method.name,
//                        PythonClassId(it.method.returnAnnotation ?: pythonNoneClassId.name),
//                        it.method.arguments.map { argument ->
//                            PythonClassId(
//                                argument.annotation ?: pythonAnyClassId.name
//                            )
//                        }
//                    )
//                }
//                withUtContext(context) {
//                    val codegen = CodeGenerator(
//                        classId,
//                        paramNames = notEmptyTests.zip(methods)
//                            .associate { (testSet, method) ->
//                                method to testSet.method.arguments.map { it.name }
//                            }
//                            .toMutableMap(),
//                        testFramework = model.testFramework,
//                        codegenLanguage = model.codegenLanguage,
//                        testClassPackageName = "",
//                    )
//                    val x = codegen.generateAsStringWithTestReport(
//                        methods.zip(notEmptyTests).map { (method, testSet) ->
//                            CgMethodTestSet(
//                                method,
//                                testSet.executions.map { execution -> execution.utExecution }
//                            )
//                        }
//                    )
//                    println(x.generatedCode)
//                }
            }
        })
    }
}

fun findSrcModule(functions: Collection<PyFunction>): Module {
    val srcModules = functions.mapNotNull { it.module }.distinct()
    return when (srcModules.size) {
        0 -> error("Module for source classes not found")
        1 -> srcModules.first()
        else -> error("Can not generate tests for classes from different modules")
    }
}

fun getDefaultModuleToImport(file: PyFile): String {
    val importPath = file.virtualFile?.let { absoluteFilePath ->
        ProjectFileIndex.SERVICE.getInstance(file.project).getContentRootForFile(absoluteFilePath)?.let {absoluteProjectPath ->
            VfsUtil.getParentDir(VfsUtilCore.getRelativeLocation(absoluteFilePath, absoluteProjectPath))
        }
    } ?: ""

    return "${importPath}.${file.name}".dropLast(3).toPath().joinToString(".")
}

fun getContentFromPyFile(file: PyFile) = file.viewProvider.contents.toString()

fun getPyCodeFromPyFile(file: PyFile): PythonCode {
    val content = getContentFromPyFile(file)
    return getFromString(content)
}