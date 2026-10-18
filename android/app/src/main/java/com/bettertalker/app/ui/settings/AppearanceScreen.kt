package com.bettertalker.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.ui.theme.AppPalettes
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.PaletteSpec
import com.bettertalker.app.ui.theme.TextureId
import com.bettertalker.app.ui.theme.ThemeMode
import com.bettertalker.app.ui.theme.textureLabel
import com.bettertalker.app.ui.theme.texturedBackground
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Grupos visuais das paletas (definidos à mão — a lista de 27 fica navegável).
 * A busca ignora os grupos (lista plana filtrada). Puro/testável.
 */
internal val PALETTE_GROUPS: List<Pair<String, List<PaletteId>>> = listOf(
    "Clássicas" to listOf(
        PaletteId.SAMSUNG, PaletteId.NEUTRO, PaletteId.QUENTE, PaletteId.PURO,
        PaletteId.VINTAGE, PaletteId.SOLARIZED, PaletteId.ARDOSIA, PaletteId.MEIA_NOITE,
    ),
    "Tecnológicas" to listOf(
        PaletteId.DRACULA, PaletteId.NORD, PaletteId.MONOKAI, PaletteId.CATPPUCCIN,
        PaletteId.CYBERPUNK, PaletteId.ROSE_PINE,
    ),
    "Vibrantes" to listOf(
        PaletteId.LAVANDA, PaletteId.PESSEGO, PaletteId.CORAL, PaletteId.CARMESIM,
        PaletteId.RUBI, PaletteId.AMETISTA, PaletteId.SAFIRA,
    ),
    "Natureza" to listOf(
        PaletteId.OCEANO, PaletteId.FLORESTA, PaletteId.POR_DO_SOL, PaletteId.MENTA,
        PaletteId.ESMERALDA, PaletteId.AREIA,
    ),
)

/**
 * Tela de Aparência: Tema → Textura de Fundo (com intensidade) → Paletas
 * agrupadas. Tudo num LazyVerticalGrid: cabeçalhos e slider ocupam a linha
 * inteira (span) e paletas/texturas ficam em 2 colunas. Tocar aplica na hora
 * (live preview — a mudança persiste no SettingsStore e o app recompõe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val mode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
    val paletteId by settings.paletteId.collectAsState(initial = PaletteId.SAMSUNG)
    val textureName by settings.homeTexture.collectAsState(initial = "NONE")
    val texture = TextureId.fromNameOrDefault(textureName)
    val intensity by settings.textureIntensity.collectAsState(initial = 0.05f)
    val scope = rememberCoroutineScope()
    var paletteQuery by remember { mutableStateOf("") }
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aparência") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad),
        ) {
            // ---------- Tema ----------
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-theme") {
                Column {
                    Text("Tema", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { scope.launch { settings.setThemeMode(m) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size,
                                ),
                            ) { Text(themeModeLabel(m)) }
                        }
                    }
                }
            }

            // ---------- Textura de Fundo (destaque, logo abaixo do tema) ----------
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-texture") {
                Text(
                    "Textura de Fundo",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(TextureId.entries.toList(), key = { "tex-${it.name}" }) { t ->
                TextureCard(
                    texture = t,
                    intensity = intensity,
                    selected = texture == t,
                    onClick = { scope.launch { settings.setHomeTexture(t.name) } },
                )
            }
            if (texture != TextureId.NONE) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "texture-intensity") {
                    Column(Modifier.padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Intensidade da Textura",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(intensity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = intensity,
                            onValueChange = { scope.launch { settings.setTextureIntensity(it) } },
                            valueRange = 0f..1f,
                        )
                    }
                }
            }

            // ---------- Paletas ----------
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-palette") {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Paleta", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = paletteQuery,
                        onValueChange = { paletteQuery = it },
                        placeholder = { Text("Buscar paleta…") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            val query = paletteQuery.trim()
            if (query.isBlank()) {
                PALETTE_GROUPS.forEach { (group, ids) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "group-$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(
                        ids.mapNotNull { id -> AppPalettes.all.firstOrNull { it.id == id } },
                        key = { it.id.name },
                    ) { spec ->
                        PaletteCard(
                            spec = spec,
                            dark = dark,
                            selected = spec.id == paletteId,
                            onClick = { scope.launch { settings.setPaletteId(spec.id) } },
                        )
                    }
                }
            } else {
                items(
                    AppPalettes.all.filter { it.label.contains(query, ignoreCase = true) },
                    key = { it.id.name },
                ) { spec ->
                    PaletteCard(
                        spec = spec,
                        dark = dark,
                        selected = spec.id == paletteId,
                        onClick = { scope.launch { settings.setPaletteId(spec.id) } },
                    )
                }
            }
        }
    }
}

/** Rótulo do modo de tema. Puro/testável. */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AUTO -> "Automático"
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Escuro"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteCard(
    spec: PaletteSpec,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = spec.scheme(dark)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    scheme.primary, scheme.tertiary, scheme.secondary,
                    scheme.surfaceVariant, scheme.surface,
                ).forEach { c ->
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.dp, scheme.outline, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check, "Selecionada",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Prévia da textura na intensidade atual + rótulo (2 colunas, sem corte). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextureCard(
    texture: TextureId,
    intensity: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .texturedBackground(
                        texture = texture,
                        background = MaterialTheme.colorScheme.surface,
                        ink = MaterialTheme.colorScheme.onSurface,
                        intensity = intensity,
                    )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(textureLabel(texture), style = MaterialTheme.typography.labelSmall)
    }
}
