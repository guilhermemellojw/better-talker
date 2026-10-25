package com.bettertalker.app.ui.copilot

import com.bettertalker.app.ui.theme.CONTENT_BLOCK_ALPHA

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.bettertalker.app.ui.components.parseBlocks

/** MIMEs aceitos para importar esboço (DOCX, PDF, RTF, JWPUB genérico). */
val OUTLINE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/pdf",
    "application/rtf",
    "text/rtf",
    "application/octet-stream"
)

/**
 * Barra de prompt única (estilo chat, box-less): campo sem contorno,
 * + à esquerda, voz e enviar à direita (T6: botão "Ferramentas" removido —
 * ações realocadas no +, nos chips e no ⋯ das respostas).
 *
 * Teclado: Enter = quebra de linha (o usuário estrutura o texto no campo
 * multilinha, maxLines = 5); o envio é SÓ pelo botão de enviar. O IME não
 * tem ação de send — evita enviar sem querer no meio da digitação.
 */
/** #17-fix: rótulo do chip de pensamento (puro/testável). O ícone já indica o modo. */
internal fun thinkingChipLabel(mode: ThinkingMode): String =
    if (mode == ThinkingMode.DEEP) "Profundo" else "Rápido"

@Composable
fun ChatPromptBar(
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onAttach: () -> Unit,
    voiceState: VoiceInputState = VoiceInputState.Idle,
    voiceSeconds: Int = 0,
    onVoice: () -> Unit = {},
    onVoiceFinish: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    thinkingMode: ThinkingMode = ThinkingMode.DEFAULT,
    onToggleThinking: () -> Unit = {},
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
                placeholder = { Text("Cole um esboço ou pergunte…") },
                modifier = Modifier.fillMaxWidth()
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    )
                    .semantics { contentDescription = "Mensagem para o Copilot" },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                // Enter = nova linha (estruturação); envio só pelo botão.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Default.Add, "Anexar esboço")
                }
                Spacer(Modifier.weight(1f))
                // #17-fix: chip Rápido/Profundo (per-session) — antes da voz,
                // à direita do campo; rotas estruturadas ignoram o modo.
                AssistChip(
                    onClick = onToggleThinking,
                    label = { Text(thinkingChipLabel(thinkingMode)) },
                    leadingIcon = {
                        Icon(
                            if (thinkingMode == ThinkingMode.DEEP) Icons.Default.Psychology
                            else Icons.Default.Bolt,
                            null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                // T3 (voz): microfone com tap-to-toggle. Primário quando o
                // campo está vazio; secundário quando já há texto.
                when (voiceState) {
                    VoiceInputState.Listening -> {
                        Text(
                            voiceElapsedLabel(voiceSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            // P2 (contraste): secondary passa AA nos dois temas.
                            color = MaterialTheme.colorScheme.secondary,
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
                // #4 (UX S): durante a geração o botão vira "Parar" (cancela
                // o job; o parcial some e o composer destrava no finally).
                if (busy) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    ) { Icon(Icons.Default.Stop, "Parar geração") }
                } else {
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

/** T1 (chat minimalista) — removida: redundante com o banner de setup. */

/**
 * Ações locais do app (o roteador `ChatIntent` as executa). São diferentes dos
 * atalhos da F15: aqui o texto é o comando, não uma pergunta ao Copilot.
 *
 * T6 (Fase 2.1): recebe os atalhos que viviam no sheet "Ferramentas" —
 * "Resumir", "Quais refs faltam?", "Mostre as seções", "Refs do esboço" e
 * "Sincronizar esboço" continuam a um toque (chips da conversa vazia).
 */
fun localSuggestionTexts(hasOutline: Boolean): List<String> = if (hasOutline) {
    listOf(
        "Ideias para a introdução",
        "Exemplo de introdução",
        "Desenvolva a introdução",
        "Resumir",
        "Quais refs faltam?",
        "Mostre as seções",
        "Refs do esboço",
        "Sincronizar esboço"
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
    onPrefill: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // T1 (P2 estético): háptica leve nos chips de ação.
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // #8f: porta das Necessidades Locais — pré-preenche o composer (o
        // tema fica com o usuário; nada é enviado sem ele completar).
        item {
            AssistChip(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onPrefill("Discurso curto para as necessidades locais sobre ")
                },
                label = { Text("Necessidades Locais") },
                modifier = Modifier.semantics {
                    contentDescription = "Necessidades Locais — preencher mensagem"
                },
            )
        }
        if (localActions.isEmpty()) {
            item {
                AssistChip(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onAttach()
                    },
                    label = { Text("Anexar esboço") },
                )
            }
        }
        // Atalhos conversacionais primeiro: são o caminho principal da F15.
        items(quickActions) { qa ->
            AssistChip(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onPick(qa.message)
                },
                label = { Text(qa.label) },
                // Rótulo só para leitor de tela: o clique envia a mensagem.
                modifier = Modifier.semantics {
                    contentDescription = qa.label + " — enviar mensagem"
                }
            )
        }
        items(localActions) { text ->
            AssistChip(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onPick(text)
                },
                label = { Text(text) },
            )
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
                icon = Icons.Default.AutoAwesome,
                title = "Colar material",
                desc = "Revise a prévia antes de aplicar",
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
            Text(
                "Dica: colar no campo de mensagem abre o modo conversar. " +
                    "Para criar cards, use \"Colar material\".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            ByodNotice()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * T2 (Fase 2) — prévia da estrutura ("insira tudo na nota") antes de
 * persistir: títulos + primeiras linhas, com Substituir/Adicionar quando
 * a nota já tem seções. Puro o parse; o clique delega ao VM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructurePreviewSheet(
    preview: CopilotViewModel.StructurePreview,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Inserir estrutura na nota",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${preview.sections.size} partes detectadas. Revise antes de inserir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            preview.sections.forEach { s ->
                val roleLabel = when (s.role) {
                    com.bettertalker.app.data.export.StructureRole.INTRO -> "Introdução"
                    com.bettertalker.app.data.export.StructureRole.CONCLUSION -> "Conclusão"
                    else -> "Ponto"
                }
                Text(
                    "$roleLabel — ${s.title}",
                    style = MaterialTheme.typography.titleSmall,
                )
                val firstLines = com.bettertalker.app.ui.editor.stripHtml(s.contentHtml)
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString(" ")
                    .take(140)
                if (firstLines.isNotBlank()) {
                    Text(
                        firstLines,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (preview.hasExisting) {
                Text(
                    "A nota já tem seções. Substituir tudo ou adicionar ao final?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReplace) { Text("Substituir") }
                    TextButton(onClick = onAppend) { Text("Adicionar") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReplace) { Text("Inserir na nota") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AttachOption(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {    androidx.compose.material3.TextButton(onClick = onClick) {
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

// T6 (Fase 2.1): `ToolsSheet` removido do composer. Realocação sem perda:
// - "Resumir", "Ideias", "Dar exemplo", "Desenvolver", "Verificar referências",
//   "Refs do esboço", "Mostrar seções" e "Sincronizar" → chips de atalho
//   (`localSuggestionTexts`, conversa vazia) e/ou texto livre no composer.
// - "Limpar conversa" → menu ⋯ de cada resposta (com confirmação).
// - Importar/colar/baixar/acervo já viviam no sheet do `+` (AttachSheet).

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
                    TextButton(onClick = onPaste) { Text("Analisar material") }
                }
            }
            if (readiness.hasMissingRefs && readiness.missingSources.isNotEmpty()) {
                Text(
                    "Faltam fontes: ${readiness.missingSources.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Abra o editor para ver o checklist de fontes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
 * #26-fix — cartão de proposta de TEXTO no estilo `<texto-do-card>`: borda,
 * header com ⋯ (Substituir a seção / Verificar / Detalhes / Desfazer /
 * Fechar), preview FORMATADO (mesmo render das mensagens: títulos, listas,
 * sub-listas ○) e botão primário adaptativo ("Usar este texto" com seção
 * vazia; "Adicionar ao final" com conteúdo). Sem ANTES (a seção já está no
 * editor) e sem truncamento silencioso. Nunca insere direto.
 */
@Composable
fun ProposalCard(
    ui: CopilotViewModel.ProposalUi,
    vm: CopilotViewModel,
    sectionContentHtml: String,
    modifier: Modifier = Modifier,
) {
    val applied = ui.applied
    val hasContent = sectionContentHtml.isNotBlank()
    val shape = MaterialTheme.shapes.medium
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember(ui.verification) { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CONTENT_BLOCK_ALPHA))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(12.dp),
        ) {
            // Header: rótulo + ⋯ (ações avançadas).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Texto proposto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.MoreVert, "Opções", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // #26-fix: substituir é a ação AVANÇADA — o padrão do
                        // botão primário é ADICIONAR (não destrutivo).
                        if (hasContent && !applied) {
                            DropdownMenuItem(
                                text = { Text("Substituir a seção") },
                                onClick = {
                                    menuOpen = false
                                    vm.acceptProposal(forceReplace = true)
                                },
                            )
                        }
                        if (!applied) {
                            DropdownMenuItem(
                                text = { Text(if (ui.verifying) "Verificando..." else "Verificar") },
                                enabled = !ui.verifying,
                                onClick = {
                                    menuOpen = false
                                    vm.verifyProposal()
                                },
                            )
                        }
                        if (ui.verification != null) {
                            DropdownMenuItem(
                                text = { Text("Ver detalhes de fidelidade") },
                                onClick = {
                                    menuOpen = false
                                    showDetails = true
                                },
                            )
                        }
                        if (applied && vm.canUndoProposal) {
                            DropdownMenuItem(
                                text = { Text("Desfazer") },
                                onClick = {
                                    menuOpen = false
                                    vm.undoProposal()
                                },
                            )
                        }
                        if (applied) {
                            DropdownMenuItem(
                                text = { Text("Fechar") },
                                onClick = {
                                    menuOpen = false
                                    vm.rejectProposal()
                                },
                            )
                        }
                    }
                }
            }
            ui.proposal.explanation?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(6.dp))

            // Preview FORMATADO — mesmo render dos blocos das mensagens.
            // Sem truncamento silencioso: o texto inteiro é renderizado.
            val afterFull = remember(ui.proposal, ui.focusText) {
                com.bettertalker.app.data.edit.renderAfterText(ui.proposal, ui.focusText)
            }
            val hasSuggestion = remember(afterFull) { stripSuggestionTags(afterFull).hasSuggestion }
            if (hasSuggestion) {
                Text(
                    "💡 Sugestão criativa",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(4.dp))
            }
            val previewBlocks = remember(afterFull) { cleanBlocks(parseBlocks(afterFull)) }
            previewBlocks.forEach { block ->
                RenderBlock(block, mark = null, onUseInCard = null)
            }
            ui.notice?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            if (!applied) {
                // A decisão fica FORA do ⋯ (é o caminho principal).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { vm.acceptProposal() },
                        modifier = Modifier.weight(1f),
                    ) { Text(proposalButtonLabel(sectionContentHtml)) }
                    OutlinedButton(
                        onClick = { vm.rejectProposal() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Manter atual") }
                }
            } else {
                Text(
                    "Aplicado ao card.",
                    style = MaterialTheme.typography.labelSmall,
                    // texto: primary (amarelo) dava 1,55:1 no light
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }

    // Detalhes de fidelidade (no ⋯ desde o #26-fix).
    val verification = ui.verification
    if (showDetails && verification != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Fidelidade do texto") },
            text = {
                Column {
                    Text(
                        "✓ ${verification.summary.supported} · ⚠ ${verification.summary.partial} · " +
                            "? ${verification.summary.insufficient} · 💡 ${verification.summary.creative}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    verification.claims.take(5).forEach { c ->
                        val glyph = when (c.status) {
                            com.bettertalker.app.data.verify.SupportStatus.SUPPORTED -> "✓"
                            com.bettertalker.app.data.verify.SupportStatus.PARTIALLY_SUPPORTED -> "⚠"
                            com.bettertalker.app.data.verify.SupportStatus.INSUFFICIENT -> "?"
                            com.bettertalker.app.data.verify.SupportStatus.CREATIVE -> "💡"
                        }
                        Text(
                            "$glyph “${c.claim.text.take(80)}” — ${c.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Fechar") }
            },
        )
    }
}
