package com.bettertalker.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.planning.RoomMethodIndex
import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.data.repo.RoomTrainingRepository
import com.bettertalker.app.domain.planning.DefaultDossierBuilder
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierBuilder
import com.bettertalker.app.domain.planning.SectionWithSubPoints
import com.bettertalker.app.domain.planning.buildDossierFrom
import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.planning.SectionGenerator
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationResult
import com.bettertalker.app.ui.theme.NOTE_COLORS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Verdade visual = HTML; markdown é derivado para Copilot/busca/sync.
 * Escritas externas (abertura, sync) entram por revisão; inserções
 * (Copilot, esboço) entram pela fila, aplicadas na árvore viva.
 */
class EditorViewModel(private val appCtx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModel() {
    private val repo = NotesRepository(appCtx.applicationContext, db)
    private val copilotRepo = com.bettertalker.app.data.repo.CopilotRepository(db)
    private val _title = MutableStateFlow("")
    private val _html = MutableStateFlow("")
    private val _mdText = MutableStateFlow("")
    /**
     * incrementado a cada escrita externa (load, sync).
     * 3.2.5c: a UI multi-seção não consome mais isto (o editor único legado
     * saiu); mantido para o Copilot/legado até a 3.2.5e.
     */
    private val _mdRevision = MutableStateFlow(0)
    private val _saving = MutableStateFlow(false)
    private val _color = MutableStateFlow(NOTE_COLORS.first().value.toLong())
    private val _folderId = MutableStateFlow<String?>(null)
    private val _pinned = MutableStateFlow(false)
    val title = _title.asStateFlow()
    val html = _html.asStateFlow()
    val mdText = _mdText.asStateFlow()
    val mdRevision = _mdRevision.asStateFlow()
    val saving = _saving.asStateFlow()
    val color = _color.asStateFlow()
    val folderId = _folderId.asStateFlow()
    val pinned = _pinned.asStateFlow()
    val attachments = repo.attachmentsFor(noteId)
    val folders = db.folderDao().observe()

    /** 3.2.5a: estado das seções (vazio em notas sem seções — legado puro). */
    private val _sections = MutableStateFlow<List<SectionUiState>>(emptyList())
    val sections = _sections.asStateFlow()

    /** Hotfix P0: aviso estrutural auto-contido (evita NPE de ordem). */
    private val structureWarningHolder = StructureWarningHolder()
    val structureWarning: StateFlow<SectionValidationResult.Invalid?> =
        structureWarningHolder.warning

    /**
     * Controlador headless de seções + sub-pontos (autosave por seção).
     * `onSaved` recomputa os agregados e atualiza o cache `NoteEntity`
     * (richHtml/mdText) — SEM tocar em `_mdRevision` (não é escrita externa).
     */
    private val sectionsController: SectionsController = SectionsController(
        noteId = noteId,
        sectionDao = db.speechSectionDao(),
        subPointDao = db.subPointDao(),
        transactionRunner = RoomTransactionRunner(db),
        scope = viewModelScope,
        debounceMs = 400L,
        onSaved = {
            if (_sections.value.isNotEmpty()) {
                _html.value = sectionsController.aggregateHtml()
                _mdText.value = sectionsController.aggregateMd()
                repo.save(noteId, _title.value, _mdText.value, _html.value,
                    curFolderId, _color.value, curPinned)
            }
        },
    )

    private var curFolderId: String? = null
    private var curPinned: Boolean = false
    private var job: Job? = null
    private var loadedOnce = false
    private var lastLocalEdit = 0L

    /**
     * Coletores que reagem a mudanças externas.
     *
     * IMPORTANTE: qualquer propriedade/estado referenciado por coletores
     * aqui DEVE ser declarado ANTES deste bloco. `StateFlow` emite o valor
     * atual sincronamente no `collect`, e `Dispatchers.Main.immediate` faz
     * o coletor rodar até a primeira suspensão durante a construção.
     *
     * Bug conhecido (3.2.5f-pre, corrigido neste hotfix): `_structureWarning`
     * era declarada depois do init e o coletor
     * `sectionsController.validationResult.collect { updateStructureWarning(it) }`
     * causava NPE. Agora o holder (`structureWarningHolder`) é declarado antes.
     */
    init {
        // reage a mudanças externas (sync, outra tela)
        viewModelScope.launch {
            db.noteDao().observeById(noteId).collect { note ->
                if (note == null) return@collect
                // 3.2.5f-pre: tipo da nota alimenta a revalidação do controller.
                sectionsController.discourseType = runCatching {
                    DiscourseType.valueOf(note.discourseType)
                }.getOrDefault(DiscourseType.S34_DISCOURSE)
                if (!loadedOnce) {
                    applyExternal(note.title, note.richHtml, note.mdText,
                        note.folderId, note.colorArgb, note.pinned)
                    loadedOnce = true
                    return@collect
                }
                // eco do próprio save: ignora
                if (_saving.value) return@collect
                if (note.richHtml != _html.value || note.title != _title.value) {
                    // digitando agora (<2s): mantém o local; um pull futuro re-entrega
                    if (System.currentTimeMillis() - lastLocalEdit < 2000) return@collect
                    applyExternal(note.title, note.richHtml, note.mdText,
                        note.folderId, note.colorArgb, note.pinned)
                } else {
                    if (note.colorArgb != 0L) _color.value = note.colorArgb
                    curFolderId = note.folderId
                    _folderId.value = note.folderId
                    curPinned = note.pinned
                    _pinned.value = note.pinned
                }
            }
        }
        // 3.2.5a: espelha o estado das seções (SectionsController headless).
        viewModelScope.launch {
            sectionsController.sections.collect { _sections.value = it }
        }
        // 3.2.5f-pre: espelha o aviso estrutural (o dismiss é local; a
        // próxima mudança republica se ainda inválido).
        viewModelScope.launch {
            sectionsController.validationResult.collect { updateStructureWarning(it) }
        }
    }

    /** Atualiza o aviso (só Invalid vira aviso; Valid/null limpam). */
    fun updateStructureWarning(result: SectionValidationResult?) {
        structureWarningHolder.update(result)
    }

    /** Dispensa o aviso (após o usuário ver). */
    fun dismissStructureWarning() {
        structureWarningHolder.dismiss()
    }

    private fun applyExternal(title: String, html: String, md: String, folderId: String?, color: Long, pinned: Boolean) {
        _title.value = title
        _html.value = html
        _mdText.value = md
        _mdRevision.value = _mdRevision.value + 1
        curFolderId = folderId
        _folderId.value = folderId
        if (color != 0L) _color.value = color
        curPinned = pinned
        _pinned.value = pinned
    }

    fun onTitle(v: String) { _title.value = v; lastLocalEdit = System.currentTimeMillis(); schedule() }

    /** Chamado pelo editor visual com HTML + markdown exportados. */
    fun onContent(html: String, md: String) {
        if (html == _html.value && md == _mdText.value) return
        _html.value = html
        _mdText.value = md
        lastLocalEdit = System.currentTimeMillis()
        schedule()
    }

    fun setColor(argb: Long) {
        _color.value = argb
        schedule()
    }

    private fun schedule() {
        job?.cancel()
        job = viewModelScope.launch {
            _saving.value = true
            delay(400)
            repo.save(noteId, _title.value, _mdText.value, _html.value,
                curFolderId, _color.value, curPinned)
            _saving.value = false
        }
    }

    /**
     * Enfileira inserção para a tela aplicar na árvore viva (preserva estilos).
     *
     * DÉBITO (3.2.5d): fachada legada (assinatura usada pelo `ChatScreen.onInsert`).
     * Direciona para a primeira BODY (ou primeira seção) como
     * [SectionAwareInsert]; a UI multi-seção consome [sectionPendingInserts].
     * TODO 3.2.5f: migrar o ChatScreen para [queueInsert] direcionado.
     */
    fun queueInsertMarkdown(markdown: String, heading: String? = null) {
        val target = _sections.value.firstOrNull { it.section.role == SectionRole.BODY }?.section?.id
            ?: _sections.value.firstOrNull()?.section?.id
            ?: return
        sectionsController.queueInsert(
            SectionAwareInsert(
                sectionId = target,
                subPointId = null,
                markdown = markdown,
                heading = heading,
            )
        )
    }

    /**
     * Fase 18 §17: trecho selecionado no editor. Volátil (não persiste,
     * não entra no autosave) — é contexto vivo para o Copilot.
     */
    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    // ---------- 3.2.5a: seções + sub-pontos (fachada p/ o SectionsController) ----------

    fun onSectionTitle(sectionId: String, title: String) =
        sectionsController.onSectionTitle(sectionId, title)

    fun onSectionMinutes(sectionId: String, minutes: Int) =
        sectionsController.onSectionMinutes(sectionId, minutes)

    fun onSectionContent(sectionId: String, html: String) =
        sectionsController.onSectionContent(sectionId, html)

    fun onSubPointContent(subPointId: String, html: String) =
        sectionsController.onSubPointContent(subPointId, html)

    // ---------- 3.2.5f.1: CRUD (fachada p/ o SectionsController) ----------

    fun addSection(role: SectionRole, afterSectionId: String? = null) =
        sectionsController.addSection(role, afterSectionId)

    fun removeSection(sectionId: String) =
        sectionsController.removeSection(sectionId)

    fun moveSection(sectionId: String, direction: MoveDirection) =
        sectionsController.moveSection(sectionId, direction)

    fun updateSectionRole(sectionId: String, role: SectionRole) =
        sectionsController.updateSectionRole(sectionId, role)

    fun addSubPoint(sectionId: String, afterSubPointId: String? = null) =
        sectionsController.addSubPoint(sectionId, afterSubPointId)

    fun removeSubPoint(subPointId: String) =
        sectionsController.removeSubPoint(subPointId)

    fun moveSubPoint(subPointId: String, direction: MoveDirection) =
        sectionsController.moveSubPoint(subPointId, direction)

    fun updateSubPointOutlineText(subPointId: String, text: String) =
        sectionsController.updateSubPointOutlineText(subPointId, text)

    // ---------- 3.2.5d: API section-aware (consumida na 3.2.5e) ----------

    /** Contexto de seleção vindo da UI (seção/sub-ponto + texto). */
    val selection: StateFlow<SelectionContext?> = sectionsController.selection

    /**
     * Inserções programáticas direcionadas (Copilot/atalhos).
     * Consumidas pela UI multi-seção desde a 3.2.5e.
     */
    val sectionPendingInserts: StateFlow<List<SectionAwareInsert>> =
        sectionsController.pendingInserts

    fun onSelectionChange(context: SelectionContext?) {
        sectionsController.onSelectionChange(context)
        // Compatibilidade: a rota chat alimenta o Copilot via
        // `selectedText` (MainActivity, ponte setSelection).
        // Deriva do contexto até a 3.2.5e ler `selection`.
        _selectedText.value = context?.selectedText?.trim()?.take(2000) ?: ""
    }

    /** Enfileira inserção direcionada (nome sem "Markdown" p/ não colidir). */
    fun queueInsert(insert: SectionAwareInsert) =
        sectionsController.queueInsert(insert)

    fun consumeInsert(insert: SectionAwareInsert) =
        sectionsController.consumeInsert(insert)

    fun unlinkAttachment(id: String) = viewModelScope.launch {
        db.attachmentDao().setNote(id, null)
        com.bettertalker.app.data.cloud.SyncScheduler.requestSync(getAppCtx())
    }

    private val outlines by lazy {
        com.bettertalker.app.data.repo.OutlineRepository(getAppCtx(), db)
    }

    /**
     * Fase 3.5b.3: DossierBuilder para o Copilot. Lazy — só instancia se
     * usado (evita custo em notas sem uso do Copilot).
     *
     * Fiação manual (sem DI), seguindo o precedente do OutlineProposerFactory.
     */
    private val dossierBuilder: DossierBuilder by lazy {
        DefaultDossierBuilder(
            referenceResolver = RoomReferenceResolver(
                passageDao = db.passageDao(),
                attachmentDao = db.attachmentDao(),
            ),
            methodIndex = RoomMethodIndex(
                trainingRepository = RoomTrainingRepository(
                    db.passageDao(),
                    db.attachmentDao(),
                ),
                attachmentDao = db.attachmentDao(),
            ),
        )
    }

    /**
     * Constrói o dossiê da seção/sub-ponto ativo. Null se não há seleção
     * ou se a seção não existe.
     *
     * Fase 3.5b.3: chamado pelo Copilot sob demanda (no `send()`). Sem cache
     * — reconstruir proativamente seria custoso (selection muda a cada 200ms).
     */
    suspend fun buildDossier(): Dossier? {
        val sel = sectionsController.selection.value ?: return null
        val sectionsWithSubs = _sections.value.map {
            SectionWithSubPoints(section = it.section, subPoints = it.subPoints)
        }
        return buildDossierFrom(
            sections = sectionsWithSubs,
            sectionId = sel.sectionId,
            subPointId = sel.subPointId,
            selectedText = sel.selectedText,
            fullContentHtml = sel.fullContentHtml,
            builder = dossierBuilder,
        )
    }

    /**
     * Fase 3.5e.2: gerador de discurso. Injetado pelo MainActivity via
     * `setSectionGenerator(...)` — o VM não tem SettingsStore nem LLM.
     * Null até a injeção (gera "não disponível").
     */
    private var sectionGenerator: SectionGenerator? = null

    /** Fase 3.5e.3: há chave remota configurada (qualquer provider)? */
    private val _canGenerateDraft = MutableStateFlow(false)
    val canGenerateDraft: StateFlow<Boolean> = _canGenerateDraft.asStateFlow()

    fun setSectionGenerator(generator: SectionGenerator, canGenerate: Boolean) {
        sectionGenerator = generator
        _canGenerateDraft.value = canGenerate
    }

    // ---------- 3.5e.3: draft do Copilot (UI) ----------

    private val _draftState = MutableStateFlow<DraftUiState>(DraftUiState.Idle)
    val draftState: StateFlow<DraftUiState> = _draftState.asStateFlow()

    private var draftJob: Job? = null

    /**
     * Gera um rascunho para [target] (sub-ponto ou seção). Assíncrono:
     * `Loading → Ready/Error` em [draftState]. Cancela geração anterior.
     *
     * Não faz nada sem chave ([canGenerateDraft]) — a UI já bloqueia o
     * item do menu; aqui é defesa em profundidade.
     */
    fun startDraft(target: DraftTarget) {
        if (!_canGenerateDraft.value) return
        if (sectionGenerator == null) return
        draftJob?.cancel()
        _draftState.value = DraftUiState.Loading(target)
        draftJob = viewModelScope.launch {
            val draft = try {
                generateDraftFor(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _draftState.value = if (draft != null) {
                DraftUiState.Ready(target, draft)
            } else {
                DraftUiState.Error(target, "Não consegui gerar o rascunho. Tente novamente.")
            }
        }
    }

    /** Cancela a geração em andamento e volta para Idle. */
    fun cancelDraft() {
        draftJob?.cancel()
        draftJob = null
        _draftState.value = DraftUiState.Idle
    }

    /**
     * Fecha a sheet. Se ainda estiver carregando, cancela o job primeiro
     * (não deixa geração órfã).
     */
    fun dismissDraft() {
        when (_draftState.value) {
            is DraftUiState.Loading -> cancelDraft()
            else -> _draftState.value = DraftUiState.Idle
        }
    }

    /**
     * Aceita o rascunho em [DraftUiState.Ready], aplicando o HTML no alvo
     * e guardando o HTML anterior em [DraftUiState.Accepted] para undo.
     *
     * O snapshot é lido de `_sections.value` ANTES de chamar
     * `onSubPointContent`/`onSectionContent` — `update()` do controller é
     * síncrono, então não há corrida.
     */
    fun acceptDraft() {
        val ready = _draftState.value as? DraftUiState.Ready ?: return
        val target = ready.target
        val applied = ready.draft.textHtml
        val previous = readTargetHtml(target) ?: return // alvo sumiu — não aplica

        when (target) {
            is DraftTarget.SubPoint -> onSubPointContent(target.subPointId, applied)
            is DraftTarget.Section -> onSectionContent(target.sectionId, applied)
        }
        _draftState.value = DraftUiState.Accepted(target, previous, applied)
    }

    /**
     * Desfaz o último aceite, restaurando [DraftUiState.Accepted.previousHtml].
     *
     * Sem comparação de conteúdo: o [DraftSheet] é um `ModalBottomSheet`,
     * então enquanto `Accepted` está ativo o usuário não consegue editar o
     * alvo — o scrim bloqueia. Assim que fecha o sheet, `dismissDraft()`
     * leva a `Idle` e o undo some. Logo, a única janela em que este método
     * é chamável é sem edição possível.
     *
     * Se o sheet deixar de ser modal no futuro, reintroduzir a validação
     * de conteúdo (o write-back normalizado do editor exige comparação
     * por conteúdo, não por HTML bruto).
     */
    fun undoDraft() {
        val accepted = _draftState.value as? DraftUiState.Accepted ?: return
        when (val target = accepted.target) {
            is DraftTarget.SubPoint -> onSubPointContent(target.subPointId, accepted.previousHtml)
            is DraftTarget.Section -> onSectionContent(target.sectionId, accepted.previousHtml)
        }
        _draftState.value = DraftUiState.Idle
    }

    /** HTML atual do alvo (null se seção/sub-ponto não existe mais). */
    private fun readTargetHtml(target: DraftTarget): String? = when (target) {
        is DraftTarget.SubPoint -> _sections.value
            .firstOrNull { it.section.id == target.sectionId }
            ?.subPoints?.firstOrNull { it.id == target.subPointId }
            ?.developedHtml
        is DraftTarget.Section -> _sections.value
            .firstOrNull { it.section.id == target.sectionId }
            ?.section?.contentHtml
    }

    /** Dossiê do alvo explícito (não depende de `selection`). */
    /**
     * Dossiê para um alvo explícito (F2b: chat com alvo fixo do FAB).
     * Null = seleção viva (mesmo que [buildDossier]).
     */
    suspend fun buildDossierFor(target: DraftTarget?): Dossier? {
        if (target == null) return buildDossier()
        val sectionsWithSubs = _sections.value.map {
            SectionWithSubPoints(section = it.section, subPoints = it.subPoints)
        }
        return when (target) {
            is DraftTarget.SubPoint -> buildDossierFrom(
                sections = sectionsWithSubs,
                sectionId = target.sectionId,
                subPointId = target.subPointId,
                selectedText = "",
                fullContentHtml = "",
                builder = dossierBuilder,
            )
            is DraftTarget.Section -> buildDossierFrom(
                sections = sectionsWithSubs,
                sectionId = target.sectionId,
                subPointId = null,
                selectedText = "",
                fullContentHtml = "",
                builder = dossierBuilder,
            )
        }
    }

    /**
     * Gera o draft para [target]. Null em falha tratável (sem gerador,
     * seção ausente, LLM indisponível). CancellationException propaga.
     */
    private suspend fun generateDraftFor(target: DraftTarget): SectionDraft? {
        val generator = sectionGenerator ?: return null
        val dossier = buildDossierFor(target) ?: return null
        return generator.generate(dossier)
    }
    val outline: kotlinx.coroutines.flow.Flow<List<com.bettertalker.app.data.db.OutlineEntity>> by lazy {
        outlines.observe(noteId)
    }

    fun unlinkOutline() = viewModelScope.launch { outlines.unlink(noteId) }

    /**
     * Lê o estado de prontidão da nota (on-demand). Delega para o
     * [CopilotRepository] (F2a; antes o cálculo morava aqui).
     *
     * DÉBITO: `hasChatHistory` carrega as linhas (`all()`) em vez de
     * um `COUNT` — aceitável (histórico por nota é pequeno); se
     * incomodar, adicionar `@Query COUNT` no ChatDao (sem migration).
     */
    suspend fun readNoteReadiness(): NoteReadiness =
        copilotRepo.readNoteReadiness(
            noteId = noteId,
            hasSections = _sections.value.isNotEmpty(),
            outlineRefsJson = outlines.get(noteId)?.refsJson.orEmpty(),
        )

    private val _pushedContext = MutableStateFlow<ChatContext?>(null)
    val pushedContext: StateFlow<ChatContext?> = _pushedContext.asStateFlow()

    /**
     * Lê a seleção atual + prontidão e empurra como contexto para o chat.
     * Chamado pelo FAB antes de navegar (~4 queries Room, on-demand).
     */
    suspend fun pushContextForChat() {
        val sel = selection.value
        _pushedContext.value = ChatContext(
            sectionId = sel?.sectionId,
            subPointId = sel?.subPointId,
            readiness = readNoteReadiness(),
        )
    }

    fun moveToFolder(fid: String?) = viewModelScope.launch {
        repo.moveNote(noteId, fid)
        curFolderId = fid
        _folderId.value = fid
    }

    fun togglePin() = viewModelScope.launch {
        repo.togglePin(noteId)
        curPinned = !curPinned
        _pinned.value = curPinned
    }

    fun trashNote() = viewModelScope.launch { repo.trash(noteId) }

    private fun getAppCtx(): android.content.Context = appCtx

    class Factory(private val ctx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(ctx, db, noteId) as T
    }
}
