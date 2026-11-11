package com.bettertalker.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bettertalker.app.navigation.resolveAssistantNoteId
import com.bettertalker.app.domain.help.AssistantHelpContext
import com.bettertalker.app.domain.help.HelpAction
import com.bettertalker.app.domain.help.HelpErrorKind
import com.bettertalker.app.domain.help.HelpSource
import com.bettertalker.app.domain.help.HelpTriggerDecision
import com.bettertalker.app.domain.help.HelpTriggerEngine
import com.bettertalker.app.domain.help.HelpTriggerId
import com.bettertalker.app.domain.onboarding.OnboardingFlow
import com.bettertalker.app.domain.onboarding.OnboardingV5State
import com.bettertalker.app.domain.onboarding.UpgradeNoticeAction
import com.bettertalker.app.ui.assistant.AssistantViewModel
import com.bettertalker.app.ui.session.NoteSession
import com.bettertalker.app.ui.session.NoteSessionHostViewModel
import com.bettertalker.app.ui.voice.PttHostViewModel
import com.bettertalker.app.ui.theme.texturedBackground
import com.bettertalker.app.data.copilot.ResolvedChatNav
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.repo.LibraryRepository
import com.bettertalker.app.navigation.Routes
import com.bettertalker.app.ui.copilot.ChatScreen
import com.bettertalker.app.ui.copilot.CopilotViewModel
import com.bettertalker.app.ui.copilot.TribunaResult
import com.bettertalker.app.ui.editor.EditorScreen
import com.bettertalker.app.ui.editor.EditorViewModel
import com.bettertalker.app.ui.account.AccountScreen
import com.bettertalker.app.ui.account.AccountViewModel
import com.bettertalker.app.ui.home.HomeScreen
import com.bettertalker.app.ui.settings.BackupScreen
import com.bettertalker.app.ui.settings.SettingsScreen
import com.bettertalker.app.ui.home.HomeViewModel
import com.bettertalker.app.ui.home.TrashScreen
import com.bettertalker.app.ui.library.LibraryScreen
import com.bettertalker.app.ui.library.LibraryViewModel
import com.bettertalker.app.ui.stage.StageScreen
import com.bettertalker.app.ui.stage.StageViewModel
import com.bettertalker.app.ui.theme.BetterTalkerTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Ação pendente ao cair na Home vinda do onboarding/ajuda. */
private enum class HomeLaunchAction { CREATE, PASTE, SCRATCH }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash Screen (Android 12+): mostra o tema de abertura (navy + ícone)
        // e troca para o tema principal quando o app está pronto.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A1: migra a chave histórica em texto claro para o Android Keystore
        // antes de qualquer tela ler a configuração. Idempotente e em IO.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                com.bettertalker.app.data.prefs.SettingsStore(applicationContext)
                    .migrateLegacySecrets()
            }
        }
        // Sync periódica independe de o usuário abrir a tela de Conta.
        com.bettertalker.app.data.cloud.SyncScheduler.ensurePeriodic(applicationContext)
        enableEdgeToEdge()
        setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val settings = androidx.compose.runtime.remember(ctx) {
                com.bettertalker.app.data.prefs.SettingsStore(ctx.applicationContext)
            }
            val mode by settings.themeMode.collectAsState(
                initial = com.bettertalker.app.ui.theme.ThemeMode.AUTO
            )
            val palette by settings.paletteId.collectAsState(
                initial = com.bettertalker.app.ui.theme.PaletteId.SAMSUNG
            )
            // #23-fix-a: TTS premium (Piper Jeff) — singleton de PROCESSO.
            // #23-fix-b: 100% offline — o modelo vem de assets (openAsset).
            // Nenhuma tela derruba o engine; só [TtsLifecycle.stop] ao sair.
            val voiceStore = com.bettertalker.app.data.voice.TtsLifecycle.store(ctx)
            val voiceRouter = com.bettertalker.app.data.voice.TtsLifecycle.router(ctx)
            // Contador de usuários do TTS: no último, apenas stop() — o
            // engine de processo continua vivo para a próxima tela.
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.bettertalker.app.data.voice.TtsLifecycle.acquire()
                onDispose { com.bettertalker.app.data.voice.TtsLifecycle.release() }
            }
            val premiumPref by settings.voicePremiumEnabled.collectAsState(initial = false)
            val ttsVoicePref by settings.ttsVoiceName.collectAsState(initial = null)
            androidx.compose.runtime.LaunchedEffect(ttsVoicePref) {
                voiceRouter.setSystemVoice(ttsVoicePref)
            }
            androidx.compose.runtime.LaunchedEffect(premiumPref) {
                // v2.7.2: instalações antigas já têm o modelo, mas não o
                // en_dict (fallback do espeak). Provisiona antes de carregar
                // o Piper; sem isso o Chat continuaria usando o subset velho.
                if (premiumPref) runCatching { voiceStore.ensureProvisioned() }
                voiceRouter.setPremiumEnabled(premiumPref)
                voiceRouter.ensurePiperOrFallback()
            }
            BetterTalkerTheme(mode = mode, palette = palette) {
                AppNav(settings, voiceRouter, voiceStore)
            }
        }
    }

    override fun onDestroy() {
        // Fim real do app (não rotação): para a fala sem derrubar o engine.
        if (isFinishing) com.bettertalker.app.data.voice.TtsLifecycle.stop()
        super.onDestroy()
    }
}

@Composable
private fun AppNav(
    settings: com.bettertalker.app.data.prefs.SettingsStore,
    voiceRouter: com.bettertalker.app.data.voice.VoiceRouter,
    voiceStore: com.bettertalker.app.data.voice.PiperModelStore,
) {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = DbProvider.get(ctx)
    val libRepo = LibraryRepository(ctx, db)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Reconhece downloads via worker (sobrevive a rotação/morte do app).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.bettertalker.app.ui.jw.JwDownloadDialog.onEnqueued = { dmId, name, pageUrl ->
            scope.launch {
                try {
                    val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val ph = libRepo.insertPlaceholder(name, null, dmId, pageUrl)
                        if (ph != null) {
                            libRepo.enqueueRegisterDownload(dmId, name, ph)
                            "Download iniciado — acompanhe na Biblioteca"
                        } else {
                            // antes: descarte silencioso ("baixada e nunca indexada")
                            com.bettertalker.app.data.util.PublicationFormatPolicy
                                .downloadBlockedReason(name)
                                ?: "Formato não reconhecido ($name). Toque Importar na Biblioteca e escolha o arquivo."
                        }
                    }
                    android.widget.Toast.makeText(
                        ctx, msg,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) { }
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // v11 (6.0a): o índice passa a guardar o texto INTEGRAL do
            // trecho (antes `text` era cortado em 500 chars). Reindexar uma
            // vez após atualizar o PassageBuilder; publicações já indexadas
            // ficam com o texto antigo até este reindex.
            if (settings.needsIndexFormat(11)) libRepo.reindexAll()
        }
    }

    // Navegação global: barra na Home/hubs/Tribuna (o Ensaio é imersivo);
    // esconde com o teclado aberto (IME).
    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
    val showGlobalBar = com.bettertalker.app.navigation.shouldShowGlobalBar(currentRoute) && !imeOpen
    // Textura de fundo global (Aparência): pintada atrás de TODO o conteúdo.
    val textureName by settings.homeTexture.collectAsState(initial = "NONE")
    val homeTexture = com.bettertalker.app.ui.theme.TextureId.fromNameOrDefault(textureName)
    val textureIntensity by settings.textureIntensity.collectAsState(initial = 0.05f)

    // Abas: o Assistente é a raiz; as demais preservam estado ao alternar.
    fun goTab(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun goAssistant() = goTab(Routes.ASSISTANT)
    fun goLibrary() = goTab(Routes.library())
    fun goPrepare() = goTab(Routes.PREPARE)
    fun goHome() = nav.navigate(Routes.home())
    fun goTrain() = goTab(Routes.TRAIN_HOME)
    fun goPresent() = goTab(Routes.PRESENT_HOME)

    // "Inserir material → Escolher arquivo": a nota nasce aqui e o editor
    // consome o uri pendente ao abrir (importa o esboço).
    var pendingImport by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, android.net.Uri>?>(null)
    }
    // 5.5: create sheet compartilhado do Assistente/Preparo.
    var showGlobalCreateSheet by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // "Colar e Pronto" v3: aviso de estrutura (esboço/template de discurso)
    // exibido no editor recém-aberto — par nota→aviso, como o pendingImport.
    var editorNotice by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
    }
    val notesRepo = remember {
        com.bettertalker.app.data.repo.NotesRepository(ctx.applicationContext, db)
    }

    // ---------- v5.0: onboarding, ajuda local e gatilhos ----------
    val onboardingState by settings.onboardingV5State.collectAsState(initial = null)
    val upgradeDismissed by settings.upgradeV5Dismissed.collectAsState(initial = null)
    val meaningfulNoteCount by db.noteDao().observeUserCount()
        .collectAsState(initial = null)
    val activeNoteCount by db.noteDao().observeCount().collectAsState(initial = null)
    var onboardingDecisionDone by rememberSaveable { mutableStateOf(false) }
    var showUpgradeSheet by rememberSaveable { mutableStateOf(false) }
    var helpContext by remember { mutableStateOf<AssistantHelpContext?>(null) }
    var homeLaunchAction by remember { mutableStateOf<HomeLaunchAction?>(null) }
    var helpTrigger by remember { mutableStateOf<HelpTriggerDecision?>(null) }
    var helpTriggerShownThisSession by rememberSaveable { mutableStateOf(false) }
    var assistantChatRequest by remember { mutableStateOf(0) }

    val helpImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val id = notesRepo.newNote(null)
                settings.setLastNoteId(id)
                pendingImport = id to uri
                nav.navigate(Routes.editor(id))
            }
        }
    }
    // 5.5: create sheet compartilhado (Assistente/Preparo) → importar S-34.
    val assistantImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val id = notesRepo.newNote(null)
                settings.setLastNoteId(id)
                pendingImport = id to uri
                nav.navigate(Routes.editor(id))
            }
        }
    }

    fun openHelp(context: AssistantHelpContext) {
        helpContext = context
        scope.launch { settings.incrementHelpMetric("help_opened") }
    }

    fun dismissUpgradeNotice() {
        showUpgradeSheet = false
        scope.launch { settings.markUpgradeV5Dismissed() }
    }

    fun openUpgradeNoticeManually() {
        showUpgradeSheet = true
        scope.launch { settings.incrementHelpMetric("upgrade_welcome_manual") }
    }

    fun handleHelpAction(action: HelpAction, context: AssistantHelpContext) {
        scope.launch { settings.incrementHelpMetric("help_action_clicked") }
        when (action) {
            HelpAction.OPEN_IMPORT -> helpImportPicker.launch(arrayOf("*/*"))
            HelpAction.OPEN_PASTE -> {
                homeLaunchAction = HomeLaunchAction.PASTE
                if (currentRoute != Routes.HOME) nav.navigate(Routes.home())
            }
            HelpAction.OPEN_CREATE -> {
                homeLaunchAction = HomeLaunchAction.CREATE
                if (currentRoute != Routes.HOME) nav.navigate(Routes.home())
            }
            HelpAction.OPEN_EDITOR -> context.noteId
                ?.let { nav.navigate(Routes.editor(it)) }
                ?: nav.navigate(Routes.home())
            HelpAction.OPEN_LIBRARY -> {
                if (context.noteId != null) {
                    nav.navigate(Routes.library(context.noteId))
                } else {
                    goLibrary()
                }
            }
            HelpAction.OPEN_MODEL -> nav.navigate(Routes.MODEL)
            HelpAction.OPEN_TRAIN -> goTrain()
            HelpAction.OPEN_PRESENT -> goPresent()
            HelpAction.OPEN_TRIBUNA -> context.noteId
                ?.let { nav.navigate(Routes.tribuna(it)) }
                ?: goPresent()
            HelpAction.OPEN_VOICE -> nav.navigate(Routes.VOICE)
            HelpAction.OPEN_ASSISTANT -> {
                assistantChatRequest++
                nav.navigate(Routes.ASSISTANT)
            }
            HelpAction.OPEN_HELP_TOPIC -> {
                scope.launch { settings.incrementHelpMetric("help_reopened") }
                nav.navigate(Routes.onboarding(true))
            }
            HelpAction.NONE -> Unit
        }
    }

    // Decisão de onboarding/upgrade só depois de banco e DataStore prontos.
    // O estado UPGRADE_SEEN sozinho não suprime: dismissed é a fonte de verdade.
    androidx.compose.runtime.LaunchedEffect(
        onboardingState, upgradeDismissed, meaningfulNoteCount,
    ) {
        if (onboardingDecisionDone) return@LaunchedEffect
        val state = onboardingState ?: return@LaunchedEffect
        val dismissed = upgradeDismissed ?: return@LaunchedEffect
        val meaningful = meaningfulNoteCount ?: return@LaunchedEffect
        onboardingDecisionDone = true
        when (OnboardingFlow.upgradeNoticeAction(state, meaningful, dismissed)) {
            UpgradeNoticeAction.SHOW_UPGRADE -> {
                // Não grava UPGRADE_SEEN aqui: se o processo morrer antes do
                // dismiss, o sheet volta na próxima abertura.
                settings.markUpgradeV5Seen()
                settings.incrementHelpMetric("upgrade_welcome_seen")
                showUpgradeSheet = true
            }
            UpgradeNoticeAction.FULL_ONBOARDING -> {
                settings.incrementHelpMetric("onboarding_started")
                nav.navigate(Routes.onboarding(false)) { launchSingleTop = true }
            }
            UpgradeNoticeAction.NONE -> Unit
        }
    }

    // Gatilho inline mínimo: biblioteca vazia. Os erros de geração/fontes já
    // têm CTA própria; não duplicamos. No máximo um por sessão.
    androidx.compose.runtime.LaunchedEffect(
        currentRoute, activeNoteCount, onboardingState, showUpgradeSheet, imeOpen,
        helpTriggerShownThisSession,
    ) {
        if (helpTriggerShownThisSession) return@LaunchedEffect
        if (onboardingState?.settled != true) {
            helpTrigger = null
            return@LaunchedEffect
        }
        if (currentRoute != Routes.HOME || activeNoteCount != 0 || imeOpen || showUpgradeSheet) {
            helpTrigger = null
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        val decision = HelpTriggerEngine.choose(
            candidates = setOf(HelpTriggerId.EMPTY_LIBRARY),
            states = settings.helpTriggerStates(),
            now = now,
            onboardingSettled = true,
        ) ?: return@LaunchedEffect
        settings.markHelpTriggerSeen(HelpTriggerId.EMPTY_LIBRARY, now)
        settings.incrementHelpMetric("trigger_shown")
        helpTriggerShownThisSession = true
        helpTrigger = decision
    }


    // v4.0: uma sessão por nota, compartilhada entre editor, chat antigo e
    // aba Assistente. O host é um ViewModel da Activity: sobrevive à rotação
    // e libera os ViewModelStores no fim real do processo.
    val sessionHost: NoteSessionHostViewModel = viewModel(
        factory = NoteSessionHostViewModel.Factory(ctx, db, voiceRouter),
    )
    fun sessionFor(noteId: String): NoteSession = sessionHost.sessionFor(noteId)

    // v4.0: PTT compartilhado pelo assistente e pelo chat do editor.
    val pttHost: PttHostViewModel = viewModel(
        factory = PttHostViewModel.Factory(ctx),
    )

    // 5.5a: corpo único do editor para a rota normal (com back) e para a
    // aba Preparo (root, sem back, com "Trocar esboço").
    @Composable
    fun EditorDestination(
        noteId: String,
        rootTab: Boolean,
        onBack: () -> Unit,
        onSwitchNote: (() -> Unit)?,
    ) {
        androidx.compose.runtime.LaunchedEffect(noteId) { settings.setLastNoteId(noteId) }
        val session = remember(noteId) { sessionFor(noteId) }
        val vm: EditorViewModel = session.editorVm
        val copilotVm: CopilotViewModel = session.copilotVm
        val pending = pendingImport
        androidx.compose.runtime.LaunchedEffect(noteId, pending) {
            if (pending != null && pending.first == noteId) {
                pendingImport = null
                copilotVm.importOutlineFile(pending.second, autoLink = true)
            }
        }
        androidx.compose.runtime.LaunchedEffect(vm) {
            val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)
            val remote = com.bettertalker.app.data.llm.ProviderFactory.resolveRemote(settings, ctx)
            val canGenerate = com.bettertalker.app.data.llm.ProviderFactory.useRemoteRoute(remote)
            val generator: com.bettertalker.app.domain.planning.SectionGenerator =
                com.bettertalker.app.data.planning.SectionGeneratorImpl(
                    com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, ctx)
                )
            vm.setSectionGenerator(generator, canGenerate)
            copilotVm.setDraftQueueStarter { targets -> vm.startDraftQueue(targets) }
        }
        val titleEv by copilotVm.titleEvent.collectAsState()
        androidx.compose.runtime.LaunchedEffect(titleEv) {
            val t = titleEv
            if (!t.isNullOrBlank()) {
                vm.onTitle(t)
                copilotVm.consumeTitle()
            }
        }
        val importNotice by copilotVm.importNotice.collectAsState()
        androidx.compose.runtime.LaunchedEffect(importNotice) {
            importNotice?.let {
                editorNotice = noteId to it
                copilotVm.consumeImportNotice()
            }
        }
        val activeSectionTitle by vm.activeSectionTitle.collectAsState()
        androidx.compose.runtime.LaunchedEffect(activeSectionTitle) {
            copilotVm.setActiveBlock(activeSectionTitle)
        }
        val liveSelection by vm.selection.collectAsState()
        androidx.compose.runtime.LaunchedEffect(liveSelection) {
            val sel = liveSelection
            copilotVm.setSelection(
                text = sel?.selectedText.orEmpty(),
                sectionId = sel?.sectionId,
                subPointId = sel?.subPointId,
            )
        }
        EditorScreen(
            vm,
            onBack = onBack,
            onAttach = { nav.navigate(Routes.library(noteId)) },
            onOpenChat = { nav.navigate(Routes.chat(noteId)) },
            copilotVm = copilotVm,
            onOpenHelp = { sectionId, sectionTitle ->
                openHelp(
                    AssistantHelpContext(
                        route = if (rootTab) Routes.PREPARE else Routes.EDITOR,
                        noteId = noteId,
                        sectionId = sectionId,
                        sectionTitle = sectionTitle,
                        source = HelpSource.HELP_BUTTON,
                    )
                )
            },
            onGenerateTribuna = {
                copilotVm.generateTribuna { result ->
                    if (result is TribunaResult.Success) {
                        nav.navigate(Routes.tribuna(noteId))
                    } else {
                        val reason = (result as? TribunaResult.Failure)?.message
                        editorNotice = noteId to (reason
                            ?: "Não consegui gerar o mapa mental. " +
                            "Tente novamente pelo menu ⋮ → Mapa mental.")
                    }
                }
            },
            onImportOutline = { uri -> copilotVm.importOutlineFile(uri) },
            onPasteOutline = { text -> copilotVm.pasteOutline(text) },
            notice = editorNotice?.takeIf { it.first == noteId }?.second,
            onNoticeShown = { editorNotice = null },
            rootTab = rootTab,
            onSwitchNote = onSwitchNote,
        )
    }

    androidx.compose.material3.Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = androidx.compose.ui.Modifier.texturedBackground(
            texture = homeTexture,
            background = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            intensity = textureIntensity,
        ),
        bottomBar = {
            if (showGlobalBar) {
                com.bettertalker.app.ui.components.GlobalBottomBar(
                    currentRoute = currentRoute,
                    onAssistant = ::goAssistant,
                    onLibrary = ::goLibrary,
                    onPrepare = ::goPrepare,
                    onTrain = ::goTrain,
                    onPresent = ::goPresent,
                )
            }
        },
    ) { pad ->
        NavHost(
            nav,
            startDestination = Routes.ASSISTANT,
            modifier = androidx.compose.ui.Modifier.padding(pad),
        ) {
        composable(
            Routes.ONBOARDING,
            arguments = listOf(
                navArgument("replay") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val replay = entry.arguments?.getBoolean("replay") ?: false
            fun leaveOnboarding(state: OnboardingV5State?, metric: String?, route: String?) {
                scope.launch {
                    if (!replay) {
                        state?.let { settings.setOnboardingV5State(it) }
                        metric?.let { settings.incrementHelpMetric(it) }
                        if (metric?.startsWith("path_") == true) {
                            settings.incrementHelpMetric("onboarding_completed")
                        }
                    }
                }
                if (!nav.popBackStack()) nav.navigate(Routes.ASSISTANT)
                if (route != null) nav.navigate(route)
            }
            com.bettertalker.app.ui.onboarding.OnboardingScreen(
                replay = replay,
                onSkip = {
                    leaveOnboarding(
                        state = if (replay) null else OnboardingV5State.DISMISSED,
                        metric = if (replay) null else "onboarding_skipped",
                        route = null,
                    )
                },
                onImport = { uri ->
                    scope.launch {
                        val id = notesRepo.newNote(null)
                        pendingImport = id to uri
                        if (!replay) {
                            settings.setOnboardingV5State(OnboardingV5State.DONE)
                            settings.incrementHelpMetric("path_import")
                            settings.incrementHelpMetric("onboarding_completed")
                        }
                        if (!nav.popBackStack()) nav.navigate(Routes.ASSISTANT)
                        nav.navigate(Routes.editor(id))
                    }
                },
                onPaste = {
                    leaveOnboarding(
                        state = if (replay) null else OnboardingV5State.DONE,
                        metric = if (replay) null else "path_paste",
                        route = null,
                    )
                    homeLaunchAction = HomeLaunchAction.PASTE
                    nav.navigate(Routes.home())
                },
                onCreate = {
                    leaveOnboarding(
                        state = if (replay) null else OnboardingV5State.DONE,
                        metric = if (replay) null else "path_create",
                        route = null,
                    )
                    homeLaunchAction = HomeLaunchAction.CREATE
                    nav.navigate(Routes.home())
                },
                onExplore = {
                    if (!replay) {
                        scope.launch { settings.incrementHelpMetric("path_explore") }
                    }
                },
                onFinish = { openLibrary ->
                    if (!openLibrary) assistantChatRequest++
                    leaveOnboarding(
                        state = if (replay) null else OnboardingV5State.DONE,
                        metric = if (replay) null else "onboarding_completed",
                        route = if (openLibrary) Routes.home() else null,
                    )
                },
            )
        }
        composable(Routes.ASSISTANT) {            val assistantVm: AssistantViewModel = viewModel(
                factory = AssistantViewModel.Factory(db, settings),
            )
            // v4.1: assistente global (sem nota) + walkie da nota vinculada.
            val globalVm: com.bettertalker.app.ui.assistant.GlobalAssistantViewModel = viewModel(
                factory = com.bettertalker.app.ui.assistant.GlobalAssistantViewModel.Factory(
                    ctx, db, settings, voiceRouter,
                ),
            )
            val notes by assistantVm.notes.collectAsState()
            val lastNoteId by assistantVm.lastNoteId.collectAsState()
            val practice by assistantVm.practiceContext.collectAsState()
            val availableIds = remember(notes) {
                notes?.map { it.id }?.toSet().orEmpty()
            }
            val targetId = resolveAssistantNoteId(lastNoteId, availableIds)
            val target = remember(notes, targetId) {
                notes?.firstOrNull { it.id == targetId }
            }
            // Nota apagada/na lixeira: não restaura um contexto fantasma.
            androidx.compose.runtime.LaunchedEffect(notes, lastNoteId, targetId) {
                if (notes != null && lastNoteId != null && targetId == null) {
                    assistantVm.clearLastNoteId()
                    assistantVm.clearPracticeContext()
                }
            }
            if (target != null) {
                // Fatia 5: recarrega readiness de Treino/Palco ao entrar na aba
                // (o ensaio acontece fora desta composição).
                androidx.compose.runtime.LaunchedEffect(target.id) {
                    assistantVm.refreshPracticeContext(target.id)
                }
                NoteChatHost(
                    session = sessionFor(target.id),
                    nav = nav,
                    ptt = pttHost.controller,
                    embedded = true,
                    // Sem foco vindo do editor, o assistente começa no
                    // discurso inteiro (não num "Tópico" sem número).
                    defaultWholeFocus = true,
                    practiceContext = practice?.takeIf { it.noteId == target.id },
                    walkieDefault = true,
                    // 5.5: “Trocar nota” abre Meus esboços em seleção.
                    onChangeNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.ASSISTANT)) },
                    // v5.1: “Conversa geral” limpa o vínculo e volta à home global.
                    onGeneralChat = {
                        assistantVm.clearLastNoteId()
                        assistantVm.clearPracticeContext()
                        globalVm.clearBinding()
                    },
                    onOpenMyOutlines = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.ASSISTANT)) },
                    onCreateNote = { showGlobalCreateSheet = true },
                    onOpenLibrary = ::goLibrary,
                    onOpenPrepare = ::goPrepare,
                    onOpenTrain = ::goTrain,
                    onOpenPresent = ::goPresent,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onBack = {},
                    onOpenEditor = ::goPrepare,
                    onOpenHelp = {
                        openHelp(
                            AssistantHelpContext(
                                route = Routes.ASSISTANT,
                                noteId = target.id,
                                noteTitle = target.title,
                                source = HelpSource.HELP_BUTTON,
                            )
                        )
                    },
                )
            } else {
                val globalNotes by globalVm.notes.collectAsState()
                com.bettertalker.app.ui.assistant.AssistantGlobalRoute(
                    vm = globalVm,
                    notes = globalNotes,
                    loading = notes == null,
                    ptt = pttHost.controller,
                    onPickNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.ASSISTANT)) },
                    onOpenMyOutlines = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.ASSISTANT)) },
                    onCreateNote = { showGlobalCreateSheet = true },
                    onOpenLibrary = ::goLibrary,
                    onOpenPrepare = ::goPrepare,
                    onOpenTrain = ::goTrain,
                    onOpenPresentRoot = ::goPresent,
                    onOpenModel = { nav.navigate(Routes.MODEL) },
                    onStartTrain = { id -> nav.navigate(Routes.stage(id)) },
                    onOpenPresent = { id -> nav.navigate(Routes.present(id)) },
                    onOpenHelp = {
                        openHelp(
                            AssistantHelpContext(
                                route = Routes.ASSISTANT,
                                source = HelpSource.HELP_BUTTON,
                            )
                        )
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    openChatRequest = assistantChatRequest,
                )
            }
        }
        composable(
            Routes.HOME,
            arguments = listOf(
                navArgument("intent") {
                    type = NavType.StringType
                    defaultValue = "manage"
                },
            ),
        ) { back ->
            val intent = com.bettertalker.app.navigation.homeIntentFromRoute(
                back.arguments?.getString("intent"),
            )
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(ctx, db, settings))
            HomeScreen(
                vm,
                intent = intent,
                onOpenNote = { id ->
                    scope.launch { settings.setLastNoteId(id) }
                    goPrepare()
                },
                onSelectNote = { id ->
                    scope.launch {
                        when (intent) {
                            com.bettertalker.app.navigation.HomeIntent.ASSISTANT,
                            com.bettertalker.app.navigation.HomeIntent.PREPARE ->
                                settings.setLastNoteId(id)
                            com.bettertalker.app.navigation.HomeIntent.TRAIN ->
                                settings.setTrainNoteId(id)
                            com.bettertalker.app.navigation.HomeIntent.PRESENT ->
                                settings.setPresentNoteId(id)
                            com.bettertalker.app.navigation.HomeIntent.MANAGE -> Unit
                        }
                    }
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
                onOpenLibrary = ::goLibrary,
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                onOpenHelp = {
                    openHelp(
                        AssistantHelpContext(
                            route = when (intent) {
                                com.bettertalker.app.navigation.HomeIntent.ASSISTANT ->
                                    Routes.ASSISTANT
                                com.bettertalker.app.navigation.HomeIntent.PREPARE ->
                                    Routes.PREPARE
                                com.bettertalker.app.navigation.HomeIntent.TRAIN ->
                                    Routes.TRAIN_HOME
                                com.bettertalker.app.navigation.HomeIntent.PRESENT ->
                                    Routes.PRESENT_HOME
                                com.bettertalker.app.navigation.HomeIntent.MANAGE ->
                                    Routes.HOME
                            },
                            source = HelpSource.HELP_BUTTON,
                        )
                    )
                },
                startCreateSheet = homeLaunchAction == HomeLaunchAction.CREATE,
                startPasteSheet = homeLaunchAction == HomeLaunchAction.PASTE,
                startScratchSheet = homeLaunchAction == HomeLaunchAction.SCRATCH,
                onLaunchConsumed = { homeLaunchAction = null },
                helpTrigger = helpTrigger,
                onHelpTriggerAction = {
                    val decision = helpTrigger
                    helpTrigger = null
                    if (decision != null) {
                        handleHelpAction(
                            decision.action,
                            AssistantHelpContext(
                                route = Routes.HOME,
                                source = HelpSource.TRIGGER,
                            ),
                        )
                    }
                },
                onDismissHelpTrigger = {
                    helpTrigger = null
                    scope.launch {
                        settings.dismissHelpTrigger(HelpTriggerId.EMPTY_LIBRARY)
                        settings.incrementHelpMetric("trigger_dismissed")
                    }
                },
                onSnoozeHelpTrigger = { helpTrigger = null },
                onImportMaterial = { uri ->
                    scope.launch {
                        val id = notesRepo.newNote(null)
                        settings.setLastNoteId(id)
                        pendingImport = id to uri
                        nav.navigate(Routes.editor(id))
                    }
                },
                onPasteCreated = { id, notice ->
                    if (notice != null) editorNotice = id to notice
                    nav.navigate(Routes.editor(id))
                },
                onTrainNote = { id ->
                    scope.launch {
                        settings.setTrainNoteId(id)
                        goTrain()
                    }
                },
                onPresentNote = { id ->
                    scope.launch {
                        settings.setPresentNoteId(id)
                        goPresent()
                    }
                },
            )
        }
        // Hubs de escolha (Treinar/Apresentar): lista visual das notas.
        composable(Routes.TRAIN_HOME) {
            val hubVm: com.bettertalker.app.ui.hub.HubViewModel = viewModel(
                factory = com.bettertalker.app.ui.hub.HubViewModel.Factory(ctx, db)
            )
            // Fatia 5: a nota atual do Treino é o contexto do Assistente.
            val trainId by hubVm.trainNoteId.collectAsState()
            androidx.compose.runtime.LaunchedEffect(trainId) {
                trainId?.let { settings.setLastNoteId(it) }
            }
            com.bettertalker.app.ui.hub.TrainHomeScreen(
                hubVm,
                onOpenStage = { nav.navigate(Routes.stage(it)) },
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onPickNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.TRAIN)) },
                onGoToLibrary = ::goLibrary,
                onOpenHelp = {
                    openHelp(
                        AssistantHelpContext(
                            route = Routes.TRAIN_HOME,
                            source = HelpSource.HELP_BUTTON,
                        )
                    )
                },
            )
        }
        composable(Routes.PRESENT_HOME) {
            val hubVm: com.bettertalker.app.ui.hub.HubViewModel = viewModel(
                factory = com.bettertalker.app.ui.hub.HubViewModel.Factory(ctx, db)
            )
            // Fatia 5: a nota atual do Palco é o contexto do Assistente.
            val presentId by hubVm.presentNoteId.collectAsState()
            androidx.compose.runtime.LaunchedEffect(presentId) {
                presentId?.let { settings.setLastNoteId(it) }
            }
            // 4.0.2: gera a Tribuna na própria rota do Palco, com progresso e
            // erro acionável — sem desviar para o editor.
            var tribunaGeneratingNoteId by remember {
                androidx.compose.runtime.mutableStateOf<String?>(null)
            }
            var tribunaFailure by remember {
                androidx.compose.runtime.mutableStateOf<TribunaFailureUi?>(null)
            }
            val startTribuna: (String) -> Unit = { id ->
                if (tribunaGeneratingNoteId == null) {
                    tribunaFailure = null
                    tribunaGeneratingNoteId = id
                    sessionFor(id).copilotVm.generateTribuna { result ->
                        tribunaGeneratingNoteId = null
                        when (result) {
                            TribunaResult.Success -> nav.navigate(Routes.tribuna(id))
                            is TribunaResult.Failure -> tribunaFailure = TribunaFailureUi(
                                noteId = id,
                                message = result.message,
                                canOpenEditor = result.canOpenEditor,
                                canConfigureAi = result.canConfigureAi,
                            )
                        }
                    }
                }
            }
            com.bettertalker.app.ui.hub.PresentHomeScreen(
                hubVm,
                onPresent = { nav.navigate(Routes.present(it)) },
                onOpenTribuna = { nav.navigate(Routes.tribuna(it)) },
                onGenerateTribuna = startTribuna,
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onPickNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.PRESENT)) },
                onGoToLibrary = ::goLibrary,
                onOpenHelp = {
                    openHelp(
                        AssistantHelpContext(
                            route = Routes.PRESENT_HOME,
                            source = HelpSource.HELP_BUTTON,
                        )
                    )
                },
            )
            if (tribunaGeneratingNoteId != null) {
                com.bettertalker.app.ui.hub.TribunaProgressDialog()
            }
            tribunaFailure?.let { failure ->
                com.bettertalker.app.ui.hub.TribunaErrorDialog(
                    message = failure.message,
                    canOpenEditor = failure.canOpenEditor,
                    canConfigureAi = failure.canConfigureAi,
                    onRetry = {
                        tribunaFailure = null
                        startTribuna(failure.noteId)
                    },
                    onOpenEditor = {
                        tribunaFailure = null
                        nav.navigate(Routes.editor(failure.noteId))
                    },
                    onConfigureAi = {
                        tribunaFailure = null
                        nav.navigate(Routes.MODEL)
                    },
                    onOpenHelp = {
                        tribunaFailure = null
                        openHelp(
                            AssistantHelpContext(
                                route = Routes.PRESENT_HOME,
                                noteId = failure.noteId,
                                errorKind = if (failure.canConfigureAi) {
                                    HelpErrorKind.NO_KEY
                                } else {
                                    HelpErrorKind.GENERATION
                                },
                                source = HelpSource.ERROR_ACTION,
                            )
                        )
                    },
                    onDismiss = { tribunaFailure = null },
                )
            }
        }
        composable(Routes.TRASH) { back ->
            val parent = remember(back) { nav.getBackStackEntry(Routes.HOME) }
            val vm: HomeViewModel = viewModel(parent, factory = HomeViewModel.Factory(ctx, db, settings))
            TrashScreen(vm, onBack = { nav.popBackStack() })
        }
        // #10b: Settings centralizada (Conta/Aparência/IA) — ⚙ da Home.
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAccount = { nav.navigate(Routes.ACCOUNT) },
                onOpenModel = { nav.navigate(Routes.MODEL) },
                onOpenAppearance = { nav.navigate(Routes.APPEARANCE) },
                onOpenVoice = { nav.navigate(Routes.VOICE) },
                onOpenDataStorage = { nav.navigate(Routes.DATA_STORAGE) },
            )
        }
        composable(Routes.BACKUP) {
            BackupScreen(
                onBack = { nav.popBackStack() },
                db = db,
            )
        }
        composable(Routes.DATA_STORAGE) {
            com.bettertalker.app.ui.settings.DataStorageSettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenBackup = { nav.navigate(Routes.BACKUP) },
                onOpenLibrary = ::goLibrary,
            )
        }
        composable(Routes.VOICE) {
            com.bettertalker.app.ui.settings.VoiceScreen(
                settings = settings,
                voiceRouter = voiceRouter,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.APPEARANCE) {
            com.bettertalker.app.ui.settings.AppearanceScreen(
                settings,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ACCOUNT) {
            val vm: AccountViewModel = viewModel(factory = AccountViewModel.Factory(ctx))
            AccountScreen(
                vm,
                onBack = { nav.popBackStack() },
                onOpenBackup = { nav.navigate(Routes.BACKUP) },
                onOpenNote = { noteId -> nav.navigate(Routes.editor(noteId)) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("noteId") { type = NavType.StringType },
            )
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            EditorDestination(
                noteId = noteId,
                rootTab = false,
                onBack = {
                    if (!nav.popBackStack(Routes.ASSISTANT, inclusive = false)) {
                        nav.popBackStack()
                    }
                },
                onSwitchNote = null,
            )
        }
        composable(Routes.PREPARE) {
            val lastId by settings.lastNoteId.collectAsState(initial = null)
            val notes by db.noteDao().observe(null, "")
                .collectAsState(initial = emptyList())
            val note = notes.firstOrNull { it.id == lastId }
            if (note == null) {
                com.bettertalker.app.ui.prepare.PrepareEmptyScreen(
                    onPickNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.PREPARE)) },
                    onCreateNote = { showGlobalCreateSheet = true },
                )
            } else {
                EditorDestination(
                    noteId = note.id,
                    rootTab = true,
                    onBack = {},
                    onSwitchNote = { nav.navigate(Routes.home(com.bettertalker.app.navigation.HomeIntent.PREPARE)) },
                )
            }
        }
        composable(
            Routes.STAGE,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            // Fatia 5: o Assistente segue a nota da prática.
            androidx.compose.runtime.LaunchedEffect(noteId) {
                settings.setTrainNoteId(noteId)
                settings.setLastNoteId(noteId)
            }
            // Voz premium (Piper): provisionada no 1º Ensaio (sem UI de
            // gerenciamento) e reaplicada depois — o roteador pode ter caído
            // no nativo no boot por falta do modelo no disco.
            androidx.compose.runtime.LaunchedEffect(noteId) {
                runCatching { voiceStore.ensureProvisioned() }
                runCatching {
                    voiceRouter.setPremiumEnabled(settings.voicePremiumEnabled.first())
                }
            }
            val stageVm: StageViewModel = viewModel(
                key = "stage-$noteId",
                factory = StageViewModel.Factory(ctx, db, settings, noteId, voiceRouter)
            )
            StageScreen(stageVm, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.PRESENT,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            // Fatia 5: o Assistente segue a nota da prática.
            androidx.compose.runtime.LaunchedEffect(noteId) {
                settings.setPresentNoteId(noteId)
                settings.setLastNoteId(noteId)
            }
            val presentVm: com.bettertalker.app.ui.present.PresentViewModel = viewModel(
                key = "present-$noteId",
                factory = com.bettertalker.app.ui.present.PresentViewModel.Factory(db, settings, noteId),
            )
            val presentBibleVm: com.bettertalker.app.ui.bible.BibleReaderViewModel =
                viewModel(
                    key = "bible-$noteId",
                    factory = com.bettertalker.app.ui.bible.BibleReaderViewModel.Factory(
                        db, voiceRouter,
                    ),
                )
            com.bettertalker.app.ui.present.PresentSessionScreen(
                presentVm,
                onBack = { nav.popBackStack() },
                onOpenEditor = { nav.navigate(Routes.editor(noteId)) },
                onOpenTribuna = { nav.navigate(Routes.tribuna(noteId)) },
                bibleVm = presentBibleVm,
                onOpenLibrary = { nav.navigate(Routes.library(noteId)) },
            )
        }
        // #20-fix-b: Tribuna — leitura do mapa de memória.
        composable(
            Routes.TRIBUNA,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            // Fatia 5: a Tribuna também é contexto de Palco.
            androidx.compose.runtime.LaunchedEffect(noteId) {
                settings.setPresentNoteId(noteId)
                settings.setLastNoteId(noteId)
            }
            val tribunaVm: com.bettertalker.app.ui.tribuna.TribunaViewModel = viewModel(
                key = "tribuna-$noteId",
                factory = com.bettertalker.app.ui.tribuna.TribunaViewModel.Factory(
                    ctx, db, settings, noteId, voiceRouter
                ),
            )
            val tribunaBibleVm: com.bettertalker.app.ui.bible.BibleReaderViewModel =
                viewModel(
                    key = "bible-$noteId",
                    factory = com.bettertalker.app.ui.bible.BibleReaderViewModel.Factory(
                        db, voiceRouter,
                    ),
                )
            com.bettertalker.app.ui.tribuna.TribunaScreen(
                tribunaVm,
                onBack = { nav.popBackStack() },
                onOpenHelp = {
                    openHelp(
                        AssistantHelpContext(
                            route = Routes.TRIBUNA,
                            noteId = noteId,
                            source = HelpSource.HELP_BUTTON,
                        )
                    )
                },
                onRegenerateSection = { sectionId, done ->
                    sessionFor(noteId).copilotVm.regenerateTribunaSection(sectionId) { result ->
                        done((result as? TribunaResult.Failure)?.message)
                    }
                },
                bibleVm = tribunaBibleVm,
                onOpenLibrary = { nav.navigate(Routes.library(noteId)) },
            )
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            NoteChatHost(
                session = sessionFor(noteId),
                nav = nav,
                ptt = pttHost.controller,
                embedded = false,
                onBack = { nav.popBackStack() },
                onOpenEditor = { nav.popBackStack() },
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("linkNote") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
            )
        ) { back ->
            val linkNote = back.arguments?.getString("linkNote")?.ifBlank { null }
            val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(db, libRepo))
            LibraryScreen(
                vm,
                onBack = { nav.popBackStack() },
                linkNoteId = linkNote,
                onOpenModel = { nav.navigate(Routes.MODEL) },
                showBack = linkNote != null,
            )
        }
        composable(Routes.MODEL) { _ ->
            val vm: com.bettertalker.app.ui.aimodel.ModelViewModel =
                viewModel(factory = com.bettertalker.app.ui.aimodel.ModelViewModel.Factory(ctx))
            com.bettertalker.app.ui.aimodel.ModelScreen(vm, onBack = { nav.popBackStack() })
        }
    }

    if (showGlobalCreateSheet) {
        com.bettertalker.app.ui.components.CreateNoteSheet(
            onDismiss = { showGlobalCreateSheet = false },
            onImportS34 = {
                showGlobalCreateSheet = false
                assistantImportPicker.launch(arrayOf("*/*"))
            },
            onPasteVm = {
                showGlobalCreateSheet = false
                homeLaunchAction = HomeLaunchAction.PASTE
                nav.navigate(Routes.home())
            },
            onStartFromScratch = {
                showGlobalCreateSheet = false
                homeLaunchAction = HomeLaunchAction.SCRATCH
                nav.navigate(Routes.home())
            },
            onQuickNote = {
                showGlobalCreateSheet = false
                scope.launch {
                    val id = notesRepo.newNote(null)
                    settings.setLastNoteId(id)
                }
            },
        )
    }

    if (showUpgradeSheet) {
        com.bettertalker.app.ui.onboarding.OnboardingUpgradeSheet(
            onDismiss = { dismissUpgradeNotice() },
            onOpenHelp = {
                dismissUpgradeNotice()
                openHelp(
                    AssistantHelpContext(
                        route = currentRoute ?: Routes.ASSISTANT,
                        source = HelpSource.HELP_REOPEN,
                    )
                )
            },
        )
    }

    helpContext?.let { context ->
        com.bettertalker.app.ui.help.HelpSheet(
            context = context,
            onDismiss = { helpContext = null },
            onAction = { action ->
                helpContext = null
                handleHelpAction(action, context)
            },
            onAskBt = {
                helpContext = null
                val note = context.noteId
                if (note != null) {
                    nav.navigate(Routes.chat(note))
                } else {
                    assistantChatRequest++
                    nav.navigate(Routes.ASSISTANT)
                }
            },
            onReplayOnboarding = {
                helpContext = null
                scope.launch { settings.incrementHelpMetric("help_reopened") }
                nav.navigate(Routes.onboarding(true))
            },
            onOpenUpgradeNotice = {
                helpContext = null
                openUpgradeNoticeManually()
            },
            onTopicAction = {
                scope.launch { settings.incrementHelpMetric("topic_opened") }
            },
        )
    }
    }
}

/** Estado do erro de geração da Tribuna no Palco (diálogo acionável). */
private data class TribunaFailureUi(
    val noteId: String,
    val message: String,
    val canOpenEditor: Boolean,
    val canConfigureAi: Boolean,
)

/**
 * Corpo compartilhado do chat de uma nota (rota antiga do editor e aba
 * Assistente). Usa a MESMA sessão para preservar fio, proposta e drafts.
 */
@Composable
private fun NoteChatHost(
    session: NoteSession,
    nav: androidx.navigation.NavHostController,
    ptt: com.bettertalker.app.data.voice.PttController,
    embedded: Boolean,
    defaultWholeFocus: Boolean = false,
    /** Fatia 5: snapshot de Treino/Palco (só no Assistente). */
    practiceContext: com.bettertalker.app.data.copilot.PracticeSnapshot? = null,
    /** v4.0 Walkie: o Assistente abre no modo PTT grande. */
    walkieDefault: Boolean = false,
    /** v4.0 Walkie: “Trocar nota” abre o seletor de contexto. */
    onChangeNote: () -> Unit = {},
    /** v5.1 Walkie: “Conversa geral” desvincula a nota e volta à home global. */
    onGeneralChat: () -> Unit = {},
    /** 5.5: Meus esboços e create sheet no menu do Assistente. */
    onOpenMyOutlines: () -> Unit = {},
    onCreateNote: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenPrepare: () -> Unit = {},
    onOpenTrain: () -> Unit = {},
    onOpenPresent: () -> Unit = {},
    /** v5.1: ⚙ da top bar do Assistente. */
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenHelp: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val editorVm = session.editorVm
    val copilotVm = session.copilotVm

    // O chat pode ser restaurado sem a rota do editor: garante o gerador
    // para o fluxo "texto total" → DraftSheet.
    androidx.compose.runtime.LaunchedEffect(editorVm) {
        val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)
        val remote = com.bettertalker.app.data.llm.ProviderFactory.resolveRemote(settings, ctx)
        val canGenerate = com.bettertalker.app.data.llm.ProviderFactory.useRemoteRoute(remote)
        editorVm.setSectionGenerator(
            com.bettertalker.app.data.planning.SectionGeneratorImpl(
                com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, ctx)
            ),
            canGenerate,
        )
    }
    // tema do esboço vira título da nota
    val chatTitleEv by copilotVm.titleEvent.collectAsState()
    androidx.compose.runtime.LaunchedEffect(chatTitleEv) {
        val t = chatTitleEv
        if (!t.isNullOrBlank()) {
            editorVm.onTitle(t)
            copilotVm.consumeTitle()
        }
    }
    val draftState by editorVm.draftState.collectAsState()
    val editorSections by editorVm.sections.collectAsState()
    val mdText by editorVm.mdText.collectAsState()

    // v4.0 Walkie: apresentação local da sessão; o chat completo é um toque.
    var walkie by androidx.compose.runtime.saveable.rememberSaveable(
        session.noteId,
        walkieDefault,
    ) {
        androidx.compose.runtime.mutableStateOf(walkieDefault)
    }
    var fullFocusRequest by androidx.compose.runtime.saveable.rememberSaveable(
        session.noteId,
    ) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val walkieNoteTitle by copilotVm.noteTitle.collectAsState()
    val activeTurn by copilotVm.activeTurn.collectAsState()

    // Fatia 5: snapshot de prática no prompt do chat (nunca resultado de treino).
    androidx.compose.runtime.LaunchedEffect(practiceContext?.promptBlock) {
        copilotVm.setPracticeContext(practiceContext?.promptBlock)
    }

    // Fatia 4 — navegação/mentoria: o chat pede; a tela dona da rota executa.
    val navScope = androidx.compose.runtime.rememberCoroutineScope()
    val navSettings = androidx.compose.runtime.remember(ctx) {
        com.bettertalker.app.data.prefs.SettingsStore(ctx)
    }
    val navigateFromChat: (ResolvedChatNav) -> Unit =
        remember(editorVm, copilotVm, nav, session.noteId, embedded, onOpenEditor) {
            { request ->
                when (request) {
                    is ResolvedChatNav.OpenSection -> {
                        editorVm.scrollToSection(request.sectionId)
                        onOpenEditor()
                    }
                    is ResolvedChatNav.DevelopCurrent -> {
                        copilotVm.send("Desenvolva este ponto")
                    }
                    ResolvedChatNav.StartTrain -> {
                        navScope.launch {
                            navSettings.setTrainNoteId(session.noteId)
                            nav.navigate(Routes.stage(session.noteId))
                        }
                    }
                    ResolvedChatNav.OpenPresent -> {
                        navScope.launch {
                            navSettings.setPresentNoteId(session.noteId)
                            nav.navigate(Routes.present(session.noteId))
                        }
                    }
                }
            }
        }
    androidx.compose.runtime.LaunchedEffect(copilotVm, nav) {
        copilotVm.navigationRequests.collect { navigateFromChat(it) }
    }

    // v4.0 — PTT: a sessão ativa troca os callbacks do motor compartilhado.
    // A transcrição entra direto no fio (sem composer) e o turno por voz é o
    // único que auto-fala a resposta.
    val pttState by ptt.state.collectAsState()
    val pttPartial by ptt.partial.collectAsState()
    val pttChoice by ptt.choice.collectAsState()
    val messages by copilotVm.messages.collectAsState()
    val chatBusy by copilotVm.chatBusy.collectAsState()
    val ttsSpeaking by copilotVm.ttsSpeaking.collectAsState()
    val ttsReady by copilotVm.ttsReady.collectAsState()
    // v4.0 Walkie: mesmo gate de permissão/barge-in do composer.
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (ttsSpeaking) copilotVm.stopSpeaking()
            if (chatBusy) copilotVm.stopGeneration()
            ptt.press()
        }
    }
    val pttPressChecked: () -> Unit = {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (ttsSpeaking) copilotVm.stopSpeaking()
            if (chatBusy) copilotVm.stopGeneration()
            ptt.press()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    var pttError by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var pttAwaiting by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var pttSawBusy by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var pttStartIndex by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0)
    }
    val latestMessages by androidx.compose.runtime.rememberUpdatedState(messages)
    androidx.compose.runtime.DisposableEffect(ptt, copilotVm) {
        ptt.onTranscript = { text ->
            pttAwaiting = true
            pttSawBusy = false
            pttStartIndex = latestMessages.size
            copilotVm.send(text)
        }
        ptt.onError = { pttError = it }
        ptt.onStopSpeaking = { copilotVm.stopSpeaking() }
        ptt.onStopThinking = { copilotVm.stopGeneration() }
        onDispose {
            // Só encerra a escuta; os callbacks ficam para a próxima tela
            // sobrescrever (evita corrida de dispose vs. nova composição).
            ptt.cancel()
        }
    }
    androidx.compose.runtime.LaunchedEffect(chatBusy, pttAwaiting, messages) {
        if (!pttAwaiting) return@LaunchedEffect
        if (chatBusy) {
            pttSawBusy = true
            return@LaunchedEffect
        }
        if (!pttSawBusy) return@LaunchedEffect
        val newAssistant = messages.drop(pttStartIndex)
            .lastOrNull { !it.fromMe }
            ?: return@LaunchedEffect
        if (newAssistant.kind == "text" &&
            newAssistant.text.isNotBlank() &&
            ttsReady
        ) {
            copilotVm.speakMessage(newAssistant.text)
            ptt.onResponseFinished(spoken = true)
        } else {
            ptt.onResponseFinished(spoken = false)
        }
        pttAwaiting = false
        pttSawBusy = false
    }
    androidx.compose.runtime.LaunchedEffect(ptt, pttAwaiting) {
        if (!pttAwaiting) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        if (pttAwaiting) {
            ptt.onResponseFinished(spoken = false)
            pttAwaiting = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(ttsSpeaking) {
        if (ttsSpeaking) ptt.onSpeakingStarted() else ptt.onSpeakingFinished()
    }

    if (walkieDefault && walkie) {
        com.bettertalker.app.ui.assistant.AssistantWalkieScreen(
            noteTitle = walkieNoteTitle,
            practiceContext = practiceContext,
            messages = messages,
            activeTurn = activeTurn,
            pttState = pttState,
            pttPartial = pttPartial,
            ttsSpeaking = ttsSpeaking,
            onPttPress = pttPressChecked,
            onPttRelease = { ptt.release() },
            onPttCancel = { ptt.cancel() },
            onOpenChat = {
                walkie = false
                fullFocusRequest = false
            },
            onOpenNote = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onOpenEditor()
            },
            onChangeNote = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onChangeNote()
            },
            onGeneralChat = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onGeneralChat()
            },
            onOpenMyOutlines = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onOpenMyOutlines()
            },
            onCreateNote = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onCreateNote()
            },
            onOpenSettings = {
                ptt.cancel()
                copilotVm.stopSpeaking()
                onOpenSettings()
            },
            onOpenHelp = onOpenHelp,
            onConfigureAi = { nav.navigate(Routes.MODEL) },
            onRetry = { copilotVm.retry() },
        )
    } else {
    ChatScreen(
        copilotVm,
        onBack = onBack,
        onInsert = { text, heading ->
            // F2.3: insere no tópico em foco; sem foco, comportamento legado.
            editorVm.queueInsertForTarget(copilotVm.currentTarget(), text, heading)
        },
        onOpenLibrary = { nav.navigate(Routes.library(session.noteId)) },
        embedded = embedded,
        onOpenNote = if (embedded) ({
            onOpenPrepare()
        }) else null,
        onOpenEditor = onOpenEditor,
        onNavigate = navigateFromChat,
        practiceContext = practiceContext,
        onBackToWalkie = if (walkieDefault) ({ walkie = true }) else null,
        focusComposerOnStart = fullFocusRequest,
        onComposerFocusConsumed = { fullFocusRequest = false },
        pttState = pttState,
        pttPartial = pttPartial,
        pttChoice = pttChoice,
        pttError = pttError,
        onPttPress = ptt::press,
        onPttRelease = ptt::release,
        onPttCancel = ptt::cancel,
        onPttErrorShown = { pttError = null },
        headings = com.bettertalker.app.data.util.headingsOf(mdText),
        draftState = draftState,
        draftSections = editorSections,
        onDraftAccept = editorVm::acceptDraft,
        onDraftDismiss = editorVm::dismissDraft,
        onDraftUndo = editorVm::undoDraft,
        onDraftRetry = {
            (draftState as? com.bettertalker.app.ui.editor.DraftUiState.Error)
                ?.target?.let(editorVm::startDraft)
        },
        onDraftNext = editorVm::nextDraft,
        onDraftContinue = editorVm::continueDraft,
    )
    }
    androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
        copilotVm.setDraftQueueStarter { targets -> editorVm.startDraftQueue(targets) }
    }
    // Fase 3.5b.3: dossiê da seção ativa sob demanda (F2b: com alvo explícito).
    androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
        copilotVm.setDossierProvider { target -> editorVm.buildDossierFor(target) }
    }
    // Onboarding F2a: contexto empurrado pelo FAB (seleção + prontidão).
    androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
        editorVm.pushedContext.collect { copilotVm.setPushedContext(it) }
    }
    // Modo guiado seção-a-seção: seções reais do editor -> chat.
    androidx.compose.runtime.LaunchedEffect(editorSections) {
        copilotVm.setGuideSections(editorSections.map { it.section })
    }
    // Modo guiado seção-a-seção: foco do chat -> rolagem no editor.
    androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
        copilotVm.focusRequests.collect { editorVm.scrollToSection(it) }
    }
    // Assistente restaurado sem foco do editor: lente = discurso inteiro.
    if (defaultWholeFocus) {
        val focusMode by copilotVm.focusMode.collectAsState()
        val chatScope by copilotVm.chatScope.collectAsState()
        androidx.compose.runtime.LaunchedEffect(focusMode, chatScope) {
            if (chatScope == null &&
                focusMode == com.bettertalker.app.ui.copilot.FocusMode.SECTION
            ) {
                copilotVm.selectWholeFocus()
            }
        }
    }
}
