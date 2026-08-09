package com.revel.obdgauge.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Default Material 3 type scale.
val ObdGaugeTypography = Typography()

// Bespoke numeric style for gauge tile values (OBD-10) — legible at arm's length off a
// dash mount, heavier weight than any default Material scale step. Sized to fit a
// four-across landscape row down to ~w480dp without mid-number wraps (paired with
// `maxLines = 1` + ellipsis at the call site as a backstop on narrower devices).
private const val GAUGE_VALUE_FONT_SIZE_SP = 34

val GaugeValueTextStyle =
    TextStyle(
        fontSize = GAUGE_VALUE_FONT_SIZE_SP.sp,
        fontWeight = FontWeight.Bold,
    )
