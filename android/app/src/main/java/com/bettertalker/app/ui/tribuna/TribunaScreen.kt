package com.bettertalker.app.ui.tribuna

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.stageSpeakerColor

/**
 * #20-fix-b: a Tribuna — leitura do mapa de memória por seção. Cards
 * empilhados com inline edit (autosave) e TTS só na frase-âncora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TribunaScreen(vm: TribunaViewModel, onBack: () -> Unit) {
    val cards by vm.cards.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val speakingId by vm.speakingId.collectAsState()
    val noteTitle by vm.noteTitle.collectAsState()
    val isDemonstration by vm.isDemonstration.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    // #20-fix-b: sair da tela para a leitura em voz (o orador pode voltar).
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tribuna") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    // Fonte: mesmo reuso do Ensaio (stageFontSp).
                    TextButton(onClick = { vm.setFontSp(fontSp - 2) }) {
                        Text("A−", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { vm.setFontSp(fontSp + 2) }) {
                        Text("A+", fontWeight = FontWeight.Bold)
                    }
                    // #20-fix-c: export da Tribuna (PDF/HTML/Copiar).
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Opções")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Exportar PDF") },
                            onClick = {
                                menuOpen = false
                                exportTribunaPdf(ctx, noteTitle, cards)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar HTML") },
                            onClick = {
                                menuOpen = false
                                shareTribunaHtml(ctx, noteTitle, cards)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Copiar") },
                            onClick = {
                                menuOpen = false
                                copyTribunaText(ctx, cards)
                            },
                        )
                    }
                },
            )
        },
    ) { pad ->
        if (cards.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nenhuma Tribuna gerada. Volte ao editor e toque em ⋮ → Gerar Tribuna.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.sectionId }) { card ->
                    TribunaCard(
                        card = card,
                        fontSp = fontSp,
                        speaking = speakingId == card.sectionId,
                        isDemonstration = isDemonstration,
                        onMainIdea = { vm.updateMainIdea(card.sectionId, it) },
                        onBibleRef = { vm.updateBibleRef(card.sectionId, it) },
                        onAnchor = { vm.updateAnchorPhrase(card.sectionId, it) },
                        onTransition = { vm.updateTransition(card.sectionId, it) },
                        onSpeak = {
                            if (speakingId == card.sectionId) vm.stopSpeaking()
                            else vm.speakAnchor(card.sectionId)
                        },
                    )
                }
            }
        }
    }
}

/** Card de UMA seção: header + os 4 campos (ou o placeholder sem cápsula). */
@Composable
private fun TribunaCard(
    card: TribunaViewModel.TribunaCardUi,
    fontSp: Int,
    speaking: Boolean,
    isDemonstration: Boolean,
    onMainIdea: (String) -> Unit,
    onBibleRef: (String) -> Unit,
    onAnchor: (String) -> Unit,
    onTransition: (String) -> Unit,
    onSpeak: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${card.order + 1}. ${card.sectionTitle} · ${card.minutes} min",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val capsule = card.capsule
            if (isDemonstration) {
                // Bloco 2 (demos): a Tribuna vira roteiro — turnos alternados;
                // cápsulas de discurso (ideia/âncora/transição) ficam ocultas.
                DemoScript(card.turns, fontSp)
            } else if (capsule == null) {
                Text(
                    "Tribuna não gerada para esta seção.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TribunaField("💡 IDEIA CENTRAL", capsule.mainIdea, fontSp, onMainIdea)
                TribunaField("📖 TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp, onBibleRef)
                TribunaField("🎯 FRASE-ÂNCORA", capsule.anchorPhrase, fontSp, onAnchor, bold = true)
                Row {
                    // TTS SÓ da âncora: o orador ouve a frase-chave e fala o resto.
                    TextButton(onClick = onSpeak) {
                        Text(if (speaking) "■ Parar" else "▶ Ouvir")
                    }
                }
                TribunaField("➡ TRANSIÇÃO (opcional)", capsule.transition.orEmpty(), fontSp, onTransition)
            }
        }
    }
}

/**
 * Bloco 2 (demos): roteiro com turnos alternados — 1º papel à direita, 2º à
 * esquerda (como um chat), com as cores de papel do Ensaio. Sem turnos
 * reconhecidos: placeholder discreto.
 */
@Composable
private fun DemoScript(turns: List<StageTurn>, fontSp: Int) {
    if (turns.isEmpty()) {
        Text(
            "Diálogo ainda não escrito — edite no editor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val speakers = turns.map { it.speaker }.distinct()
    // Block 2: no tema claro, os papéis usam as variantes escuras (contraste).
    val darkTheme = com.bettertalker.app.ui.theme.relativeLuminance(
        MaterialTheme.colorScheme.surface
    ) < 0.5
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turns.forEach { turn ->
            val index = speakers.indexOf(turn.speaker).coerceAtLeast(0)
            val alignRight = index % 2 == 0
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            turn.speaker,
                            style = MaterialTheme.typography.labelSmall,
                            color = stageSpeakerColor(index, dark = darkTheme),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            turn.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSp.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TribunaField(
    label: String,
    value: String,
    fontSp: Int,
    onChange: (String) -> Unit,
    bold: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSp.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------- #20-fix-c: export (PDF / HTML / Copiar) ----------

private fun tribunaCardsData(
    cards: List<TribunaViewModel.TribunaCardUi>,
): List<com.bettertalker.app.data.export.TribunaCardData> = cards.map {
    com.bettertalker.app.data.export.TribunaCardData(
        order = it.order,
        sectionTitle = it.sectionTitle,
        minutes = it.minutes,
        capsule = it.capsule,
    )
}

private fun exportTitle(noteTitle: String): String =
    "Tribuna " + noteTitle.ifBlank { "" }

/** PDF via PrintManager (mesmo caminho do Ensaio/export do editor). */
private fun exportTribunaPdf(
    ctx: android.content.Context,
    noteTitle: String,
    cards: List<TribunaViewModel.TribunaCardUi>,
) {
    val html = com.bettertalker.app.data.export.buildTribunaHtml(noteTitle, tribunaCardsData(cards))
    com.bettertalker.app.data.export.PdfExportManager.printDiscurso(ctx, exportTitle(noteTitle), html)
}

/** HTML no cache + Share Intent (mesmo padrão do export do editor). */
private fun shareTribunaHtml(
    ctx: android.content.Context,
    noteTitle: String,
    cards: List<TribunaViewModel.TribunaCardUi>,
) {
    val html = com.bettertalker.app.data.export.buildTribunaHtml(noteTitle, tribunaCardsData(cards))
    val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
    val base = com.bettertalker.app.data.export.exportFileBaseName(exportTitle(noteTitle))
    val file = java.io.File(dir, "$base.html")
    file.writeText(html, Charsets.UTF_8)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        ctx, "${ctx.packageName}.fileprovider", file
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Tribuna")
        putExtra(android.content.Intent.EXTRA_TITLE, exportTitle(noteTitle))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Compartilhar Tribuna"))
}

/** Texto puro da Tribuna (só cards com cápsula). */
private fun copyTribunaText(
    ctx: android.content.Context,
    cards: List<TribunaViewModel.TribunaCardUi>,
) {
    val text = buildString {
        cards.filter { it.capsule != null }.forEach { c ->
            val cap = c.capsule!!
            append("${c.order + 1}. ${c.sectionTitle} — ${c.minutes} min\n")
            append("Ideia: ${cap.mainIdea}\n")
            cap.bibleRef?.takeIf { it.isNotBlank() }?.let { append("Texto-chave: $it\n") }
            append("Âncora: ${cap.anchorPhrase}\n")
            cap.transition?.takeIf { it.isNotBlank() }?.let { append("Transição: $it\n") }
            append("\n")
        }
    }.trim()
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Tribuna", text))
    android.widget.Toast.makeText(ctx, "Tribuna copiada", android.widget.Toast.LENGTH_SHORT).show()
}
