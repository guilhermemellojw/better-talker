package com.bettertalker.app.ui.present

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.clampStageFontSp
import com.bettertalker.app.ui.stage.parseStageTurns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Apresentar live — carrega a Tribuna pronta e mantém o estado do palco:
 * uma seção por vez, timer total/seção, navegação manual e pausa por ciclo
 * de vida. O palco é read-only; nada é editado/gravado aqui.
 */
class PresentViewModel(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val noteId: String,
) : ViewModel() {

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _cues = MutableStateFlow<List<PresentCue>>(emptyList())
    val cues: StateFlow<List<PresentCue>> = _cues.asStateFlow()

    private val _readiness = MutableStateFlow(PresentReadiness.MISSING)
    val readiness: StateFlow<PresentReadiness> = _readiness.asStateFlow()

    private val _isDemonstration = MutableStateFlow(false)
    val isDemonstration: StateFlow<Boolean> = _isDemonstration.asStateFlow()

    private val _speakers = MutableStateFlow<List<String>>(emptyList())
    val speakers: StateFlow<List<String>> = _speakers.asStateFlow()

    private val _scriptItems = MutableStateFlow<List<PresentScriptItem>>(emptyList())
    val scriptItems: StateFlow<List<PresentScriptItem>> = _scriptItems.asStateFlow()

    private val _savedSession = MutableStateFlow<PresentSavedSession?>(null)
    val savedSession: StateFlow<PresentSavedSession?> = _savedSession.asStateFlow()

    private val _prep = MutableStateFlow(PresentPrep())
    val prep: StateFlow<PresentPrep> = _prep.asStateFlow()

    private val _loadReady = MutableStateFlow(false)
    val loadReady: StateFlow<Boolean> = _loadReady.asStateFlow()

    private val _stageStarted = MutableStateFlow(false)
    val stageStarted: StateFlow<Boolean> = _stageStarted.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _scriptIndex = MutableStateFlow(0)
    val scriptIndex: StateFlow<Int> = _scriptIndex.asStateFlow()

    private val _totalElapsed = MutableStateFlow(0L)
    val totalElapsed: StateFlow<Long> = _totalElapsed.asStateFlow()

    private val _sectionElapsed = MutableStateFlow(0L)
    val sectionElapsed: StateFlow<Long> = _sectionElapsed.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Pausa automática por perda de foco/chamada/tela; retomada explícita. */
    private val _pausedByLifecycle = MutableStateFlow(false)
    val pausedByLifecycle: StateFlow<Boolean> = _pausedByLifecycle.asStateFlow()

    private val _fontSp = MutableStateFlow(STAGE_FONT_DEFAULT_SP)
    val fontSp: StateFlow<Int> = _fontSp.asStateFlow()

    private var timerJob: Job? = null
    private var demo = false

    init {
        viewModelScope.launch {
            settings.stageFontSp.collect { _fontSp.value = it }
        }
    }

    fun load() {
        viewModelScope.launch {
            val note = db.noteDao().get(noteId)
            _title.value = note?.title.orEmpty()
            demo = note?.discourseType == DiscourseType.MINISTRY_PART.name ||
                note?.discourseType == DiscourseType.FREE_DEMO.name
            _isDemonstration.value = demo

            val rows = db.speechSectionDao().forNote(noteId).sortedBy { it.order }
            val cues = rows.mapIndexed { index, row ->
                PresentCue(
                    sectionId = row.id,
                    index = index,
                    title = row.title.ifBlank { "Seção ${index + 1}" },
                    minutes = row.minutes,
                    capsule = row.tribunaJson?.let { tribunaCapsuleFromJson(it) },
                    turns = if (demo) parseStageTurns(row.contentHtml) else emptyList(),
                )
            }
            _cues.value = cues
            _readiness.value = presentReadinessOf(cues, demo)
            _speakers.value = presentSpeakers(cues)
            _scriptItems.value = if (demo) presentScriptItems(cues) else emptyList()

            val saved = decodePresentSession(
                raw = settings.presentSession(noteId),
                totalSections = cues.size,
            )
            _savedSession.value = saved
            _prep.value = PresentPrep(
                mode = if (demo) PresentMode.SCRIPT else PresentMode.CUES,
                startIndex = saved?.index ?: 0,
                timerVisible = true,
                orientation = PresentOrientation.PORTRAIT,
                roleIndex = 0,
                continueSaved = saved != null,
            )
            _currentIndex.value = _prep.value.startIndex.coerceIn(0, (cues.size - 1).coerceAtLeast(0))
            _scriptIndex.value = presentScriptStartIndex(_scriptItems.value, _currentIndex.value)
            _loadReady.value = true
        }
    }

    fun updatePrep(prep: PresentPrep) {
        val maxIndex = (_cues.value.size - 1).coerceAtLeast(0)
        val mode = if (prep.mode == PresentMode.SCRIPT && !demo) PresentMode.CUES else prep.mode
        _prep.value = prep.copy(
            mode = mode,
            startIndex = prep.startIndex.coerceIn(0, maxIndex),
            roleIndex = if (_speakers.value.isEmpty()) 0
            else prep.roleIndex.coerceIn(0, _speakers.value.lastIndex),
        )
    }

    /** Entra no palco: restaura seção/tempo se "continuar" estiver ligado. */
    fun startSession() {
        if (_cues.value.isEmpty() || _readiness.value == PresentReadiness.MISSING) return
        val saved = _savedSession.value
        val continuing = _prep.value.continueSaved && saved != null
        val index = (if (continuing) saved!!.index else _prep.value.startIndex)
            .coerceIn(0, _cues.value.lastIndex)
        _scriptIndex.value = presentScriptStartIndex(_scriptItems.value, index)
        _currentIndex.value = if (_prep.value.mode == PresentMode.SCRIPT &&
            _scriptItems.value.isNotEmpty()
        ) {
            _scriptItems.value[_scriptIndex.value].cueIndex
        } else {
            index
        }
        _totalElapsed.value = if (continuing) saved!!.elapsedSec else 0L
        _sectionElapsed.value = 0L
        _stageStarted.value = true
        _pausedByLifecycle.value = false
        _running.value = true
        startTimer()
    }

    fun next() {
        if (!_stageStarted.value) return
        val previousSection = _currentIndex.value
        if (_prep.value.mode == PresentMode.SCRIPT) {
            val items = _scriptItems.value
            if (items.isEmpty() || _scriptIndex.value >= items.lastIndex) return
            _scriptIndex.value += 1
            _currentIndex.value = items[_scriptIndex.value].cueIndex
        } else {
            if (_currentIndex.value >= _cues.value.lastIndex) return
            _currentIndex.value += 1
        }
        if (_currentIndex.value != previousSection) _sectionElapsed.value = 0L
        saveSessionSoon()
    }

    fun previous() {
        if (!_stageStarted.value) return
        val previousSection = _currentIndex.value
        if (_prep.value.mode == PresentMode.SCRIPT) {
            val items = _scriptItems.value
            if (items.isEmpty() || _scriptIndex.value <= 0) return
            _scriptIndex.value -= 1
            _currentIndex.value = items[_scriptIndex.value].cueIndex
        } else {
            if (_currentIndex.value <= 0) return
            _currentIndex.value -= 1
        }
        if (_currentIndex.value != previousSection) _sectionElapsed.value = 0L
        saveSessionSoon()
    }

    /** Pausa automática quando o app perde foco/chamada/tela. */
    fun pauseByLifecycle() {
        if (!_stageStarted.value) return
        _running.value = false
        _pausedByLifecycle.value = true
        saveSessionSoon()
    }

    /** Retomada explícita; nunca volta sozinho após interrupção. */
    fun resumeAfterLifecycle() {
        if (!_stageStarted.value) return
        _pausedByLifecycle.value = false
        _running.value = true
        startTimer()
    }

    /** Encerra o palco, salva a posição e só então chama [onDone]. */
    fun endSession(onDone: (() -> Unit)? = null) {
        _running.value = false
        _pausedByLifecycle.value = false
        _stageStarted.value = false
        timerJob?.cancel()
        timerJob = null
        viewModelScope.launch {
            persistSession()
            onDone?.invoke()
        }
    }

    fun saveSessionNow() {
        viewModelScope.launch { persistSession() }
    }

    fun bumpFont(delta: Int) {
        val next = clampStageFontSp(_fontSp.value + delta)
        _fontSp.value = next
        viewModelScope.launch { settings.setStageFontSp(next) }
    }

    private fun saveSessionSoon() {
        viewModelScope.launch { persistSession() }
    }

    private suspend fun persistSession() {
        if (_cues.value.isEmpty()) return
        val session = PresentSavedSession(
            index = _currentIndex.value.coerceIn(0, _cues.value.lastIndex),
            elapsedSec = _totalElapsed.value,
            updatedAt = System.currentTimeMillis(),
        )
        settings.setPresentSession(noteId, encodePresentSession(session))
        _savedSession.value = session
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (_running.value) {
                    _totalElapsed.value += 1
                    _sectionElapsed.value += 1
                }
            }
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val noteId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PresentViewModel(db, settings, noteId) as T
    }
}
