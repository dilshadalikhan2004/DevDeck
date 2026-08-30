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

    @Test
    fun `typeerror from traceback without original_line still patches`() {
        val result = HeuristicDiagnosticEngine.diagnose(
            trace = """
                File "test_errors.py", line 7, in trigger_type_error
                    print("Hello " + name)
                TypeError: can only concatenate str (not "NoneType") to str
            """.trimIndent(),
            source = null,
            fPath = null,
            lNum = null,
            origLine = null
        )
        assertEquals("print(\"Hello \" + str(name))", result.repairCode)
        assertEquals(7, result.repairLine)
        assertEquals(false, result.abstained)
    }

    @Test
    fun `keyerror from traceback without original_line still patches`() {
        val result = HeuristicDiagnosticEngine.diagnose(
            trace = """
                File "test_errors.py", line 18, in trigger_key_error
                    print(config["database_url"])
                KeyError: 'database_url'
            """.trimIndent(),
            source = null,
            fPath = "test_errors.py",
            lNum = null,
            origLine = "null"
        )
        assertEquals("print(config.get(\"database_url\", None))", result.repairCode)
        assertEquals(false, result.abstained)
    }

    @Test
    fun `modulenotfounderror abstains with discover suggestion`() {
        val result = HeuristicDiagnosticEngine.diagnose(
            trace = "ModuleNotFoundError: No module named 'tests.unit'\nFailed to import test module: unit",
            source = "import unittest",
            fPath = "tests/unit/test_receipts.py",
            lNum = 1,
            origLine = "import unittest"
        )

        assertEquals(null, result.repairCode)
        assertTrue(result.abstained)
        assertTrue(result.fix.contains("discover") || result.rootCause.contains("package"))
    }
}

