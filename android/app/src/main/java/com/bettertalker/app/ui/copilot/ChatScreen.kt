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
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.bettertalker.app.data.util.stripInternalTags
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
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
    headings: List<String> = emptyList(),
    // T2 (Mini Discurso P2): o mesmo DraftSheet do editor, hospedado no chat
    // para o fluxo "conversar → gerar" sem trocar de tela.
    draftState: com.bettertalker.app.ui.editor.DraftUiState =
        com.bettertalker.app.ui.editor.DraftUiState.Idle,
    draftSections: List<com.bettertalker.app.ui.editor.SectionUiState> = emptyList(),
    onDraftAccept: () -> Unit = {},
    onDraftDismiss: () -> Unit = {},
    onDraftUndo: () -> Unit = {},
    onDraftRetry: () -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.chatBusy.collectAsState()
    val runState by vm.runState.collectAsState()
    // Fases de progresso do provider (o DeepSeek alimenta Generating/parcial).
    val localPhase by vm.localPhase.collectAsState()
    val localPartial by vm.localPartial.collectAsState()
    val thinkingMode by vm.thinkingMode.collectAsState()
    // #17-fix: parcial limpo durante o streaming (só marcadores internos —
    // HTML/markdown intactos; marcador sem par = removido como órfão).
    val cleanedPartial = remember(localPartial) { stripInternalTags(localPartial) }
    // Onboarding F2a: prontidão da nota (banner de setup) + refresh na entrada.
    val readiness by vm.readiness.collectAsState()
    // T2 (chat minimalista): visibilidade combinada no VM (só 1ª vez).
    val showSetupBanner by vm.showSetupBanner.collectAsState()
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
    // Modo guiado seção-a-seção: seções reais do editor (navegação + indicador).
    val guideSections by vm.guideSections.collectAsState()
    // #10c: escopo do chat (Tópico ↔ Discurso inteiro).
    val scopeMode by vm.scopeMode.collectAsState()
    val guideIndex = remember(guideSections, chatScope) {
        guideSections.indexOfFirst { it.id == chatScope }
    }
    val guideCurrent = if (guideIndex >= 0) guideSections[guideIndex] else null
    val proposal by vm.proposal.collectAsState()
    // FALTA-2: proposta de ESTRUTURA da seção (coexiste com a de texto).
    val structureProposal by vm.structureProposal.collectAsState()
    // F5b (Modo C): proposta de estrutura da NOTA inteira (texto colado).
    val noteStructureProposal by vm.noteStructureProposal.collectAsState()
    // #15d-escopo: sugestão de vincular publicações (RAG fraco por escopo).
    val scopeSuggestion by vm.scopeSuggestion.collectAsState()
    // F5d (Modo B): opções de ângulos para um tema do zero.
    val multiProposal by vm.multiProposal.collectAsState()
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
        hasContext = messages.isNotEmpty(),
        hasTopicEmpty = messages.isEmpty() && chatScope != null,
        hasProvenance = evidence.isNotEmpty(),
        hasProposal = proposal != null || structureProposal != null ||
            noteStructureProposal != null || multiProposal != null || scopeSuggestion,
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
    // T3 (P2 estético): "seguir o fim" — o chat acompanha mensagens novas e o
    // parcial do streaming; o usuário "solta" ao arrastar para cima e reata
    // pelo botão "novas". Antes, a checagem de layout corria com a própria
    // inserção do item e o fim do texto ficava abaixo do fold.
    var followEnd by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    followEnd = !listState.canScrollForward
                else -> Unit
            }
        }
    }
    LaunchedEffect(messages.size) {
        // #25-fix-a: novo alvo = última MENSAGEM (cards renderizam depois e
        // escondiam a resposta) e a contagem observa SÓ messages.size — o
        // busy não conta: um turno que só gera proposta não incrementa.
        when (val action = messagesEffect(
            messageCount = messages.size,
            hasContext = messages.isNotEmpty(),
            hasTopicEmpty = messages.isEmpty() && chatScope != null,
            followEnd = followEnd,
        )) {
            is MessagesEffect.ScrollTo -> listState.animateScrollToItem(action.index)
            MessagesEffect.CountUnseen -> unseenCount++
            MessagesEffect.Nothing -> Unit
        }
    }
    // #25-fix-a: voltar a seguir o fim (pílula ou arrasto até o fim) zera o contador.
    LaunchedEffect(followEnd) { if (followEnd) unseenCount = 0 }
    // T3: o parcial do streaming cresce DENTRO do mesmo item — rola junto,
    // instantâneo, alinhando o FIM do item (offset máximo).
    LaunchedEffect(localPartial) {
        if (localPartial.isNotBlank() && followEnd) {
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

    // T1 (chat minimalista): sugestões do olá só até a primeira mensagem do
    // usuário (regra pura em shouldShowSuggestions, testável sem Compose).
    val showSuggestions = shouldShowSuggestions(
        hasUserMessage = messages.any { it.fromMe },
        inputBlank = input.isBlank(),
    )

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
                        onAttach = { showAttach = true },
                        onPrefill = { input = it; composerFocus.requestFocus() }
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // #25-fix-a: pílula "N novas" ACIMA do composer (C2) — não
                // flutua sobre a lista; o texto nunca fica coberto. Aparece
                // também durante a geração (busy) para não deixar o usuário
                // sem indicador enquanto o parcial cresce.
                if (unseenCount > 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                unseenCount = 0
                                followEnd = true
                                scope.launch { listState.animateScrollToItem(lastItemIndex) }
                            }
                        ) { Text(if (unseenCount == 1) "Nova mensagem ↓" else "$unseenCount novas ↓") }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                ChatPromptBar(
                    input = input,
                    onInput = { input = it },
                    busy = busy,
                    onSend = { doSend(input) },
                    onAttach = { showAttach = true },
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
                    thinkingMode = thinkingMode,
                    onToggleThinking = vm::toggleThinkingMode,
                    focusRequester = composerFocus,
                )
                // T1 (chat minimalista): caption removida (redundante com o banner).
                Spacer(Modifier.height(4.dp))
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // Onboarding F2a: banner de setup (fora do scroll da conversa).
                // T2: só antes do 1º uso (combinado no VM).
                val r = readiness
                if (showSetupBanner && r != null) {
                    SetupBanner(
                        readiness = r,
                        onImportOutline = { showAttach = true },
                        onPaste = { showAttach = true },
                        onDownloadBases = { doSend("bases") },
                        onDismiss = { vm.dismissSetupBanner() },
                    )
                }
                // Modo guiado seção-a-seção: barra fixa com o tópico em foco,
                // gate de aprovação e navegação (mesma lógica dos comandos).
                // #10c: escopo do chat — em Discurso inteiro a barra some e só
                // o chip fica; em Tópico, barra + chip lado a lado.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val sec = guideCurrent
                    if (scopeMode == ScopeMode.SECTION && sec != null) {
                        Box(Modifier.weight(1f)) {
                            GuideBar(
                                label = guideStripLabel(
                                    index = guideIndex,
                                    total = guideSections.size,
                                    title = sec.title,
                                    minutes = sec.minutes,
                                ),
                                approved = sec.approvedAt != null,
                                onPrev = vm::previousSection,
                                onNext = { vm.nextSection() },
                                onApprove = vm::approveCurrentSection,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    ScopeChip(
                        whole = scopeMode == ScopeMode.WHOLE,
                        onClick = vm::toggleScope,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
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
                                "Conversa deste tópico. Peça ao Copilot para " +
                                    "desenvolver este tópico e depois diga " +
                                    "“insira no tópico”.",
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
                                    onOpenLibrary = onOpenLibrary,
                                    // #22-fix (P2): o ⋮ vive DENTRO do item da
                                    // mensagem (canto superior direito) — sem
                                    // linha própria no LazyColumn.
                                    actions = { mod ->
                                        MessageActions(
                                            m, vm, hasScope = chatScope != null,
                                            ttsReady = ttsReady,
                                            speaking = speakingId == m.id,
                                            onSpeak = {
                                                speakingId = m.id
                                                vm.speakMessage(m.text)
                                            },
                                            onStopSpeak = { vm.stopSpeaking() },
                                            onClearConversation = { confirmClear = true },
                                            modifier = mod,
                                        )
                                    },
                                )
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
                    // FALTA-2: proposta de ESTRUTURA da seção (coexiste com a de texto).
                    structureProposal?.let { sp ->
                        item(key = "structure-proposal") {
                            StructureProposalCard(sp, vm)
                        }
                    }
                    // F5d (Modo B): opções/ângulos — acima do cartão de
                    // aplicação (a escolha alimenta o fluxo do #5b).
                    multiProposal?.let { mp ->
                        item(key = "multi-proposal") {
                            MultiProposalCard(mp, vm)
                        }
                    }
                    // F5b (Modo C): proposta de estrutura da nota inteira.
                    noteStructureProposal?.let { nsp ->
                        item(key = "note-structure-proposal") {
                            NoteStructureProposalCard(nsp, vm)
                        }
                    }
                    // #15d-escopo: card discreto — vincular publicações quando
                    // o RAG falhou por escopo (não bloqueia; 1× por sessão).
                    if (scopeSuggestion) {
                        item(key = "scope-suggestion") {
                            ScopeSuggestionCard(
                                onOpenLibrary = {
                                    vm.dismissScopeSuggestion()
                                    onOpenLibrary()
                                },
                                onDismiss = { vm.dismissScopeSuggestion() },
                            )
                        }
                    }
                    // Estados legíveis: nada de spinner mudo (§30 F15).
                    // Fase real do progresso (parcial do streaming)
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
                                cleanedPartial.isNotBlank()
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
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            buildAnnotatedString {
                                                append(cleanedPartial)
                                                withStyle(
                                                    SpanStyle(
                                                        color = cursorColor.copy(alpha = cursorAlpha)
                                                    )
                                                ) { append("▍") }
                                            },
                                            style = MaterialTheme.typography.bodyLarge
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

    // T2 (Mini Discurso P2): preview + fidelidade + aceitar/descartar/desfazer
    // (o mesmo pipeline do editor — gate de alucinação incluído).
    if (draftState !is com.bettertalker.app.ui.editor.DraftUiState.Idle) {
        com.bettertalker.app.ui.editor.DraftSheet(
            state = draftState,
            sections = draftSections,
            onAccept = onDraftAccept,
            onDismiss = onDraftDismiss,
            onRetry = onDraftRetry,
            onUndo = onDraftUndo,
        )
    }

    // T2 (Fase 2): prévia da estrutura ("insira tudo") antes de persistir.
    val structurePreview by vm.structurePreview.collectAsState()
    structurePreview?.let { preview ->
        StructurePreviewSheet(
            preview = preview,
            onDismiss = vm::dismissStructurePreview,
            onReplace = { vm.applyStructure(replace = true) },
            onAppend = { vm.applyStructure(replace = false) },
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
    onOpenLibrary: () -> Unit,
    /** #22-fix (P2): ações da mensagem renderizadas DENTRO do item (canto). */
    actions: (@Composable (Modifier) -> Unit)? = null,
) {
    if (item.fromMe) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                ChatMessageText(item.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
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
            else -> Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
                    // #22-fix (P1): selo "BT · " inline no primeiro bloco —
                    // sem coluna de avatar (texto em x=0).
                    ChatMessageText(
                        item.text,
                        Modifier.fillMaxWidth(),
                        onUseInCard = { vm.insertTextIntoScope(it) },
                        leadingMark = assistantInlineMark(),
                    )
                    // #17-fix: tempo de resposta sob a bolha (payload; sem schema).
                    item.durationMs?.let { ms ->
                        if (ms > 0) {
                            Text(
                                "pensou ${"%.1f".format(ms / 1000.0)}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                // #22-fix (P2): ⋮ no canto superior direito do bloco.
                if (hasMessageActions(item) && actions != null) {
                    actions(Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

/** #22-fix (P1): selo "BT · " inline — substitui a coluna de avatar (24dp). */
@Composable
private fun assistantInlineMark(): AnnotatedString {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(color) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            ) { append("BT · ") }
        }
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
    onClearConversation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // T1 (P2 estético): háptica leve nos chips de ação.
        val haptic = LocalHapticFeedback.current
        // T1 (chat minimalista): ações escondidas em overflow ⋯ — mesma
        // Fase 18 §21 (proposta F5), F2.3 (tópico), T4 (voz) e T2 (mini),
        // sem poluir a conversa.
        var menuOpen by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    menuOpen = true
                },
                // #22-fix (P2): ⋮ compacto (32dp) dentro do item da mensagem.
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.MoreVert, "Mais ações", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Fase 18 §21: sugestão conversacional vira proposta F5.
                DropdownMenuItem(
                    text = { Text("Criar proposta") },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        menuOpen = false
                        vm.createProposal(item.id)
                    }
                )
                // F2.3: manda a resposta para o tópico em foco (roteamento
                // pelo alvo atual; sem foco, cai no comportamento legado).
                if (hasScope) {
                    DropdownMenuItem(
                        text = { Text("Inserir no tópico") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            menuOpen = false
                            vm.insertTextIntoScope(item.text)
                        }
                    )
                }
                // T4 (voz): lê a resposta em voz alta (texto pós-gate).
                DropdownMenuItem(
                    text = { Text(speakButtonLabel(speaking)) },
                    enabled = ttsReady,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        menuOpen = false
                        if (speaking) onStopSpeak() else onSpeak()
                    }
                )
                // T2 (Mini Discurso P2): gera o mini discurso do tópico em foco.
                if (hasScope) {
                    DropdownMenuItem(
                        text = { Text("Gerar mini discurso") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            menuOpen = false
                            vm.requestMiniSpeech()
                        }
                    )
                }
                // T6 (Fase 2.1): "Limpar conversa" veio do sheet Ferramentas
                // (com diálogo de confirmação do ChatScreen).
                DropdownMenuItem(
                    text = {
                        Text("Limpar conversa", color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        menuOpen = false
                        onClearConversation()
                    }
                )
            }
        }
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

/**
 * T1 (chat minimalista): quick actions do olá só antes da 1ª mensagem do
 * usuário e com o composer vazio. Puro/testável (sem Compose).
 */
internal fun shouldShowSuggestions(hasUserMessage: Boolean, inputBlank: Boolean): Boolean =
    !hasUserMessage && inputBlank

/**
 * #25-fix-a: índice do último ITEM DE MENSAGEM — alvo do auto-scroll quando
 * chega mensagem nova. Cards (fontes/proposta/estrutura) e estados renderizam
 * DEPOIS das mensagens e NÃO entram aqui (antes, o alvo era o último item e o
 * card escondia a resposta). Puro/testável.
 */
internal fun lastMessageIndex(
    messageCount: Int,
    hasContext: Boolean,
    hasTopicEmpty: Boolean,
): Int {
    val offset = (if (hasContext) 1 else 0) + (if (hasTopicEmpty) 1 else 0)
    return (offset + messageCount - 1).coerceAtLeast(0)
}

/** #25-fix-a: ação do efeito de mensagem nova. Puro/testável. */
internal sealed interface MessagesEffect {
    data class ScrollTo(val index: Int) : MessagesEffect
    data object CountUnseen : MessagesEffect
    data object Nothing : MessagesEffect
}

/**
 * #25-fix-a — o efeito observa SÓ `messages.size` (busy não conta): mensagem
 * nova com followEnd = rola até a última mensagem; sem followEnd = conta uma
 * nova; lista vazia = nada. Puro/testável.
 */
internal fun messagesEffect(
    messageCount: Int,
    hasContext: Boolean,
    hasTopicEmpty: Boolean,
    followEnd: Boolean,
): MessagesEffect = when {
    messageCount <= 0 -> MessagesEffect.Nothing
    followEnd -> MessagesEffect.ScrollTo(lastMessageIndex(messageCount, hasContext, hasTopicEmpty))
    else -> MessagesEffect.CountUnseen
}

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
 * fim. Espelha a ordem real dos itens (contexto, mensagens — já com as ações
 * dentro —, fontes, proposta, estados). Puro/testável.
 */
internal fun chatLastItemIndex(
    messageCount: Int,
    hasContext: Boolean,
    hasTopicEmpty: Boolean,
    hasProvenance: Boolean,
    hasProposal: Boolean,
    runStateItems: Int
): Int {
    val total = (if (hasContext) 1 else 0) +
        (if (hasTopicEmpty) 1 else 0) +
        messageCount +
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

/**
 * #15b — pipeline de render da bolha do assistente: primeiro o badge de
 * sugestão ([stripSuggestionTags] detecta `〈sugestão〉`), depois a remoção
 * das demais tags internas (`〈decisão〉`, órfãs) via stripPromptTags — o
 * badge continua (o par `〈sugestão〉` já saiu na 1ª etapa). Puro/testável.
 */
internal fun cleanedForRender(md: String): SuggestionText {
    val suggested = stripSuggestionTags(md)
    return suggested.copy(
        text = com.bettertalker.app.data.util.stripPromptTags(suggested.text)
    )
}

/**
 * #21-fix: strip por bloco — o parse roda no texto cru (a tag
 * `<texto-do-card>` já virou bloco próprio) e cada bloco perde os marcadores
 * internos; o conteúdo do card fica preservado (só os marcadores saem).
 * Puro/testável.
 */
internal fun cleanBlocks(blocks: List<MdBlock>): List<MdBlock> = blocks.map { b ->
    when (b) {
        is MdBlock.Title -> b.copy(text = cleanedForRender(b.text).text)
        is MdBlock.Quote -> b.copy(text = cleanedForRender(b.text).text)
        is MdBlock.Bullet -> b.copy(text = cleanedForRender(b.text).text)
        is MdBlock.Check -> b.copy(text = cleanedForRender(b.text).text)
        is MdBlock.Para -> b.copy(text = cleanedForRender(b.text).text)
        is MdBlock.TextForCard -> b.copy(text = stripInternalTags(b.text))
        is MdBlock.Divider -> b
    }
}

/** Texto formatado do assistente (markdown próprio, selecionável). */
@Composable
fun ChatMessageText(
    md: String,
    modifier: Modifier = Modifier,
    onUseInCard: ((String) -> Unit)? = null,
    /** #22-fix (P1): selo prependado ao primeiro bloco de texto (ex.: "BT · "). */
    leadingMark: AnnotatedString? = null,
) {
    if (md.isBlank()) return
    // #21-fix: parse ANTES do strip — o <texto-do-card> vira bloco próprio
    // (posição preservada) e o strip de marcadores roda por bloco.
    val hasSuggestion = remember(md) { stripSuggestionTags(md).hasSuggestion }
    val blocks = remember(md) { cleanBlocks(parseBlocks(md)) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hasSuggestion) {
                Text(
                    "💡 Sugestão criativa",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            var markPending = leadingMark
            blocks.forEach { block ->
                // #22-fix (P1): o selo entra no PRIMEIRO bloco de texto
                // (Divider/TextForCard não consomem).
                val isTextBlock = block is MdBlock.Title || block is MdBlock.Quote ||
                    block is MdBlock.Bullet || block is MdBlock.Check || block is MdBlock.Para
                val mark = if (isTextBlock) markPending else null
                if (isTextBlock) markPending = null
                // #20-fix-a: render extraído (reusado pelo preview do card).
                RenderBlock(block, mark, onUseInCard)
            }
        }
    }
}

/**
 * #20-fix-a: render de um bloco markdown — extraído do ChatMessageText para
 * ser reusado dentro do `<texto-do-card>` (preview formatado).
 */
@Composable
private fun RenderBlock(
    block: MdBlock,
    mark: AnnotatedString?,
    onUseInCard: ((String) -> Unit)?,
) {
    when (block) {
        is MdBlock.Title -> Text(
            markPlain(block.text, mark),
            // P1: pesos e lineHeights vêm da AppTypography.
            style = if (block.level >= 3) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 4.dp)
        )
        is MdBlock.Quote -> RenderQuote(block.text, mark)
        is MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
        is MdBlock.Bullet -> RenderBullet(block, mark)
        is MdBlock.Check -> RenderCheck(block, mark)
        is MdBlock.Para -> Text(
            markInline(block.text, mark),
            style = MaterialTheme.typography.bodyLarge
        )
        is MdBlock.TextForCard -> RenderTextForCard(block, onUseInCard)
    }
}

@Composable
private fun RenderQuote(text: String, mark: AnnotatedString?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
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
            markInline(text, mark),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

@Composable
private fun RenderBullet(block: MdBlock.Bullet, mark: AnnotatedString?) {
    Row(
        verticalAlignment = Alignment.Top,
        // #22-fix-b: recuo progressivo por profundidade.
        modifier = Modifier.padding(start = (block.depth * 16).dp),
    ) {
        Text(
            // T2: lista ordenada preserva o número; #22-fix-b:
            // sub-nível usa ○ (como o web); topo segue "•".
            bulletMarker(block.number, block.depth),
            style = MaterialTheme.typography.bodyLarge,
            // #22-fix-b: o negrito é do primeiro nível.
            fontWeight = if (block.depth == 0) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(min = 26.dp)
        )
        Text(markInline(block.text, mark), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RenderCheck(block: MdBlock.Check, mark: AnnotatedString?) {
    Row(
        verticalAlignment = Alignment.Top,
        // #22-fix-b: recuo progressivo por profundidade.
        modifier = Modifier.padding(start = (block.depth * 16).dp),
    ) {
        Text(
            if (block.done) "☑  " else "☐  ",
            style = MaterialTheme.typography.bodyLarge,
            color = if (block.done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.widthIn(min = 26.dp)
        )
        Text(markInline(block.text, mark), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * #20-fix-a: preview do `<texto-do-card>` — parseia markdown dentro do bloco
 * (títulos, listas, sub-listas com ○). O INSERT continua passando o texto
 * CRU (`onUseInCard(block.text)`) — o editor rich entende markdown.
 */
@Composable
private fun RenderTextForCard(
    block: MdBlock.TextForCard,
    onUseInCard: ((String) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                MaterialTheme.shapes.medium,
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Texto para o card",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (onUseInCard != null) {
                TextButton(onClick = { onUseInCard(block.text) }) {
                    Text("⤓ Usar no card")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val innerBlocks = remember(block.text) { cardInnerBlocks(block.text) }
        innerBlocks.forEach { inner ->
            RenderBlock(inner, mark = null, onUseInCard = null)
        }
    }
}

/**
 * #20-fix-a: blocos do preview do card — cards aninhados são ignorados
 * (nunca existem na prática). Puro/testável.
 */
internal fun cardInnerBlocks(text: String): List<MdBlock> =
    parseBlocks(text).filter { it !is MdBlock.TextForCard }

/** #22-fix-b: marcador do item de lista (○ em sub-níveis; • no topo). Puro/testável. */
internal fun bulletMarker(number: Int?, depth: Int): String =
    number?.let { "$it.  " } ?: if (depth > 0) "○  " else "•  "

/** #22-fix (P1): texto do bloco com o selo prependado (quando houver). */
private fun markPlain(text: String, mark: AnnotatedString?): AnnotatedString =
    if (mark == null) AnnotatedString(text)
    else buildAnnotatedString { append(mark); append(text) }

@Composable
private fun markInline(text: String, mark: AnnotatedString?): AnnotatedString =
    if (mark == null) inline(text) else buildAnnotatedString { append(mark); append(inline(text)) }

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

/**
 * Modo guiado seção-a-seção: barra fixa acima da conversa com o tópico em
 * foco, gate de aprovação e navegação. Os botões usam a MESMA lógica dos
 * comandos de texto (`vm.nextSection/previousSection/approveCurrentSection`).
 */
@Composable
private fun GuideBar(
    label: String,
    approved: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onApprove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Seção anterior")
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (approved) {
            Icon(
                Icons.Filled.CheckCircle,
                "Seção aprovada",
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onApprove) { Text("Aprovar") }
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Próxima seção")
        }
    }
}

/**
 * #10c — chip do escopo do chat: "Tópico" (default) ↔ "Discurso inteiro".
 * Um toque alterna; em WHOLE a GuideBar some e a escrita pede o tópico.
 */
@Composable
private fun ScopeChip(
    whole: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(if (whole) "Discurso inteiro" else "Tópico") },
        leadingIcon = {
            Icon(
                Icons.Default.SwapHoriz,
                null,
                modifier = Modifier.size(16.dp),
            )
        },
        modifier = modifier,
    )
}
