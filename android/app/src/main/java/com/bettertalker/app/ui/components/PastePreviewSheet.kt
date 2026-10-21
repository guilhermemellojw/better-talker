package com.bettertalker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.util.PasteKind
import com.bettertalker.app.data.util.PastePlan
import com.bettertalker.app.data.util.PastePreview
import com.bettertalker.app.data.util.label
import com.bettertalker.app.data.util.pastePreviewOf
import com.bettertalker.app.data.util.planPaste
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.delay

/** Onde a colagem vai ser aplicada (decisão da tela, não do parser). */
enum class PasteDestination { NEW_NOTE, CURRENT_NOTE }

/**
 * Contexto da folha unificada de colar. A Home usa [NEW_NOTE]; Editor/Chat
 * usam [CURRENT_NOTE]. O B mínimo não permite trocar o destino (B+).
 */
data class PasteSheetRequest(
    val initialText: String = "",
    val defaultDestination: PasteDestination,
    val currentNoteTitle: String? = null,
    val currentNoteHasSections: Boolean = false,
    val folderName: String? = null,
)

/**
 * Fase B — uma única porta de colar material.
 *
 * Prévia ao vivo de [planPaste] (debounce 200ms), texto editável, destino
 * contextual, rejeição inline e "Aplicar" desabilitado quando o material não
 * gera cards. O caller aplica pelo método existente da tela.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastePreviewSheet(
    request: PasteSheetRequest,
    onDismiss: () -> Unit,
    onApply: (PastePlan, String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // Snapshot da área de transferência no momento em que a folha abre.
    val initialText = remember {
        request.initialText.ifBlank { clipboard.getText()?.text?.trim().orEmpty() }
    }
    var text by remember { mutableStateOf(initialText) }
    var plan by remember { mutableStateOf(planPaste(initialText)) }
    var clipboardEmpty by remember { mutableStateOf(false) }

    // Live preview: 200ms após a última edição (não bloqueia o Apply).
    LaunchedEffect(text) {
        if (text == initialText) return@LaunchedEffect
        delay(200)
        plan = planPaste(text)
    }

    val preview = remember(plan) { pastePreviewOf(plan) }
    val currentHasSections = request.defaultDestination == PasteDestination.CURRENT_NOTE &&
        request.currentNoteHasSections
    val reviewInChat = request.defaultDestination == PasteDestination.CURRENT_NOTE &&
        (plan.kind is PasteKind.S34 || plan.kind == PasteKind.FreeText)
    val cardsLabel = if (currentHasSections && plan.kind is PasteKind.VmTalk) {
        val bodyCount = plan.sections.count { it.role == SectionRole.BODY }
        val noun = if (bodyCount == 1) "card" else "cards"
        val bodyNoun = if (bodyCount == 1) "corpo" else "corpos"
        "$bodyCount $noun ($bodyNoun da parte — a nota já tem estrutura)"
    } else {
        preview.cardsLabel
    }
    val canApply = text.isNotBlank() && preview.canApply
    val applyLabel = when {
        reviewInChat -> "Revisar no chat"
        request.defaultDestination == PasteDestination.NEW_NOTE -> "Criar nota"
        else -> "Aplicar"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Colar material",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Revise o que será criado. A detecção é automática.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Texto") },
                placeholder = { Text("Cole aqui o material da reunião…") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val clip = clipboard.getText()?.text?.trim().orEmpty()
                        if (clip.isBlank()) {
                            clipboardEmpty = true
                        } else {
                            clipboardEmpty = false
                            text = clip
                            plan = planPaste(clip)
                        }
                    },
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Colar da área de transferência")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${text.length} caracteres",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (clipboardEmpty) {
                Text(
                    "Nada para colar na área de transferência.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            DetectionCard(
                plan = plan,
                preview = preview,
                cardsLabel = cardsLabel,
                reviewInChat = reviewInChat,
            )
            DestinationCard(request)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canApply,
                    onClick = {
                        // Recalcula na hora do clique: o debounce da prévia não
                        // pode aplicar um plano de um texto anterior.
                        val latest = planPaste(text)
                        plan = latest
                        if (text.isNotBlank() && latest.kind !is PasteKind.VmRejected) {
                            onApply(latest, text)
                        }
                    },
                ) {
                    Text(applyLabel)
                }
            }
        }
    }
}

@Composable
private fun DetectionCard(
    plan: PastePlan,
    preview: PastePreview,
    cardsLabel: String,
    reviewInChat: Boolean,
) {
    val rejected = plan.kind is PasteKind.VmRejected
    val container = when {
        rejected -> MaterialTheme.colorScheme.errorContainer
        plan.kind is PasteKind.S34 -> MaterialTheme.colorScheme.tertiaryContainer
        plan.kind == PasteKind.FreeText -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        rejected -> MaterialTheme.colorScheme.onErrorContainer
        plan.kind is PasteKind.S34 -> MaterialTheme.colorScheme.onTertiaryContainer
        plan.kind == PasteKind.FreeText -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    var showIgnored by remember(preview) { mutableStateOf(false) }

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = content.copy(alpha = 0.12f),
                    contentColor = content,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        preview.kindLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            preview.detailLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Cards: $cardsLabel",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (reviewInChat) {
                Text(
                    "Este tipo será revisado no chat antes de aplicar.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (rejected) {
                preview.rejectionMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            preview.ignoredLabel?.let { label ->
                TextButton(
                    onClick = { showIgnored = !showIgnored },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Icon(
                        if (showIgnored) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(showIgnored) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        preview.ignoredParts.forEach { part ->
                            Text(
                                "• Parte ${part.partNumber} · ${part.nature.label()}: ${part.title}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationCard(request: PasteSheetRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Destino", style = MaterialTheme.typography.titleSmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                if (request.defaultDestination == PasteDestination.NEW_NOTE) {
                    "Nova nota"
                } else {
                    "Nota atual"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (request.defaultDestination == PasteDestination.NEW_NOTE) {
            Text(
                "Pasta: ${request.folderName ?: "Sem pasta"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            request.currentNoteTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Nota: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (request.currentNoteHasSections) {
                Text(
                    "A nota já tem estrutura; será anexado só o corpo da parte.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
