package com.bettertalker.app.ui.tribuna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.NoteImageDao
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.voice.TextToSpeechManager
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.TribunaDetail
import com.bettertalker.app.domain.speech.TribunaMapStatus
import com.bettertalker.app.domain.speech.TribunaCue
import com.bettertalker.app.domain.speech.TribunaCueKind
import com.bettertalker.app.domain.speech.toJson
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import com.bettertalker.app.domain.speech.tribunaMapQuality
import com.bettertalker.app.ui.stage.StageTurn
import com.bettertalker.app.ui.stage.parseStageTurns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** #20-fix-b: seam de TTS (fake nos testes; produção = TextToSpeechManager). */
interface TribunaSpeaker {
    fun speak(text: String)
    fun stop()
    fun shutdown()
    val speaking: Flow<Boolean>
}

/** #20-fix-b: seam das preferências de fonte (reusa a chave do Ensaio). */
interface TribunaFontPrefs {
    val fontSp: Flow<Int>
    suspend fun setFontSp(v: Int)
}

/** v5.1: seam do nível de detalhe (DataStore na produção). */
interface TribunaDetailPrefs {
    fun noteDetail(noteId: String): Flow<TribunaDetail>
    fun sectionDetail(noteId: String, sectionId: String): Flow<TribunaDetail?>
    suspend fun setNoteDetail(noteId: String, detail: TribunaDetail)
    suspend fun setSectionDetail(noteId: String, sectionId: String, detail: TribunaDetail)
    suspend fun clearSectionDetail(noteId: String, sectionId: String)
}

/**
 * #20-fix-b: a Tribuna — mapa de memória por seção. Lê os `tribunaJson`,
 * edita inline com autosave debounced e lê SÓ a frase-âncora (o orador
 * completa o resto de coração).
 *
 * Testável sem Context/Room: DAO fake + seams (speaker/fontPrefs) +
 * debounceMs = 0 + runBlocking (mesmo padrão do SectionsController).
 */
class TribunaViewModel(
    private val dao: SpeechSectionDao,
    private val noteId: String,
    private val speaker: TribunaSpeaker,
    private val fontPrefs: TribunaFontPrefs,
    private val debounceMs: Long = 400L,
    /** Testes passam um escopo; produção usa o viewModelScope. */
    private val scope: CoroutineScope? = null,
    /** #20-fix-c: título da nota (export). Testes usam o default. */
    private val noteTitleProvider: suspend () -> String = { "" },
    /** Bloco 2 (demos): nota MINISTRY_PART → a Tribuna vira roteiro (turnos). */
    private val isDemonstrationProvider: suspend () -> Boolean = { false },
    /** v4.2: imagens por seção (null nos testes antigos; produção = Room). */
    private val imageDao: NoteImageDao? = null,
    /** v5.1: nível de detalhe (null nos testes antigos). */
    private val detailPrefs: TribunaDetailPrefs? = null,
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    data class TribunaCardUi(
        val sectionId: String,
        val order: Int,
        val sectionTitle: String,
        val minutes: Int,
        /** null = Tribuna ainda não gerada para esta seção. */
        val capsule: TribunaCapsule?,
        /** Bloco 2 (demos): turnos do diálogo (vazio nos discursos). */
        val turns: List<StageTurn> = emptyList(),
        /** v4.2: imagens da seção (thumbnails/legendas; nunca no HTML). */
        val images: List<NoteImageEntity> = emptyList(),
        /** v5.1: se a seção tem refs bíblicas/publicações. */
        val sectionHasRefs: Boolean = false,
        /** v5.1: última seção não exige transição. */
        val isLastSection: Boolean = true,
        /** v5.1: override de detalhe desta seção; null = padrão da nota. */
        val sectionDetail: TribunaDetail? = null,
        /** v5.1: updatedAt da linha que originou o card (guarda anti-regressão). */
        val dbUpdatedAt: Long = 0,
    ) {
        /** v5.1: qualidade com motivos (fonte única do badge). */
        val quality
            get() = tribunaMapQuality(capsule, sectionHasRefs, isLastSection)

        /** v4.2: compatibilidade com testes antigos. */
        val mapComplete: Boolean
            get() = quality.status == TribunaMapStatus.COMPLETE

        /** v4.2: true = cápsula gerada antes do formato de cues. */
        val legacyMap: Boolean
            get() = quality.status == TribunaMapStatus.LEGACY

        /** v5.1: folha v2 intencionalmente vazia. */
        val emptyCueSheet: Boolean
            get() = capsule != null && capsule.cueSheetVersion >= 2 &&
                capsule.cues.isEmpty()
    }

    private val _cards = MutableStateFlow<List<TribunaCardUi>>(emptyList())
    val cards: StateFlow<List<TribunaCardUi>> = _cards.asStateFlow()

    private val _isDemonstration = MutableStateFlow(false)
    /** Bloco 2 (demos): true = MINISTRY_PART → a tela vira roteiro (turnos). */
    val isDemonstration: StateFlow<Boolean> = _isDemonstration.asStateFlow()

    private val _speakingId = MutableStateFlow<String?>(null)

    /**
     * Seção cuja âncora está sendo lida. Derivado: quando o TTS para
     * (naturalmente ou por stop), volta a null.
     */
    val speakingId: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(_speakingId, speaker.speaking) { id, on ->
            if (on) id else null
        }.stateIn(workScope, SharingStarted.Eagerly, null)

    val fontSp: StateFlow<Int> = fontPrefs.fontSp
        .stateIn(workScope, SharingStarted.Lazily, STAGE_FONT_DEFAULT_SP)

    /** v5.1: detalhe efetivo da nota (override por seção fica no DataStore). */
    val noteDetail: StateFlow<TribunaDetail> =
        (detailPrefs?.noteDetail(noteId) ?: flowOf(TribunaDetail.STANDARD))
            .stateIn(workScope, SharingStarted.Eagerly, TribunaDetail.STANDARD)

    fun setNoteDetail(detail: TribunaDetail) {
        workScope.launch { detailPrefs?.setNoteDetail(noteId, detail) }
    }

    fun setSectionDetail(sectionId: String, detail: TribunaDetail) {
        workScope.launch { detailPrefs?.setSectionDetail(noteId, sectionId, detail) }
    }

    /** v5.1: volta a seção ao detalhe padrão da nota. */
    fun clearSectionDetail(sectionId: String) {
        workScope.launch { detailPrefs?.clearSectionDetail(noteId, sectionId) }
    }

    private val persistJobs = mutableMapOf<String, Job>()

    /** v5.1: seções com edição local ainda não confirmada pelo banco. */
    private val dirtySections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** v5.1: versão da última edição; evita limpar “dirty” de edição mais nova. */
    private val editVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** v5.1: carimbo do último autosave local por seção. */
    private val lastLocalSaveAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markDirty(sectionId: String) {
        dirtySections += sectionId
        editVersions.merge(sectionId, 1L, Long::plus)
    }

    private val _noteTitle = MutableStateFlow("")
    /** Título da nota (para o export); vazio até o load. */
    val noteTitle: StateFlow<String> = _noteTitle.asStateFlow()

    /** Lê as seções da nota (live) + parse dos tribunaJson e turnos (demo). */
    fun load() {
        workScope.launch {
            _noteTitle.value = noteTitleProvider().ifBlank { "" }
        }
        workScope.launch {
            val imagesFlow: Flow<List<NoteImageEntity>> =
                imageDao?.observeForNote(noteId) ?: flowOf(emptyList())
            val rowsFlow = dao.observeForNote(noteId)
            val detailsFlow = rowsFlow.flatMapLatest { rows ->
                if (detailPrefs == null) {
                    flowOf(emptyMap<String, TribunaDetail>())
                } else {
                    val flows = rows.map { row ->
                        detailPrefs.sectionDetail(noteId, row.id)
                    }
                    if (flows.isEmpty()) {
                        flowOf(emptyMap<String, TribunaDetail>())
                    } else {
                        combine(flows) { values ->
                            rows.mapIndexedNotNull { index, row ->
                                values.getOrNull(index)?.let { row.id to it }
                            }.toMap()
                        }
                    }
                }
            }
            combine(rowsFlow, imagesFlow, detailsFlow) { rows, images, details ->
                // Bloco 2 (demos): o tipo é relido a cada emissão — se a nota
                // for demonstração, a tela renderiza como roteiro.
                val demo = isDemonstrationProvider()
                _isDemonstration.value = demo
                val sorted = rows.sortedBy { it.order }
                sorted.mapIndexed { index, e ->
                    val domain = runCatching { e.toDomain() }.getOrNull()
                    TribunaCardUi(
                        sectionId = e.id,
                        order = e.order,
                        sectionTitle = e.title,
                        minutes = e.minutes,
                        capsule = e.tribunaJson?.let { tribunaCapsuleFromJson(it) },
                        turns = if (demo) parseStageTurns(e.contentHtml) else emptyList(),
                        images = images.filter { it.sectionId == e.id },
                        sectionHasRefs = domain?.let {
                            it.bibleRefs.isNotEmpty() || it.publicationRefs.isNotEmpty()
                        } ?: false,
                        isLastSection = index == sorted.lastIndex,
                        sectionDetail = details[e.id],
                        dbUpdatedAt = e.updatedAt,
                    )
                }
            }.collect { computed ->
                // Merge atômico: uma emissão do banco não regride a edição
                // otimista; a cápsula local é lida no momento do update.
                _cards.update { current ->
                    computed.map { card ->
                        val currentCard = current.firstOrNull { it.sectionId == card.sectionId }
                        val lastSave = lastLocalSaveAt[card.sectionId] ?: 0L
                        if (card.dbUpdatedAt < lastSave) {
                            // Emissão atrasada de um autosave anterior: descarta.
                            currentCard ?: card
                        } else {
                            val local = currentCard?.capsule
                            if (dirtySections.contains(card.sectionId) && local != null) {
                                card.copy(capsule = local)
                            } else {
                                card
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateMainIdea(sectionId: String, text: String) =
        edit(sectionId) { it.copy(mainIdea = text) }

    fun updateBibleRef(sectionId: String, text: String) =
        edit(sectionId) { it.copy(bibleRef = text.trim().ifBlank { null }) }

    fun updateAnchorPhrase(sectionId: String, text: String) =
        edit(sectionId) { it.copy(anchorPhrase = text) }

    fun updateTransition(sectionId: String, text: String) =
        edit(sectionId) { it.copy(transition = text.trim().ifBlank { null }) }

    /** v4.2: edita o texto de uma cue da folha. */
    fun updateCueText(sectionId: String, cueIndex: Int, text: String) =
        editCues(sectionId) { cues ->
            cues.mapIndexed { index, cue ->
                if (index == cueIndex) cue.copy(text = text) else cue
            }
        }

    /** v4.2: edita as refs de uma cue (lista já normalizada pela UI). */
    fun updateCueRefs(sectionId: String, cueIndex: Int, refs: List<String>) =
        editCues(sectionId) { cues ->
            cues.mapIndexed { index, cue ->
                if (index == cueIndex) cue.copy(refs = refs) else cue
            }
        }

    /** v5.1: adiciona um lembrete no fim (converte cápsula antiga). */
    fun addCue(sectionId: String, kind: TribunaCueKind = TribunaCueKind.REASONING) {
        editOrCreate(sectionId) { capsule ->
            capsule.copy(
                cues = capsule.cues + TribunaCue(kind = kind, text = ""),
                cueSheetVersion = 2,
            )
        }
    }

    /** v5.1: remove só o lembrete; a cápsula/imagem da seção permanece. */
    fun removeCue(sectionId: String, cueIndex: Int) =
        editCues(sectionId) { cues -> cues.filterIndexed { index, _ -> index != cueIndex } }

    /** v5.1: undo de remoção restaura a cápsula inteira. */
    fun restoreCapsule(sectionId: String, capsule: TribunaCapsule) {
        setCapsule(sectionId, capsule)
    }

    fun moveCue(sectionId: String, cueIndex: Int, direction: Int) =
        editCues(sectionId) { cues ->
            val target = cueIndex + direction
            if (cueIndex !in cues.indices || target !in cues.indices) {
                cues
            } else {
                cues.toMutableList().also {
                    val tmp = it[cueIndex]
                    it[cueIndex] = it[target]
                    it[target] = tmp
                }
            }
        }

    fun updateCueKind(sectionId: String, cueIndex: Int, kind: TribunaCueKind) =
        editCues(sectionId) { cues ->
            cues.mapIndexed { index, cue ->
                if (index == cueIndex) cue.copy(kind = kind) else cue
            }
        }

    fun updateCueImage(
        sectionId: String,
        cueIndex: Int,
        imageId: String?,
        imageCaption: String?,
    ) = editCues(sectionId) { cues ->
        cues.mapIndexed { index, cue ->
            if (index == cueIndex) {
                cue.copy(imageId = imageId, imageCaption = imageCaption)
            } else {
                cue
            }
        }
    }

    fun updateKeyTerms(sectionId: String, keyTerms: List<String>) =
        edit(sectionId) { it.copy(keyTerms = keyTerms.map { t -> t.trim() }.filter { it.isNotEmpty() }) }

    fun updateTimeMinutes(sectionId: String, minutes: Int?) =
        edit(sectionId) { it.copy(timeMinutes = minutes?.takeIf { m -> m > 0 }) }

    /** v5.1: converte cápsula antiga em folha vazia, preservando o cabeçalho. */
    fun convertToCueSheet(sectionId: String) =
        edit(sectionId) { it.copy(cueSheetVersion = 2) }

    private fun editCues(
        sectionId: String,
        transform: (List<TribunaCue>) -> List<TribunaCue>,
    ) = editOrCreate(sectionId) { capsule ->
        capsule.copy(
            cues = transform(capsule.cues),
            cueSheetVersion = 2,
        )
    }

    private fun editOrCreate(
        sectionId: String,
        transform: (TribunaCapsule) -> TribunaCapsule,
    ) {
        markDirty(sectionId)
        _cards.update { list ->
            list.map { card ->
                if (card.sectionId != sectionId) return@map card
                val base = card.capsule ?: TribunaCapsule(
                    sectionId = sectionId,
                    mainIdea = "",
                    anchorPhrase = "",
                )
                card.copy(capsule = transform(base))
            }
        }
        persistSnapshot(sectionId)
    }

    private fun setCapsule(sectionId: String, capsule: TribunaCapsule) {
        markDirty(sectionId)
        _cards.update { list ->
            list.map { card ->
                if (card.sectionId == sectionId) card.copy(capsule = capsule) else card
            }
        }
        persistSnapshot(sectionId)
    }

    private fun persistSnapshot(sectionId: String) {
        val capsule = _cards.value.firstOrNull { it.sectionId == sectionId }?.capsule
            ?: return
        dirtySections += sectionId
        val version = editVersions[sectionId] ?: 0L
        persistJobs[sectionId]?.cancel()
        persistJobs[sectionId] = workScope.launch {
            delay(debounceMs)
            val entity = dao.get(sectionId) ?: return@launch
            val stamp = maxOf(
                System.currentTimeMillis(),
                (lastLocalSaveAt[sectionId] ?: 0L) + 1,
            )
            lastLocalSaveAt[sectionId] = stamp
            dao.upsert(
                entity.copy(
                    tribunaJson = capsule.toJson(),
                    updatedAt = stamp,
                )
            )
            // Só libera a guarda quando o banco tem exatamente este snapshot;
            // uma edição mais nova mantém a seção “dirty”.
            if (editVersions[sectionId] == version &&
                _cards.value.firstOrNull { it.sectionId == sectionId }?.capsule == capsule
            ) {
                dirtySections.remove(sectionId)
            }
        }
    }

    /** Update otimista + autosave debounced (400ms em produção). */
    private fun edit(sectionId: String, transform: (TribunaCapsule) -> TribunaCapsule) {
        markDirty(sectionId)
        _cards.update { list ->
            list.map { c ->
                if (c.sectionId == sectionId) c.copy(capsule = c.capsule?.let(transform)) else c
            }
        }
        persistSnapshot(sectionId)
    }

    /** TTS SÓ da frase-âncora. Sem cápsula/âncora → nada. */
    fun speakAnchor(sectionId: String) {
        val phrase = _cards.value.firstOrNull { it.sectionId == sectionId }
            ?.capsule?.anchorPhrase?.takeIf { it.isNotBlank() }
            ?: return
        _speakingId.value = sectionId
        speaker.speak(phrase)
    }

    fun stopSpeaking() {
        speaker.stop()
        _speakingId.value = null
    }

    fun setFontSp(v: Int) {
        workScope.launch { fontPrefs.setFontSp(v) }
    }

    override fun onCleared() {
        // Ownership é do processo (TtsLifecycle); a tela só para a fala.
        speaker.stop()
        super.onCleared()
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val noteId: String,
        private val voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // #23-fix-a: engine injetável (router); default = nativo.
            val tts: com.bettertalker.app.data.voice.VoiceEngine =
                voiceEngine ?: TextToSpeechManager(ctx.applicationContext)
            val speaker = object : TribunaSpeaker {
                override fun speak(text: String) = tts.speak(text)
                override fun stop() = tts.stop()
                override fun shutdown() = tts.stop()
                override val speaking: Flow<Boolean> = tts.speaking
            }
            val prefs = object : TribunaFontPrefs {
                override val fontSp: Flow<Int> = settings.stageFontSp
                override suspend fun setFontSp(v: Int) = settings.setStageFontSp(v)
            }
            val detailPrefs = object : TribunaDetailPrefs {
                override fun noteDetail(noteId: String) = settings.tribunaDetail(noteId)
                override fun sectionDetail(noteId: String, sectionId: String) =
                    settings.tribunaSectionDetail(noteId, sectionId)
                override suspend fun setNoteDetail(noteId: String, detail: TribunaDetail) =
                    settings.setTribunaDetail(noteId, detail)
                override suspend fun setSectionDetail(
                    noteId: String,
                    sectionId: String,
                    detail: TribunaDetail,
                ) = settings.setTribunaSectionDetail(noteId, sectionId, detail)
                override suspend fun clearSectionDetail(noteId: String, sectionId: String) =
                    settings.clearTribunaSectionDetail(noteId, sectionId)
            }
            return TribunaViewModel(
                db.speechSectionDao(), noteId, speaker, prefs,
                noteTitleProvider = { db.noteDao().get(noteId)?.title.orEmpty() },
                isDemonstrationProvider = {
                    val type = db.noteDao().get(noteId)?.discourseType
                    type == com.bettertalker.app.domain.speech.DiscourseType.MINISTRY_PART.name ||
                        type == com.bettertalker.app.domain.speech.DiscourseType.FREE_DEMO.name
                },
                imageDao = db.noteImageDao(),
                detailPrefs = detailPrefs,
            ) as T
        }
    }
}
