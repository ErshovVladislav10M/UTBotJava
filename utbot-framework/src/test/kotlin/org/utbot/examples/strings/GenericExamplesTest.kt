package org.utbot.examples.strings

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.utbot.examples.UtValueTestCaseChecker
import org.utbot.examples.eq
import org.utbot.examples.isException
import org.utbot.framework.codegen.CodeGeneration
import org.utbot.framework.plugin.api.CodegenLanguage

@Disabled("TODO: Fails and takes too long")
internal class GenericExamplesTest : UtValueTestCaseChecker(
    testClass = GenericExamples::class,
    testCodeGeneration = true,
    languagePipelines = listOf(
        CodeGenerationLanguageLastStage(CodegenLanguage.JAVA),
        CodeGenerationLanguageLastStage(CodegenLanguage.KOTLIN, CodeGeneration)
    )
) {
    @Test
    fun testContainsOkWithIntegerType() {
        checkWithException(
            GenericExamples<Int>::containsOk,
            eq(2),
            { obj, result -> obj == null && result.isException<NullPointerException>() },
            { obj, result -> obj != null && result.isSuccess && result.getOrNull() == false }
        )
    }

    @Test
    fun testContainsOkExampleTest() {
        check(
            GenericExamples<String>::containsOkExample,
            eq(1),
            { result -> result == true }
        )
    }
}
