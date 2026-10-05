package com.bettertalker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * P1 estético — escala tipográfica do app sobre a base do Material 3
 * (Roboto do sistema; **nenhuma fonte é baixada**).
 *
 * Consolida os ajustes que antes viviam em overrides locais do chat:
 * corpo mais respirável (26/22sp) e títulos com pesos/hierarquia claros.
 */
val AppTypography: Typography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        titleLarge = base.titleLarge.copy(
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 26.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
        ),
    )
}
