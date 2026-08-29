package com.devdeck.app.ai

import com.devdeck.app.model.PatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicDiagnosticEngineTest {

    @Test
    fun `guards a NoneType attribute access`() {
        val result = HeuristicDiagnosticEngine.diagnose(
            trace = "AttributeError: 'NoneType' object has no attribute 'name'",
            source = null,
            fPath = "example.py",
            lNum = 4,
            origLine = "return user.name"
        )

        assertEquals("example.py", result.repairFile)
        assertEquals(4, result.repairLine)
        assertEquals("return user.name if user else None", result.repairCode)
        assertEquals(PatchType.SINGLE_LINE, result.patchType)
        assertTrue(result.rootCause.contains("Attribute access on None"))
    }
}
