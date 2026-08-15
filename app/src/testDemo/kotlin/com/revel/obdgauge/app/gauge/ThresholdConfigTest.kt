package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.testing.datasource.ScenarioChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-66 re-baselined the seed thresholds to the researched caution/danger temperatures for a
 * loaded Sprinter/Revel on grades (all °F):
 * - coolant: green <215 / amber 215–225 / red ≥225 (danger boundary inclusive)
 * - trans:   green <215 / amber 215–240 / red ≥240
 * - oil:     green <245 / amber 245–260 / red ≥260
 * The RED boundary is inclusive so a reading sitting exactly on the danger line already reads —
 * and pulses — RED (see `ThresholdConfig`/`GaugeTile`).
 */
class ThresholdConfigTest {
    @Test
    fun `coolant below 215 is green`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.COOLANT, 214.9))
    }

    @Test
    fun `coolant at 215 is amber (green boundary is exclusive)`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.COOLANT, 215.0))
    }

    @Test
    fun `coolant just below 225 is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.COOLANT, 224.9))
    }

    @Test
    fun `coolant at 225 is red (danger boundary is inclusive)`() {
        assertEquals(ThresholdZone.RED, ThresholdConfig.classify(ScenarioChannel.COOLANT, 225.0))
    }

    @Test
    fun `trans below 215 is green`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 214.9))
    }

    @Test
    fun `trans at 215 (TOWN_HEAT_SOAK tail) is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 215.0))
    }

    @Test
    fun `trans just below 240 is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 239.9))
    }

    @Test
    fun `trans at 240 is red (danger boundary is inclusive)`() {
        assertEquals(ThresholdZone.RED, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 240.0))
    }

    @Test
    fun `oil below 245 is green`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 244.9))
    }

    @Test
    fun `oil at 245 is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 245.0))
    }

    @Test
    fun `oil at 260 is red (danger boundary is inclusive)`() {
        assertEquals(ThresholdZone.RED, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 260.0))
    }

    @Test
    fun `boost is always neutral`() {
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(ScenarioChannel.BOOST, -2.0))
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify(ScenarioChannel.BOOST, 18.0))
    }

    @Test
    fun `unknown gauge id is neutral`() {
        assertEquals(ThresholdZone.NEUTRAL, ThresholdConfig.classify("unknownGauge", 42.0))
    }
}
