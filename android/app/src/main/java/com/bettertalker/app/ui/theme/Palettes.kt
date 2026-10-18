package com.bettertalker.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Arquitetura de paletas (fonte única): cada paleta é um [PaletteSpec] com
 * rótulo + os dois esquemas M3 (claro/escuro). `AppPalettes.all` alimenta a
 * tela de Aparência (miniaturas) e o ContrastAuditTest (WCAG AA).
 *
 * Todos os tokens M3 consumidos pelo app estão preenchidos (incluindo
 * surfaceContainer*) — nunca cai no baseline cinza-frio do Material.
 */

/** Especificação de uma paleta curada. */
data class PaletteSpec(
    val id: PaletteId,
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    fun scheme(darkMode: Boolean): ColorScheme = if (darkMode) dark else light
}

/** OCEANO — azuis profundos, leitura calma. */
internal val OceanoLight = lightColorScheme(
    primary = Color(0xFF01579B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCE4F7),
    onPrimaryContainer = Color(0xFF01325A),
    secondary = Color(0xFF356B8C),
    secondaryContainer = Color(0xFFD6E6F2),
    onSecondaryContainer = Color(0xFF1D3A4D),
    tertiary = Color(0xFF0B5394),
    tertiaryContainer = Color(0xFFD6E4F7),
    onTertiaryContainer = Color(0xFF0A3B66),
    surface = Color(0xFFF5F9FF),
    onSurface = Color(0xFF102A3C),
    surfaceVariant = Color(0xFFE1EDF7),
    onSurfaceVariant = Color(0xFF3A5163),
    surfaceContainerLow = Color(0xFFEFF6FC),
    surfaceContainer = Color(0xFFEAF3FA),
    surfaceContainerHigh = Color(0xFFE3EFF8),
    surfaceContainerHighest = Color(0xFFDCEAF5),
    background = Color(0xFFF5F9FF),
    onBackground = Color(0xFF102A3C),
    outline = Color(0xFFB9CEDA),
    errorContainer = Color(0xFFFDE8E8)
)

internal val OceanoDark = darkColorScheme(
    primary = Color(0xFF6FB7E8),
    onPrimary = Color(0xFF00243D),
    primaryContainer = Color(0xFF0E3A5C),
    onPrimaryContainer = Color(0xFFCBE4F5),
    secondary = Color(0xFF9FBED2),
    secondaryContainer = Color(0xFF1C3040),
    onSecondaryContainer = Color(0xFFC5D8E4),
    tertiary = Color(0xFF8AB8E8),
    tertiaryContainer = Color(0xFF16324D),
    onTertiaryContainer = Color(0xFFCBE0F5),
    surface = Color(0xFF12222E),
    onSurface = Color(0xFFD7E6EF),
    surfaceVariant = Color(0xFF1C3040),
    onSurfaceVariant = Color(0xFFB4C8D6),
    surfaceContainerLow = Color(0xFF162936),
    surfaceContainer = Color(0xFF1A2F3E),
    surfaceContainerHigh = Color(0xFF1E3545),
    surfaceContainerHighest = Color(0xFF233C4E),
    background = Color(0xFF0C1821),
    onBackground = Color(0xFFD7E6EF),
    outline = Color(0xFF3A5568),
    errorContainer = Color(0xFF4A1F1F)
)

/** FLORESTA — verdes naturais, alto conforto de leitura. */
internal val FlorestaLight = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEBD0),
    onPrimaryContainer = Color(0xFF0A3310),
    secondary = Color(0xFF3E6B4A),
    secondaryContainer = Color(0xFFD8E8DC),
    onSecondaryContainer = Color(0xFF1E3A26),
    tertiary = Color(0xFF00594F),
    tertiaryContainer = Color(0xFFCCE8E2),
    onTertiaryContainer = Color(0xFF00382F),
    surface = Color(0xFFF4FAF4),
    onSurface = Color(0xFF16281B),
    surfaceVariant = Color(0xFFDFEDE1),
    onSurfaceVariant = Color(0xFF3B5240),
    surfaceContainerLow = Color(0xFFEDF6EE),
    surfaceContainer = Color(0xFFE8F3E9),
    surfaceContainerHigh = Color(0xFFE1EFE3),
    surfaceContainerHighest = Color(0xFFDAEADD),
    background = Color(0xFFF4FAF4),
    onBackground = Color(0xFF16281B),
    outline = Color(0xFFB8CDBC),
    errorContainer = Color(0xFFFDE8E8)
)

internal val FlorestaDark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0A2E10),
    primaryContainer = Color(0xFF1E4A24),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFA3C4AB),
    secondaryContainer = Color(0xFF1A2B1E),
    onSecondaryContainer = Color(0xFFC6DCCB),
    tertiary = Color(0xFF7FCFC0),
    tertiaryContainer = Color(0xFF123A33),
    onTertiaryContainer = Color(0xFFC4E8E0),
    surface = Color(0xFF0F1A12),
    onSurface = Color(0xFFD8E8DB),
    surfaceVariant = Color(0xFF1A2B1E),
    onSurfaceVariant = Color(0xFFB4CBB8),
    surfaceContainerLow = Color(0xFF132116),
    surfaceContainer = Color(0xFF17281A),
    surfaceContainerHigh = Color(0xFF1B2E1F),
    surfaceContainerHighest = Color(0xFF1F3423),
    background = Color(0xFF0A130C),
    onBackground = Color(0xFFD8E8DB),
    outline = Color(0xFF365239),
    errorContainer = Color(0xFF4A1F1F)
)

/** PÔR DO SOL — laranjas/rosas quentes, acolhedor. */
internal val PorDoSolLight = lightColorScheme(
    primary = Color(0xFFBF360C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCCB),
    onPrimaryContainer = Color(0xFF4A1700),
    secondary = Color(0xFF9C4A3C),
    secondaryContainer = Color(0xFFF7DAD2),
    onSecondaryContainer = Color(0xFF4A211A),
    tertiary = Color(0xFFA61C5C),
    tertiaryContainer = Color(0xFFF8D3E3),
    onTertiaryContainer = Color(0xFF4A0A28),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF33201A),
    surfaceVariant = Color(0xFFF6E3D9),
    onSurfaceVariant = Color(0xFF5A4036),
    surfaceContainerLow = Color(0xFFFFF2EB),
    surfaceContainer = Color(0xFFFFEDE4),
    surfaceContainerHigh = Color(0xFFFBE6DB),
    surfaceContainerHighest = Color(0xFFF6DFD2),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF33201A),
    outline = Color(0xFFD9C2B5),
    errorContainer = Color(0xFFFDE8E8)
)

internal val PorDoSolDark = darkColorScheme(
    primary = Color(0xFFFFB59B),
    onPrimary = Color(0xFF4A1500),
    primaryContainer = Color(0xFF6B2A0E),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFE0B4A8),
    secondaryContainer = Color(0xFF3A2820),
    onSecondaryContainer = Color(0xFFF0D3CA),
    tertiary = Color(0xFFF0A8C8),
    tertiaryContainer = Color(0xFF5C1F3C),
    onTertiaryContainer = Color(0xFFF8D3E3),
    surface = Color(0xFF241713),
    onSurface = Color(0xFFF4DED4),
    surfaceVariant = Color(0xFF3A2820),
    onSurfaceVariant = Color(0xFFD9C0B4),
    surfaceContainerLow = Color(0xFF2A1B16),
    surfaceContainer = Color(0xFF30201A),
    surfaceContainerHigh = Color(0xFF36241E),
    surfaceContainerHighest = Color(0xFF3C2822),
    background = Color(0xFF1A100C),
    onBackground = Color(0xFFF4DED4),
    outline = Color(0xFF5C4238),
    errorContainer = Color(0xFF4A1F1F)
)

/** Catálogo completo — fonte única da UI de Aparência e da auditoria. */
object AppPalettes {
    val all: List<PaletteSpec> = listOf(
        PaletteSpec(PaletteId.SAMSUNG, "Samsung", SamsungLight, SamsungDark),
        PaletteSpec(PaletteId.NEUTRO, "Neutro", NeutroLight, NeutroDark),
        PaletteSpec(PaletteId.QUENTE, "Quente", QuenteLight, QuenteDark),
        PaletteSpec(PaletteId.PURO, "Minimalista", PuroLight, PuroDark),
        PaletteSpec(PaletteId.OCEANO, "Oceano", OceanoLight, OceanoDark),
        PaletteSpec(PaletteId.FLORESTA, "Floresta", FlorestaLight, FlorestaDark),
        PaletteSpec(PaletteId.POR_DO_SOL, "Pôr do Sol", PorDoSolLight, PorDoSolDark),
    ) + GENERATED.map { g ->
        val (light, dark) = generatedSchemes(g.seed)
        PaletteSpec(g.id, g.label, light, dark)
    }

    fun of(id: PaletteId): PaletteSpec =
        all.firstOrNull { it.id == id } ?: all.first()
}

// ---------------------------------------------------------------------------
// Gerador de paletas (20 temas inspirados em editores/design systems).
//
// As âncoras de LUMINÂNCIA abaixo são escolhidas para o par de contraste
// WCAG AA passar por construção (o ContrastAuditTest é a rede de segurança):
// - claro: superfícies 97/92%, títulos 12/22%, primárias 22% (o pior matiz,
//   amarelo, fica ~0.13 de luminância → branco passa com folga);
// - escuro: superfícies 12/18%, textos 90/75%, primárias 75%.
// O par onPrimary usa preto/branco pela luminância do fundo (nunca quebra).
// ---------------------------------------------------------------------------

internal data class PaletteSeed(val hue: Float, val sat: Float, val tertiaryHue: Float)
private data class GeneratedPalette(val id: PaletteId, val label: String, val seed: PaletteSeed)

/** HSL → Color (h/s/l em 0..1; h em graus). Puro/testável. */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s.coerceIn(0f, 1f)
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l.coerceIn(0f, 1f) - c / 2f
    return Color(r1 + m, g1 + m, b1 + m, 1f)
}

/** Texto sobre [bg] pela luminância (branco ou quase-preto, sempre AA). Puro. */
internal fun onFor(bg: Color): Color =
    if (relativeLuminance(bg) > 0.22) Color(0xFF1A1A1A) else Color(0xFFFFFFFF)

/** Esquema claro gerado de uma semente (âncoras de luminância p/ AA). Puro. */
internal fun generatedLight(seed: PaletteSeed): ColorScheme {
    val primary = hsl(seed.hue, seed.sat, 0.22f)
    val secondary = hsl(seed.hue + 15f, seed.sat * 0.65f, 0.22f)
    val tertiary = hsl(seed.tertiaryHue, seed.sat, 0.22f)
    val surface = hsl(seed.hue, seed.sat * 0.25f, 0.97f)
    val variant = hsl(seed.hue, seed.sat * 0.25f, 0.92f)
    val onSurface = hsl(seed.hue, seed.sat * 0.3f, 0.12f)
    val onVariant = hsl(seed.hue, seed.sat * 0.3f, 0.22f)
    return lightColorScheme(
        primary = primary, onPrimary = onFor(primary),
        primaryContainer = hsl(seed.hue, seed.sat * 0.7f, 0.88f),
        onPrimaryContainer = hsl(seed.hue, seed.sat * 0.5f, 0.20f),
        secondary = secondary,
        secondaryContainer = hsl(seed.hue + 15f, seed.sat * 0.4f, 0.88f),
        onSecondaryContainer = hsl(seed.hue + 15f, seed.sat * 0.4f, 0.20f),
        tertiary = tertiary,
        tertiaryContainer = hsl(seed.tertiaryHue, seed.sat * 0.5f, 0.88f),
        onTertiaryContainer = hsl(seed.tertiaryHue, seed.sat * 0.4f, 0.20f),
        surface = surface, onSurface = onSurface,
        surfaceVariant = variant, onSurfaceVariant = onVariant,
        surfaceContainerLow = hsl(seed.hue, seed.sat * 0.25f, 0.955f),
        surfaceContainer = hsl(seed.hue, seed.sat * 0.25f, 0.94f),
        surfaceContainerHigh = hsl(seed.hue, seed.sat * 0.25f, 0.925f),
        surfaceContainerHighest = hsl(seed.hue, seed.sat * 0.25f, 0.90f),
        background = surface, onBackground = onSurface,
        outline = hsl(seed.hue, seed.sat * 0.3f, 0.75f),
        errorContainer = Color(0xFFFDE8E8),
    )
}

/** Esquema escuro gerado de uma semente (âncoras de luminância p/ AA). Puro. */
internal fun generatedDark(seed: PaletteSeed): ColorScheme {
    val primary = hsl(seed.hue, seed.sat * 0.9f, 0.75f)
    val secondary = hsl(seed.hue + 15f, seed.sat * 0.55f, 0.78f)
    val tertiary = hsl(seed.tertiaryHue, seed.sat * 0.85f, 0.75f)
    val surface = hsl(seed.hue, seed.sat * 0.25f, 0.12f)
    val variant = hsl(seed.hue, seed.sat * 0.25f, 0.18f)
    val onSurface = hsl(seed.hue, seed.sat * 0.2f, 0.90f)
    val onVariant = hsl(seed.hue, seed.sat * 0.25f, 0.75f)
    return darkColorScheme(
        primary = primary, onPrimary = onFor(primary),
        primaryContainer = hsl(seed.hue, seed.sat * 0.5f, 0.22f),
        onPrimaryContainer = hsl(seed.hue, seed.sat * 0.6f, 0.88f),
        secondary = secondary,
        secondaryContainer = hsl(seed.hue + 15f, seed.sat * 0.35f, 0.22f),
        onSecondaryContainer = hsl(seed.hue + 15f, seed.sat * 0.4f, 0.86f),
        tertiary = tertiary,
        tertiaryContainer = hsl(seed.tertiaryHue, seed.sat * 0.45f, 0.22f),
        onTertiaryContainer = hsl(seed.tertiaryHue, seed.sat * 0.5f, 0.88f),
        surface = surface, onSurface = onSurface,
        surfaceVariant = variant, onSurfaceVariant = onVariant,
        surfaceContainerLow = hsl(seed.hue, seed.sat * 0.25f, 0.14f),
        surfaceContainer = hsl(seed.hue, seed.sat * 0.25f, 0.16f),
        surfaceContainerHigh = hsl(seed.hue, seed.sat * 0.25f, 0.18f),
        surfaceContainerHighest = hsl(seed.hue, seed.sat * 0.25f, 0.21f),
        background = hsl(seed.hue, seed.sat * 0.25f, 0.08f), onBackground = onSurface,
        outline = hsl(seed.hue, seed.sat * 0.3f, 0.35f),
        errorContainer = Color(0xFF4A1F1F),
    )
}

private fun generatedSchemes(seed: PaletteSeed): Pair<ColorScheme, ColorScheme> =
    generatedLight(seed) to generatedDark(seed)

/** As 20 paletas-tema (seeds de matiz/satura). */
private val GENERATED: List<GeneratedPalette> = listOf(
    GeneratedPalette(PaletteId.DRACULA, "Dracula", PaletteSeed(270f, 0.45f, 330f)),
    GeneratedPalette(PaletteId.NORD, "Nord", PaletteSeed(210f, 0.18f, 175f)),
    GeneratedPalette(PaletteId.SOLARIZED, "Solarized", PaletteSeed(45f, 0.35f, 195f)),
    GeneratedPalette(PaletteId.MONOKAI, "Monokai", PaletteSeed(330f, 0.55f, 75f)),
    GeneratedPalette(PaletteId.ROSE_PINE, "Rosé Pine", PaletteSeed(330f, 0.35f, 265f)),
    GeneratedPalette(PaletteId.CATPPUCCIN, "Catppuccin", PaletteSeed(265f, 0.35f, 190f)),
    GeneratedPalette(PaletteId.CYBERPUNK, "Cyberpunk", PaletteSeed(60f, 0.85f, 310f)),
    GeneratedPalette(PaletteId.VINTAGE, "Vintage", PaletteSeed(35f, 0.30f, 90f)),
    GeneratedPalette(PaletteId.LAVANDA, "Lavanda", PaletteSeed(270f, 0.35f, 300f)),
    GeneratedPalette(PaletteId.PESSEGO, "Pêssego", PaletteSeed(25f, 0.55f, 340f)),
    GeneratedPalette(PaletteId.MENTA, "Menta", PaletteSeed(160f, 0.40f, 140f)),
    GeneratedPalette(PaletteId.CARMESIM, "Carmesim", PaletteSeed(350f, 0.55f, 15f)),
    GeneratedPalette(PaletteId.ARDOSIA, "Ardósia", PaletteSeed(215f, 0.15f, 190f)),
    GeneratedPalette(PaletteId.AREIA, "Areia", PaletteSeed(40f, 0.35f, 25f)),
    GeneratedPalette(PaletteId.MEIA_NOITE, "Meia-noite", PaletteSeed(225f, 0.45f, 250f)),
    GeneratedPalette(PaletteId.CORAL, "Coral", PaletteSeed(12f, 0.65f, 340f)),
    GeneratedPalette(PaletteId.ESMERALDA, "Esmeralda", PaletteSeed(155f, 0.55f, 45f)),
    GeneratedPalette(PaletteId.AMETISTA, "Ametista", PaletteSeed(275f, 0.50f, 290f)),
    GeneratedPalette(PaletteId.RUBI, "Rubi", PaletteSeed(345f, 0.65f, 10f)),
    GeneratedPalette(PaletteId.SAFIRA, "Safira", PaletteSeed(220f, 0.70f, 205f)),
)
