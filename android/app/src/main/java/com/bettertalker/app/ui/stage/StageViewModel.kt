package com.bettertalker.app.ui.stage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.prefs.STAGE_FONT_MAX_SP
import com.bettertalker.app.data.prefs.STAGE_FONT_MIN_SP
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.voice.SessionSpeechRecognizer
import com.bettertalker.app.data.voice.SessionSpeechState
import com.bettertalker.app.data.voice.prepareForSpeech
import com.bettertalker.app.domain.coach.buildCoachFeedback
import com.bettertalker.app.domain.coach.coachFeedbackSpeech
import com.bettertalker.app.domain.coach.computeSpeechMetrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ensaio v1 — estado do leitor de tribuna: texto agregado da nota,
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
    voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null,
) : ViewModel() {

    // T2 — TTS de ensaio: reaproveita o mesmo gerenciador do chat.
    // #23-fix-a: VoiceEngine injetável (router); default = nativo.
    private val tts: com.bettertalker.app.data.voice.VoiceEngine =
        voiceEngine ?: com.bettertalker.app.data.voice.TextToSpeechManager(appCtx)

    /** Motor de voz pronto (pt-BR instalado). */
    val ttsReady: StateFlow<Boolean> = tts.ready

    /** Leitura em andamento. */
    val ttsSpeaking: StateFlow<Boolean> = tts.speaking

    // T3-A — STT de sessão + análise local (sem áudio persistido).
    private val sessionStt = SessionSpeechRecognizer(appCtx)
    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _analysisElapsed = MutableStateFlow(0L)
    val analysisElapsed: StateFlow<Long> = _analysisElapsed.asStateFlow()

    private var analysisStartElapsed = 0L
    private var analysisTimerJob: Job? = null
    private var sectionTitles: List<String> = emptyList()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Texto agregado do discurso (mesma travessia da exportação). */
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    /** Modo Teleprompter: turnos do diálogo (vazio = texto corrido). */
    private val _turns = MutableStateFlow<List<StageTurn>>(emptyList())
    val turns: StateFlow<List<StageTurn>> = _turns.asStateFlow()

    /** "Modo Dupla": papel em destaque (null = desligado). */
    private val _duoRole = MutableStateFlow<String?>(null)
    val duoRole: StateFlow<String?> = _duoRole.asStateFlow()

    /** Texto falado pelo TTS: só as falas (sem rótulos) quando há diálogo. */
    private var speechText: String = ""

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
        // T3-A: erro do STT no meio do ensaio → feedback parcial ou mensagem.
        viewModelScope.launch {
            sessionStt.state.collect { st ->
                if (st is SessionSpeechState.Error) handleSessionError(st.message)
            }
        }
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
            val note = db.noteDao().get(noteId)
            _title.value = note?.title.orEmpty()
            _totalMinutes.value = sections.sumOf { it.minutes }
            sectionTitles = sections.sortedBy { it.order }
                .map { it.title }
                .filter { it.isNotBlank() }
            val type = com.bettertalker.app.domain.speech.DiscourseType
                .fromNameOrDefault(note?.discourseType)
            // Ensaio por tipo: S-34 lê subpontos; V&M lê contentHtml, sem
            // títulos/metadados (o Stage antigo lia o texto agregado inteiro).
            _content.value = buildStageContent(type, sections, subPoints)
            // Modo Teleprompter: turnos do diálogo — só demonstrações V&M com
            // ≥2 papéis distintos (evita falso positivo em negritos de discurso).
            val isDemo = type ==
                com.bettertalker.app.domain.speech.DiscourseType.MINISTRY_PART ||
                type == com.bettertalker.app.domain.speech.DiscourseType.FREE_DEMO
            val parsed = if (isDemo) {
                sections.sortedBy { it.order }.flatMap { parseStageTurns(it.contentHtml) }
            } else {
                emptyList()
            }
            val turns = if (parsed.map { it.speaker.lowercase() }.distinct().size >= 2) {
                parsed
            } else {
                emptyList()
            }
            _turns.value = turns
            speechText = stageSpeechText(turns, _content.value)
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

    /** Modo Teleprompter: cicla o papel em destaque (null → 1º → 2º → …). */
    fun cycleDuoRole() {
        val speakers = _turns.value.map { it.speaker }.distinct()
        if (speakers.size < 2) return
        _duoRole.value = nextDuoRole(_duoRole.value, speakers)
    }

    private fun speakContent() {
        // Modo Teleprompter: fala sem os rótulos de papel ("Publicador:").
        val text = com.bettertalker.app.data.voice.prepareForSpeech(speechText)
        if (text.isNotBlank()) tts.speak(text)
    }

    fun stopSpeech() = tts.stop()

    // ---------- T3-A — Ensaio com análise (STT local) ----------

    /** Inicia a escuta e o relógio da análise; o timer do Stage continua. */
    fun startAnalysis() {
        val current = _analysisState.value
        if (current is AnalysisState.Listening || current is AnalysisState.Processing) return
        sessionStt.cancelSession()
        _analysisElapsed.value = 0
        analysisStartElapsed = _elapsed.value
        _analysisState.value = AnalysisState.Listening
        analysisTimerJob?.cancel()
        analysisTimerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _analysisElapsed.value =
                    (_elapsed.value - analysisStartElapsed).coerceAtLeast(0L)
            }
        }
        sessionStt.startSession()
    }

    /** Encerra a escuta, espera o último segmento e calcula as métricas. */
    fun stopAnalysis() {
        val current = _analysisState.value
        if (current !is AnalysisState.Listening && current !is AnalysisState.Processing) return
        _analysisState.value = AnalysisState.Processing
        sessionStt.stopSession()
        viewModelScope.launch {
            val end = withTimeoutOrNull(ANALYSIS_STOP_TIMEOUT_MS) {
                sessionStt.state.first {
                    it is SessionSpeechState.Idle || it is SessionSpeechState.Error
                }
            }
            if (_analysisState.value !is AnalysisState.Processing) return@launch
            if (end is SessionSpeechState.Error) {
                handleSessionError(end.message)
            } else {
                finishAnalysis(sessionStt.transcript.value, warning = null)
            }
        }
    }

    /** Descarta o resultado e a transcrição (privacidade). */
    fun dismissAnalysis() {
        sessionStt.cancelSession()
        analysisTimerJob?.cancel()
        analysisTimerJob = null
        _analysisState.value = AnalysisState.Idle
        _analysisElapsed.value = 0
    }

    /** "Treinar de novo": descarta e reabre o microfone. */
    fun restartAnalysis() {
        dismissAnalysis()
        startAnalysis()
    }

    /** "Ouvir feedback": lê o texto do resultado; toque de novo para. */
    fun toggleFeedbackSpeech() {
        if (tts.speaking.value) {
            tts.stop()
            return
        }
        val result = _analysisState.value as? AnalysisState.Result ?: return
        tts.speak(prepareForSpeech(coachFeedbackSpeech(result.feedback)))
    }

    private fun handleSessionError(message: String) {
        val current = _analysisState.value
        if (current !is AnalysisState.Listening && current !is AnalysisState.Processing) return
        analysisTimerJob?.cancel()
        analysisTimerJob = null
        val duration = (_elapsed.value - analysisStartElapsed).coerceAtLeast(0L).toInt()
        val transcript = sessionStt.transcript.value
        if (transcript.isBlank() && duration < 2) {
            _analysisState.value = AnalysisState.Error(message)
            sessionStt.clearTranscript()
        } else {
            finishAnalysis(transcript, warning = message)
        }
    }

    private fun finishAnalysis(transcript: String, warning: String?) {
        analysisTimerJob?.cancel()
        analysisTimerJob = null
        val duration = (_elapsed.value - analysisStartElapsed).coerceAtLeast(0L).toInt()
        val target = speechText.ifBlank { _content.value }
        val metrics = computeSpeechMetrics(
            transcript = transcript,
            targetText = target,
            durationSec = duration,
        )
        val feedback = buildCoachFeedback(
            metrics = metrics,
            targetMinutes = _totalMinutes.value,
            sectionTitles = sectionTitles,
        )
        _analysisElapsed.value = duration.toLong()
        _analysisState.value = AnalysisState.Result(metrics, feedback, warning)
        // A transcrição não sobrevive ao cálculo: só métricas/feedback ficam.
        sessionStt.clearTranscript()
    }

    override fun onCleared() {
        // Ownership é do processo (TtsLifecycle); a tela só para a fala.
        analysisTimerJob?.cancel()
        sessionStt.destroy()
        tts.stop()
        super.onCleared()
    }

    companion object {
        private const val ANALYSIS_STOP_TIMEOUT_MS = 4_500L
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val noteId: String,
        private val voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StageViewModel(db, settings, noteId, ctx.applicationContext, voiceEngine) as T
    }
}
