package com.bettertalker.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            BetterTalkerTheme(mode = mode, palette = palette) { AppNav(settings) }
        }
    }
}

@Composable
private fun AppNav(settings: com.bettertalker.app.data.prefs.SettingsStore) {
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
            // v5: Passage.ref populado (proveniência "symbol section §n").
            if (settings.needsIndexFormat(5)) libRepo.reindexAll()
        }
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(ctx, db))
            // compartilha o mesmo VM com a Lixeira (mesmo ciclo da Home)
            HomeScreen(
                vm,
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                // #10b: ⚙ → SettingsScreen (Conta/Aparência/IA).
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.TRASH) { back ->
            val parent = remember(back) { nav.getBackStackEntry(Routes.HOME) }
            val vm: HomeViewModel = viewModel(parent, factory = HomeViewModel.Factory(ctx, db))
            TrashScreen(vm, onBack = { nav.popBackStack() })
        }
        // #10b: Settings centralizada (Conta/Aparência/IA) — ⚙ da Home.
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings,
                onBack = { nav.popBackStack() },
                onOpenAccount = { nav.navigate(Routes.ACCOUNT) },
                onOpenModel = { nav.navigate(Routes.MODEL) },
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
            val vm: EditorViewModel = viewModel(key = noteId, factory = EditorViewModel.Factory(ctx, db, noteId))
            val copilotVm: CopilotViewModel = viewModel(key = "cop-$noteId", factory = CopilotViewModel.Factory(ctx, db, noteId))
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
            EditorScreen(
                vm,
                onBack = { nav.popBackStack() },
                onAttach = { nav.navigate(Routes.library(noteId)) },
                onOpenChat = { nav.navigate(Routes.chat(noteId)) },
                // Ensaio: rota própria (rota declarada aqui para não tocar
                // os arquivos de navegação fora do escopo do handoff).
                onOpenStage = { nav.navigate("stage/$noteId") },
                onGenerateTribuna = { copilotVm.generateTribuna() },
                onOpenTribuna = { nav.navigate(Routes.tribuna(noteId)) },
                // #7e2: empty state reusa o pipeline do chat (mesmo VM por nota).
                onImportOutline = { uri -> copilotVm.importOutlineFile(uri) },
                onPasteOutline = { text -> copilotVm.pasteOutline(text) },
            )
        }
        composable(
            "stage/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { back ->
            val noteId = back.arguments?.getString("noteId") ?: return@composable
            val stageVm: StageViewModel = viewModel(
                key = "stage-$noteId",
                factory = StageViewModel.Factory(ctx, db, settings, noteId)
            )
            StageScreen(stageVm, onBack = { nav.popBackStack() })
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
                    ctx, db, settings, noteId
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
                factory = CopilotViewModel.Factory(ctx, db, noteId)
            )
            val editorVm: EditorViewModel = viewModel(
                editorEntry, key = noteId,
                factory = EditorViewModel.Factory(ctx, db, noteId)
            )
            // tema do esboço vira título da nota
            val chatTitleEv by copilotVm.titleEvent.collectAsState()
            androidx.compose.runtime.LaunchedEffect(chatTitleEv) {
                val t = chatTitleEv
                if (!t.isNullOrBlank()) {
                    editorVm.onTitle(t)
                    copilotVm.consumeTitle()
                }
            }
            // T2 (Mini Discurso P2): pedido do chat → gera no editor (mesma sheet).
            val draftState by editorVm.draftState.collectAsState()
            val editorSections by editorVm.sections.collectAsState()
            val draftReq by copilotVm.draftReq.collectAsState()
            androidx.compose.runtime.LaunchedEffect(draftReq) {
                val req = draftReq
                if (req != null) {
                    editorVm.requestDraftForSection(req.sectionId)
                    copilotVm.consumeDraftRequest()
                }
            }
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
            )
            // Fase 18 §17: seleção viva do editor -> contexto do Copilot.
            val liveSelection by editorVm.selectedText.collectAsState()
            androidx.compose.runtime.LaunchedEffect(liveSelection) {
                copilotVm.setSelection(liveSelection)
            }
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
            arguments = listOf(navArgument("linkNote") {
                type = NavType.StringType; nullable = true; defaultValue = null
            })
        ) { back ->
            val linkNote = back.arguments?.getString("linkNote")?.ifBlank { null }
            val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(db, libRepo))
            LibraryScreen(vm, onBack = { nav.popBackStack() }, linkNoteId = linkNote,
                onOpenModel = { nav.navigate(Routes.MODEL) })
        }
        composable(Routes.MODEL) { _ ->
            val vm: com.bettertalker.app.ui.aimodel.ModelViewModel =
                viewModel(factory = com.bettertalker.app.ui.aimodel.ModelViewModel.Factory(ctx))
            com.bettertalker.app.ui.aimodel.ModelScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
