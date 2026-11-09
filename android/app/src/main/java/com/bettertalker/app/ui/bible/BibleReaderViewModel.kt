package com.bettertalker.app.ui.bible

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.bible.RoomBiblePassageSource
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.voice.VoiceRouter
import com.bettertalker.app.domain.bible.BiblePassage
import com.bettertalker.app.domain.bible.BiblePassageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Seam de TTS do leitor (VoiceRouter na produção; fake nos testes). */
interface BibleSpeaker {
    fun speak(text: String)
    fun stop()
    val speaking: Flow<Boolean>
}

data class BibleReaderUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val passage: BiblePassage? = null,
    val error: String? = null,
    val speaking: Boolean = false,
)

/**
 * v5.2 — estado do leitor TNM. Abre por refs livres, carrega o texto do
 * acervo e comanda TTS; a UI só renderiza o estado.
 */
class BibleReaderViewModel(
    private val reader: BiblePassageReader,
    private val speaker: BibleSpeaker,
    /** Testes passam um escopo; produção usa o viewModelScope. */
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    private val _state = MutableStateFlow(BibleReaderUiState())
    val state: StateFlow<BibleReaderUiState> = _state.asStateFlow()

    private var readJob: Job? = null

    init {
        workScope.launch {
            speaker.speaking.collect { on ->
                _state.update { it.copy(speaking = on) }
            }
        }
    }

    fun open(rawRefs: List<String>) {
        val refs = rawRefs.map { it.trim() }.filter { it.isNotEmpty() }
        if (refs.isEmpty()) return
        readJob?.cancel()
        speaker.stop()
        _state.value = BibleReaderUiState(visible = true, loading = true)
        readJob = workScope.launch {
            val passage = try {
                reader.read(refs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update {
                it.copy(
                    loading = false,
                    passage = passage,
                    error = if (passage == null) {
                        "Não foi possível abrir a leitura agora."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun close() {
        readJob?.cancel()
        speaker.stop()
        _state.value = BibleReaderUiState()
    }

    fun toggleSpeak() {
        val passage = _state.value.passage ?: return
        if (_state.value.speaking) {
            speaker.stop()
        } else {
            val text = passage.speechText
            if (text.isNotBlank()) speaker.speak(text)
        }
    }

    fun stopSpeaking() {
        speaker.stop()
    }

    override fun onCleared() {
        speaker.stop()
        super.onCleared()
    }

    class Factory(
        private val db: AppDatabase,
        private val voiceRouter: VoiceRouter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val reader = BiblePassageReader(
                RoomBiblePassageSource(db.passageDao(), db.attachmentDao())
            )
            val speaker = object : BibleSpeaker {
                override fun speak(text: String) = voiceRouter.speak(text, 1f, 1f)
                override fun stop() = voiceRouter.stop()
                override val speaking: Flow<Boolean> = voiceRouter.speaking
            }
            return BibleReaderViewModel(reader, speaker) as T
        }
    }
}
