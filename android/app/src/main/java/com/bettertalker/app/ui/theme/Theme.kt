package com.bettertalker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Paleta Samsung clássica: amarelo como destaque primário.
val SamsungYellow = Color(0xFFFBD44A)
val SamsungYellowDeep = Color(0xFFF6C90E)
val SamsungYellowSoft = Color(0xFFFCE9A8)
val SamsungInk = Color(0xFF1B1B1B)

/** P1 — cor explícita dos links (contraste WCAG AA verificado em teste). */
val LinkBlueLight = Color(0xFF1A6FEB)
val LinkBlueDark = Color(0xFF8AB4F8)

/** Luminância relativa WCAG (0..1). Pura/testável. */
fun relativeLuminance(c: Color): Double {
    fun lin(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** Razão de contraste WCAG entre duas cores (1..21). Pura/testável. */
fun contrastRatio(fg: Color, bg: Color): Double {
    val a = relativeLuminance(fg)
    val b = relativeLuminance(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

// ---------------------------------------------------------------------------
// #7d3 — 4 paletas curadas × claro/escuro.
//
// Regra: TODOS os tokens M3 consumidos pelo app estão preenchidos (incluindo
// surfaceContainer* e os containers de secondary/tertiary), em vez de cair no
// baseline cinza-frio do Material. Os pares de contraste são auditados em
// ContrastAuditTest (todas as paletas × os dois modos).
// `internal` para o teste auditar o esquema REAL.
// ---------------------------------------------------------------------------

/** SAMSUNG — paleta histórica (amarelo + quente), mesmos hex base. */
internal val SamsungLight = lightColorScheme(
    primary = SamsungYellowDeep,
    onPrimary = SamsungInk,
    primaryContainer = SamsungYellowSoft,
    onPrimaryContainer = SamsungInk,
    secondary = Color(0xFF5B6B7B),
    secondaryContainer = Color(0xFFE8EDF3),
    onSecondaryContainer = Color(0xFF2F3A44),
    tertiary = LinkBlueLight,
    tertiaryContainer = Color(0xFFD8E6FF),
    onTertiaryContainer = Color(0xFF0E3B75),
    surface = Color(0xFFFFFDF5),
    onSurface = SamsungInk,
    surfaceVariant = Color(0xFFFFF3C4),
    onSurfaceVariant = Color(0xFF5C5442),
    surfaceContainerLow = Color(0xFFFFF9E3),
    surfaceContainer = Color(0xFFFFF6D9),
    surfaceContainerHigh = Color(0xFFF9EDC8),
    surfaceContainerHighest = Color(0xFFEFE3B8),
    background = Color(0xFFFFFDF5),
    onBackground = SamsungInk,
    outline = Color(0xFFE3D9B8),
    errorContainer = Color(0xFFFDE8E8)
)

internal val SamsungDark = darkColorScheme(
    primary = SamsungYellow,
    onPrimary = SamsungInk,
    primaryContainer = Color(0xFF3A2F10),
    onPrimaryContainer = SamsungYellowSoft,
    secondary = Color(0xFF9AA7B8),
    secondaryContainer = Color(0xFF2C3540),
    onSecondaryContainer = Color(0xFFD3DCE6),
    tertiary = LinkBlueDark,
    tertiaryContainer = Color(0xFF1E2F4A),
    onTertiaryContainer = Color(0xFFCFE0FF),
    surface = Color(0xFF1E1C15),
    onSurface = Color(0xFFF0E9DC),
    surfaceVariant = Color(0xFF2A2618),
    onSurfaceVariant = Color(0xFFCFC5AC),
    surfaceContainerLow = Color(0xFF232017),
    surfaceContainer = Color(0xFF29251B),
    surfaceContainerHigh = Color(0xFF302B1F),
    surfaceContainerHighest = Color(0xFF383224),
    background = Color(0xFF141310),
    onBackground = Color(0xFFF0E9DC),
    outline = Color(0xFF4A4230),
    errorContainer = Color(0xFF4A1F1F)
)

/** NEUTRO — clean, baixa saturação (cinza-azulado). */
internal val NeutroLight = lightColorScheme(
    primary = Color(0xFF546E7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E2E7),
    onPrimaryContainer = Color(0xFF263238),
    secondary = Color(0xFF455A64),
    secondaryContainer = Color(0xFFDCE3E6),
    onSecondaryContainer = Color(0xFF263238),
    tertiary = Color(0xFF01579B),
    tertiaryContainer = Color(0xFFD6E7F8),
    onTertiaryContainer = Color(0xFF0B3C5D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFF44474A),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF3F5F6),
    surfaceContainerHigh = Color(0xFFEDF0F2),
    surfaceContainerHighest = Color(0xFFE4E8EA),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    outline = Color(0xFFCFD8DC)
)

internal val NeutroDark = darkColorScheme(
    primary = Color(0xFF90A4AE),
    onPrimary = Color(0xFF102027),
    primaryContainer = Color(0xFF37474F),
    onPrimaryContainer = Color(0xFFCFD8DC),
    secondary = Color(0xFFB0BEC5),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFCFD8DC),
    tertiary = Color(0xFF81D4FA),
    tertiaryContainer = Color(0xFF1E3A4F),
    onTertiaryContainer = Color(0xFFB3E5FC),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E9EA),
    surfaceVariant = Color(0xFF263238),
    onSurfaceVariant = Color(0xFFB9C2C7),
    surfaceContainerLow = Color(0xFF222527),
    surfaceContainer = Color(0xFF26292C),
    surfaceContainerHigh = Color(0xFF2A2E31),
    surfaceContainerHighest = Color(0xFF2F3438),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E9EA),
    outline = Color(0xFF37474F)
)

/** QUENTE — leitura estilo Kindle (bege/dourado suave). */
internal val QuenteLight = lightColorScheme(
    primary = Color(0xFFB8860B),
    onPrimary = Color(0xFF2A1E00),
    primaryContainer = Color(0xFFF0E2BC),
    onPrimaryContainer = Color(0xFF3E2C00),
    secondary = Color(0xFF75664F),
    secondaryContainer = Color(0xFFE4D9C0),
    onSecondaryContainer = Color(0xFF4A3B22),
    tertiary = Color(0xFF0D47A1),
    tertiaryContainer = Color(0xFFD3E0F0),
    onTertiaryContainer = Color(0xFF0D3C6E),
    surface = Color(0xFFF5EFE0),
    onSurface = Color(0xFF262115),
    surfaceVariant = Color(0xFFEDE4CE),
    onSurfaceVariant = Color(0xFF5A4F38),
    surfaceContainerLow = Color(0xFFF2EBD9),
    surfaceContainer = Color(0xFFEFE7D2),
    surfaceContainerHigh = Color(0xFFE9DFC5),
    surfaceContainerHighest = Color(0xFFE2D6B8),
    background = Color(0xFFFAF6EE),
    onBackground = Color(0xFF262115),
    outline = Color(0xFFD4C8A8)
)

internal val QuenteDark = darkColorScheme(
    primary = Color(0xFFE0B65C),
    onPrimary = Color(0xFF1F1600),
    primaryContainer = Color(0xFF4A3A14),
    onPrimaryContainer = Color(0xFFF2DFAE),
    secondary = Color(0xFFC9B48C),
    secondaryContainer = Color(0xFF3E362A),
    onSecondaryContainer = Color(0xFFE3D5B8),
    tertiary = Color(0xFF8FB8F0),
    tertiaryContainer = Color(0xFF1F2F44),
    onTertiaryContainer = Color(0xFFC7DCF8),
    surface = Color(0xFF221E18),
    onSurface = Color(0xFFEDE4D4),
    surfaceVariant = Color(0xFF2E2820),
    onSurfaceVariant = Color(0xFFC9BCA2),
    surfaceContainerLow = Color(0xFF27221B),
    surfaceContainer = Color(0xFF2B261E),
    surfaceContainerHigh = Color(0xFF302A21),
    surfaceContainerHighest = Color(0xFF352E24),
    background = Color(0xFF1A1613),
    onBackground = Color(0xFFEDE4D4),
    outline = Color(0xFF4A4230)
)

/** PURO — alto contraste minimalista (azul + cinzas neutros). */
internal val PuroLight = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF616161),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF212121),
    tertiary = Color(0xFF1565C0),
    tertiaryContainer = Color(0xFFD6E4F7),
    onTertiaryContainer = Color(0xFF0D3C6E),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF424242),
    surfaceContainerLow = Color(0xFFF0F0F0),
    surfaceContainer = Color(0xFFEBEBEB),
    surfaceContainerHigh = Color(0xFFE6E6E6),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF121212),
    outline = Color(0xFFBDBDBD)
)

internal val PuroDark = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF001D36),
    primaryContainer = Color(0xFF0D3C6E),
    onPrimaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFFBDBDBD),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFD6D6D6),
    tertiary = Color(0xFF90CAF9),
    tertiaryContainer = Color(0xFF10263D),
    onTertiaryContainer = Color(0xFFA8CFF5),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF131313),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF1D1D1D),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E8),
    outline = Color(0xFF424242)
)

/** #7d3: paletas curadas disponíveis na tela "Aparência" (27 no total). */
enum class PaletteId {
    SAMSUNG, NEUTRO, QUENTE, PURO, OCEANO, FLORESTA, POR_DO_SOL,
    DRACULA, NORD, SOLARIZED, MONOKAI, ROSE_PINE, CATPPUCCIN, CYBERPUNK,
    VINTAGE, LAVANDA, PESSEGO, MENTA, CARMESIM, ARDOSIA, AREIA, MEIA_NOITE,
    CORAL, ESMERALDA, AMETISTA, RUBI, SAFIRA,
}

/** Rótulo amigável da paleta (tela Aparência). Puro/testável. */
fun paletteLabel(id: PaletteId): String = AppPalettes.of(id).label

/** Esquema M3 da paleta no modo pedido. Puro/testável. */
internal fun paletteScheme(id: PaletteId, dark: Boolean): ColorScheme =
    AppPalettes.of(id).scheme(dark)

/**
 * exp/chat-contrast (2026-10-22): alpha único dos blocos de CONTEÚDO
 * (raciocínio, orientações do orador, texto para o card, proposta).
 * Antes havia 0,4 e 0,3 para o mesmo papel — padronizado em 0,35.
 */
const val CONTENT_BLOCK_ALPHA = 0.35f

val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

// Cores de nota/pasta estilo Samsung Notes
val FolderYellow = SamsungYellow
val FolderBlue = Color(0xFF7FB3F7)
val FolderGreen = Color(0xFF8BD1A8)
val FolderPink = Color(0xFFF2A3C0)
val FolderGray = Color(0xFFC9D2DC)

val NOTE_COLORS = listOf(FolderYellow, FolderBlue, FolderGreen, FolderPink, FolderGray)

enum class ThemeMode { AUTO, LIGHT, DARK }

@Composable
fun BetterTalkerTheme(
    mode: ThemeMode = ThemeMode.AUTO,
    palette: PaletteId = PaletteId.SAMSUNG,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val target = paletteScheme(palette, dark)
    // Troca de tema/paleta suave: os tokens principais interpolam.
    val scheme = target.copy(
        primary = androidx.compose.animation.animateColorAsState(target.primary, label = "primary").value,
        onPrimary = androidx.compose.animation.animateColorAsState(target.onPrimary, label = "onPrimary").value,
        primaryContainer = androidx.compose.animation.animateColorAsState(target.primaryContainer, label = "primaryContainer").value,
        secondary = androidx.compose.animation.animateColorAsState(target.secondary, label = "secondary").value,
        tertiary = androidx.compose.animation.animateColorAsState(target.tertiary, label = "tertiary").value,
        surface = androidx.compose.animation.animateColorAsState(target.surface, label = "surface").value,
        surfaceVariant = androidx.compose.animation.animateColorAsState(target.surfaceVariant, label = "surfaceVariant").value,
        background = androidx.compose.animation.animateColorAsState(target.background, label = "background").value,
        outline = androidx.compose.animation.animateColorAsState(target.outline, label = "outline").value,
        errorContainer = androidx.compose.animation.animateColorAsState(target.errorContainer, label = "errorContainer").value,
    )
    // Block 2: a Status Bar segue o tema (ícones claros no escuro).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        // exp/chat-contrast (2026-10-22): o Compose usa Color.Black como
        // LocalContentColor padrao. As telas do app usam
        // Scaffold(containerColor = Color.Transparent) para a textura do fundo
        // aparecer — e Surface com cor transparente/custom NAO define o content
        // color. Resultado: todo texto sem cor explicita ficava PRETO no dark
        // (ex.: o titulo do card "Orientacoes do orador" = 1,00:1, invisivel).
        // Prover o content color do tema conserta a classe inteira (10 telas).
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}
