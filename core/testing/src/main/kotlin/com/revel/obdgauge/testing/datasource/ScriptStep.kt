package com.revel.obdgauge.testing.datasource

import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState

/**
 * One step of a [Scenario]'s replay script. [FakeVehicleDataSource] walks these in order,
 * `delay`-ing its `tickInterval` after each.
 */
internal sealed interface ScriptStep {
    /**
     * Change the connection state. Transitions away from [LinkState.Ready] mark every current
     * reading stale (value/timestamp frozen) until the next [Emit]; the return to [Ready] itself
     * does not refresh readings — they stay stale until fresh data actually arrives.
     */
    data class Connect(
        val state: LinkState,
    ) : ScriptStep

    /** Update the named [ScenarioChannel] keys with fresh values, timestamped at the current
     *  tick and marked not stale. */
    data class Emit(
        val values: Map<String, Double>,
    ) : ScriptStep
}

/** Returns the fixed, deterministic replay script for [scenario]. */
internal fun scenarioSteps(scenario: Scenario): List<ScriptStep> =
    when (scenario) {
        Scenario.IDLE -> idleScript()
        Scenario.TOWN_HEAT_SOAK -> townHeatSoakScript()
        Scenario.GRADE_CLIMB -> gradeClimbScript()
        Scenario.DISCONNECT_RECONNECT -> disconnectReconnectScript()
    }

// Scripted fixture data below: each scenario's literal values ARE the fixture. The whole point
// is a fixed, human-readable narrative a reviewer can check straight against the acceptance
// criteria (e.g. "TOWN_HEAT_SOAK crosses the amber coolant/oil/trans thresholds"). Extracting
// every value into a named constant would obscure the story rather than clarify it — the same
// rationale as the `ui/theme/Color.kt` MagicNumber exclusion in config/detekt/detekt.yml.

@Suppress("MagicNumber")
private fun idleScript(): List<ScriptStep> {
    val jitter = listOf(0.0, 0.5, -0.5, 0.25, -0.25, 0.5, -0.5, 0.0)
    return listOf(ScriptStep.Connect(LinkState.Ready)) +
        jitter.map { j ->
            ScriptStep.Emit(
                mapOf(
                    ScenarioChannel.COOLANT to 190.0 + j,
                    ScenarioChannel.OIL_TEMP to 200.0 + j,
                    ScenarioChannel.TRANS_TEMP to 160.0 + j,
                    ScenarioChannel.BOOST to j * 0.2,
                    ScenarioChannel.RPM to 780.0 + j * 20.0,
                ),
            )
        }
}

@Suppress("MagicNumber")
private fun townHeatSoakScript(): List<ScriptStep> {
    val coolant = listOf(190.0, 197.0, 204.0, 211.0, 218.0, 225.0)
    val oil = listOf(200.0, 208.0, 216.0, 224.0, 232.0, 240.0)
    val trans = listOf(160.0, 171.0, 182.0, 193.0, 204.0, 215.0)
    val boost = listOf(0.0, 2.0, 0.0, 3.0, 0.0, 1.0)
    val rpm = listOf(780.0, 950.0, 780.0, 1100.0, 780.0, 900.0)
    return listOf(ScriptStep.Connect(LinkState.Ready)) +
        coolant.indices.map { i ->
            ScriptStep.Emit(
                mapOf(
                    ScenarioChannel.COOLANT to coolant[i],
                    ScenarioChannel.OIL_TEMP to oil[i],
                    ScenarioChannel.TRANS_TEMP to trans[i],
                    ScenarioChannel.BOOST to boost[i],
                    ScenarioChannel.RPM to rpm[i],
                ),
            )
        }
}

@Suppress("MagicNumber")
private fun gradeClimbScript(): List<ScriptStep> {
    val boost = listOf(0.0, 4.0, 8.0, 12.0, 15.0, 11.0, 6.0, 2.0)
    val rpm = listOf(2000.0, 2400.0, 2800.0, 3200.0, 3000.0, 2600.0, 2200.0, 2000.0)
    val coolant = listOf(195.0, 198.0, 201.0, 203.0, 205.0, 206.0, 207.0, 208.0)
    val oil = listOf(205.0, 206.0, 207.0, 208.0, 209.0, 209.0, 210.0, 210.0)
    val trans = listOf(175.0, 182.0, 188.0, 193.0, 197.0, 200.0, 203.0, 206.0)
    return listOf(ScriptStep.Connect(LinkState.Ready)) +
        boost.indices.map { i ->
            ScriptStep.Emit(
                mapOf(
                    ScenarioChannel.BOOST to boost[i],
                    ScenarioChannel.RPM to rpm[i],
                    ScenarioChannel.COOLANT to coolant[i],
                    ScenarioChannel.OIL_TEMP to oil[i],
                    ScenarioChannel.TRANS_TEMP to trans[i],
                ),
            )
        }
}

@Suppress("MagicNumber")
private fun disconnectReconnectScript(): List<ScriptStep> =
    listOf(
        ScriptStep.Connect(LinkState.Ready),
        ScriptStep.Emit(mapOf(ScenarioChannel.COOLANT to 190.0, ScenarioChannel.RPM to 780.0)),
        ScriptStep.Emit(mapOf(ScenarioChannel.COOLANT to 191.0, ScenarioChannel.RPM to 790.0)),
        ScriptStep.Connect(LinkState.Error(LinkError.Timeout)),
        ScriptStep.Connect(LinkState.Scanning),
        ScriptStep.Connect(LinkState.Connecting),
        ScriptStep.Connect(LinkState.Ready),
        ScriptStep.Emit(mapOf(ScenarioChannel.COOLANT to 192.0, ScenarioChannel.RPM to 800.0)),
    )
