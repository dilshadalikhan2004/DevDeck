package com.devdeck.app.ai

import com.devdeck.app.model.DiagnosticResult
import org.junit.Assert.*
import org.junit.Test

class GroundingTest {

    @Test
    fun testGroundingWithRepositorySymbols() {
        val agent = DiagnosticAgent(null)
        
        val rawOutput = "<<<FIX>>>allowed_func(x)<<<END>>>"
        val repositorySymbols = setOf("allowed_func")
        val originalLine = "print(x)"

        val result = agent.parseResponse(
            rawOutput,
            10.0f,
            100,
            "test.py",
            1,
            originalLine,
            "error",
            "context",
            repositorySymbols
        )

        assertEquals("allowed_func(x)", result.repairCode)
        assertEquals("One-line fix suggested by on-device AI.", result.rootCause)
    }

    @Test
    fun testRejectionOfHallucinatedSymbols() {
        val agent = DiagnosticAgent(null)
        
        // AI invents 'secret_func' which is not in repositorySymbols or originalLine
        val rawOutput = "<<<FIX>>>secret_func(x)<<<END>>>"
        val repositorySymbols = setOf("allowed_func")
        val originalLine = "print(x)"

        val result = agent.parseResponse(
            rawOutput,
            10.0f,
            100,
            "test.py",
            1,
            originalLine,
            "error",
            "context",
            repositorySymbols
        )

        // Should fall back to heuristic or report ungrounded
        assertNotEquals("secret_func(x)", result.repairCode)
    }

    @Test
    fun testUnknownFixAbstainsInsteadOfGuessing() {
        val agent = DiagnosticAgent(null)
        val result = agent.parseResponse(
            "<<<WHY>>>Not enough evidence<<<END_WHY>>>\n<<<FIX>>>UNKNOWN<<<END>>>",
            10.0f,
            100,
            "test.py",
            1,
            "print(x)",
            "error",
            "context",
            emptySet()
        )
        assertTrue(result.abstained)
        assertTrue(result.rootCause.contains("NEEDS_CONTEXT"))
    }
}
