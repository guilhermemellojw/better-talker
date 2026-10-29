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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bettertalker.app.ui.theme.texturedBackground
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
            // v7: parágrafo do JWPUB indexado pelo número REAL (data-pnum) —
            // antes o §N caía na PERGUNTA do esboço e o texto do parágrafo
            // ficava sem numeração (a citação devolvia o título).
            if (settings.needsIndexFormat(7)) libRepo.reindexAll()
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

    // Hubs de escolha de nota (Treinar/Apresentar) — o usuário escolhe
    // visualmente; nada de "última nota" silenciosa nem sheet.
    fun goTrain() {
        nav.navigate(Routes.TRAIN_HOME) { launchSingleTop = true }
    }
    fun goPresent() {
        nav.navigate(Routes.PRESENT_HOME) { launchSingleTop = true }
    }

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
                    onHome = {
                        nav.navigate(Routes.HOME) {
                            launchSingleTop = true
                            popUpTo(Routes.HOME)
                        }
                    },
                    onTrain = ::goTrain,
                    onPresent = ::goPresent,
                )
            }
        },
    ) { pad ->
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = androidx.compose.ui.Modifier.padding(pad),
        ) {
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
            )
        }
        // Hubs de escolha (Treinar/Apresentar): lista visual das notas.
        composable(Routes.TRAIN_HOME) {
            val hubVm: com.bettertalker.app.ui.hub.HubViewModel = viewModel(
                factory = com.bettertalker.app.ui.hub.HubViewModel.Factory(ctx, db)
            )
            com.bettertalker.app.ui.hub.TrainHomeScreen(
                hubVm,
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.stage(it)) },
            )
        }
        composable(Routes.PRESENT_HOME) {
            val hubVm: com.bettertalker.app.ui.hub.HubViewModel = viewModel(
                factory = com.bettertalker.app.ui.hub.HubViewModel.Factory(ctx, db)
            )
            com.bettertalker.app.ui.hub.PresentHomeScreen(
                hubVm,
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.present(it)) },
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
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
            AccountScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            // Navegação global: memoriza a última nota aberta (Treinar/Apresentar).
            androidx.compose.runtime.LaunchedEffect(noteId) { settings.setLastNoteId(noteId) }
            val vm: EditorViewModel = viewModel(key = noteId, factory = EditorViewModel.Factory(ctx, db, noteId))
            val copilotVm: CopilotViewModel = viewModel(key = "cop-$noteId", factory = CopilotViewModel.Factory(ctx, db, noteId, voiceRouter))
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
                onBack = { nav.popBackStack() },
                onAttach = { nav.navigate(Routes.library(noteId)) },
                onFindDownloads = { nav.navigate(Routes.library(noteId, scan = true)) },
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
            // EditorViewModel do editor (mesma store da rota editor p/ enfileirar inserções)
            val editorEntry = remember(back) { nav.getBackStackEntry(Routes.EDITOR) }
            // Fase 18 §41: UMA instância de CopilotViewModel por nota, ancorada na
            // rota do editor — proposta/verificação/estado sobrevivem ao
            // editor↔chat. (Antes, cada rota tinha sua instância e o Voltar
            // destruía a proposta em exibição.)
            val copilotVm: CopilotViewModel = viewModel(
                editorEntry, key = "cop-$noteId",
                factory = CopilotViewModel.Factory(ctx, db, noteId, voiceRouter)
            )
            val editorVm: EditorViewModel = viewModel(
                editorEntry, key = noteId,
                factory = EditorViewModel.Factory(ctx, db, noteId)
            )
            // O chat pode ser restaurado sem a rota do editor: garante o
            // gerador para o fluxo "texto total" → DraftSheet.
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
            ChatScreen(
                copilotVm,
                onBack = { nav.popBackStack() },
                onInsert = { text, heading ->
                    // F2.3: insere no tópico em foco; sem foco, comportamento legado.
                    editorVm.queueInsertForTarget(copilotVm.currentTarget(), text, heading)
                },
                onOpenLibrary = { nav.navigate(Routes.library(noteId)) },
                headings = com.bettertalker.app.data.util.headingsOf(editorVm.mdText.collectAsState().value),
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
            )
            androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
                copilotVm.setDraftQueueStarter { targets -> editorVm.startDraftQueue(targets) }
            }
            // A seleção é capturada na rota do editor (acima) e persiste
            // na instância única do CopilotViewModel ancorada no editor.
            // Fase 3.5b.3: dossiê da seção ativa sob demanda (F2b: com alvo explícito).
            androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
                copilotVm.setDossierProvider { target -> editorVm.buildDossierFor(target) }
            }
            // Onboarding F2a: contexto empurrado pelo FAB (seleção + prontidão).
            androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
                editorVm.pushedContext.collect { copilotVm.setPushedContext(it) }
            }
            // Modo guiado seção-a-seção: seções reais do editor -> chat
            // (navegação, indicador e gate; mesmo padrão do setSelection).
            androidx.compose.runtime.LaunchedEffect(editorSections) {
                copilotVm.setGuideSections(editorSections.map { it.section })
            }
            // Modo guiado seção-a-seção: foco do chat -> rolagem no editor.
            androidx.compose.runtime.LaunchedEffect(editorVm, copilotVm) {
                copilotVm.focusRequests.collect { editorVm.scrollToSection(it) }
            }
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("linkNote") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument("scan") {
                    type = NavType.BoolType; defaultValue = false
                },
            )
        ) { back ->
            val linkNote = back.arguments?.getString("linkNote")?.ifBlank { null }
            val scanOnOpen = back.arguments?.getBoolean("scan") == true
            val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(db, libRepo))
            LibraryScreen(
                vm,
                onBack = { nav.popBackStack() },
                linkNoteId = linkNote,
                scanOnOpen = scanOnOpen,
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
