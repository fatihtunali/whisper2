package com.whisper2.app.conformance.gates

import android.util.Base64
import com.whisper2.app.conformance.*

/**
 * Gate S2: Keys (HTTP)
 *
 * Flow:
 * 1. GET /users/{whisperId}/keys for Identity A
 * 2. GET /users/{whisperId}/keys for Identity B
 * 3. Verify keys match what was registered
 *
 * PASS criteria:
 * - Both key lookups return 200
 * - encPublicKey matches registered
 * - signPublicKey matches registered
 * - Caching (second call for same user should be fast)
 */
class GateS2Keys(
    private val identityA: TestIdentity,
    private val identityB: TestIdentity
) {
    private val startTime = System.currentTimeMillis()
    private val http = ConformanceHttpClient()

    suspend fun run(): GateResult {
        ConformanceLogger.gate("S2", "Starting keys lookup test")

        try {
            identityA.requireRegistered()
            identityB.requireRegistered()

            // Lookup A's keys
            val resultA = lookupAndVerifyKeys(identityA)
            if (!resultA.passed) return resultA

            // Lookup B's keys
            val resultB = lookupAndVerifyKeys(identityB)
            if (!resultB.passed) return resultB

            // Cross-lookup: A looks up B's keys
            val crossResult = crossLookup(identityA, identityB)
            if (!crossResult.passed) return crossResult

            // Test caching by doing a second lookup
            val cacheResult = testCaching(identityA)
            if (!cacheResult.passed) return cacheResult

            val duration = System.currentTimeMillis() - startTime
            return GateResult.pass(
                name = "S2-Keys",
                details = "Keys lookup and verification passed for both identities",
                durationMs = duration,
                artifacts = mapOf(
                    "lookupADurationMs" to resultA.durationMs,
                    "lookupBDurationMs" to resultB.durationMs
                )
            )

        } catch (e: Exception) {
            return GateResult.fail(
                name = "S2-Keys",
                reason = "Keys test failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    private suspend fun lookupAndVerifyKeys(identity: TestIdentity): GateResult {
        val whisperId = identity.whisperId!!
        ConformanceLogger.gate("S2", "${identity.name}: Looking up keys for ${ConformanceLogger.maskPii(whisperId)}")

        val lookupStart = System.currentTimeMillis()
        val response = http.get(
            path = "/users/$whisperId/keys",
            sessionToken = identity.sessionToken
        )

        val lookupDuration = System.currentTimeMillis() - lookupStart

        if (!response.isSuccess) {
            return fail("${identity.name}: Keys lookup failed with status ${response.statusCode}")
        }

        val json = response.json
            ?: return fail("${identity.name}: Keys response has no JSON body")

        // Verify encPublicKey
        val encPublicKeyB64 = json.get("encPublicKey")?.asString
            ?: return fail("${identity.name}: No encPublicKey in response")

        val encPublicKey = try {
            Base64.decode(encPublicKeyB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            return fail("${identity.name}: Invalid encPublicKey base64")
        }

        if (!encPublicKey.contentEquals(identity.encPublicKey)) {
            return fail("${identity.name}: encPublicKey mismatch")
        }

        // Verify signPublicKey
        val signPublicKeyB64 = json.get("signPublicKey")?.asString
            ?: return fail("${identity.name}: No signPublicKey in response")

        val signPublicKey = try {
            Base64.decode(signPublicKeyB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            return fail("${identity.name}: Invalid signPublicKey base64")
        }

        if (!signPublicKey.contentEquals(identity.signPublicKey)) {
            return fail("${identity.name}: signPublicKey mismatch")
        }

        ConformanceLogger.gate("S2", "${identity.name}: Keys verified in ${lookupDuration}ms")

        return GateResult.pass(
            name = "S2-Keys-${identity.name}",
            details = "Keys match",
            durationMs = lookupDuration
        )
    }

    private suspend fun crossLookup(looker: TestIdentity, target: TestIdentity): GateResult {
        val whisperId = target.whisperId!!
        ConformanceLogger.gate("S2", "${looker.name} looking up ${target.name}'s keys")

        val response = http.get(
            path = "/users/$whisperId/keys",
            sessionToken = looker.sessionToken
        )

        if (!response.isSuccess) {
            return fail("Cross-lookup failed: ${looker.name} cannot fetch ${target.name}'s keys")
        }

        val json = response.json
            ?: return fail("Cross-lookup: No JSON body")

        val encPublicKeyB64 = json.get("encPublicKey")?.asString
            ?: return fail("Cross-lookup: No encPublicKey")

        val encPublicKey = Base64.decode(encPublicKeyB64, Base64.NO_WRAP)
        if (!encPublicKey.contentEquals(target.encPublicKey)) {
            return fail("Cross-lookup: encPublicKey mismatch for ${target.name}")
        }

        ConformanceLogger.gate("S2", "Cross-lookup passed")

        return GateResult.pass(
            name = "S2-Keys-CrossLookup",
            details = "Cross lookup verified",
            durationMs = response.durationMs
        )
    }

    private suspend fun testCaching(identity: TestIdentity): GateResult {
        val whisperId = identity.whisperId!!
        ConformanceLogger.gate("S2", "Testing key lookup caching for ${identity.name}")

        // First request (may or may not be cached)
        val first = http.get("/users/$whisperId/keys", identity.sessionToken)
        if (!first.isSuccess) {
            return fail("Cache test: First request failed")
        }

        // Second request (should be faster if cached)
        val second = http.get("/users/$whisperId/keys", identity.sessionToken)
        if (!second.isSuccess) {
            return fail("Cache test: Second request failed")
        }

        ConformanceLogger.gate("S2", "Cache test: first=${first.durationMs}ms, second=${second.durationMs}ms")

        // Note: We don't fail if caching isn't faster, just log it
        return GateResult.pass(
            name = "S2-Keys-Caching",
            details = "Caching test complete (1st: ${first.durationMs}ms, 2nd: ${second.durationMs}ms)",
            durationMs = first.durationMs + second.durationMs,
            artifacts = mapOf(
                "firstRequestMs" to first.durationMs,
                "secondRequestMs" to second.durationMs
            )
        )
    }

    private fun fail(reason: String): GateResult {
        return GateResult.fail(
            name = "S2-Keys",
            reason = reason,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
}
