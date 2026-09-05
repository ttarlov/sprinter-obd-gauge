package com.revel.obdgauge.app.maintenance

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** OBD-79 Roborazzi refs: the Maintenance list (chips across OK/due-soon/overdue/never-serviced) and detail screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaintenanceScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 4)

    // Taller than the dashboard's own w800dp-h360dp-land profile: this screen scrolls (it's a
    // data-entry list, not the dash-mount primary view), and the reference needs every category/
    // status visible at once rather than cropped to whatever fits the dashboard's landscape strip.
    @Config(qualifiers = "w800dp-h1000dp")
    @Test
    fun `maintenance list, chips across every status`() {
        composeTestRule.setContent {
            ObdGaugeTheme {
                MaintenanceScreen(
                    state = MaintenanceListUiState.Loaded(rows = sampleRows(), currentOdometerMiles = 94_700),
                    onSetOdometer = {},
                    onItemClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "maintenance_list.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `maintenance detail, editable part numbers`() {
        val item =
            ServiceItem(
                id = 1,
                name = "Engine oil + filter",
                category = MaintenanceCategory.ROUTINE,
                intervalMiles = 10_000,
                intervalMonths = 12,
                partNumbers = listOf("A6421800009"),
                specNotes = "MB 229.51/.52, 5W-30, ~13 L; filter cap torque 25 Nm.",
                isSeed = true,
            )
        val records =
            listOf(
                MaintenanceRecord(
                    id = 1,
                    serviceItemId = 1,
                    performedDateEpochDay = today.minusMonths(2).toEpochDay(),
                    odometerMiles = 84_700,
                    notes = "Full synthetic, dealer",
                ),
            )

        composeTestRule.setContent {
            ObdGaugeTheme {
                MaintenanceDetailScreen(
                    state = MaintenanceDetailUiState(item, records, currentOdometerMiles = 94_700),
                    onSave = {},
                    onLogService = { _, _, _, _ -> },
                    onBack = {},
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(SCREENSHOT_DIR + "maintenance_detail.png")
    }

    private fun sampleRows(): List<MaintenanceListRow> {
        val okItem =
            ServiceItem(id = 1, name = "Coolant", category = MaintenanceCategory.SCHEDULED, intervalMiles = 60_000)
        val dueSoonItem =
            ServiceItem(
                id = 2,
                name = "Engine oil + filter",
                category = MaintenanceCategory.ROUTINE,
                intervalMiles = 10_000,
            )
        val overdueItem =
            ServiceItem(id = 3, name = "Tire rotation", category = MaintenanceCategory.ROUTINE, intervalMiles = 7_500)
        val neverServicedItem =
            ServiceItem(
                id = 4,
                name = "Rear differential fluid",
                category = MaintenanceCategory.DRIVELINE,
                intervalMiles = 40_000,
            )
        val disabledItem =
            ServiceItem(
                id = 5,
                name = "Transfer case fluid",
                category = MaintenanceCategory.DRIVELINE,
                intervalMiles = 40_000,
                enabled = false,
            )

        return listOf(
            MaintenanceListRow(
                okItem,
                MaintenanceStatus.forItem(okItem, record(0, 90_000), currentOdometerMiles = 94_700, today = today),
            ),
            MaintenanceListRow(
                dueSoonItem,
                MaintenanceStatus.forItem(dueSoonItem, record(1, 84_800), currentOdometerMiles = 94_700, today = today),
            ),
            MaintenanceListRow(
                overdueItem,
                MaintenanceStatus.forItem(overdueItem, record(2, 85_000), currentOdometerMiles = 94_700, today = today),
            ),
            MaintenanceListRow(
                neverServicedItem,
                MaintenanceStatus.forItem(neverServicedItem, null, currentOdometerMiles = 94_700, today = today),
            ),
            MaintenanceListRow(
                disabledItem,
                MaintenanceStatus.forItem(
                    disabledItem,
                    record(3, 50_000),
                    currentOdometerMiles = 94_700,
                    today = today,
                ),
            ),
        )
    }

    private fun record(
        id: Long,
        odometerMiles: Int,
    ) = MaintenanceRecord(
        id = id,
        serviceItemId = id,
        performedDateEpochDay = today.minusMonths(1).toEpochDay(),
        odometerMiles = odometerMiles,
    )

    private companion object {
        const val SCREENSHOT_DIR = "src/testDemo/screenshots/"
    }
}
