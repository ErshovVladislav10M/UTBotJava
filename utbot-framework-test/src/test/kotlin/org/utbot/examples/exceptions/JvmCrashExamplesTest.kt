package org.utbot.examples.exceptions

import org.utbot.tests.infrastructure.UtValueTestCaseChecker
import org.utbot.tests.infrastructure.DoNotCalculate
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.utbot.testcheckers.eq

internal class JvmCrashExamplesTest : UtValueTestCaseChecker(testClass = JvmCrashExamples::class) {
    @Test
    @Disabled("JIRA:1527")
    fun testExit() {
        check(
            JvmCrashExamples::exit,
            eq(2)
        )
    }

    @Test
    @Disabled("Java 11 transition")
    fun testCrash() {
        check(
            JvmCrashExamples::crash,
            eq(1), // we expect only one execution after minimization
            coverage = DoNotCalculate
        )
    }
}
