package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.testing.datasource.ScenarioChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdConfigTest {
    @Test
    fun `coolant below 220 is green`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.COOLANT, 219.9))
    }

    @Test
    fun `coolant at 220 is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.COOLANT, 220.0))
    }

    @Test
    fun `coolant at 230 is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.COOLANT, 230.0))
    }

    @Test
    fun `coolant above 230 is red`() {
        assertEquals(ThresholdZone.RED, ThresholdConfig.classify(ScenarioChannel.COOLANT, 230.1))
    }

    @Test
    fun `trans below 200 is green`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 199.9))
    }

    @Test
    fun `trans at 200 is amber (green boundary is exclusive)`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 200.0))
    }

    @Test
    fun `trans at 215 (TOWN_HEAT_SOAK tail) is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 215.0))
    }

    @Test
    fun `trans at 250 is amber (red boundary is exclusive)`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 250.0))
    }

    @Test
    fun `trans above 250 is red`() {
        assertEquals(ThresholdZone.RED, ThresholdConfig.classify(ScenarioChannel.TRANS_TEMP, 250.1))
    }

    @Test
    fun `oil at 235 is green (inclusive normal band)`() {
        assertEquals(ThresholdZone.GREEN, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 235.0))
    }

    @Test
    fun `oil above 235 (TOWN_HEAT_SOAK tail 240) is amber`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 240.0))
    }

    @Test
    fun `oil never reaches red`() {
        assertEquals(ThresholdZone.AMBER, ThresholdConfig.classify(ScenarioChannel.OIL_TEMP, 1000.0))
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
