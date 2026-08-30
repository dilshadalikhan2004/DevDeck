package com.devdeck.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDisplayNamesTest {
    @Test
    fun knownBinsUseCatalogNames() {
        assertEquals("Gemma 2B IT", ModelDisplayNames.fromPath("/data/local/tmp/gemma-2b-it-gpu.bin"))
        assertEquals("Qwen2.5 Coder 1.5B", ModelDisplayNames.fromPath("qwen2.5-coder-1.5b-gpu.bin"))
        assertEquals("Phi-3.5 Mini", ModelDisplayNames.fromPath("phi-3.5-mini-gpu.bin"))
    }

    @Test
    fun unknownBinUsesFileName() {
        assertEquals("Llama 3 8b Instruct", ModelDisplayNames.fromPath("/sdcard/llama-3-8b-instruct.bin"))
    }

    @Test
    fun blankFallsBack() {
        assertEquals("On-device model", ModelDisplayNames.fromPath(null))
        assertEquals("On-device model", ModelDisplayNames.fromPath("  "))
    }
}
