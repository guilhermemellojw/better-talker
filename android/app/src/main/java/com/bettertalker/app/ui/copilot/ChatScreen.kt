package com.bettertalker.app.ui.copilot

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.llm.LocalPhase
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.voice.VoiceInputState
import com.bettertalker.app.ui.components.MdBlock
import com.bettertalker.app.ui.components.inline
import com.bettertalker.app.ui.components.parseBlocks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Conversa com o Copilot (exclusiva do FAB do editor). Histórico salvo por nota.
 * Todo o guia (seções, draft, refs, bases) vive aqui como mensagem/comando.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: CopilotViewModel,
    onBack: () -> Unit,
    onInsert: (text: String, heading: String?) -> Unit,
    onOpenLibrary: () -> Unit,
    headings: List<String> = emptyList()
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.chatBusy.collectAsState()
    val runState by vm.runState.collectAsState()
    // F2.1 — fases do Gemma local (só observa; remotos seguem o rótulo legado).
    val localPhase by vm.localPhase.collectAsState()
    val localPartial by vm.localPartial.collectAsState()
    // Onboarding F2a: prontidão da nota (banner de setup) + refresh na entrada.
    val readiness by vm.readiness.collectAsState()
    val setupDismissed by vm.setupBannerDismissed.collectAsState()
    LaunchedEffect(vm) { vm.refreshReadiness() }
    val evidence by vm.chatEvidence.collectAsState()
    val evidenceSummary by vm.evidenceSummary.collectAsState()
    val contextText by vm.contextLabelText.collectAsState()
    // FOCO: alvo empurrado ("Conversando sobre: …"), resolvido async na entrada.
    val conversationLabel by vm.conversationLabel.collectAsState()
    // T4: nome do tópico em foco (confirmação da inserção).
    val topicName by vm.topicName.collectAsState()
    // T5: destinos do seletor — tópicos (BODY) da nota; sem seções, headings legados.
    val topicDests by vm.insertDestinations.collectAsState()
    val destinations = remember(topicDests, headings) {
        insertionDestinations(topicDests, headings)
    }
    // F2.3: escopo da conversa (tópico vs. global).
    val chatScope by vm.chatScope.collectAsState()
    val proposal by vm.proposal.collectAsState()
    val insert by vm.insertReq.collectAsState()
    val sectionBusy by vm.sectionBusy.collectAsState()
    val outlineInfo by vm.outlineInfo.collectAsState()
    val outlineSections by vm.outlineSections.collectAsState()
    val merges by vm.merges.collectAsState()
    val draft by vm.draft.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val dl = rememberDownloadCtl(vm, snack)
    var input by rememberSaveable { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showPublications by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pasteText by rememberSaveable { mutableStateOf("") }
    // T3 (voz): STT nativo — estado, permissão, cronômetro e foco do composer.
    val voiceState by vm.voiceState.collectAsState()
    var voiceSeconds by remember { mutableIntStateOf(0) }
    val composerFocus = remember { FocusRequester() }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startVoiceInput() }
    LaunchedEffect(voiceState) {
        if (voiceState is VoiceInputState.Listening) {
            voiceSeconds = 0
            while (true) {
                delay(1000)
                voiceSeconds += 1
            }
        } else {
            voiceSeconds = 0
        }
    }
    LaunchedEffect(voiceState) {
        when (val v = voiceState) {
            is VoiceInputState.Result -> {
                // Transcrição cai no composer, editável, com foco.
                input = v.text
                composerFocus.requestFocus()
                vm.consumeVoiceResult()
            }
            is VoiceInputState.Error -> {
                snack.showSnackbar(v.message)
                vm.consumeVoiceResult()
            }
            else -> Unit
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.cancelVoiceInput()
            vm.stopSpeaking()
        }
    }
    // T4 (voz): leitura em voz alta das respostas (TTS nativo).
    val ttsReady by vm.ttsReady.collectAsState()
    val ttsInitialized by vm.ttsInitialized.collectAsState()
    val ttsSpeaking by vm.ttsSpeaking.collectAsState()
    var speakingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ttsSpeaking) {
        if (!ttsSpeaking) speakingId = null
    }
    LaunchedEffect(ttsInitialized, ttsReady) {
        if (ttsInitialized && !ttsReady) {
            snack.showSnackbar(com.bettertalker.app.data.voice.TTS_LANGUAGE_UNAVAILABLE)
        }
    }
    // auto-scroll só se já estiver no fim; senão acumula pílula "novas"
    var unseenCount by remember { mutableStateOf(0) }
    val atBottom by remember {
        derivedStateOf {
            val l = listState.layoutInfo
            l.totalItemsCount == 0 ||
                l.visibleItemsInfo.lastOrNull()?.index == l.totalItemsCount - 1
        }
    }
    // T2 (Bug #13): índice do último item do LazyColumn — alvo do auto-scroll
    // "para o fim" (garante os botões da última resposta visíveis).
    val runStateItems = runStateItemCount(
        runState,
        runState is ChatRunState.Generating &&
            localPhase == LocalPhase.Generating && localPartial.isNotBlank()
    )
    val lastItemIndex = chatLastItemIndex(
        messageCount = messages.size,
        actionsCount = messages.count { hasMessageActions(it) },
        hasContext = messages.isNotEmpty(),
        hasTopicEmpty = messages.isEmpty() && chatScope != null,
        hasProvenance = evidence.isNotEmpty(),
        hasProposal = proposal != null,
        runStateItems = runStateItems
    )

    val outlinePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importOutlineFile(uri) }

    LaunchedEffect(insert) {
        val req = insert
        if (req != null) {
            onInsert(req.text, req.heading)
            vm.consumeInsert()
            // T4: confirmação com o tópico em foco (sem foco: "na nota").
            scope.launch { snack.showSnackbar(insertConfirmation(topicName)) }
        }
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) {
            val l = listState.layoutInfo
            // sem layout ainda (abertura): rola; só acumula se o usuário subiu
            val last = l.visibleItemsInfo.lastOrNull()?.index
            if (last == null || last == l.totalItemsCount - 1 || l.totalItemsCount == 0) {
                // T2 (Bug #13): alvo = ÚLTIMO item real da lista (item de ações da
                // última resposta), não o topo da última mensagem — com resposta
                // longa o topo deixava os botões fora da tela.
                listState.animateScrollToItem(lastItemIndex)
            } else {
                unseenCount++
            }
        }
    }
    // T3 (P2 estético): o parcial do streaming cresce DENTRO do mesmo item —
    // o efeito acima não dispara (messages.size não muda) e o fim do texto
    // ficava abaixo do fold. Rola instantâneo junto com o parcial, alinhando
    // o FIM do item (offset máximo) e só quando o usuário já está no fim.
    LaunchedEffect(localPartial) {
        if (localPartial.isNotBlank() && atBottom) {
            listState.scrollToItem(lastItemIndex, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseenCount = 0 }
    dl.host(scope)

    fun doSend(text: String) {
        if (text.isBlank()) return
        if (busy) {
            scope.launch { snack.showSnackbar("Aguarde a resposta…") }
            return
        }
        vm.send(text)
        input = ""
    }

    // sugestões do olá: só até a primeira mensagem do usuário
    val showSuggestions = messages.none { it.fromMe } && input.isBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text("Copilot")
                        conversationLabel?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (showSuggestions) {
                    // Atalhos da F15 + ações locais do app. Todos chamam o MESMO
                    // doSend: nenhum atalho tem prompt próprio (§9 F15).
                    ChatQuickActions(
                        quickActions = vm.quickActions,
                        localActions = localSuggestionTexts(outlineInfo != null),
                        onPick = { doSend(it) },
                        onAttach = { showAttach = true }
                    )
                    Spacer(Modifier.height(6.dp))
                }
                ChatPromptBar(
                    input = input,
                    onInput = { input = it },
                    busy = busy,
                    onSend = { doSend(input) },
                    onAttach = { showAttach = true },
                    onTools = { showTools = true },
                    voiceState = voiceState,
                    voiceSeconds = voiceSeconds,
                    onVoice = {
                        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.startVoiceInput()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceFinish = { vm.finishVoiceInput() },
                    onVoiceCancel = { vm.cancelVoiceInput() },
                    focusRequester = composerFocus,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ChatCaption()
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // Onboarding F2a: banner de setup (fora do scroll da conversa).
                val r = readiness
                if (r != null && !r.isSetupComplete && !setupDismissed) {
                    SetupBanner(
                        readiness = r,
                        onImportOutline = { showAttach = true },
                        onPaste = { showAttach = true },
                        onDownloadBases = { doSend("bases") },
                        onDismiss = { vm.dismissSetupBanner() },
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Rótulo de contexto: o bloco em foco, sem o usuário digitar id (§13).
                    if (messages.isNotEmpty()) {
                        item(key = "ctx") {
                            Text(
                                "Contexto: $contextText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    // F2.3: conversa por tópico ainda vazia — estado explícito.
                    if (messages.isEmpty() && chatScope != null) {
                        item(key = "topic-empty") {
                            Text(
                                "Conversa deste tópico. Converse com o Copilot para " +
                                    "decidir a abordagem e depois toque em " +
                                    "“Criar mini discurso” no card.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    messages.forEach { m ->
                        item(key = m.id) {
                            // T5: entrada com fade sutil (~150ms) + placement.
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = tween(150),
                                    fadeOutSpec = tween(150)
                                )
                            ) {
                                MessageBubble(
                                    item = m, vm = vm, dl = dl, ctx = ctx, scope = scope,
                                    destinations = destinations, sectionBusy = sectionBusy,
                                    liveSections = outlineSections,
                                    merges = merges, draft = draft,
                                    onOpenLibrary = onOpenLibrary
                                )
                            }
                        }
                        // T2 (Bug #13): ações em item PRÓPRIO — nunca ficam
                        // cortadas/escondidas no fim de uma resposta longa.
                        if (hasMessageActions(m)) {
                            item(key = m.id + ":actions") {
                                Box(
                                    Modifier.animateItem(
                                        fadeInSpec = tween(150),
                                        fadeOutSpec = tween(150)
                                    )
                                ) {
                                    // T1: as ações são um item próprio do LazyColumn
                                    // (espaçamento 14dp); o offset mantém o par
                                    // mensagem+ações visualmente junto.
                                    MessageActions(
                                        m, vm, hasScope = chatScope != null,
                                        ttsReady = ttsReady,
                                        speaking = speakingId == m.id,
                                        onSpeak = {
                                            speakingId = m.id
                                            vm.speakMessage(m.text)
                                        },
                                        onStopSpeak = { vm.stopSpeaking() },
                                        modifier = Modifier.offset(y = (-8).dp)
                                    )
                                }
                            }
                        }
                    }
                    // Fontes e apoio: proveniência recolhível, discreta (§14).
                    if (evidence.isNotEmpty()) {
                        item(key = "prov") {
                            ProvenanceDisclosure(evidence, evidenceSummary)
                        }
                    }
                    // Fase 18 §21: proposta F5 — ANTES/DEPOIS, verificar, aceitar/rejeitar.
                    val prop = proposal
                    if (prop != null) {
                        item(key = "proposal") {
                            ProposalCard(prop, vm)
                        }
                    }
                    // Estados legíveis: nada de spinner mudo (§30 F15).
                    // F2.1: com o Gemma local, o rótulo reflete a fase real
                    // (load/RAG/geração); remotos mantêm o texto legado.
                    when (val s = runState) {
                        is ChatRunState.Sending, is ChatRunState.Generating -> {
                            item {
                                Text(
                                    when (localPhase) {
                                        LocalPhase.LoadingModel -> "Carregando modelo…"
                                        LocalPhase.ReadingSources -> "Lendo as fontes…"
                                        LocalPhase.Generating -> "Gerando resposta…"
                                        else -> "Copilot está escrevendo…"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            // Texto parcial transitório (não persiste; some
                            // quando a resposta final é postada).
                            if (s is ChatRunState.Generating &&
                                localPhase == LocalPhase.Generating &&
                                localPartial.isNotBlank()
                            ) {
                                item {
                                    // T3: cursor pulsante no fim do parcial —
                                    // continua texto puro (sem parser); some
                                    // quando a resposta final chega.
                                    val cursorAlpha by rememberInfiniteTransition(label = "cursor")
                                        .animateFloat(
                                            initialValue = 0.2f,
                                            targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(400, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "cursorAlpha"
                                        )
                                    val cursorColor = MaterialTheme.colorScheme.onSurface
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            buildAnnotatedString {
                                                append(localPartial)
                                                withStyle(
                                                    SpanStyle(
                                                        color = cursorColor.copy(alpha = cursorAlpha)
                                                    )
                                                ) { append("▍") }
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                        is ChatRunState.Error -> item {
                            ChatErrorRow(s.message, onDismiss = vm::dismissError, onRetry = vm::retry)
                        }
                        is ChatRunState.Cancelled -> item {
                            ChatErrorRow(s.message, onDismiss = vm::dismissError, onRetry = vm::retry)
                        }
                        else -> Unit
                    }
                }
            }
            if (unseenCount > 0 && !busy) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        unseenCount = 0
                        scope.launch { listState.animateScrollToItem(lastItemIndex) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                ) { Text(if (unseenCount == 1) "Nova mensagem ↓" else "$unseenCount novas ↓") }
            }
        }
    }

    if (showAttach) {
        AttachSheet(
            onDismiss = { showAttach = false },
            onImport = {
                showAttach = false
                outlinePicker.launch(OUTLINE_MIMES)
            },
            onPaste = { showAttach = false; showPaste = true },
            // T3: atalho para o catálogo (Biblioteca abre com a seção no topo).
            onPublications = { showAttach = false; onOpenLibrary() },
            // T2: vincular publicação do acervo sem sair do chat.
            onAttachFromLibrary = { showAttach = false; showPublications = true }
        )
    }

    if (showPublications) {
        PublicationPickerSheet(vm = vm, onDismiss = { showPublications = false })
    }

    if (showTools) {
        ToolsSheet(
            onDismiss = { showTools = false },
            onSend = { showTools = false; doSend(it) },
            onClear = { showTools = false; confirmClear = true }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar conversa?") },
            text = { Text("O histórico desta conversa será apagado.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearChat() }) { Text("Limpar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }

    if (showPaste) {
        var t by rememberSaveable(pasteText) { mutableStateOf(pasteText) }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Colar esboço") },
            text = {
                OutlinedTextField(
                    value = t, onValueChange = { t = it },
                    placeholder = { Text("Cole o texto do esboço (ex: ponto da apostila)…") },
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasteText = t
                    showPaste = false
                    vm.pasteOutline(t)
                }) { Text("Analisar") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun MessageBubble(
    item: CopilotViewModel.ChatItem,
    vm: CopilotViewModel,
    dl: DownloadCtl,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    destinations: List<InsertDestination>,
    sectionBusy: String?,
    liveSections: List<OutlineSection>,
    merges: List<com.bettertalker.app.data.util.PastedOutlineAnalyzer.MergeSuggestion>,
    draft: List<DraftSection>,
    onOpenLibrary: () -> Unit
) {
    if (item.fromMe) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                ChatMessageText(item.text, Modifier.padding(12.dp))
            }
        }
        return
    }
    // assistente: texto corrido sem cartão, estilo ChatGPT
    Column(Modifier.fillMaxWidth()) {
        // Origem honesta: só marca quando NÃO veio do Copilot remoto.
        if (item.origin == MessageOrigin.LOCAL) {
            Text(
                "Resposta local",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        when (item.kind) {
            "ideas" -> IdeasBody(item, vm, destinations)
            "refs" -> RefsBody(vm, dl, ctx, scope, item.text, item.detectedJson)
            "bases" -> BasesBody(item, dl, ctx, onOpenLibrary)
            "sections" -> SectionsBody(item, vm, sectionBusy, liveSections)
            "draft" -> DraftBody(item, vm, draft, merges)
            else -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                // P1: presença do Copilot nas respostas de texto.
                AssistantAvatar()
                ChatMessageText(item.text, Modifier.weight(1f))
            }
        }
    }
}

/** P1 — identidade do Copilot: monograma "BT" no círculo primário (24dp). */
@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "BT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * T2 (Bug #13): ações de uma resposta do Copilot em composable/item PRÓPRIO
 * (fora do bubble) — resposta longa não consegue mais escondê-las no fim do
 * item; o auto-scroll mira este item (último da lista).
 */
@Composable
private fun MessageActions(
    item: CopilotViewModel.ChatItem,
    vm: CopilotViewModel,
    hasScope: Boolean,
    ttsReady: Boolean = false,
    speaking: Boolean = false,
    onSpeak: () -> Unit = {},
    onStopSpeak: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // T1 (P2 estético): háptica leve nos chips de ação.
        val haptic = LocalHapticFeedback.current
        // Fase 18 §21: sugestão conversacional vira proposta F5.
        TextButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                vm.createProposal(item.id)
            }
        ) { Text("Criar proposta") }
        // F2.3: manda a resposta para o tópico em foco (roteamento
        // pelo alvo atual; sem foco, cai no comportamento legado).
        if (hasScope) {
            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    vm.insertTextIntoScope(item.text)
                }
            ) { Text("Inserir no tópico") }
        }
        // T4 (voz): lê a resposta em voz alta (texto pós-gate).
        TextButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                if (speaking) onStopSpeak() else onSpeak()
            },
            enabled = ttsReady,
        ) { Text(speakButtonLabel(speaking)) }
    }
}

/** T4 (voz): rótulo do botão de leitura. Puro/testável. */
internal fun speakButtonLabel(speaking: Boolean): String =
    if (speaking) "⏸ Parar" else "▶ Ouvir"

/** T4 (voz): descrição acessível do botão de leitura. Puro/testável. */
internal fun speakButtonDescription(speaking: Boolean, ready: Boolean): String = when {
    !ready -> "Leitura em voz alta indisponível"
    speaking -> "Parar leitura"
    else -> "Ouvir a resposta em voz alta"
}

/** T2 (Bug #13): kinds sem ações (têm corpo próprio); só texto do Copilot tem. */
private val ACTIONLESS_KINDS = setOf("ideas", "refs", "bases", "sections", "draft")

/** T2 (Bug #13): resposta do Copilot de texto corrido → tem item de ações. */
internal fun hasMessageActions(item: CopilotViewModel.ChatItem): Boolean =
    !item.fromMe && item.kind !in ACTIONLESS_KINDS

/**
 * T2 (Bug #13): itens do estado de execução no fim da lista (espelha o
 * `when (runState)` do LazyColumn). Puro/testável.
 */
internal fun runStateItemCount(runState: ChatRunState, partialVisible: Boolean): Int =
    when (runState) {
        is ChatRunState.Sending -> 1
        is ChatRunState.Generating -> if (partialVisible) 2 else 1
        is ChatRunState.Error, is ChatRunState.Cancelled -> 1
        else -> 0
    }

/**
 * T2 (Bug #13): índice do último item do LazyColumn — alvo do auto-scroll ao
 * fim. Espelha a ordem real dos itens (contexto, mensagens+ações, fontes,
 * proposta, estados). Puro/testável.
 */
internal fun chatLastItemIndex(
    messageCount: Int,
    actionsCount: Int,
    hasContext: Boolean,
    hasTopicEmpty: Boolean,
    hasProvenance: Boolean,
    hasProposal: Boolean,
    runStateItems: Int
): Int {
    val total = (if (hasContext) 1 else 0) +
        (if (hasTopicEmpty) 1 else 0) +
        messageCount + actionsCount +
        (if (hasProvenance) 1 else 0) +
        (if (hasProposal) 1 else 0) +
        runStateItems
    return (total - 1).coerceAtLeast(0)
}

/** T3 — texto exibido após remover as tags de criação + flag do badge. */
data class SuggestionText(val text: String, val hasSuggestion: Boolean)

private val SUGGESTION_PAIR = Regex(
    Regex.escape(com.bettertalker.app.data.llm.SUGGESTION_OPEN) + "(.*?)" +
        Regex.escape(com.bettertalker.app.data.llm.SUGGESTION_CLOSE),
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * T3 — remove as tags `〈sugestão〉…〈/sugestão〉` do texto exibido (elas são
 * invisíveis ao usuário, que vê o badge 💡 Sugestão criativa) e sinaliza a
 * presença de criação. Tags soltas (sem par) também somem. Puro/testável.
 */
fun stripSuggestionTags(md: String): SuggestionText {
    if (md.isBlank()) return SuggestionText(md, false)
    val has = md.contains(com.bettertalker.app.data.llm.SUGGESTION_OPEN)
    val cleaned = SUGGESTION_PAIR.replace(md) { it.groupValues[1] }
        .replace(com.bettertalker.app.data.llm.SUGGESTION_OPEN, "")
        .replace(com.bettertalker.app.data.llm.SUGGESTION_CLOSE, "")
    return SuggestionText(cleaned, has)
}

/** Texto formatado do assistente (markdown próprio, selecionável). */
@Composable
fun ChatMessageText(md: String, modifier: Modifier = Modifier) {
    if (md.isBlank()) return
    val suggested = remember(md) { stripSuggestionTags(md) }
    val blocks = remember(suggested.text) { parseBlocks(suggested.text) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggested.hasSuggestion) {
                Text(
                    "💡 Sugestão criativa",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Title -> Text(
                        block.text,
                        // P1: pesos e lineHeights vêm da AppTypography.
                        style = if (block.level >= 3) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.titleLarge
                    )
                    is MdBlock.Quote -> Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                MaterialTheme.shapes.small
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.small
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            inline(block.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                    is MdBlock.Divider -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    )
                    is MdBlock.Bullet -> Row(verticalAlignment = Alignment.Top) {
                        Text(
                            // T2: lista ordenada preserva o número; bullet comum segue "•".
                            block.number?.let { "$it.  " } ?: "•  ",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 26.dp)
                        )
                        Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
                    }
                    is MdBlock.Check -> Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (block.done) "☑  " else "☐  ",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (block.done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.widthIn(min = 26.dp)
                        )
                        Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
                    }
                    is MdBlock.Para -> Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun IdeasBody(
    item: CopilotViewModel.ChatItem,
    vm: CopilotViewModel,
    destinations: List<InsertDestination>
) {
    if (item.section.isNotBlank()) {
        Text(item.section, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
    }
    // sem "escondidos" locais: dispensar reescreve a mensagem no banco,
    // então o número exibido é sempre o número que "insere a N" usa
    item.cards.forEachIndexed { i, card ->
        IdeaCardRow(
            card = card,
            destinations = destinations,
            number = i + 1,
            onInsert = { body, dest -> vm.insert(card, body, dest) },
            onDismiss = { vm.dismissIdeaCard(item.id, i) }
        )
        if (i < item.cards.lastIndex) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionsBody(
    item: CopilotViewModel.ChatItem,
    vm: CopilotViewModel,
    sectionBusy: String?,
    liveSections: List<OutlineSection>
) {
    if (item.text.isNotBlank()) {
        Text(item.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
    }
    item.secItems.forEachIndexed { i, s ->
        // conteúdo atual da seção (subtópicos, refs): resolve pelo título
        val body = liveSections.firstOrNull { it.title == s.title }?.body.orEmpty()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = (s.level * 12).dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.titleSmall)
                if (s.minutes != null) {
                    Text(
                        "${s.minutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (body.isNotBlank()) {
                    Text(
                        body.take(140) + if (body.length > 140) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            TextButton(
                onClick = { vm.generateForSectionTitle(s.title) },
                enabled = sectionBusy != s.title
            ) { Text(if (sectionBusy == s.title) "…" else "Gerar ideias") }
        }
        if (i < item.secItems.lastIndex) Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DraftBody(
    item: CopilotViewModel.ChatItem,
    vm: CopilotViewModel,
    draft: List<DraftSection>,
    merges: List<com.bettertalker.app.data.util.PastedOutlineAnalyzer.MergeSuggestion>
) {
    if (item.draftTitle.isNotBlank()) {
        Text(item.draftTitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
    }
    Text(
        "Toque para editar, ou diga “tópico 2 chama X”, “10 min no 3”, “fundir 1 e 2”.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(6.dp))
    // índices operam no draft atual (mensagem única, sempre ressincronizada)
    item.draftItems.forEachIndexed { i, d ->
        DraftRow(
            index = i,
            title = d.title,
            minutes = d.minutes,
            included = d.included,
            body = d.body,
            level = d.level,
            onTitle = { vm.updateDraftTitle(i, it) },
            onMinutes = { vm.updateDraftMinutes(i, it) },
            onToggle = { vm.toggleDraftInclude(i) },
            onRemove = { vm.removeDraft(i) }
        )
        Spacer(Modifier.height(6.dp))
    }
    if (draft.isNotEmpty()) {
        merges.forEach { m ->
            val a = draft.getOrNull(m.a)?.title ?: return@forEach
            val b = draft.getOrNull(m.b)?.title ?: return@forEach
            Column(Modifier.padding(10.dp)) {
                Text("Parecem o mesmo tópico — fundir?", style = MaterialTheme.typography.titleSmall)
                Text("• $a", style = MaterialTheme.typography.bodySmall)
                Text("• $b", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.acceptMerge(m.a, m.b) }) { Text("Fundir") }
                    TextButton(onClick = { vm.dismissMerge(m.a, m.b) }) { Text("Manter separados") }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.linkDraft() },
            enabled = item.draftItems.size >= 2
        ) { Text("Vincular") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { vm.clearDraft() }) { Text("Descartar") }
    }
}

@Composable
private fun RefsBody(
    vm: CopilotViewModel,
    dl: DownloadCtl,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    title: String,
    detectedJson: String
) {
    var statuses by remember(detectedJson) { mutableStateOf<List<RefDetector.RefStatus>?>(null) }
    var tick by remember(detectedJson) { mutableStateOf(0) }
    LaunchedEffect(detectedJson, tick) {
        statuses = try {
            vm.resolveChatRefs(detectedJson)
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (title.isNotBlank()) {
        Text(title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
    }
    val cur = statuses
    if (cur == null) {
        Text("Verificando…", color = MaterialTheme.colorScheme.secondary)
    } else if (cur.isEmpty()) {
        Text("Nenhuma referência encontrada.")
    } else {
        cur.forEach { st ->
            RefFullRow(st, dl, ctx, scope, onDownloaded = { tick++ })
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RefFullRow(
    st: RefDetector.RefStatus,
    dl: DownloadCtl,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onDownloaded: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            (if (st.resolved) "✅ " else "⬇️ ") + st.ref.label,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall
        )
        if (st.resolved) {
            Text(
                "No arquivo: ${st.fileName ?: "baixada"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            if (st.hint.isNotBlank()) {
                Text(st.hint, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Column {
                TextButton(onClick = { dl.openPage(ctx, st.downloadUrl, onDownloaded) }) {
                    Text(if (!st.exact && st.probePub == null && st.apiPub == null) "Procurar edição" else "Baixar no site")
                }
                if (st.apiPub != null || st.probePub != null) {
                    TextButton(onClick = { dl.downloadDirect(ctx, scope, st, onDownloaded) }) { Text("Baixar direto") }
                }
            }
        }
        // T3 — verificação de conteúdo (independente de "edição baixada").
        st.snippet?.let { sn ->
            Text(
                "📖 No acervo: $sn",
                style = MaterialTheme.typography.labelSmall,
                // P2 (contraste): o amarelo primário dá 1.55:1 no tema claro;
                // secondary passa AA nos dois temas (5.38 / 6.97).
                color = MaterialTheme.colorScheme.secondary
            )
        }
        st.contentWarning?.let { w ->
            Text(
                w,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun BasesBody(
    item: CopilotViewModel.ChatItem,
    dl: DownloadCtl,
    ctx: android.content.Context,
    onOpenLibrary: () -> Unit
) {
    if (item.text.isNotBlank()) Text(item.text)
    Spacer(Modifier.height(4.dp))
    item.slots.forEach { slot ->
        val pub = BASE_PUBS.firstOrNull { it.slot == slot }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "• ${pub?.title ?: slot}",
                modifier = Modifier.weight(1f)
            )
            if (pub != null) {
                TextButton(onClick = { dl.openPage(ctx, pub.landingUrl) }) { Text("Baixar") }
                TextButton(onClick = {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(pub.downloadsUrl)
                        )
                    )
                }) { Text("Formatos") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onOpenLibrary) { Text("Abrir Biblioteca") }
}
