package com.whisper2.app.conformance

import com.whisper2.app.conformance.gates.*
import kotlinx.coroutines.runBlocking

/**
 * Conformance Test Runner
 *
 * Orchestrates all conformance gates sequentially.
 * Each gate runs in isolation with its own WS connections.
 */
class ConformanceRunner {

    private val results = mutableListOf<GateResult>()
    private var suiteStartTime: Long = 0

    // Test identities (created once, reused across gates)
    lateinit var identityA: TestIdentity
        private set
    lateinit var identityB: TestIdentity
        private set

    // ==========================================================================
    // Main Entry Point
    // ==========================================================================

    fun runAll(): ConformanceSuiteResult = runBlocking {
        suiteStartTime = System.currentTimeMillis()

        ConformanceLogger.info("=" .repeat(70))
        ConformanceLogger.info("CONFORMANCE SUITE STARTING")
        ConformanceLogger.info("=" .repeat(70))

        // Validate configuration
        try {
            ConformanceConfig.requireValid()
            ConformanceLogger.info("Configuration validated")
        } catch (e: Exception) {
            ConformanceLogger.error("Configuration validation failed", e)
            results.add(GateResult.fail("S0-Config", e.message ?: "Config validation failed", 0, error = e))
            return@runBlocking buildResult()
        }

        // Create test identities
        try {
            identityA = TestIdentity.createA()
            identityB = TestIdentity.createB()
            ConformanceLogger.info("Test identities created")
            ConformanceLogger.info("  Identity A: ${identityA.name} (deviceId=${identityA.deviceId})")
            ConformanceLogger.info("  Identity B: ${identityB.name} (deviceId=${identityB.deviceId})")
        } catch (e: Exception) {
            ConformanceLogger.error("Failed to create test identities", e)
            results.add(GateResult.fail("S0-Identity", e.message ?: "Identity creation failed", 0, error = e))
            return@runBlocking buildResult()
        }

        // Run gates sequentially
        runGate("S1-Register") { GateS1Register(identityA, identityB).run() }
        runGate("S2-Keys") { GateS2Keys(identityA, identityB).run() }
        runGate("S3-DirectMessage") { GateS3DirectMessage(identityA, identityB).run() }
        runGate("S4-Pagination") { GateS4Pagination(identityA, identityB).run() }
        runGate("S5-ContactsBackup") { GateS5ContactsBackup(identityA, identityB).run() }
        runGate("S6-Attachments") { GateS6Attachments(identityA, identityB).run() }
        runGate("S7-CallsSignaling") { GateS7CallsSignaling(identityA, identityB).run() }

        buildResult()
    }

    private suspend fun runGate(name: String, gate: suspend () -> GateResult) {
        ConformanceLogger.info("")
        ConformanceLogger.info("-" .repeat(70))
        ConformanceLogger.info("Starting Gate: $name")
        ConformanceLogger.info("-" .repeat(70))

        ConformanceLogger.reset() // Clear frame/request logs for this gate

        val startTime = System.currentTimeMillis()
        val result = try {
            val gateResult = gate()
            gateResult.copy(
                wsFrames = ConformanceLogger.getWsFrameSummary(),
                httpRequests = ConformanceLogger.getHttpRequestSummary()
            )
        } catch (e: Exception) {
            ConformanceLogger.error("Gate $name threw exception", e)
            GateResult.fail(
                name = name,
                reason = "Exception: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                wsFrames = ConformanceLogger.getWsFrameSummary(),
                httpRequests = ConformanceLogger.getHttpRequestSummary(),
                error = e
            )
        }

        results.add(result)

        if (result.passed) {
            ConformanceLogger.gatePass(name, result.details)
        } else {
            ConformanceLogger.gateFail(name, result.details)
        }

        ConformanceLogger.info(result.toReport())
    }

    private fun buildResult(): ConformanceSuiteResult {
        val totalDuration = System.currentTimeMillis() - suiteStartTime
        val suiteResult = ConformanceSuiteResult(
            gates = results.toList(),
            totalDurationMs = totalDuration
        )

        ConformanceLogger.info(suiteResult.toReport())

        return suiteResult
    }

    // ==========================================================================
    // Individual Gate Access (for selective testing)
    // ==========================================================================

    suspend fun runGateS1(): GateResult {
        ConformanceConfig.requireValid()
        identityA = TestIdentity.createA()
        identityB = TestIdentity.createB()
        return GateS1Register(identityA, identityB).run()
    }

    suspend fun runGateS2(): GateResult {
        require(this::identityA.isInitialized && identityA.isRegistered) {
            "Run S1 first to register identities"
        }
        return GateS2Keys(identityA, identityB).run()
    }

    suspend fun runGateS3(): GateResult {
        require(this::identityA.isInitialized && identityA.isRegistered) {
            "Run S1 first to register identities"
        }
        return GateS3DirectMessage(identityA, identityB).run()
    }

    suspend fun runGateS6(): GateResult {
        require(this::identityA.isInitialized && identityA.isRegistered) {
            "Run S1 first to register identities"
        }
        return GateS6Attachments(identityA, identityB).run()
    }

    suspend fun runGateS7(): GateResult {
        require(this::identityA.isInitialized && identityA.isRegistered) {
            "Run S1 first to register identities"
        }
        return GateS7CallsSignaling(identityA, identityB).run()
    }
}
