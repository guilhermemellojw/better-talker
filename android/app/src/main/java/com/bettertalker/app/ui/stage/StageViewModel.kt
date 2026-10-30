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
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import com.bettertalker.app.domain.coach.SpeechSessionTimer
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
 * Treino — estado da sessão: fonte da nota, preparação (escopo/modo/timer),
 * texto recortado, cronômetro (decorrido/meta/restante) e análise STT local.
 *
 * Cronômetro: NÃO inicia ao entrar; só depois do "Começar treino". A meta é
 * a soma das seções no discurso inteiro ou a duração da seção no trecho.
 * O texto e a transcrição não são gravados; a transcrição é descartada após
 * as métricas.
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

    // Countdown inicial: só libera a leitura/timer quando o microfone está
    // pronto (onReadyForSpeech) ou depois do timeout de segurança.
    private val _readingGate = MutableStateFlow(false)
    val readingGate: StateFlow<Boolean> = _readingGate.asStateFlow()

    private val _prepareCountdown = MutableStateFlow<Int?>(null)
    val prepareCountdown: StateFlow<Int?> = _prepareCountdown.asStateFlow()

    private val _micWarning = MutableStateFlow<String?>(null)
    val micWarning: StateFlow<String?> = _micWarning.asStateFlow()

    private var prepareFallbackJob: Job? = null
    private var prepareCountdownJob: Job? = null

    /** STT do sistema disponível (sem ele, "Com análise" fica desabilitado). */
    private val hasSpeechRecognizer = android.speech.SpeechRecognizer
        .isRecognitionAvailable(appCtx)
    private val _analysisAvailable = MutableStateFlow(hasSpeechRecognizer)
    val analysisAvailable: StateFlow<Boolean> = _analysisAvailable.asStateFlow()

    private val sessionTimer = SpeechSessionTimer()
    private var analysisTimerJob: Job? = null
    private var sectionTitles: List<String> = emptyList()

    // ---------- Treino MVP (preparação + escopo) ----------

    /** Opções de trecho (seções/subpontos) carregadas do banco. */
    private val _trainingSections = MutableStateFlow<List<TrainSectionOption>>(emptyList())
    val trainingSections: StateFlow<List<TrainSectionOption>> =
        _trainingSections.asStateFlow()

    /** Última preparação escolhida nesta nota. */
    private val _prep = MutableStateFlow(defaultTrainPrep(hasSpeechRecognizer))
    val prep: StateFlow<TrainPrep> = _prep.asStateFlow()

    /** Fonte carregada: a preparação já pode ser mostrada. */
    private val _prepReady = MutableStateFlow(false)
    val prepReady: StateFlow<Boolean> = _prepReady.asStateFlow()

    /** O treino só começa no "Começar treino" (timer não corre ao abrir). */
    private val _trainingStarted = MutableStateFlow(false)
    val trainingStarted: StateFlow<Boolean> = _trainingStarted.asStateFlow()

    /** Existe texto utilizável em algum escopo. */
    private val _hasTrainingText = MutableStateFlow(false)
    val hasTrainingText: StateFlow<Boolean> = _hasTrainingText.asStateFlow()

    /** Rótulo do escopo atual ("Discurso inteiro", título da seção...). */
    private val _scopeLabel = MutableStateFlow("")
    val scopeLabel: StateFlow<String> = _scopeLabel.asStateFlow()

    private var sections: List<SpeechSection> = emptyList()
    private var subPointsBySection: Map<String, List<SubPoint>> = emptyMap()
    private var discourseType: DiscourseType = DiscourseType.S34_DISCOURSE
    private var allTurns: List<StageTurn> = emptyList()

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

    /** Meta do escopo atual (soma das seções ou duração da seção; 0 = sem meta). */
    private val _totalMinutes = MutableStateFlow(0)
    val totalMinutes: StateFlow<Int> = _totalMinutes.asStateFlow()

    private val _elapsed = MutableStateFlow(0L)
    val elapsed: StateFlow<Long> = _elapsed.asStateFlow()

    private val _timerRunning = MutableStateFlow(false)
    val timerRunning: StateFlow<Boolean> = _timerRunning.asStateFlow()

    private val _fontSp = MutableStateFlow(STAGE_FONT_DEFAULT_SP)
    val fontSp: StateFlow<Int> = _fontSp.asStateFlow()

    private var timerJob: Job? = null

    init {
        // T3-A: falha persistente do STT → Interrupted (sem resultado
        // automático); a transcrição fica preservada para Retomar/Parar.
        // Listening só chega no onReadyForSpeech: é o sinal para o countdown.
        viewModelScope.launch {
            sessionStt.state.collect { st ->
                when (st) {
                    is SessionSpeechState.Interrupted -> onSessionInterrupted(st.message)
                    SessionSpeechState.Listening -> onSpeechReady()
                    else -> Unit
                }
            }
        }
        // O relógio de fala começa na PRIMEIRA fala, não no toque: evita
        // penalizar quem demora alguns segundos para começar.
        viewModelScope.launch {
            sessionStt.speechStarted.collect { started ->
                if (!started) return@collect
                if (_analysisState.value is AnalysisState.Listening) {
                    sessionTimer.beginSpeech()
                } else {
                    sessionTimer.markSpeechStarted()
                }
            }
        }
        // Preferência persistida de fonte (emite o valor salvo; padrão 24).
        viewModelScope.launch {
            settings.stageFontSp.collect { _fontSp.value = it }
        }
        // Fonte da nota + preparação do Treino. O cronômetro NÃO começa aqui:
        // o spec exige "Começar treino" explícito.
        viewModelScope.launch {
            sections = db.speechSectionDao().forNote(noteId).map { it.toDomain() }
            subPointsBySection = sections.associate { s ->
                s.id to db.subPointDao().forSection(s.id).map { it.toDomain() }
            }
            val note = db.noteDao().get(noteId)
            _title.value = note?.title.orEmpty()
            discourseType = DiscourseType.fromNameOrDefault(note?.discourseType)
            _totalMinutes.value = sections.sumOf { it.minutes }
            sectionTitles = sections.sortedBy { it.order }
                .map { it.title }
                .filter { it.isNotBlank() }
            // Ensaio por tipo: S-34 lê subpontos; V&M lê contentHtml, sem
            // títulos/metadados (o Stage antigo lia o texto agregado inteiro).
            _content.value = buildStageContent(discourseType, sections, subPointsBySection)
            // Modo Teleprompter: turnos do diálogo — só demonstrações V&M com
            // ≥2 papéis distintos (evita falso positivo em negritos de discurso).
            allTurns = parsedTurnsFor(sections, discourseType)
            _turns.value = allTurns
            speechText = stageSpeechText(allTurns, _content.value)
            _trainingSections.value = trainSectionOptions(sections, subPointsBySection)
            _hasTrainingText.value = _content.value.isNotBlank() || allTurns.isNotEmpty()
            _prep.value = decodeTrainPrep(
                raw = settings.trainPrep(noteId),
                sections = sections,
                subPointsBySection = subPointsBySection,
                analysisAvailable = hasSpeechRecognizer,
            )
            _prepReady.value = true
        }
    }

    fun startTimer() {
        if (!_prep.value.timerEnabled) {
            _timerRunning.value = false
            timerJob?.cancel()
            timerJob = null
            return
        }
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

    // ---------- Treino MVP — preparação e escopo ----------

    /** Atualiza a preparação na UI (a persistência acontece no Começar). */
    fun updatePrep(prep: TrainPrep) {
        _prep.value = sanitizeTrainPrep(
            prep = prep,
            sections = sections,
            subPointsBySection = subPointsBySection,
            analysisAvailable = hasSpeechRecognizer,
        )
    }

    /**
     * "Começar treino": fixa escopo/modo/timer, recorta o texto e a meta e só
     * então inicia o cronômetro (se ligado). A análise é iniciada pela tela
     * (depois da permissão de microfone) quando o modo é ANALYSIS.
     */
    fun startTraining(prep: TrainPrep = _prep.value) {
        if (!_prepReady.value) return
        val safe = sanitizeTrainPrep(
            prep = prep,
            sections = sections,
            subPointsBySection = subPointsBySection,
            analysisAvailable = hasSpeechRecognizer,
        )
        _prep.value = safe
        tts.stop()
        dismissAnalysis()
        _elapsed.value = 0L
        _scopeLabel.value = trainScopeLabel(sections, subPointsBySection, safe.scope)
        _totalMinutes.value = trainTargetMinutes(sections, subPointsBySection, safe.scope)
        _content.value = buildStageContentForScope(
            discourseType = discourseType,
            sections = sections,
            subPointsBySection = subPointsBySection,
            scope = safe.scope,
        )
        _turns.value = turnsForScope(safe.scope)
        speechText = stageSpeechText(_turns.value, _content.value)
        sectionTitles = scopedSectionTitles(safe.scope)
        _duoRole.value = null
        // Com análise, o texto fica coberto pelo countdown e o timer só
        // começa quando o microfone está pronto (releaseReading).
        val gateReading = safe.mode == TrainMode.ANALYSIS
        _readingGate.value = gateReading
        _prepareCountdown.value = null
        _micWarning.value = null
        _trainingStarted.value = true
        if (safe.timerEnabled && !gateReading) {
            startTimer()
        } else {
            _timerRunning.value = false
            timerJob?.cancel()
            timerJob = null
        }
        viewModelScope.launch { settings.setTrainPrep(noteId, encodeTrainPrep(safe)) }
    }

    /** "Treinar de novo": mesmo escopo/modo, timer e análise do zero. */
    fun restartTraining() = startTraining(_prep.value)

    /** "Treinar outro trecho": volta para a preparação sem manter o timer. */
    fun backToPrep() {
        tts.stop()
        dismissAnalysis()
        pauseTimer()
        _elapsed.value = 0L
        _trainingStarted.value = false
    }

    private fun scopedSectionTitles(scope: TrainScope): List<String> {
        val ordered = sections.sortedBy { it.order }
        val scoped = when (scope.kind) {
            TrainScopeKind.WHOLE -> ordered
            TrainScopeKind.SECTION -> ordered.filter { it.id == scope.sectionId }
            TrainScopeKind.SUBPOINT -> ordered.filter { section ->
                subPointsBySection[section.id].orEmpty().any { it.id == scope.subPointId }
            }
        }
        return scoped.map { it.title }.filter { it.isNotBlank() }
    }

    /** Turnos do teleprompter limitados ao escopo (subponto = texto corrido). */
    private fun turnsForScope(scope: TrainScope): List<StageTurn> = when (scope.kind) {
        TrainScopeKind.WHOLE -> allTurns
        TrainScopeKind.SECTION -> parsedTurnsFor(
            sections.filter { it.id == scope.sectionId },
            discourseType,
        )
        TrainScopeKind.SUBPOINT -> emptyList()
    }

    /** Demonstrações V&M com ≥2 papéis; os demais tipos leem texto corrido. */
    private fun parsedTurnsFor(
        source: List<SpeechSection>,
        type: DiscourseType,
    ): List<StageTurn> {
        val isDemo = type == DiscourseType.MINISTRY_PART ||
            type == DiscourseType.FREE_DEMO
        if (!isDemo) return emptyList()
        val parsed = source.sortedBy { it.order }.flatMap { parseStageTurns(it.contentHtml) }
        return if (parsed.map { it.speaker.lowercase() }.distinct().size >= 2) {
            parsed
        } else {
            emptyList()
        }
    }

    fun bumpFont(delta: Int) {
        val next = clampStageFontSp(_fontSp.value + delta)
        _fontSp.value = next
        viewModelScope.launch { settings.setStageFontSp(next) }
    }

    /** T2 — botão ▶ Ouvir / ⏸ Parar: uma leitura por vez (o manager flusha). */
    fun toggleSpeech() {
        if (tts.speaking.value) {
            tts.stop()
            return
        }
        // P0 voz: não inicia TTS durante a análise (escuta/pausa/processando).
        if (isAnalysisActive(_analysisState.value)) return
        speakContent()
    }

    /** Modo Teleprompter: cicla o papel em destaque (null → 1º → 2º → …). */
    fun cycleDuoRole() {
        val speakers = _turns.value.map { it.speaker }.distinct()
        if (speakers.size < 2) return
        _duoRole.value = nextDuoRole(_duoRole.value, speakers)
    }

    private fun speakContent() {
        // P0 voz: TTS e STT nunca falam/ouvem ao mesmo tempo.
        if (isAnalysisActive(_analysisState.value)) return
        // Modo Teleprompter: fala sem os rótulos de papel ("Publicador:").
        val text = com.bettertalker.app.data.voice.prepareForSpeech(speechText)
        if (text.isNotBlank()) tts.speak(text)
    }

    fun stopSpeech() = tts.stop()

    // ---------- T3-A — Ensaio com análise (STT local) ----------

    /**
     * Inicia a escuta. No começo do treino, fica em [AnalysisState.Preparing]
     * até o onReadyForSpeech; a leitura só é liberada depois do countdown.
     * Num toque manual posterior, a leitura já está liberada e só o banner
     * "Preparando microfone…" aparece.
     */
    fun startAnalysis() {
        // P0 voz: microfone e TTS são mutuamente exclusivos.
        tts.stop()
        val current = _analysisState.value
        if (current is AnalysisState.Preparing ||
            current is AnalysisState.Listening ||
            current is AnalysisState.Paused ||
            current is AnalysisState.Processing
        ) return
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        _micWarning.value = null
        sessionStt.cancelSession()
        sessionTimer.start()
        _analysisElapsed.value = 0
        _analysisState.value = AnalysisState.Preparing
        sessionStt.startSession()
        startPrepareFallback()
    }

    /** Pausa a escuta sem perder a transcrição; o timer do Stage continua. */
    fun pauseAnalysis() {
        if (_analysisState.value !is AnalysisState.Listening) return
        sessionTimer.pause()
        _analysisElapsed.value = sessionTimer.speechSeconds()
        _analysisState.value = AnalysisState.Paused
        sessionStt.pauseSession()
    }

    /** Retoma do ponto anterior, mantendo o texto acumulado. */
    fun resumeAnalysis() {
        val current = _analysisState.value
        if (current !is AnalysisState.Paused && current !is AnalysisState.Interrupted) return
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        _micWarning.value = null
        sessionTimer.resume()
        _analysisState.value = AnalysisState.Preparing
        sessionStt.resumeSession()
        startPrepareFallback()
    }

    /**
     * O usuário negou o microfone no "Começar treino": libera a leitura e o
     * timer, mantém a sessão em modo leitura e deixa o banner de permissão.
     */
    fun onMicDenied() {
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        _micWarning.value = "Microfone não permitido. O início não será analisado."
        val wasGate = _readingGate.value
        _readingGate.value = false
        if (wasGate && _prep.value.timerEnabled) startTimer()
    }

    /** onReadyForSpeech: começa o countdown (ou libera a análise manual). */
    private fun onSpeechReady() {
        if (_analysisState.value !is AnalysisState.Preparing) return
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        if (_readingGate.value) {
            beginPrepareCountdown(warning = null)
        } else {
            _analysisState.value = AnalysisState.Listening
            startAnalysisTicker()
        }
    }

    /** Se o serviço não avisar que está pronto, libera com aviso. */
    private fun startPrepareFallback() {
        prepareFallbackJob?.cancel()
        prepareFallbackJob = viewModelScope.launch {
            delay(MIC_READY_TIMEOUT_MS)
            if (_analysisState.value !is AnalysisState.Preparing) return@launch
            if (_readingGate.value) {
                beginPrepareCountdown("O início pode não ser capturado.")
            } else {
                _micWarning.value = "O início da análise pode não ter sido capturado."
                releaseReadingNow()
            }
        }
    }

    /** "Prepare-se… 3, 2, 1": só depois libera leitura e timer. */
    private fun beginPrepareCountdown(warning: String?) {
        if (_analysisState.value !is AnalysisState.Preparing) return
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        _micWarning.value = warning
        prepareCountdownJob?.cancel()
        prepareCountdownJob = viewModelScope.launch {
            for (second in 3 downTo 1) {
                _prepareCountdown.value = second
                delay(1_000)
            }
            _prepareCountdown.value = null
            prepareCountdownJob = null
            releaseReadingNow()
        }
    }

    /** Libera a leitura/timer quando o microfone está pronto ou no fallback. */
    private fun releaseReadingNow() {
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        val wasGate = _readingGate.value
        _readingGate.value = false
        if (_analysisState.value is AnalysisState.Preparing) {
            sessionTimer.beginListeningAfterPrepare()
            _analysisState.value = AnalysisState.Listening
            startAnalysisTicker()
        }
        if (wasGate && _prep.value.timerEnabled) startTimer()
    }

    /** Parar explícito: espera o último segmento e calcula as métricas. */
    fun stopAnalysis() {
        val current = _analysisState.value
        if (current !is AnalysisState.Preparing &&
            current !is AnalysisState.Listening &&
            current !is AnalysisState.Paused &&
            current !is AnalysisState.Interrupted &&
            current !is AnalysisState.Processing
        ) return
        val warning = (current as? AnalysisState.Interrupted)?.message
        if (current is AnalysisState.Preparing || current is AnalysisState.Listening) {
            sessionTimer.pause()
        }
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        _readingGate.value = false
        _analysisState.value = AnalysisState.Processing
        sessionStt.stopSession()
        viewModelScope.launch {
            withTimeoutOrNull(ANALYSIS_STOP_TIMEOUT_MS) {
                sessionStt.state.first { it is SessionSpeechState.Idle }
            }
            if (_analysisState.value !is AnalysisState.Processing) return@launch
            finishAnalysis(sessionStt.transcript.value, warning)
        }
    }

    /** Descarta o resultado e a transcrição (privacidade). */
    fun dismissAnalysis() {
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        _readingGate.value = false
        _micWarning.value = null
        sessionStt.cancelSession()
        analysisTimerJob?.cancel()
        analysisTimerJob = null
        sessionTimer.reset()
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

    private fun startAnalysisTicker() {
        analysisTimerJob?.cancel()
        analysisTimerJob = viewModelScope.launch {
            while (true) {
                delay(500)
                _analysisElapsed.value = sessionTimer.speechSeconds()
            }
        }
    }

    private fun onSessionInterrupted(message: String) {
        val current = _analysisState.value
        if (current !is AnalysisState.Listening && current !is AnalysisState.Preparing) return
        prepareFallbackJob?.cancel()
        prepareFallbackJob = null
        prepareCountdownJob?.cancel()
        prepareCountdownJob = null
        _prepareCountdown.value = null
        val wasGate = _readingGate.value
        _readingGate.value = false
        if (wasGate) {
            _micWarning.value = "O início pode não ser capturado."
            sessionTimer.beginListeningAfterPrepare()
            if (_prep.value.timerEnabled) startTimer()
        }
        sessionTimer.pause()
        _analysisElapsed.value = sessionTimer.speechSeconds()
        _analysisState.value = AnalysisState.Interrupted(message)
    }

    private fun finishAnalysis(transcript: String, warning: String?) {
        analysisTimerJob?.cancel()
        analysisTimerJob = null
        val speechSec = sessionTimer.speechSeconds().toInt()
        val sessionSec = sessionTimer.sessionSeconds().toInt()
        val target = speechText.ifBlank { _content.value }
        val metrics = computeSpeechMetrics(
            transcript = transcript,
            targetText = target,
            speechDurationSec = speechSec,
            sessionDurationSec = sessionSec,
        )
        val feedback = buildCoachFeedback(
            metrics = metrics,
            targetMinutes = _totalMinutes.value,
            sectionTitles = sectionTitles,
        )
        val startWarning = _micWarning.value?.takeIf { it.isNotBlank() }
        val combinedWarning = listOfNotNull(warning, startWarning)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }
        _analysisElapsed.value = speechSec.toLong()
        _analysisState.value = AnalysisState.Result(metrics, feedback, combinedWarning)
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
        /** Sem onReadyForSpeech nesse prazo, libera a leitura com aviso. */
        private const val MIC_READY_TIMEOUT_MS = 2_500L
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
