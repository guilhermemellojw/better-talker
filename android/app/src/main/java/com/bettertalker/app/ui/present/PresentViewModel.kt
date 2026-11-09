package com.bettertalker.app.ui.present

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.StageTurnKind
import com.bettertalker.app.ui.stage.clampStageFontSp
import com.bettertalker.app.ui.stage.parseStageScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** v5.1: loader de uma nota para o Palco (Room na produção). */
interface PresentSource {
    suspend fun load(noteId: String): PresentSourceData
}

data class PresentSourceData(
    val title: String = "",
    val discourseType: String? = null,
    val sections: List<SpeechSectionEntity> = emptyList(),
    val images: List<NoteImageEntity> = emptyList(),
)

/** v5.1: seam das preferências do Palco (DataStore na produção). */
interface PresentPrefs {
    val fontSp: Flow<Int>
    suspend fun setFontSp(v: Int)
    fun mapMode(noteId: String): Flow<Boolean>
    suspend fun setMapMode(noteId: String, map: Boolean)
    fun focusCue(noteId: String, sectionId: String): Flow<Int>
    suspend fun setFocusCue(noteId: String, sectionId: String, index: Int)
    suspend fun savedSession(noteId: String): String?
    suspend fun setSavedSession(noteId: String, raw: String)
}

/**
 * Apresentar live — carrega a Tribuna pronta e mantém o estado do palco:
 * uma seção por vez, timer total/seção, navegação manual e pausa por ciclo
 * de vida. O palco é read-only; nada é editado/gravado aqui.
 */
class PresentViewModel(
    private val source: PresentSource,
    private val prefs: PresentPrefs,
    private val noteId: String,
    /** Testes passam um escopo; produção usa o viewModelScope. */
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    constructor(db: AppDatabase, settings: SettingsStore, noteId: String) : this(
        source = roomPresentSource(db),
        prefs = dataStorePresentPrefs(settings),
        noteId = noteId,
    )

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

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

    /** v5.1: mapa (todas as cues) x foco (uma cue), por nota. */
    private val _mapMode = MutableStateFlow(true)
    val mapMode: StateFlow<Boolean> = _mapMode.asStateFlow()

    /** v5.1: lembrete em foco por seção, persistido no DataStore. */
    private val _focusCueIndex = MutableStateFlow(0)
    val focusCueIndex: StateFlow<Int> = _focusCueIndex.asStateFlow()

    private var timerJob: Job? = null
    private var demo = false

    /**
     * v4.2: checkpoint de tempo ACUMULADO por seção. Ao navegar/entrar em
     * pausa, o tempo da seção atual é guardado e restaurado ao voltar.
     */
    private val sectionElapsedById = mutableMapOf<String, Long>()

    init {
        workScope.launch {
            prefs.fontSp.collect { _fontSp.value = it }
        }
        workScope.launch {
            prefs.mapMode(noteId).collect { _mapMode.value = it }
        }
    }

    fun load() {
        workScope.launch {
            val data = source.load(noteId)
            _title.value = data.title
            demo = data.discourseType == DiscourseType.MINISTRY_PART.name ||
                data.discourseType == DiscourseType.FREE_DEMO.name
            _isDemonstration.value = demo

            val rows = data.sections.sortedBy { it.order }
            // v5.3: imagem é recurso de discurso; demo não carrega imagens.
            val images = if (demo) emptyList() else data.images
            val cues = rows.mapIndexed { index, row ->
                val script = if (demo) parseStageScript(row.contentHtml) else emptyList()
                PresentCue(
                    sectionId = row.id,
                    index = index,
                    title = row.title.ifBlank { "Seção ${index + 1}" },
                    minutes = row.minutes,
                    capsule = row.tribunaJson?.let { tribunaCapsuleFromJson(it) },
                    turns = script.filter { it.kind == StageTurnKind.SPEECH },
                    script = script,
                    sectionImages = images.filter { it.sectionId == row.id },
                    noteImages = images,
                )
            }
            _cues.value = cues
            _readiness.value = presentReadinessOf(cues, demo)
            _speakers.value = presentSpeakers(cues)
            _scriptItems.value = if (demo) presentScriptItems(cues) else emptyList()

            val saved = decodePresentSession(
                raw = prefs.savedSession(noteId),
                totalSections = cues.size,
            )
            _savedSession.value = saved
            _prep.value = PresentPrep(
                mode = if (demo) PresentMode.SCRIPT else PresentMode.CUES,
                startIndex = saved?.index ?: 0,
                timerVisible = true,
                orientation = PresentOrientation.AUTO,
                roleIndex = 0,
                continueSaved = saved != null,
            )
            _currentIndex.value = _prep.value.startIndex.coerceIn(0, (cues.size - 1).coerceAtLeast(0))
            _scriptIndex.value = presentScriptStartIndex(_scriptItems.value, _currentIndex.value)
            restoreFocusForCurrentSection()
            _loadReady.value = true
        }
    }

    fun setMapMode(map: Boolean) {
        _mapMode.value = map
        workScope.launch { prefs.setMapMode(noteId, map) }
    }

    fun setFocusCue(index: Int) {
        val cue = _cues.value.getOrNull(_currentIndex.value) ?: return
        val max = (cue.capsule?.cues?.size ?: 0) - 1
        val safe = index.coerceIn(0, max.coerceAtLeast(0))
        _focusCueIndex.value = safe
        workScope.launch {
            prefs.setFocusCue(noteId, cue.sectionId, safe)
        }
    }

    private suspend fun restoreFocusForCurrentSection() {
        val cue = _cues.value.getOrNull(_currentIndex.value) ?: return
        val sectionId = cue.sectionId
        val max = (cue.capsule?.cues?.size ?: 0) - 1
        val saved = prefs.focusCue(noteId, sectionId).first()
        // Navegação rápida: só aplica se a seção ainda é a atual.
        val stillCurrent = _cues.value.getOrNull(_currentIndex.value)?.sectionId == sectionId
        if (stillCurrent) {
            _focusCueIndex.value = saved.coerceIn(0, max.coerceAtLeast(0))
        }
    }

    fun updatePrep(prep: PresentPrep) {
        val maxIndex = (_cues.value.size - 1).coerceAtLeast(0)
        // v5.3: demo só tem roteiro; discurso só tem lembretes.
        val mode = if (demo) PresentMode.SCRIPT else PresentMode.CUES
        _prep.value = prep.copy(
            mode = mode,
            startIndex = prep.startIndex.coerceIn(0, maxIndex),
            roleIndex = if (_speakers.value.isEmpty()) 0
            else prep.roleIndex.coerceIn(0, _speakers.value.lastIndex),
            scriptFocusOnly = if (demo) prep.scriptFocusOnly else false,
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
        if (!continuing) sectionElapsedById.clear()
        _sectionElapsed.value = 0L
        _stageStarted.value = true
        _pausedByLifecycle.value = false
        _running.value = true
        workScope.launch { restoreFocusForCurrentSection() }
        startTimer()
    }

    fun next() {
        if (!_stageStarted.value) return
        val previousSection = _currentIndex.value
        checkpointSectionElapsed()
        if (_prep.value.mode == PresentMode.SCRIPT) {
            val items = _scriptItems.value
            if (items.isEmpty() || _scriptIndex.value >= items.lastIndex) return
            _scriptIndex.value += 1
            _currentIndex.value = items[_scriptIndex.value].cueIndex
        } else {
            if (_currentIndex.value >= _cues.value.lastIndex) return
            _currentIndex.value += 1
        }
        restoreSectionElapsed(previousSection)
        workScope.launch { restoreFocusForCurrentSection() }
        saveSessionSoon()
    }

    fun previous() {
        if (!_stageStarted.value) return
        val previousSection = _currentIndex.value
        checkpointSectionElapsed()
        if (_prep.value.mode == PresentMode.SCRIPT) {
            val items = _scriptItems.value
            if (items.isEmpty() || _scriptIndex.value <= 0) return
            _scriptIndex.value -= 1
            _currentIndex.value = items[_scriptIndex.value].cueIndex
        } else {
            if (_currentIndex.value <= 0) return
            _currentIndex.value -= 1
        }
        restoreSectionElapsed(previousSection)
        workScope.launch { restoreFocusForCurrentSection() }
        saveSessionSoon()
    }

    /** v4.2: salva o tempo acumulado da seção atual (checkpoint manual). */
    fun checkpointSectionElapsed() {
        val id = _cues.value.getOrNull(_currentIndex.value)?.sectionId ?: return
        sectionElapsedById[id] = _sectionElapsed.value
    }

    private fun restoreSectionElapsed(previousIndex: Int) {
        if (_currentIndex.value == previousIndex) return
        val id = _cues.value.getOrNull(_currentIndex.value)?.sectionId
        _sectionElapsed.value = id?.let { sectionElapsedById[it] } ?: 0L
    }

    /** Pausa automática quando o app perde foco/chamada/tela. */
    fun pauseByLifecycle() {
        if (!_stageStarted.value) return
        checkpointSectionElapsed()
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
        workScope.launch {
            persistSession()
            onDone?.invoke()
        }
    }

    fun saveSessionNow() {
        workScope.launch { persistSession() }
    }

    fun bumpFont(delta: Int) {
        val next = clampStageFontSp(_fontSp.value + delta)
        _fontSp.value = next
        workScope.launch { prefs.setFontSp(next) }
    }

    private fun saveSessionSoon() {
        workScope.launch { persistSession() }
    }

    private suspend fun persistSession() {
        if (_cues.value.isEmpty()) return
        val session = PresentSavedSession(
            index = _currentIndex.value.coerceIn(0, _cues.value.lastIndex),
            elapsedSec = _totalElapsed.value,
            updatedAt = System.currentTimeMillis(),
        )
        prefs.setSavedSession(noteId, encodePresentSession(session))
        _savedSession.value = session
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = workScope.launch {
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

private fun roomPresentSource(db: AppDatabase): PresentSource = object : PresentSource {
    override suspend fun load(noteId: String): PresentSourceData {
        val note = db.noteDao().get(noteId)
        return PresentSourceData(
            title = note?.title.orEmpty(),
            discourseType = note?.discourseType,
            sections = db.speechSectionDao().forNote(noteId),
            images = db.noteImageDao().forNote(noteId),
        )
    }
}

private fun dataStorePresentPrefs(settings: SettingsStore): PresentPrefs =
    object : PresentPrefs {
        override val fontSp: Flow<Int> = settings.stageFontSp
        override suspend fun setFontSp(v: Int) = settings.setStageFontSp(v)
        override fun mapMode(noteId: String): Flow<Boolean> =
            settings.presentMapMode(noteId)
        override suspend fun setMapMode(noteId: String, map: Boolean) =
            settings.setPresentMapMode(noteId, map)
        override fun focusCue(noteId: String, sectionId: String): Flow<Int> =
            settings.presentFocusCue(noteId, sectionId)
        override suspend fun setFocusCue(noteId: String, sectionId: String, index: Int) =
            settings.setPresentFocusCue(noteId, sectionId, index)
        override suspend fun savedSession(noteId: String): String? =
            settings.presentSession(noteId)
        override suspend fun setSavedSession(noteId: String, raw: String) =
            settings.setPresentSession(noteId, raw)
    }
