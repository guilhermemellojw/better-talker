package com.bettertalker.app.ui.tribuna

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.domain.speech.TribunaDetail
import com.bettertalker.app.domain.speech.TribunaMapStatus
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.tribunaMapQuality
import com.bettertalker.app.data.bible.splitBibleRefsInput
import com.bettertalker.app.ui.bible.BibleReaderSheet
import com.bettertalker.app.ui.bible.BibleReaderViewModel
import com.bettertalker.app.ui.components.NoteImageFullScreen
import com.bettertalker.app.ui.components.NoteImageStrip
import com.bettertalker.app.ui.components.NoteImageThumb
import com.bettertalker.app.ui.stage.DemoScript
import kotlinx.coroutines.launch

/**
 * #20-fix-b: a Tribuna — leitura do mapa de memória por seção. Cards
 * empilhados com inline edit (autosave) e TTS só na frase-âncora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TribunaScreen(
    vm: TribunaViewModel,
    onBack: () -> Unit,
    /** v5.0: ajuda contextual na top bar. */
    onOpenHelp: () -> Unit = {},
    /** v4.2: regenera só uma seção; entrega a mensagem de erro (ou null). */
    onRegenerateSection: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    /** v5.2: leitor TNM compartilhado com o Palco. */
    bibleVm: BibleReaderViewModel,
    /** v5.2: abrir a Biblioteca quando a TNM não estiver no acervo. */
    onOpenLibrary: () -> Unit = {},
) {
    val cards by vm.cards.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val speakingId by vm.speakingId.collectAsState()
    val noteTitle by vm.noteTitle.collectAsState()
    val isDemonstration by vm.isDemonstration.collectAsState()
    val noteDetail by vm.noteDetail.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var regeneratingId by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<NoteImageEntity?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    // #20-fix-b: sair da tela para a leitura em voz (o orador pode voltar).
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Mapa mental") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    com.bettertalker.app.ui.help.HelpTopBarButton(onOpenHelp)
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        "Nenhum mapa mental gerado ainda.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = onBack) {
                        Text("Voltar ao Palco")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DetailChips(
                        selected = noteDetail,
                        onSelect = { vm.setNoteDetail(it) },
                    )
                }
                items(cards, key = { it.sectionId }) { card ->
                    TribunaCard(
                        card = card,
                        fontSp = fontSp,
                        speaking = speakingId == card.sectionId,
                        isDemonstration = isDemonstration,
                        regenerating = regeneratingId == card.sectionId,
                        onMainIdea = { vm.updateMainIdea(card.sectionId, it) },
                        onBibleRef = { vm.updateBibleRef(card.sectionId, it) },
                        onAnchor = { vm.updateAnchorPhrase(card.sectionId, it) },
                        onTransition = { vm.updateTransition(card.sectionId, it) },
                        onTimeMinutes = { vm.updateTimeMinutes(card.sectionId, it) },
                        onKeyTerms = { vm.updateKeyTerms(card.sectionId, it) },
                        onCueText = { index, text -> vm.updateCueText(card.sectionId, index, text) },
                        onCueRefs = { index, refs -> vm.updateCueRefs(card.sectionId, index, refs) },
                        onCueKind = { index, kind -> vm.updateCueKind(card.sectionId, index, kind) },
                        onCueImage = { index, image ->
                            vm.updateCueImage(
                                card.sectionId, index, image?.id, image?.caption,
                            )
                        },
                        onAddCue = { vm.addCue(card.sectionId) },
                        onRemoveCue = { index ->
                            val previous = card.capsule
                            vm.removeCue(card.sectionId, index)
                            if (previous != null) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Lembrete removido.",
                                        actionLabel = "Desfazer",
                                        duration = androidx.compose.material3.SnackbarDuration.Short,
                                    )
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        vm.restoreCapsule(card.sectionId, previous)
                                    }
                                }
                            }
                        },
                        onMoveCue = { index, direction -> vm.moveCue(card.sectionId, index, direction) },
                        onReadBible = { refs -> bibleVm.open(refs) },
                        onConvert = { vm.convertToCueSheet(card.sectionId) },
                        onSectionDetail = { detail ->
                            if (detail == null) {
                                vm.clearSectionDetail(card.sectionId)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Detalhe desta seção: padrão da nota.",
                                    )
                                }
                            } else {
                                vm.setSectionDetail(card.sectionId, detail)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Detalhe desta seção: ${detailLabel(detail)}. " +
                                            "Vale ao regenerar.",
                                    )
                                }
                            }
                        },
                        onPreviewImage = { previewImage = it },
                        onRegenerate = {
                            if (regeneratingId == null) {
                                regeneratingId = card.sectionId
                                onRegenerateSection(card.sectionId) { error ->
                                    regeneratingId = null
                                    if (error != null) {
                                        scope.launch { snackbarHostState.showSnackbar(error) }
                                    }
                                }
                            }
                        },
                        onSpeak = {
                            if (speakingId == card.sectionId) vm.stopSpeaking()
                            else vm.speakAnchor(card.sectionId)
                        },
                    )
                }
            }
        }
    }

    previewImage?.let { image ->
        NoteImageFullScreen(
            path = image.path,
            caption = image.caption,
            onDismiss = { previewImage = null },
        )
    }
    BibleReaderSheet(
        vm = bibleVm,
        onOpenLibrary = {
            bibleVm.close()
            onOpenLibrary()
        },
    )
}

/** v5.1: card de UMA seção — folha de lembretes ou fallback legado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TribunaCard(
    card: TribunaViewModel.TribunaCardUi,
    fontSp: Int,
    speaking: Boolean,
    isDemonstration: Boolean,
    regenerating: Boolean,
    onMainIdea: (String) -> Unit,
    onBibleRef: (String) -> Unit,
    onAnchor: (String) -> Unit,
    onTransition: (String) -> Unit,
    onTimeMinutes: (Int?) -> Unit,
    onKeyTerms: (List<String>) -> Unit,
    onCueText: (Int, String) -> Unit,
    onCueRefs: (Int, List<String>) -> Unit,
    onReadBible: (List<String>) -> Unit,
    onCueKind: (Int, TribunaCueKind) -> Unit,
    onCueImage: (Int, NoteImageEntity?) -> Unit,
    onAddCue: () -> Unit,
    onRemoveCue: (Int) -> Unit,
    onMoveCue: (Int, Int) -> Unit,
    onConvert: () -> Unit,
    onSectionDetail: (TribunaDetail?) -> Unit,
    onPreviewImage: (NoteImageEntity) -> Unit,
    onRegenerate: () -> Unit,
    onSpeak: () -> Unit,
) {
    var confirmRegen by remember(card.sectionId) { mutableStateOf(false) }
    var detailMenu by remember(card.sectionId) { mutableStateOf(false) }
    val capsule = card.capsule
    val requestRegenerate = {
        if (capsule?.cues?.isNotEmpty() == true) confirmRegen = true else onRegenerate()
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${card.order + 1}. ${card.sectionTitle} · ${card.minutes} min",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                MapBadge(card)
                Box {
                    IconButton(onClick = { detailMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Opções do mapa")
                    }
                    DropdownMenu(
                        expanded = detailMenu,
                        onDismissRequest = { detailMenu = false },
                    ) {
                        Text(
                            "Detalhe desta seção",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        TribunaDetail.values().forEach { detail ->
                            DropdownMenuItem(
                                text = { Text(detailLabel(detail)) },
                                leadingIcon = if (card.sectionDetail == detail) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Detalhe ativo",
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    detailMenu = false
                                    onSectionDetail(detail)
                                },
                            )
                        }
                        if (card.sectionDetail != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Usar padrão da nota") },
                                onClick = {
                                    detailMenu = false
                                    onSectionDetail(null)
                                },
                            )
                        }
                    }
                }
            }
            card.sectionDetail?.let { detail ->
                Text(
                    "Detalhe desta seção: ${detailLabel(detail)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // v4.2: thumbnail + legenda no TOPO da seção; toque abre em tela
            // cheia. A imagem nunca é exibida sozinha nem entra no texto.
            // v5.3: imagem é recurso de discurso; demo não mostra imagens.
            if (!isDemonstration && card.images.isNotEmpty()) {
                NoteImageStrip(images = card.images, thumbSizeDp = 68)
            }
            if (isDemonstration) {
                DemoScript(turns = card.turns, fontSp = fontSp)
            } else if (capsule == null) {
                Text(
                    "Mapa mental não gerado para esta seção.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AddCueButton(onAddCue)
                    RegenerateButton(regenerating, requestRegenerate)
                }
            } else if (capsule.cues.isEmpty() && !card.emptyCueSheet) {
                // Compatibilidade: cápsulas antigas continuam editáveis.
                TribunaField("💡 IDEIA CENTRAL", capsule.mainIdea, fontSp, onMainIdea)
                TribunaField("📖 TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp, onBibleRef)
                ReadTextButton(capsule.bibleRef, onReadBible)
                TribunaField("🎯 FRASE-ÂNCORA", capsule.anchorPhrase, fontSp, onAnchor, bold = true)
                TribunaField("➡ TRANSIÇÃO (opcional)", capsule.transition.orEmpty(), fontSp, onTransition)
                TribunaTimeField(capsule.timeMinutes, onTimeMinutes)
                TribunaKeyTermsField(capsule.keyTerms, onKeyTerms)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onSpeak) {
                        Text(if (speaking) "■ Parar" else "▶ Ouvir âncora")
                    }
                    TextButton(onClick = onConvert) { Text("Converter em lembretes") }
                    AddCueButton(onAddCue)
                    RegenerateButton(regenerating, requestRegenerate)
                }
            } else if (card.emptyCueSheet) {
                Text(
                    "Mapa vazio. Adicione o primeiro lembrete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TribunaField("🎯 FRASE-ÂNCORA", capsule.anchorPhrase, fontSp, onAnchor, bold = true)
                TribunaField("📖 TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp, onBibleRef)
                ReadTextButton(capsule.bibleRef, onReadBible)
                TribunaTimeField(capsule.timeMinutes, onTimeMinutes)
                TribunaKeyTermsField(capsule.keyTerms, onKeyTerms)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onSpeak) {
                        Text(if (speaking) "■ Parar" else "▶ Ouvir âncora")
                    }
                    AddCueButton(onAddCue)
                    RegenerateButton(regenerating, requestRegenerate)
                }
            } else {
                TribunaField("🎯 FRASE-ÂNCORA", capsule.anchorPhrase, fontSp, onAnchor, bold = true)
                TribunaField("📖 TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp, onBibleRef)
                ReadTextButton(capsule.bibleRef, onReadBible)
                TribunaField("💡 IDEIA CENTRAL", capsule.mainIdea, fontSp, onMainIdea)
                TribunaField("➡ TRANSIÇÃO (opcional)", capsule.transition.orEmpty(), fontSp, onTransition)
                TribunaTimeField(capsule.timeMinutes, onTimeMinutes)
                TribunaKeyTermsField(capsule.keyTerms, onKeyTerms)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                capsule.cues.forEachIndexed { index, cue ->
                    TribunaCueEditor(
                        cue = cue,
                        cueIndex = index,
                        fontSp = fontSp,
                        images = card.cueImages,
                        onText = { onCueText(index, it) },
                        onRefs = { onCueRefs(index, it) },
                        onReadText = if (
                            cue.kind in setOf(
                                TribunaCueKind.SCRIPTURE,
                                TribunaCueKind.READING,
                            ) && cue.refs.isNotEmpty()
                        ) {
                            { onReadBible(cue.refs) }
                        } else {
                            null
                        },
                        onKind = { onCueKind(index, it) },
                        onImage = { onCueImage(index, it) },
                        onMove = { direction -> onMoveCue(index, direction) },
                        onRemove = { onRemoveCue(index) },
                        onPreviewImage = onPreviewImage,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AddCueButton(onAddCue)
                    TextButton(onClick = onSpeak) {
                        Text(if (speaking) "■ Parar" else "▶ Ouvir âncora")
                    }
                    RegenerateButton(regenerating, requestRegenerate)
                }
            }
        }
    }
    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text("Regenerar seção?") },
            text = { Text("Os lembretes atuais desta seção serão substituídos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegen = false
                    onRegenerate()
                }) { Text("Regenerar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegen = false }) { Text("Cancelar") }
            },
        )
    }
}

private fun detailLabel(detail: TribunaDetail): String = when (detail) {
    TribunaDetail.COMPACT -> "Compacto"
    TribunaDetail.STANDARD -> "Padrão"
    TribunaDetail.DETAILED -> "Detalhado"
}

@Composable
private fun DetailChips(selected: TribunaDetail, onSelect: (TribunaDetail) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Detalhe da geração",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TribunaDetail.values().forEach { detail ->
                FilterChip(
                    selected = selected == detail,
                    onClick = { onSelect(detail) },
                    label = { Text(detailLabel(detail)) },
                )
            }
        }
        Text(
            "A mudança vale ao gerar ou regenerar uma seção.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadTextButton(ref: String?, onRead: (List<String>) -> Unit) {
    if (ref.isNullOrBlank()) return
    TextButton(onClick = { onRead(listOf(ref)) }) { Text("Ler texto") }
}

@Composable
private fun AddCueButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("+ Lembrete") }
}

@Composable
private fun MapBadge(card: TribunaViewModel.TribunaCardUi) {
    val quality = card.quality
    val badge = when {
        card.capsule == null ->
            Triple("Sem mapa", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        card.emptyCueSheet ->
            Triple("Mapa vazio", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        quality.status == TribunaMapStatus.LEGACY ->
            Triple("Mapa antigo", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        quality.complete ->
            Triple(
                if (quality.warnings.isEmpty()) {
                    "Mapa completo"
                } else {
                    "Mapa completo · ${quality.warnings.joinToString(", ")}"
                },
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        else ->
            Triple(
                "Mapa incompleto · ${quality.reasons.joinToString(", ")}",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
    }
    Surface(shape = RoundedCornerShape(50), color = badge.second) {
        Text(
            badge.first,
            style = MaterialTheme.typography.labelSmall,
            color = badge.third,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun RegenerateButton(regenerating: Boolean, onRegenerate: () -> Unit) {
    TextButton(onClick = onRegenerate, enabled = !regenerating) {
        if (regenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text("↻ Regenerar seção")
        }
    }
}

/** Rótulo curto do lembrete. */
private fun cueKindLabel(kind: TribunaCueKind): String = when (kind) {
    TribunaCueKind.HOOK -> "GANCHO"
    TribunaCueKind.CONTEXT -> "CONTEXTO"
    TribunaCueKind.QUESTION -> "PERGUNTA"
    TribunaCueKind.REASONING -> "RACIOCÍNIO"
    TribunaCueKind.SCRIPTURE -> "TEXTO"
    TribunaCueKind.READING -> "LEITURA"
    TribunaCueKind.ILLUSTRATION -> "ILUSTRAÇÃO"
    TribunaCueKind.QUOTE -> "CITAÇÃO"
    TribunaCueKind.APPLICATION -> "APLICAÇÃO"
    TribunaCueKind.IMAGE -> "IMAGEM"
    TribunaCueKind.DELIVERY -> "ENTREGA"
    TribunaCueKind.TRANSITION -> "TRANSIÇÃO"
    TribunaCueKind.INVITATION -> "CONVITE"
    TribunaCueKind.RECAP -> "RECAPITULAÇÃO"
    TribunaCueKind.APPEAL -> "APELO"
}

private val CUE_KIND_GROUPS: List<Pair<String, List<TribunaCueKind>>> = listOf(
    "Abertura" to listOf(
        TribunaCueKind.HOOK, TribunaCueKind.CONTEXT, TribunaCueKind.QUESTION,
    ),
    "Desenvolvimento" to listOf(
        TribunaCueKind.REASONING, TribunaCueKind.SCRIPTURE, TribunaCueKind.READING,
        TribunaCueKind.ILLUSTRATION, TribunaCueKind.QUOTE, TribunaCueKind.APPLICATION,
        TribunaCueKind.IMAGE,
    ),
    "Entrega" to listOf(TribunaCueKind.DELIVERY),
    "Fecho" to listOf(
        TribunaCueKind.TRANSITION, TribunaCueKind.INVITATION,
        TribunaCueKind.RECAP, TribunaCueKind.APPEAL,
    ),
)

/** v4.2/v5.2: parser de refs digitadas; preserva listas "1:26,31". */
private fun parseCueRefs(raw: String): List<String> =
    splitBibleRefsInput(raw)

@Composable
private fun TribunaTimeField(minutes: Int?, onChange: (Int?) -> Unit) {
    var text by remember(minutes) { mutableStateOf(minutes?.toString().orEmpty()) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "⏱ TEMPO (min)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() }
                onChange(text.toIntOrNull())
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TribunaKeyTermsField(terms: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(terms.joinToString(", ")) }
    LaunchedEffect(terms) {
        if (parseCueRefs(text) != terms) text = terms.joinToString(", ")
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "🔑 TERMOS-CHAVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                onChange(parseCueRefs(raw))
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TribunaCueEditor(
    cue: TribunaCue,
    cueIndex: Int,
    fontSp: Int,
    images: List<NoteImageEntity>,
    onText: (String) -> Unit,
    onRefs: (List<String>) -> Unit,
    onReadText: (() -> Unit)?,
    onKind: (TribunaCueKind) -> Unit,
    onImage: (NoteImageEntity?) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onPreviewImage: (NoteImageEntity) -> Unit,
) {
    var refsText by remember(cueIndex) {
        mutableStateOf(cue.refs.joinToString(", "))
    }
    LaunchedEffect(cue.refs) {
        if (parseCueRefs(refsText) != cue.refs) {
            refsText = cue.refs.joinToString(", ")
        }
    }
    val image = cue.imageId?.let { id -> images.firstOrNull { it.id == id } }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CueKindMenu(kind = cue.kind, onKind = onKind)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Mover lembrete para cima")
            }
            IconButton(onClick = { onMove(1) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Mover lembrete para baixo")
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, "Remover lembrete")
            }
        }
        BasicTextField(
            value = cue.text,
            onValueChange = onText,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSp.sp,
                color = if (cue.kind == TribunaCueKind.DELIVERY) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontStyle = if (cue.kind == TribunaCueKind.DELIVERY) {
                    FontStyle.Italic
                } else {
                    FontStyle.Normal
                },
                fontWeight = if (cue.kind == TribunaCueKind.SCRIPTURE) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (cue.refs.isNotEmpty() ||
            cue.kind in setOf(
                TribunaCueKind.SCRIPTURE,
                TribunaCueKind.READING,
                TribunaCueKind.QUOTE,
            )
        ) {
            BasicTextField(
                value = refsText,
                onValueChange = { raw ->
                    refsText = raw
                    onRefs(parseCueRefs(raw))
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        onReadText?.let {
            TextButton(onClick = it) { Text("Ler texto") }
        }
        if (cue.kind == TribunaCueKind.IMAGE) {
            val orphan = cue.imageId != null && image == null
            if (images.isEmpty()) {
                if (orphan) {
                    Text(
                        "Sem imagem — a imagem atribuída não está mais na nota.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { onImage(null) }) {
                        Text("Limpar referência")
                    }
                } else {
                    Text(
                        "Anexe uma imagem no editor para usar este lembrete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var imageMenu by remember(cueIndex) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { imageMenu = true }) {
                        Text(
                            when {
                                image != null -> "Trocar imagem"
                                orphan -> "Sem imagem"
                                else -> "Escolher imagem"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = imageMenu,
                        onDismissRequest = { imageMenu = false },
                    ) {
                        images.forEach { candidate ->
                            DropdownMenuItem(
                                text = {
                                    Text(candidate.caption.ifBlank { "Imagem da seção" })
                                },
                                onClick = {
                                    imageMenu = false
                                    onImage(candidate)
                                },
                            )
                        }
                        if (cue.imageId != null) {
                            DropdownMenuItem(
                                text = { Text("Sem imagem") },
                                onClick = {
                                    imageMenu = false
                                    onImage(null)
                                },
                            )
                        }
                    }
                    if (image != null) {
                        NoteImageThumb(
                            path = image.path,
                            caption = cue.imageCaption ?: image.caption,
                            sizeDp = 64,
                            modifier = Modifier.clickable { onPreviewImage(image) },
                        )
                        Text(
                            cue.imageCaption ?: image.caption.ifBlank { "Imagem da seção" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (orphan) {
                    Text(
                        "Sem imagem — escolha outra ou limpe a referência.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun CueKindMenu(kind: TribunaCueKind, onKind: (TribunaCueKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                cueKindLabel(kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CUE_KIND_GROUPS.forEach { (group, kinds) ->
                Text(
                    group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                kinds.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(cueKindLabel(option)) },
                        onClick = {
                            open = false
                            onKind(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bloco 2 (demos): o roteiro completo é renderizado pelo componente
 * compartilhado `DemoScript` (mesmo do Palco). A edição continua nos campos
 * acima; aqui não há destaque de turno.
 */

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
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
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
    "Mapa mental " + noteTitle.ifBlank { "" }

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
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Mapa mental")
        putExtra(android.content.Intent.EXTRA_TITLE, exportTitle(noteTitle))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Compartilhar mapa mental"))
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
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Mapa mental", text))
    android.widget.Toast.makeText(ctx, "Mapa mental copiado", android.widget.Toast.LENGTH_SHORT).show()
}
