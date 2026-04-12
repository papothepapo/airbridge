package com.mintlabs.airpodsnative9.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerampEqPresetParserTest {
    @Test
    fun parsesPowerampTextPreset() {
        val preset = PowerampEqPresetParser.parse(
            raw = """
                Preamp: -6.0 dB
                Filter 1: ON PK Fc 32 Hz Gain -1.0 dB Q 0.70
                Filter 2: ON PK Fc 64 Hz Gain 2.5 dB Q 1.10
                Filter 3: OFF HS Fc 8000 Hz Gain 1.2 dB Q 0.70
            """.trimIndent(),
            fallbackName = "AutoEQ"
        )

        assertEquals("AutoEQ", preset.name)
        assertEquals(-6.0f, preset.preampDb)
        assertEquals(3, preset.bands.size)
        assertEquals("PK", preset.bands[0].typeLabel)
        assertEquals(64f, preset.bands[1].frequencyHz)
        assertTrue(!preset.bands[2].enabled)
    }

    @Test
    fun parsesPowerampJsonPreset() {
        val preset = PowerampEqPresetParser.parse(
            raw = """
                {
                  "name": "Forum Sample",
                  "preamp": -4.5,
                  "parametric": true,
                  "bands": [
                    { "enabled": true, "frequency": 90.0, "gain": 0.0, "q": 0.0, "type": 0, "channels": 0 },
                    { "enabled": true, "frequency": 10000.0, "gain": 0.0, "q": 0.0, "type": 1, "channels": 0 },
                    { "enabled": true, "frequency": 105.0, "gain": 11.0, "q": 0.70, "type": 0, "channels": 1 },
                    { "enabled": true, "frequency": 59.0, "gain": -18.1, "q": 0.30, "type": 2, "channels": 1 }
                  ]
                }
            """.trimIndent()
        )

        assertEquals("Forum Sample", preset.name)
        assertEquals(-4.5f, preset.preampDb)
        assertEquals(4, preset.bands.size)
        assertEquals("LS", preset.bands[0].typeLabel)
        assertEquals("HS", preset.bands[1].typeLabel)
        assertEquals("PK", preset.bands[3].typeLabel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGraphicEqTextPreset() {
        PowerampEqPresetParser.parse(
            raw = """
                GraphicEQ: 25 0; 40 1; 63 2
            """.trimIndent()
        )
    }
}
