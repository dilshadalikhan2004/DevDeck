package com.devdeck.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IncidentSourceTest {
    @Test
    fun recoversPythonTracebackLine() {
        val trace = """
            Traceback (most recent call last):
              File "test_errors.py", line 7, in trigger_type_error
                print("Hello " + name)
            TypeError: can only concatenate str (not "NoneType") to str
        """.trimIndent()
        val (line, num) = IncidentSource.fromTrace(trace)
        assertEquals("print(\"Hello \" + name)", line)
        assertEquals(7, num)
    }

    @Test
    fun ignoresJsonNullOriginal() {
        assertEquals(null, IncidentSource.usableLine("null"))
        assertEquals(null, IncidentSource.usableLine(""))
        assertEquals("x = 1", IncidentSource.usableLine("  x = 1 "))
    }

    @Test
    fun recoversFromContextMarker() {
        val ctx = "    6 |     name = None\n>>>    7 |     print(\"Hello \" + name)\n"
        assertEquals("print(\"Hello \" + name)", IncidentSource.fromContext(ctx))
    }
}
