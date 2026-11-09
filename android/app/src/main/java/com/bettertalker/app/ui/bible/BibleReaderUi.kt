package com.bettertalker.app.ui.bible

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.domain.bible.BiblePassage
import com.bettertalker.app.domain.bible.BiblePassageStatus
import com.bettertalker.app.domain.bible.BibleVerse
import com.bettertalker.app.ui.stage.StageAccent
import com.bettertalker.app.ui.stage.StageBg
import com.bettertalker.app.ui.stage.StageDim
import com.bettertalker.app.ui.stage.StageFg

/**
 * v5.2 — leitor TNM na Tribuna (bottom sheet). Mesmo conteúdo do overlay do
 * Palco; fecha sem alterar o estado da tela de origem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleReaderSheet(
    vm: BibleReaderViewModel,
    onOpenLibrary: () -> Unit,
) {
    val state by vm.state.collectAsState()
    if (!state.visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = vm::close,
        sheetState = sheetState,
    ) {
        BibleReaderContent(
            state = state,
            onSpeak = vm::toggleSpeak,
            onClose = vm::close,
            onOpenLibrary = onOpenLibrary,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        )
    }
}

/**
 * v5.2 — overlay de tela cheia do Palco. O cronômetro continua: abrir/fechar
 * o leitor não pausa a apresentação.
 */
@Composable
fun BibleReaderOverlay(
    vm: BibleReaderViewModel,
    onOpenLibrary: () -> Unit,
) {
    val state by vm.state.collectAsState()
    if (!state.visible) return
    // Dialog full-screen: bloqueia qualquer toque/drag de atravessar para o
    // Palco (Encerrar, Anterior/Próxima e swipe horizontal). Back fecha pelo
    // onDismissRequest; o cronômetro do Palco continua.
    Dialog(
        onDismissRequest = vm::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = StageBg) {
            BibleReaderContent(
                state = state,
                onSpeak = vm::toggleSpeak,
                onClose = vm::close,
                onOpenLibrary = onOpenLibrary,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                large = true,
                background = StageBg,
                foreground = StageFg,
                accent = StageAccent,
                dim = StageDim,
            )
        }
    }
}

@Composable
private fun BibleReaderContent(
    state: BibleReaderUiState,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    background: Color? = null,
    foreground: Color? = null,
    accent: Color? = null,
    dim: Color? = null,
) {
    val ctx = LocalContext.current
    val bg = background ?: MaterialTheme.colorScheme.surface
    val fg = foreground ?: MaterialTheme.colorScheme.onSurface
    val ac = accent ?: MaterialTheme.colorScheme.primary
    val dm = dim ?: MaterialTheme.colorScheme.onSurfaceVariant
    val passage = state.passage
    val titleSize = if (large) 28.sp else 22.sp
    val verseSize = if (large) 22.sp else 18.sp
    val lineHeight = if (large) 34.sp else 28.sp

    Surface(modifier, color = bg) {
        Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        passage?.displayRef?.ifBlank { "Leitura" } ?: "Leitura",
                        color = fg,
                        fontSize = titleSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (titleSize.value * 1.2f).sp,
                    )
                    if (passage != null && passage.sourceLabel.isNotBlank()) {
                        Text(
                            passage.sourceLabel,
                            color = dm,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Fechar leitura", tint = fg)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = dm.copy(alpha = 0.35f))
            when {
                state.loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = ac, strokeWidth = 2.dp)
                    Text("Buscando versículos…", color = dm)
                }
                state.error != null -> Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                passage != null -> BiblePassageBody(
                    passage = passage,
                    accent = ac,
                    dim = dm,
                    fg = fg,
                    verseSize = verseSize,
                    lineHeight = lineHeight,
                    onOpenLibrary = onOpenLibrary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (passage != null && passage.verses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSpeak) {
                        Text(if (state.speaking) "■ Parar" else "▶ Ouvir", color = ac)
                    }
                    TextButton(
                        onClick = {
                            if (passage.copyText.isNotBlank()) {
                                copyToClipboard(ctx, passage)
                            }
                        },
                    ) { Text("Copiar", color = ac) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Fechar", color = dm) }
                }
            }
        }
    }
}

@Composable
private fun BiblePassageBody(
    passage: BiblePassage,
    accent: Color,
    dim: Color,
    fg: Color,
    verseSize: TextUnit,
    lineHeight: TextUnit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        val status = when (passage.status) {
            BiblePassageStatus.RESOLVED ->
                if (passage.chapterOnly) "Capítulo completo" else "Texto completo"
            BiblePassageStatus.PARTIAL -> passage.foundLabel
            BiblePassageStatus.UNRESOLVED -> "Nenhum versículo encontrado no acervo."
            BiblePassageStatus.MISSING_CORPUS ->
                if (passage.needsLibrary) "TNM não está no acervo." else passage.warning
        }
        if (status != null) {
            Text(
                status,
                color = if (passage.status == BiblePassageStatus.MISSING_CORPUS) {
                    MaterialTheme.colorScheme.error
                } else {
                    dim
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        passage.warning
            ?.takeIf { it.trimEnd('.', ' ') != status?.trimEnd('.', ' ') }
            ?.let { Text(it, color = dim, style = MaterialTheme.typography.bodySmall) }
        if (passage.needsLibrary) {
            Button(
                onClick = onOpenLibrary,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Abrir Biblioteca") }
        }
        if (passage.verses.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                passage.groups.forEach { group ->
                    item(key = "cap-${group.book}-${group.chapter}") {
                        Text(
                            "${group.book} ${group.chapter}",
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(group.verses, key = { it.ref }) { verse ->
                        VerseRow(verse, fg, accent, verseSize, lineHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerseRow(
    verse: BibleVerse,
    fg: Color,
    accent: Color,
    verseSize: TextUnit,
    lineHeight: TextUnit,
) {
    SelectionContainer {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(
                verse.verse.toString(),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(30.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                verse.text,
                color = fg,
                fontSize = verseSize,
                lineHeight = lineHeight,
            )
        }
    }
}

private fun copyToClipboard(ctx: Context, passage: BiblePassage) {
    val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(
        ClipData.newPlainText(passage.displayRef, passage.copyText)
    )
    Toast.makeText(ctx, "Texto bíblico copiado", Toast.LENGTH_SHORT).show()
}
