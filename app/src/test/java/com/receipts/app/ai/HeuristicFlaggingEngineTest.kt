package com.receipts.app.ai

import com.receipts.app.model.RiskCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicFlaggingEngineTest {

    private val engine = HeuristicFlaggingEngine()

    @Test
    fun `flags numbers and currencies`() = runBlocking {
        val text = "The price is $19.99 and the growth is 15%."
        val spans = engine.flagRiskySpans(text)
        
        assertEquals(2, spans.size)
        assertEquals("$19.99", spans[0].text)
        assertEquals(RiskCategory.NUMBER, spans[0].category)
        assertEquals("15%", spans[1].text)
        assertEquals(RiskCategory.NUMBER, spans[1].category)
    }

    @Test
    fun `flags dates`() = runBlocking {
        val text = "Event on Jan 1st, 2026."
        val spans = engine.flagRiskySpans(text)
        
        assertEquals(1, spans.size)
        assertEquals("Jan 1st, 2026", spans[0].text)
        assertEquals(RiskCategory.DATE, spans[0].category)
    }

    @Test
    fun `flags risky claims`() = runBlocking {
        val text = "Research shows that this is guaranteed."
        val spans = engine.flagRiskySpans(text)
        
        assertEquals(2, spans.size)
        assertTrue(spans.any { it.text.equals("research shows", ignoreCase = true) })
        assertTrue(spans.any { it.text.equals("guaranteed", ignoreCase = true) })
    }

    @Test
    fun `deduplicates overlapping spans`() = runBlocking {
        // The engine prevents starting a new span inside an old one.
        val text = "The 100% guarantee is real."
        val spans = engine.flagRiskySpans(text)
        
        assertTrue(spans.isNotEmpty())
        spans.forEachIndexed { index, span ->
            if (index > 0) {
                assertTrue(span.startIndex >= spans[index-1].endIndex)
            }
        }
    }

    @Test
    fun `flags AI markers`() = runBlocking {
        val text = "As an AI language model, I can say this is important to note."
        val spans = engine.flagRiskySpans(text)
        
        assertTrue(spans.any { it.text.contains("AI language model", ignoreCase = true) })
        assertTrue(spans.any { it.text.contains("important to note", ignoreCase = true) })
    }
}
