package com.bettertalker.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.bettertalker.app.ui.assistant.AssistantLanding
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
                            "Formato não reconhecido ($name). Toque Importar na Biblioteca e escolha o arquivo."
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
            // v10: JWPUB antigo sem data-pnum ganha §N no início do <p>
            // (label/índice do DocumentParagraph; `it` por data-pid).
            // Reindexar uma vez após atualizar o extrator.
            if (settings.needsIndexFormat(10)) libRepo.reindexAll()
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
    fun goHome() = goTab(Routes.HOME)
    fun goTrain() = goTab(Routes.TRAIN_HOME)
    fun goPresent() = goTab(Routes.PRESENT_HOME)

    // "Inserir material → Escolher arquivo": a nota nasce aqui e o editor
    // consome o uri pendente ao abrir (importa o esboço).
    var pendingImport by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, android.net.Uri>?>(null)
    }
    // "Colar e Pronto" v3: aviso de estrutura (esboço/template de discurso)
    // exibido no editor recém-aberto — par nota→aviso, como o pendingImport.
    var editorNotice by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
    }
    val notesRepo = remember {
        com.bettertalker.app.data.repo.NotesRepository(ctx.applicationContext, db)
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
                    onHome = ::goHome,
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
        composable(Routes.ASSISTANT) {
            val assistantVm: AssistantViewModel = viewModel(
                factory = AssistantViewModel.Factory(db, settings),
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
                    onChangeNote = ::goHome,
                    onBack = {},
                    onOpenEditor = { nav.navigate(Routes.editor(target.id)) },
                )
            } else {
                AssistantLanding(
                    notes = notes.orEmpty(),
                    loading = notes == null,
                    onOpenNote = { id ->
                        // Fixa o contexto antes de abrir o editor; voltar já
                        // cai no chat dessa nota.
                        scope.launch { settings.setLastNoteId(id) }
                        nav.navigate(Routes.editor(id))
                    },
                )
            }
        }
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(ctx, db, settings))
            // compartilha o mesmo VM com a Lixeira (mesmo ciclo da Home)
            HomeScreen(
                vm,
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                // #10b: ⚙ → SettingsScreen (Conta/Aparência/IA).
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onImportMaterial = { uri ->
                    scope.launch {
                        val id = notesRepo.newNote(null)
                        pendingImport = id to uri
                        nav.navigate(Routes.editor(id))
                    }
                },
                // "Colar texto": abre o editor já estruturado + aviso quando a
                // estrutura foi aplicada (esboço S-34 ou template de discurso).
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
                onPickNote = { nav.navigate(Routes.picker("train")) },
                onGoToLibrary = ::goHome,
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
            com.bettertalker.app.ui.hub.PresentHomeScreen(
                hubVm,
                onPresent = { nav.navigate(Routes.present(it)) },
                onOpenTribuna = { nav.navigate(Routes.tribuna(it)) },
                onGenerateTribuna = { id -> nav.navigate(Routes.editor(id, generate = true)) },
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onPickNote = { nav.navigate(Routes.picker("present")) },
                onGoToLibrary = ::goHome,
            )
        }
        composable(
            Routes.PICKER,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = entry.arguments?.getString("mode") ?: "train"
            val hubVm: com.bettertalker.app.ui.hub.HubViewModel = viewModel(
                factory = com.bettertalker.app.ui.hub.HubViewModel.Factory(ctx, db)
            )
            com.bettertalker.app.ui.hub.HubPickerScreen(
                vm = hubVm,
                mode = mode,
                onBack = { nav.popBackStack() },
            )
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
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
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
                navArgument("generate") { type = NavType.BoolType; defaultValue = false },
            )
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            val autoGenerate = back.arguments?.getBoolean("generate") ?: false
            // Navegação global: memoriza a última nota aberta (Treinar/Apresentar).
            androidx.compose.runtime.LaunchedEffect(noteId) { settings.setLastNoteId(noteId) }
            // v4.0: mesma sessão usada pela aba Assistente e pelo chat antigo.
            val session = remember(noteId) { sessionFor(noteId) }
            val vm: EditorViewModel = session.editorVm
            val copilotVm: CopilotViewModel = session.copilotVm
            // Auto-gerar Tribuna se vindo do PresentHome
            if (autoGenerate) {
                androidx.compose.runtime.LaunchedEffect(noteId) {
                    copilotVm.generateTribuna { ok ->
                        if (ok) nav.navigate(Routes.tribuna(noteId)) {
                            popUpTo(Routes.EDITOR) { inclusive = true }
                        }
                    }
                }
            }
            // "Escolher arquivo" da Home: importa o esboço na nota recém-criada.
            val pending = pendingImport
            androidx.compose.runtime.LaunchedEffect(noteId, pending) {
                if (pending != null && pending.first == noteId) {
                    pendingImport = null
                    // autoLink: a Home importa e a nota já abre com as seções
                    // (Introdução + corpos + Conclusão) em vez de só o draft.
                    copilotVm.importOutlineFile(pending.second, autoLink = true)
                }
            }
            // 3.5e.2/3.5e.3: injeta o gerador de draft (provider do diálogo LLM).
            androidx.compose.runtime.LaunchedEffect(vm) {
                val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx)
                val remote = com.bettertalker.app.data.llm.ProviderFactory.resolveRemote(settings, ctx)
                val canGenerate = com.bettertalker.app.data.llm.ProviderFactory.useRemoteRoute(remote)
                val generator: com.bettertalker.app.domain.planning.SectionGenerator =
                    com.bettertalker.app.data.planning.SectionGeneratorImpl(
                        com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, ctx)
                    )
                vm.setSectionGenerator(generator, canGenerate)
                // "Texto total" no chat: fila de drafts seção a seção.
                copilotVm.setDraftQueueStarter { targets -> vm.startDraftQueue(targets) }
            }
            // tema do esboço vira título da nota
            val titleEv by copilotVm.titleEvent.collectAsState()
            androidx.compose.runtime.LaunchedEffect(titleEv) {
                val t = titleEv
                if (!t.isNullOrBlank()) {
                    vm.onTitle(t)
                    copilotVm.consumeTitle()
                }
            }
            // T2 (Bug #10): seção ativa do editor vira foco do Copilot
            // (fonte de "Criar proposta"; antes o foco nunca era setado).
            val activeSectionTitle by vm.activeSectionTitle.collectAsState()
            androidx.compose.runtime.LaunchedEffect(activeSectionTitle) {
                copilotVm.setActiveBlock(activeSectionTitle)
            }
            // Card-first: captura a seleção ENQUANTO o editor está na tela
            // (a rota do chat não vê mudanças de seleção do editor).
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
                // v4.0: o editor é imersivo e volta para a porta de entrada.
                onBack = {
                    if (!nav.popBackStack(Routes.ASSISTANT, inclusive = false)) {
                        nav.popBackStack()
                    }
                },
                onAttach = { nav.navigate(Routes.library(noteId)) },
                onOpenChat = { nav.navigate(Routes.chat(noteId)) },
                copilotVm = copilotVm,
                onGenerateTribuna = {
                    // Só abre a Tribuna se a geração salvou ao menos uma
                    // cápsula; falha permanece no editor com erro/retry.
                    copilotVm.generateTribuna { ok ->
                        if (ok) {
                            nav.navigate(Routes.tribuna(noteId))
                        } else {
                            editorNotice = noteId to
                                "Não consegui gerar a Tribuna. Tente novamente pelo menu ⋮ → Gerar Tribuna."
                        }
                    }
                },
                // #7e2: empty state reusa o pipeline do chat (mesmo VM por nota).
                onImportOutline = { uri -> copilotVm.importOutlineFile(uri) },
                onPasteOutline = { text -> copilotVm.pasteOutline(text) },
                // "Colar e Pronto" v3: aviso de estrutura aplicada (uma vez).
                notice = editorNotice?.takeIf { it.first == noteId }?.second,
                onNoticeShown = { editorNotice = null },
            )
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
            com.bettertalker.app.ui.present.PresentSessionScreen(
                presentVm,
                onBack = { nav.popBackStack() },
                onOpenEditor = { nav.navigate(Routes.editor(noteId)) },
                onOpenTribuna = { nav.navigate(Routes.tribuna(noteId)) },
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
            com.bettertalker.app.ui.tribuna.TribunaScreen(
                tribunaVm, onBack = { nav.popBackStack() }
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
            )
        }
        composable(Routes.MODEL) { _ ->
            val vm: com.bettertalker.app.ui.aimodel.ModelViewModel =
                viewModel(factory = com.bettertalker.app.ui.aimodel.ModelViewModel.Factory(ctx))
            com.bettertalker.app.ui.aimodel.ModelScreen(vm, onBack = { nav.popBackStack() })
        }
    }
    }
}

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
    /** v4.0 Walkie: “Trocar nota” leva ao seletor/Esboços. */
    onChangeNote: () -> Unit = {},
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
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
    val nextStep by copilotVm.nextStepSuggestion.collectAsState()

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
            nextStep = nextStep,
            onPttPress = pttPressChecked,
            onPttRelease = { ptt.release() },
            onPttCancel = { ptt.cancel() },
            onOpenChat = {
                walkie = false
                fullFocusRequest = false
            },
            onDigit = {
                walkie = false
                fullFocusRequest = true
            },
            onOpenNote = onOpenEditor,
            onChangeNote = onChangeNote,
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
            nav.navigate(Routes.editor(session.noteId))
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
