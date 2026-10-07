package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bettertalker.app.data.copilot.EvidenceMeta
import com.bettertalker.app.data.copilot.QuickAction
import com.bettertalker.app.data.copilot.glyph
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.voice.VoiceInputState
import com.bettertalker.app.ui.components.ByodNotice

/** MIMEs aceitos para importar esboço (DOCX, PDF, RTF, JWPUB genérico). */
val OUTLINE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/pdf",
    "application/rtf",
    "text/rtf",
    "application/octet-stream"
)

/**
 * Barra de prompt única (estilo Gemini, box-less): campo sem contorno,
 * + e Ferramentas à esquerda, enviar em círculo à direita.
 *
 * Teclado: `ImeAction.Send` envia; a quebra de linha do usuário é preservada
 * pelo campo multilinha (maxLines = 5). O botão de ação do IME substitui o
 * Enter/Shift+Enter da web — é a equivalência mais natural do Android (§8 F15).
 */
@Composable
fun ChatPromptBar(
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onTools: () -> Unit,
    voiceState: VoiceInputState = VoiceInputState.Idle,
    voiceSeconds: Int = 0,
    onVoice: () -> Unit = {},
    onVoiceFinish: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    // Vazio nunca envia; e o bloqueio some quando o turno termina — inclusive
    // depois de erro (§24 F15).
    val canSend = input.isNotBlank() && !busy
    Surface(
        modifier = modifier.fillMaxWidth().imePadding(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("Digite uma mensagem...") },
                modifier = Modifier.fillMaxWidth()
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    ),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Default.Add, "Anexar esboço")
                }
                IconButton(onClick = onTools) {
                    Icon(Icons.Default.Tune, "Ferramentas")
                }
                Spacer(Modifier.weight(1f))
                // T3 (voz): microfone com tap-to-toggle. Primário quando o
                // campo está vazio; secundário quando já há texto.
                when (voiceState) {
                    VoiceInputState.Listening -> {
                        Text(
                            voiceElapsedLabel(voiceSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                        IconButton(onClick = onVoiceCancel) {
                            Icon(Icons.Default.Close, "Cancelar gravação")
                        }
                        FilledIconButton(onClick = onVoiceFinish) {
                            Icon(Icons.Default.Stop, "Concluir e transcrever")
                        }
                    }
                    VoiceInputState.Processing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    else -> {
                        val desc = voiceMicDescription(voiceState)
                        if (canSend) {
                            IconButton(onClick = onVoice) {
                                Icon(Icons.Default.Mic, desc)
                            }
                        } else {
                            FilledIconButton(onClick = onVoice) {
                                Icon(Icons.Default.Mic, desc)
                            }
                        }
                    }
                }
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            }
        }
    }
}

/** T3 (voz): rótulo do cronômetro da escuta ("Ouvindo… 0:07"). Puro/testável. */
internal fun voiceElapsedLabel(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "Ouvindo… ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** T3 (voz): descrição acessível do microfone por estado. Puro/testável. */
internal fun voiceMicDescription(state: VoiceInputState): String = when (state) {
    VoiceInputState.Idle -> "Falar"
    VoiceInputState.Listening -> "Ouvindo — conclua ou cancele"
    VoiceInputState.Processing -> "Transcrevendo"
    is VoiceInputState.Result, is VoiceInputState.Error -> "Falar"
}

/**
 * "Fontes e apoio" — proveniência recolhível (§14 F15). Cabeçalho mostra só a
 * contagem por trilho; os metadados vêm dos trechos reais, nunca inventados.
 */
@Composable
fun ProvenanceDisclosure(
    evidence: List<EvidenceMeta>,
    summary: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics {
                contentDescription = if (expanded)
                    "Ocultar fontes e apoio" else "Mostrar fontes e apoio"
            }
        ) {
            Text("Fontes e apoio ▸", style = MaterialTheme.typography.labelMedium)
            Text(
                " $summary",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        if (expanded) {
            evidence.take(5).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        e.track.glyph(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            e.reference,
                            style = MaterialTheme.typography.bodySmall
                        )
                        // Categoria de técnica quando existir; nada inventado.
                        e.category?.takeIf { it != TrainingCategory.UNKNOWN }?.let { c ->
                            Text(
                                "técnica: ${c.serial}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Erro do turno: texto humano já pronto, com "Fechar" e "Tentar novamente".
 * Nada de HTTP, stack ou nome de exceção (§23 F15).
 */
@Composable
fun ChatErrorRow(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Erro é lido ao aparecer, sem sequestrar o foco do campo.
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text("Tentar novamente") }
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    }
}

/** Legenda discreta sob o prompt. */
@Composable
fun ChatCaption(modifier: Modifier = Modifier) {
    Text(
        "100% offline • confira as citações",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(top = 2.dp)
    )
}

/**
 * Ações locais do app (o roteador `ChatIntent` as executa). São diferentes dos
 * atalhos da F15: aqui o texto é o comando, não uma pergunta ao Copilot.
 */
fun localSuggestionTexts(hasOutline: Boolean): List<String> = if (hasOutline) {
    listOf(
        "Ideias para a introdução",
        "Exemplo de introdução",
        "Desenvolva a introdução",
        "Resumir",
        "Quais refs faltam?",
        "Mostre as seções"
    )
} else {
    listOf("Resumir", "Quais refs faltam?")
}

/**
 * Atalhos da F15. Cada um vira uma mensagem de usuário e entra no MESMO
 * pipeline do chat livre — não existe `quickAction -> prompt especial`.
 * Somem depois da primeira mensagem do usuário.
 */
@Composable
fun ChatQuickActions(
    quickActions: List<QuickAction>,
    localActions: List<String>,
    onPick: (String) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (localActions.isEmpty()) {
            item { AssistChip(onClick = onAttach, label = { Text("Anexar esboço") }) }
        }
        // Atalhos conversacionais primeiro: são o caminho principal da F15.
        items(quickActions) { qa ->
            AssistChip(
                onClick = { onPick(qa.message) },
                label = { Text(qa.label) },
                // Rótulo só para leitor de tela: o clique envia a mensagem.
                modifier = Modifier.semantics {
                    contentDescription = qa.label + " — enviar mensagem"
                }
            )
        }
        items(localActions) { text ->
            AssistChip(onClick = { onPick(text) }, label = { Text(text) })
        }
    }
}

/** Sheet do + : importar, colar, anexar do acervo ou baixar publicações. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onPublications: () -> Unit,
    onAttachFromLibrary: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AttachOption(
                icon = Icons.Default.UploadFile,
                title = "Importar esboço",
                desc = "DOCX, PDF ou JWPUB",
                onClick = onImport
            )
            AttachOption(
                icon = Icons.Default.ContentPaste,
                title = "Colar texto",
                desc = "Ex: ponto da apostila",
                onClick = onPaste
            )
            AttachOption(
                icon = Icons.Default.MenuBook,
                title = "Baixar publicações",
                desc = "Baixe do site oficial (BYOD)",
                onClick = onPublications
            )
            AttachOption(
                icon = Icons.Default.LibraryBooks,
                title = "Anexar do acervo",
                desc = "Vincule uma publicação já importada",
                onClick = onAttachFromLibrary
            )
            Spacer(Modifier.height(8.dp))
            ByodNotice()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private data class ToolItem(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val send: String?
)

/** Sheet Ferramentas: ações do Copilot + conversa (era o ⋮ da top bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit
) {
    val tools = listOf(
        ToolItem(Icons.Default.Summarize, "Resumir", "Resumo da nota com citações", "Resumir"),
        ToolItem(Icons.Default.Lightbulb, "Ideias", "Gerar para uma seção", "Ideias"),
        ToolItem(Icons.Default.FormatQuote, "Dar exemplo",
            "Modelo pronto seguindo as publicações", "Exemplo"),
        ToolItem(Icons.Default.Edit, "Desenvolver",
            "Rascunho redigido da seção", "Desenvolva"),
        ToolItem(Icons.Default.FactCheck, "Verificar referências",
            "Publicações citadas na nota", "Quais referências faltam?"),
        ToolItem(Icons.Default.Download, "Refs do esboço",
            "Baixar o que o esboço cita", "refs do esboço"),
        ToolItem(Icons.Default.FormatListBulleted, "Mostrar seções",
            "Listar e gerar por seção", "Mostre as seções"),
        ToolItem(Icons.Default.Sync, "Sincronizar",
            "Atualizar esboço pelo texto da nota", "Sincronizar esboço")
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Ferramentas", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            tools.forEach { t ->
                androidx.compose.material3.TextButton(onClick = { onSend(t.send!!) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Conversa", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = onClear) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, null,
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Limpar conversa", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Onboarding F2a: banner de setup do Copilot (fora do scroll da conversa).
 * Aparece quando a prontidão está incompleta; some quando completa ou
 * dispensado (o dismiss é volátil — volta se o estado incompletar de novo).
 */
@Composable
fun SetupBanner(
    readiness: com.bettertalker.app.ui.editor.NoteReadiness,
    onImportOutline: () -> Unit,
    onPaste: () -> Unit,
    onDownloadBases: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Prepare o Copilot",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Dispensar")
                }
            }
            if (!readiness.hasOutline && !readiness.hasSections) {
                Text(
                    "Falta vincular o esboço do discurso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onImportOutline) { Text("Importar esboço") }
                    TextButton(onClick = onPaste) { Text("Colar texto") }
                }
            }
            if (readiness.hasMissingBases) {
                Text(
                    "Faltam publicações de apoio para baixar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(onClick = onDownloadBases) { Text("Baixar publicações") }
            }
        }
    }
}

/**
 * Fase 18 §21 — cartão de proposta F5: ANTES/DEPOIS, Verificar (F6),
 * Aceitar/Rejeitar, Desfazer após aceite. Nunca insere direto.
 */
@Composable
fun ProposalCard(
    ui: CopilotViewModel.ProposalUi,
    vm: CopilotViewModel,
    modifier: Modifier = Modifier
) {
    val afterFull = remember(ui.proposal, ui.focusText) {
        com.bettertalker.app.data.edit.renderAfterText(ui.proposal, ui.focusText)
    }
    // T3 — tags 〈sugestão〉 somem do texto exibido; badge sinaliza criação.
    val after = remember(afterFull) { stripSuggestionTags(afterFull) }
    val before = remember(ui.focusText) { stripSuggestionTags(ui.focusText) }
    Column(
        modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text("Proposta", style = MaterialTheme.typography.titleSmall)
        ui.proposal.explanation?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "ANTES" + if (before.hasSuggestion) " · 💡 Sugestão criativa" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(before.text.take(600), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "DEPOIS" + if (after.hasSuggestion) " · 💡 Sugestão criativa" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(after.text.take(1200), style = MaterialTheme.typography.bodySmall)
        ui.verification?.let { v ->
            Spacer(Modifier.height(4.dp))
            Text(
                "✓ ${v.summary.supported} · ⚠ ${v.summary.partial} · " +
                    "? ${v.summary.insufficient} · 💡 ${v.summary.creative}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Ocultar detalhes" else "Ver detalhes")
            }
            if (expanded) {
                v.claims.take(5).forEach { c ->
                    val glyph = when (c.status) {
                        com.bettertalker.app.data.verify.SupportStatus.SUPPORTED -> "✓"
                        com.bettertalker.app.data.verify.SupportStatus.PARTIALLY_SUPPORTED -> "⚠"
                        com.bettertalker.app.data.verify.SupportStatus.INSUFFICIENT -> "?"
                        com.bettertalker.app.data.verify.SupportStatus.CREATIVE -> "💡"
                    }
                    Text(
                        "$glyph “${c.claim.text.take(80)}” — ${c.reason}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        ui.notice?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ui.applied) {
                TextButton(
                    onClick = { vm.verifyProposal() },
                    enabled = !ui.verifying
                ) { Text(if (ui.verifying) "Verificando..." else "Verificar") }
                TextButton(onClick = { vm.acceptProposal() }) { Text("Aceitar") }
                TextButton(onClick = { vm.rejectProposal() }) { Text("Rejeitar") }
            } else {
                if (vm.canUndoProposal) {
                    TextButton(onClick = { vm.undoProposal() }) { Text("Desfazer") }
                }
                TextButton(onClick = { vm.rejectProposal() }) { Text("Fechar") }
            }
        }
    }
}
