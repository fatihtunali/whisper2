package com.whisper2.app.conformance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conformance Test Suite
 *
 * Runs all conformance gates (S1-S7) against the staging server.
 * Must be run with stagingConformance build variant.
 *
 * Run with:
 *   ./gradlew :app:connectedStagingConformanceDebugAndroidTest
 *
 * Or specific test:
 *   ./gradlew :app:connectedStagingConformanceDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.whisper2.app.conformance.ConformanceSuiteTest#testFullSuite
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ConformanceSuiteTest {

    private lateinit var runner: ConformanceRunner

    @Before
    fun setUp() {
        runner = ConformanceRunner()
    }

    // ==========================================================================
    // Full Suite Test
    // ==========================================================================

    @Test
    fun testFullSuite() {
        ConformanceLogger.info("Starting full conformance suite")

        val result = runner.runAll()

        // Print full report
        println(result.toReport())

        // Assert all gates passed
        assertTrue(
            "Conformance suite failed: ${result.failedCount}/${result.gates.size} gates failed.\n" +
            result.gates.filter { !it.passed }.joinToString("\n") { "  - ${it.name}: ${it.details}" },
            result.allPassed
        )

        ConformanceLogger.info("Conformance suite completed: ${result.passedCount}/${result.gates.size} passed")
    }

    // ==========================================================================
    // Individual Gate Tests (for debugging)
    // ==========================================================================

    @Test
    fun testGateS1_Register() = runBlocking {
        ConformanceLogger.info("Testing Gate S1: Register")

        val result = runner.runGateS1()
        println(result.toReport())

        assertTrue("S1-Register failed: ${result.details}", result.passed)
    }

    @Test
    fun testGateS2_Keys() = runBlocking {
        ConformanceLogger.info("Testing Gate S2: Keys")

        // S2 requires S1 to run first
        val s1Result = runner.runGateS1()
        if (!s1Result.passed) {
            fail("S1 prerequisite failed: ${s1Result.details}")
        }

        val result = runner.runGateS2()
        println(result.toReport())

        assertTrue("S2-Keys failed: ${result.details}", result.passed)
    }

    @Test
    fun testGateS3_DirectMessage() = runBlocking {
        ConformanceLogger.info("Testing Gate S3: Direct Message")

        // S3 requires S1 to run first
        val s1Result = runner.runGateS1()
        if (!s1Result.passed) {
            fail("S1 prerequisite failed: ${s1Result.details}")
        }

        val result = runner.runGateS3()
        println(result.toReport())

        assertTrue("S3-DirectMessage failed: ${result.details}", result.passed)
    }

    @Test
    fun testGateS6_Attachments() = runBlocking {
        ConformanceLogger.info("Testing Gate S6: Attachments")

        // S6 requires S1 to run first
        val s1Result = runner.runGateS1()
        if (!s1Result.passed) {
            fail("S1 prerequisite failed: ${s1Result.details}")
        }

        val result = runner.runGateS6()
        println(result.toReport())

        assertTrue("S6-Attachments failed: ${result.details}", result.passed)
    }

    @Test
    fun testGateS7_CallsSignaling() = runBlocking {
        ConformanceLogger.info("Testing Gate S7: Calls Signaling")

        // S7 requires S1 to run first
        val s1Result = runner.runGateS1()
        if (!s1Result.passed) {
            fail("S1 prerequisite failed: ${s1Result.details}")
        }

        val result = runner.runGateS7()
        println(result.toReport())

        assertTrue("S7-CallsSignaling failed: ${result.details}", result.passed)
    }

    // ==========================================================================
    // Critical Gates Only (UI Readiness Check)
    // ==========================================================================

    /**
     * Run only the critical gates required before UI implementation:
     * - S1: Register
     * - S3: Direct Message E2E
     * - S6: Attachments E2E
     */
    @Test
    fun testCriticalGatesForUI() = runBlocking {
        ConformanceLogger.info("Testing critical gates for UI readiness")

        // S1: Register
        val s1Result = runner.runGateS1()
        println(s1Result.toReport())
        assertTrue("S1-Register failed: ${s1Result.details}", s1Result.passed)

        // S3: Direct Message
        val s3Result = runner.runGateS3()
        println(s3Result.toReport())
        assertTrue("S3-DirectMessage failed: ${s3Result.details}", s3Result.passed)

        // S6: Attachments
        val s6Result = runner.runGateS6()
        println(s6Result.toReport())
        assertTrue("S6-Attachments failed: ${s6Result.details}", s6Result.passed)

        ConformanceLogger.info("All critical gates passed! Ready for UI implementation.")
    }
}
