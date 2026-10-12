package com.bettertalker.app.ui.stage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.export.buildExportPlainText
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.prefs.STAGE_FONT_MAX_SP
import com.bettertalker.app.data.prefs.STAGE_FONT_MIN_SP
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Modo Palco v1 — estado do leitor de tribuna: texto agregado da nota,
 * cronômetro global (decorrido/meta/restante) e preferências de leitura.
 *
 * Cronômetro: inicia ao entrar na tela e pode ser pausado/retomado tocando
 * no relógio (tempo de preparação não contamina a leitura). Meta = Σ dos
 * `minutes` das seções (dado já existente no S-34).
 */

/** Estados/cor do cronômetro pela fração da meta. */
enum class StageTimerState { IDLE, GREEN, AMBER, RED }

/** MM:SS (minutos passam de 59 sem quebrar). Puro/testável. */
fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Verde < 50% da meta; âmbar 50–80%; vermelho ≥ 80%. Sem meta = IDLE. */
fun stageTimerState(elapsedSec: Long, targetMin: Int): StageTimerState {
    if (targetMin <= 0) return StageTimerState.IDLE
    val ratio = elapsedSec.toDouble() / (targetMin * 60.0)
    return when {
        ratio < 0.5 -> StageTimerState.GREEN
        ratio < 0.8 -> StageTimerState.AMBER
        else -> StageTimerState.RED
    }
}

/** Segundos restantes até a meta (nunca negativo). Puro/testável. */
fun stageRemainingSeconds(elapsedSec: Long, targetMin: Int): Long =
    (targetMin * 60L - elapsedSec).coerceAtLeast(0L)

/** Segundos excedidos além da meta (0 enquanto dentro). Puro/testável. */
fun stageOvertimeSeconds(elapsedSec: Long, targetMin: Int): Long =
    (elapsedSec - targetMin * 60L).coerceAtLeast(0L)

/** Tamanho da fonte de leitura dentro dos limites do palco. Puro/testável. */
fun clampStageFontSp(v: Int): Int = v.coerceIn(STAGE_FONT_MIN_SP, STAGE_FONT_MAX_SP)

class StageViewModel(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val noteId: String,
    appCtx: android.content.Context,
) : ViewModel() {

    // T2 — TTS de ensaio: reaproveita o mesmo gerenciador do chat.
    private val tts = com.bettertalker.app.data.voice.TextToSpeechManager(appCtx)

    /** Motor de voz pronto (pt-BR instalado). */
    val ttsReady: StateFlow<Boolean> = tts.ready

    /** Leitura em andamento. */
    val ttsSpeaking: StateFlow<Boolean> = tts.speaking

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Texto agregado do discurso (mesma travessia da exportação). */
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    /** Meta global = soma dos minutos das seções (0 = sem meta). */
    private val _totalMinutes = MutableStateFlow(0)
    val totalMinutes: StateFlow<Int> = _totalMinutes.asStateFlow()

    private val _elapsed = MutableStateFlow(0L)
    val elapsed: StateFlow<Long> = _elapsed.asStateFlow()

    private val _timerRunning = MutableStateFlow(true)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    private val _fontSp = MutableStateFlow(STAGE_FONT_DEFAULT_SP)
    val fontSp: StateFlow<Int> = _fontSp.asStateFlow()

    private var timerJob: Job? = null

    init {
        // Preferência persistida de fonte (emite o valor salvo; padrão 24).
        viewModelScope.launch {
            settings.stageFontSp.collect { _fontSp.value = it }
        }
        // Conteúdo da nota: agregado pronto para leitura.
        viewModelScope.launch {
            val sections = db.speechSectionDao().forNote(noteId).map { it.toDomain() }
            val subPoints = sections.associate { s ->
                s.id to db.subPointDao().forSection(s.id).map { it.toDomain() }
            }
            _title.value = db.noteDao().get(noteId)?.title.orEmpty()
            _totalMinutes.value = sections.sumOf { it.minutes }
            _content.value = buildExportPlainText(sections, subPoints)
        }
        startTimer()
    }

    fun startTimer() {
        _timerRunning.value = true
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _elapsed.value = _elapsed.value + 1
            }
        }
    }

    fun pauseTimer() {
        _timerRunning.value = false
        timerJob?.cancel()
        timerJob = null
    }

    fun toggleTimer() {
        if (_timerRunning.value) pauseTimer() else startTimer()
    }

    fun bumpFont(delta: Int) {
        val next = clampStageFontSp(_fontSp.value + delta)
        _fontSp.value = next
        viewModelScope.launch { settings.setStageFontSp(next) }
    }

    /** T2 — botão ▶ Ouvir / ⏸ Parar: uma leitura por vez (o manager flusha). */
    fun toggleSpeech() {
        if (tts.speaking.value) tts.stop() else speakContent()
    }

    private fun speakContent() {
        val text = com.bettertalker.app.data.voice.prepareForSpeech(_content.value)
        if (text.isNotBlank()) tts.speak(text)
    }

    fun stopSpeech() = tts.stop()

    override fun onCleared() {
        tts.shutdown()
        super.onCleared()
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val noteId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StageViewModel(db, settings, noteId, ctx.applicationContext) as T
    }
}
