package com.bettertalker.app.ui.session

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.voice.VoiceEngine
import com.bettertalker.app.ui.copilot.CopilotViewModel
import com.bettertalker.app.ui.editor.EditorViewModel

/**
 * Sessão de UMA nota para o v4.0.
 *
 * O editor, o chat antigo e a aba Assistente precisam falar com os MESMOS
 * `EditorViewModel`/`CopilotViewModel`: fio único, proposta em memória e fila
 * de drafts sobrevivem à troca de rota. O dono do `ViewModelStore` é o AppNav,
 * não a rota do editor — assim o `popBackStack` do editor não destrói a sessão.
 */
class NoteSession(
    val noteId: String,
    ctx: Context,
    db: AppDatabase,
    voiceEngine: VoiceEngine?,
) : ViewModelStoreOwner {

    override val viewModelStore = ViewModelStore()

    val editorVm: EditorViewModel = ViewModelProvider(
        this,
        EditorViewModel.Factory(ctx.applicationContext, db, noteId),
    )[EditorViewModel::class.java]

    val copilotVm: CopilotViewModel = ViewModelProvider(
        this,
        CopilotViewModel.Factory(ctx.applicationContext, db, noteId, voiceEngine),
    )[CopilotViewModel::class.java]

    fun clear() = viewModelStore.clear()
}
