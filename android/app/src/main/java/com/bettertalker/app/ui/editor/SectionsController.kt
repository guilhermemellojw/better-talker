package com.bettertalker.app.ui.editor

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationResult
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SpeechSectionValidator
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Controlador headless das seções do editor (tarefa 3.2.5a).
 *
 * Consome `speech_sections` + `sub_points` via Flow, mantém o estado de UI
 * por seção ([SectionUiState]) e faz autosave por seção com debounce.
 *
 * Testável sem Context/Room: fakes de DAO + [debounceMs] = 0 + `runBlocking`
 * (+ [awaitSaves] para determinismo).
 *
 * Eco/concorrência: enquanto uma seção está `isDirty`/`isSaving`, o estado
 * local dela vence o que vem do banco. Necessário porque salvar a seção A
 * reinvalida a tabela `speech_sections` e o Room reemite TODAS as seções da
 * nota — sem essa regra, uma edição em andamento na seção B seria
 * sobrescrita pelo valor antigo do banco.
 *
 * @param noteId nota observada
 * @param sectionDao DAO de seções
 * @param subPointDao DAO de sub-pontos
 * @param transactionRunner runner de transação (Room em produção; fake em teste)
 * @param scope escopo (viewModelScope em produção; runBlocking no teste)
 * @param debounceMs debounce do autosave por seção (400 em produção, 0 em teste)
 * @param idProvider gerador de ids (UUID por default; injetável para teste)
 * @param clock relógio (System por default; injetável para teste)
 * @param onSaved callback pós-save (o VM recomputa agregados e salva o cache)
 */
class SectionsController(
    private val noteId: String,
    private val sectionDao: SpeechSectionDao,
    private val subPointDao: SubPointDao,
    private val transactionRunner: TransactionRunner,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 400L,
    private val idProvider: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onSaved: suspend () -> Unit = {},
) {
    private val _sections = MutableStateFlow<List<SectionUiState>>(emptyList())
    val sections: StateFlow<List<SectionUiState>> = _sections.asStateFlow()

    /**
     * Tipo de discurso da nota (3.2.5f-pre): seleciona o conjunto de regras
     * da revalidação. O VM atualiza a partir da NoteEntity; default S-34.
     */
    var discourseType: DiscourseType = DiscourseType.S34_DISCOURSE

    /**
     * Resultado da revalidação estrutural (3.2.5f-pre): informativo, nunca
     * bloqueia. Null = sem seções (nada a validar).
     */
    private val _validationResult = MutableStateFlow<SectionValidationResult?>(null)
    val validationResult: StateFlow<SectionValidationResult?> = _validationResult.asStateFlow()

    /** Jobs de autosave por seção (sectionId → job ativo). */
    private val saveJobs = mutableMapOf<String, Job>()

    /** Jobs de persistência estrutural atômica (Hotfix P0.2; aguardados em [awaitSaves]). */
    private val atomicJobs = mutableListOf<Job>()

    /**
     * Escopo interno (SupervisorJob filho de [scope]): isola coletores e
     * autosaves e permite [close] determinístico em teste. Em produção o
     * viewModelScope cancela tudo junto; [close] existe p/ os testes não
     * pendurarem o `runBlocking`.
     */
    private val controlJob = SupervisorJob(scope.coroutineContext[Job])
    private val innerScope = CoroutineScope(scope.coroutineContext + controlJob)

    init {
        observe()
        // 3.2.5f-pre: revalida a cada mudança estrutural (o aviso da UI
        // deriva daqui; dismiss é local ao VM e a próxima mudança republica).
        innerScope.launch {
            _sections.collect { sections ->
                _validationResult.value = if (sections.isEmpty()) {
                    null
                } else {
                    SpeechSectionValidator.validate(
                        sections.map { it.section },
                        discourseType,
                    )
                }
            }
        }
    }

    /** Encerra coletores e autosaves pendentes (idempotente). */
    fun close() {
        controlJob.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        innerScope.launch {
            sectionDao.observeForNote(noteId)
                .flatMapLatest { entities ->
                    if (entities.isEmpty()) {
                        // Nota sem seções (legado puro): nada a observar.
                        flowOf(emptyList())
                    } else {
                        val ids = entities.map { it.id }
                        // GUARD: observeForSections nunca recebe lista vazia.
                        combine(flowOf(entities), subPointDao.observeForSections(ids)) { secs, subs ->
                            secs.map { entity ->
                                val domain = entity.toDomain()
                                val subPoints = subs
                                    .filter { it.sectionId == domain.id }
                                    .sortedBy { it.order }
                                    .map { it.toDomain() }
                                val existing = _sections.value.firstOrNull { it.section.id == domain.id }
                                val local = existing?.takeIf { it.isDirty || it.isSaving }
                                SectionUiState(
                                    section = local?.section ?: domain,
                                    subPoints = local?.subPoints ?: subPoints,
                                    isSaving = existing?.isSaving ?: false,
                                    isDirty = existing?.isDirty ?: false,
                                )
                            }
                        }
                    }
                }
                .collect { _sections.value = it }
        }
    }

    // ---------- ações (UI) ----------

    fun onSectionTitle(sectionId: String, title: String) {
        update(sectionId) { it.copy(section = it.section.copy(title = title, updatedAt = clock())) }
    }

    fun onSectionMinutes(sectionId: String, minutes: Int) {
        update(sectionId) { it.copy(section = it.section.copy(minutes = minutes, updatedAt = clock())) }
    }

    /** Conteúdo rico de INTRO/CONCLUSION (seções BODY usam sub-pontos). */
    fun onSectionContent(sectionId: String, html: String) {
        update(sectionId) {
            it.copy(section = it.section.copy(contentHtml = html, updatedAt = clock()))
        }
    }

    /** Desenvolvimento de um sub-ponto (HTML); suja a seção pai (autosave dela). */
    fun onSubPointContent(subPointId: String, html: String) {
        val parent = _sections.value.firstOrNull { s -> s.subPoints.any { it.id == subPointId } }
            ?: return
        update(parent.section.id) { state ->
            state.copy(subPoints = state.subPoints.map { sp ->
                if (sp.id == subPointId) sp.copy(developedHtml = html, updatedAt = clock()) else sp
            })
        }
    }

    // ---------- 3.2.5f.1: CRUD de seções ----------

    /**
     * Adiciona uma nova seção.
     *
     * @param role papel (INTRO/BODY/CONCLUSION)
     * @param afterSectionId se presente, entra logo após esta; senão, no fim
     * @return a seção criada (já no estado local)
     */
    fun addSection(role: SectionRole, afterSectionId: String? = null): SpeechSection {
        val now = clock()
        val newId = idProvider()
        val all = _sections.value
        val insertAt = if (afterSectionId != null) {
            all.indexOfFirst { it.section.id == afterSectionId }.let {
                if (it < 0) all.size else it + 1
            }
        } else {
            all.size
        }
        val newSection = SpeechSection(
            id = newId,
            noteId = noteId,
            order = insertAt, // renormalizado abaixo
            role = role,
            title = defaultTitleFor(role),
            minutes = defaultMinutesFor(role),
            contentHtml = "",
            bibleRefs = emptyList(),
            publicationRefs = emptyList(),
            methodPrinciple = null,
            createdAt = now,
            updatedAt = now,
        )
        val updated = all.toMutableList().apply {
            add(insertAt, SectionUiState(section = newSection, subPoints = emptyList()))
        }
        val renormalized = renormalizeSections(updated)
        _sections.value = renormalized
        // Hotfix P0.2: persiste TUDO atomicamente (não agenda save individual).
        launchAtomicPersist(renormalized)
        return newSection
    }

    /**
     * Remove uma seção. Hotfix P0.2: renormaliza em memória e persiste
     * TUDO atomicamente (antes só deletava a linha → gap de order +
     * colisão UNIQUE no próximo add).
     */
    fun removeSection(sectionId: String) {
        if (_sections.value.none { it.section.id == sectionId }) return
        val renormalized = renormalizeSections(
            _sections.value.filterNot { it.section.id == sectionId }
        )
        _sections.value = renormalized
        launchAtomicPersist(renormalized)
    }

    /** Move uma seção UP/DOWN. Sem efeito nos extremos (no-op). */
    fun moveSection(sectionId: String, direction: MoveDirection) {
        val list = _sections.value.toMutableList()
        val idx = list.indexOfFirst { it.section.id == sectionId }
        if (idx < 0) return
        val target = when (direction) {
            MoveDirection.UP -> idx - 1
            MoveDirection.DOWN -> idx + 1
        }
        if (target < 0 || target >= list.size) return
        list[idx] = list[target].also { list[target] = list[idx] }
        val renormalized = renormalizeSections(list)
        _sections.value = renormalized
        launchAtomicPersist(renormalized)
    }

    /** Muda o role de uma seção (sem validar — validação é aviso). */
    fun updateSectionRole(sectionId: String, newRole: SectionRole) {
        update(sectionId) {
            it.copy(section = it.section.copy(role = newRole, updatedAt = clock()))
        }
    }

    // ---------- 3.2.5f.1: CRUD de sub-pontos ----------

    /**
     * Adiciona um sub-ponto vazio a uma seção.
     *
     * Permite qualquer role (usuário é dono; a UI decide quando oferecer).
     *
     * @param afterSubPointId se presente, entra após este; senão, no fim
     * @return o sub-ponto criado, ou null se a seção não existe
     */
    fun addSubPoint(sectionId: String, afterSubPointId: String? = null): SubPoint? {
        val current = _sections.value.firstOrNull { it.section.id == sectionId } ?: return null
        val now = clock()
        val newId = idProvider()
        val insertAt = if (afterSubPointId != null) {
            current.subPoints.indexOfFirst { it.id == afterSubPointId }.let {
                if (it < 0) current.subPoints.size else it + 1
            }
        } else {
            current.subPoints.size
        }
        val newSubPoint = SubPoint(
            id = newId,
            sectionId = sectionId,
            order = insertAt, // renormalizado abaixo
            outlineText = "",
            bibleRefs = emptyList(),
            publicationRefs = emptyList(),
            instruction = null,
            developedHtml = "",
            createdAt = now,
            updatedAt = now,
        )
        val newList = current.subPoints.toMutableList().apply { add(insertAt, newSubPoint) }
        val renormalized = renormalizeSubPoints(newList)
        val updatedSections = _sections.value.map { state ->
            if (state.section.id == sectionId) state.copy(subPoints = renormalized) else state
        }
        _sections.value = updatedSections
        // Hotfix P0.2: persiste TUDO atomicamente (UNIQUE(sectionId, order)).
        launchAtomicPersist(updatedSections)
        return newSubPoint
    }

    /** Remove um sub-ponto. Hotfix P0.2: persiste TUDO atomicamente. */
    fun removeSubPoint(subPointId: String) {
        val parentIdx = _sections.value.indexOfFirst { s -> s.subPoints.any { it.id == subPointId } }
        if (parentIdx < 0) return
        val parent = _sections.value[parentIdx]
        val filtered = parent.subPoints.filterNot { it.id == subPointId }
        val renormalized = renormalizeSubPoints(filtered)
        val updatedSections = _sections.value.mapIndexed { i, state ->
            if (i == parentIdx) state.copy(subPoints = renormalized) else state
        }
        _sections.value = updatedSections
        launchAtomicPersist(updatedSections)
    }

    /** Move um sub-ponto UP/DOWN dentro da seção. Sem efeito nos extremos. */
    fun moveSubPoint(subPointId: String, direction: MoveDirection) {
        val parentIdx = _sections.value.indexOfFirst { s -> s.subPoints.any { it.id == subPointId } }
        if (parentIdx < 0) return
        val parent = _sections.value[parentIdx]
        val list = parent.subPoints.toMutableList()
        val idx = list.indexOfFirst { it.id == subPointId }
        if (idx < 0) return
        val target = when (direction) {
            MoveDirection.UP -> idx - 1
            MoveDirection.DOWN -> idx + 1
        }
        if (target < 0 || target >= list.size) return
        list[idx] = list[target].also { list[target] = list[idx] }
        val renormalized = renormalizeSubPoints(list)
        val updatedSections = _sections.value.mapIndexed { i, state ->
            if (i == parentIdx) state.copy(subPoints = renormalized) else state
        }
        _sections.value = updatedSections
        launchAtomicPersist(updatedSections)
    }

    /** Atualiza o `outlineText` de um sub-ponto (edição manual). */
    fun updateSubPointOutlineText(subPointId: String, newText: String) {
        val parent = _sections.value.firstOrNull { s -> s.subPoints.any { it.id == subPointId } }
            ?: return
        update(parent.section.id) { state ->
            state.copy(subPoints = state.subPoints.map { sp ->
                if (sp.id == subPointId) sp.copy(outlineText = newText, updatedAt = clock()) else sp
            })
        }
    }

    // ---------- helpers privados (3.2.5f.1) ----------

    /**
     * Renormaliza `order` das seções para 0..N contíguo.
     * Retorna nova lista; não persiste.
     */
    private fun renormalizeSections(list: List<SectionUiState>): List<SectionUiState> =
        list.mapIndexed { idx, state ->
            if (state.section.order == idx) state
            else state.copy(section = state.section.copy(order = idx))
        }

    /** Renormaliza `order` dos sub-pontos para 0..N contíguo. */
    private fun renormalizeSubPoints(list: List<SubPoint>): List<SubPoint> =
        list.mapIndexed { idx, sp ->
            if (sp.order == idx) sp else sp.copy(order = idx)
        }

    private fun defaultTitleFor(role: SectionRole): String = when (role) {
        SectionRole.INTRO -> "Introdução"
        SectionRole.BODY -> "Novo ponto"
        SectionRole.CONCLUSION -> "Conclusão"
    }

    private fun defaultMinutesFor(role: SectionRole): Int = when (role) {
        SectionRole.INTRO -> 1
        SectionRole.BODY -> 5
        SectionRole.CONCLUSION -> 1
    }

    private fun update(sectionId: String, transform: (SectionUiState) -> SectionUiState) {
        val current = _sections.value.firstOrNull { it.section.id == sectionId } ?: return
        val updated = transform(current).copy(isDirty = true)
        _sections.value = _sections.value.map { if (it.section.id == sectionId) updated else it }
        scheduleSave(sectionId)
    }

    // ---------- autosave ----------

    private fun scheduleSave(sectionId: String) {
        saveJobs[sectionId]?.cancel()
        saveJobs[sectionId] = innerScope.launch {
            markSaving(sectionId, true)
            try {
                if (debounceMs > 0) delay(debounceMs)
                saveSection(sectionId)
                markDirty(sectionId, false)
            } finally {
                // cancelamento não pode deixar a seção presa em "salvando"
                markSaving(sectionId, false)
            }
            onSaved()
        }
    }

    private suspend fun saveSection(sectionId: String) {
        val current = _sections.value.firstOrNull { it.section.id == sectionId } ?: return
        sectionDao.upsert(current.section.toEntity())
        if (current.subPoints.isNotEmpty()) {
            subPointDao.upsertAll(current.subPoints.map { it.toEntity() })
        }
    }

    /**
     * Persiste TODAS as seções + sub-pontos em transação, com orders
     * renormalizados 0..N.
     *
     * Estratégia: delete tudo + reinsere tudo (atômico). Evita colisão com
     * UNIQUE(noteId, order) / UNIQUE(sectionId, order) em mutações estruturais
     * (add/remove/move).
     *
     * Custo aceitável: notas têm dezenas de seções, não milhares.
     *
     * Hotfix P0.2: substitui o padrão "renormaliza só em memória + upsert
     * individual" que causava perda silenciosa de dados quando o `order`
     * colidia com o de outra seção existente.
     */
    private suspend fun persistSectionsAtomically(sections: List<SectionUiState>) {
        transactionRunner.run {
            // CASCADE no schema apaga sub-pontos junto com as seções.
            sectionDao.deleteForNote(noteId)
            sectionDao.upsertAll(sections.map { it.section.toEntity() })
            val allSubPoints = sections.flatMap { state ->
                state.subPoints.map { it.toEntity() }
            }
            if (allSubPoints.isNotEmpty()) {
                subPointDao.upsertAll(allSubPoints)
            }
        }
    }

    /** Dispara persistência atômica rastreada por [awaitSaves] (determinismo em teste). */
    private fun launchAtomicPersist(sections: List<SectionUiState>) {
        val job = scope.launch {
            persistSectionsAtomically(sections)
            onSaved()
        }
        synchronized(atomicJobs) { atomicJobs += job }
        job.invokeOnCompletion { synchronized(atomicJobs) { atomicJobs -= job } }
    }

    private fun markSaving(sectionId: String, saving: Boolean) {
        _sections.value = _sections.value.map {
            if (it.section.id == sectionId) it.copy(isSaving = saving) else it
        }
    }

    private fun markDirty(sectionId: String, dirty: Boolean) {
        _sections.value = _sections.value.map {
            if (it.section.id == sectionId) it.copy(isDirty = dirty) else it
        }
    }

    /** Aguarda os autosaves + persists atômicos pendentes (determinismo em teste). */
    suspend fun awaitSaves() {
        while (true) {
            val pending: List<Job> = saveJobs.values.toList() +
                synchronized(atomicJobs) { atomicJobs.toList() }
            if (pending.none { it.isActive }) break
            pending.forEach { it.join() }
        }
    }

    // ---------- 3.2.5d: API section-aware (consumida na 3.2.5e) ----------

    private val _pendingInserts = MutableStateFlow<List<SectionAwareInsert>>(emptyList())
    val pendingInserts: StateFlow<List<SectionAwareInsert>> = _pendingInserts.asStateFlow()

    /** Enfileira inserção direcionada a uma seção/sub-ponto. */
    fun queueInsert(insert: SectionAwareInsert) {
        _pendingInserts.value = _pendingInserts.value + insert
    }

    /** Marca inserção como consumida (a UI chama após aplicar). */
    fun consumeInsert(insert: SectionAwareInsert) {
        _pendingInserts.value = _pendingInserts.value.filterNot { it == insert }
    }

    private val _selection = MutableStateFlow<SelectionContext?>(null)
    val selection: StateFlow<SelectionContext?> = _selection.asStateFlow()

    /** Atualiza o contexto de seleção (chamado pela UI). */
    fun onSelectionChange(context: SelectionContext?) {
        _selection.value = context
    }

    // ---------- agregados (cache p/ Copilot/sync) ----------

    /**
     * HTML agregado das seções: INTRO/CONCLUSION usam `contentHtml`;
     * BODY usa os `developedHtml` dos sub-pontos, na ordem.
     */
    fun aggregateHtml(): String = _sections.value.joinToString("\n") { s ->
        when (s.section.role) {
            SectionRole.INTRO, SectionRole.CONCLUSION -> s.section.contentHtml
            SectionRole.BODY -> s.subPoints.joinToString("\n") { it.developedHtml }
        }
    }

    /**
     * Markdown agregado. DÉBITO (3.2.5a): ainda não há helper headless de
     * HTML→MD; por ora retorna o HTML agregado (o Copilot tolera; a
     * verificação de stale usa `richHtml`). Resolver na 3.2.5b/c.
     */
    fun aggregateMd(): String = aggregateHtml()
}
