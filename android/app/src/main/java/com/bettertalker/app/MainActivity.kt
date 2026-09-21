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
import com.bettertalker.app.ui.copilot.CopilotSheet
import com.bettertalker.app.ui.copilot.CopilotViewModel
import com.bettertalker.app.ui.editor.EditorScreen
import com.bettertalker.app.ui.editor.EditorViewModel
import com.bettertalker.app.ui.account.AccountScreen
import com.bettertalker.app.ui.account.AccountViewModel
import com.bettertalker.app.ui.home.HomeScreen
import com.bettertalker.app.ui.home.HomeViewModel
import com.bettertalker.app.ui.home.TrashScreen
import com.bettertalker.app.ui.library.LibraryScreen
import com.bettertalker.app.ui.library.LibraryViewModel
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
            BetterTalkerTheme(mode = mode) { AppNav(settings) }
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
    // Reindex geral one-shot (formato de índice v3).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.bettertalker.app.ui.jw.JwDownloadDialog.onEnqueued = { dmId, name ->
            scope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val ph = libRepo.insertPlaceholder(name)
                        if (ph != null) libRepo.enqueueRegisterDownload(dmId, name, ph)
                    }
                } catch (_: Exception) { }
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (settings.needsIndexFormat(4)) libRepo.reindexAll()
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
                onOpenAccount = { nav.navigate(Routes.ACCOUNT) },
                settings = settings
            )
        }
        composable(Routes.TRASH) { back ->
            val parent = remember(back) { nav.getBackStackEntry(Routes.HOME) }
            val vm: HomeViewModel = viewModel(parent, factory = HomeViewModel.Factory(ctx, db))
            TrashScreen(vm, onBack = { nav.popBackStack() })
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
            val showSheet = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val sheetTitle by vm.title.collectAsState()
            val sheetMdText by vm.mdText.collectAsState()
            EditorScreen(
                vm,
                onBack = { nav.popBackStack() },
                onCopilot = { showSheet.value = true },
                onAttach = { nav.navigate(Routes.library(noteId)) }
            )
            if (showSheet.value) {
                CopilotSheet(
                    copilotVm,
                    onDismiss = { showSheet.value = false },
                    onInsert = { text, heading -> vm.insertUnderHeading(heading, text) },
                    noteText = sheetTitle + "\n" + sheetMdText,
                    headings = com.bettertalker.app.data.util.headingsOf(sheetMdText)
                )
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
            LibraryScreen(vm, onBack = { nav.popBackStack() }, linkNoteId = linkNote)
        }
    }
}
