package com.bettertalker.app.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.voice.VoiceEngine

/**
 * Dono das sessões de nota do v4.0, ancorado na Activity.
 *
 * Precisa sobreviver à rotação: o `NavBackStackEntry` do editor deixou de ser
 * o dono dos ViewModels, então o mapa fica aqui. `onCleared` (fim real da
 * Activity) libera os `ViewModelStore`s das notas abertas.
 */
class NoteSessionHostViewModel(
    private val ctx: Context,
    private val db: AppDatabase,
    private val voiceEngine: VoiceEngine?,
) : ViewModel() {

    private val sessions = mutableMapOf<String, NoteSession>()

    fun sessionFor(noteId: String): NoteSession =
        sessions.getOrPut(noteId) {
            NoteSession(noteId, ctx, db, voiceEngine)
        }

    override fun onCleared() {
        sessions.values.forEach { it.clear() }
        sessions.clear()
        super.onCleared()
    }

    class Factory(
        private val ctx: Context,
        private val db: AppDatabase,
        private val voiceEngine: VoiceEngine?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NoteSessionHostViewModel(ctx.applicationContext, db, voiceEngine) as T
    }
}
