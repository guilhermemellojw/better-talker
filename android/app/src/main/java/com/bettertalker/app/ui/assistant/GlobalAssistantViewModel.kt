package com.bettertalker.app.ui.assistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.copilot.CitationContract
import com.bettertalker.app.data.copilot.GlobalAssistantPrompt
import com.bettertalker.app.data.copilot.GlobalPromptTurn
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.library.GlobalLibraryReaderContent
import com.bettertalker.app.data.library.GlobalLibraryResult
import com.bettertalker.app.data.library.GlobalLibrarySource
import com.bettertalker.app.data.library.GlobalLibraryStatus
import com.bettertalker.app.data.library.GlobalLibraryTool
import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.LlmAction
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderFactory
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.voice.VoiceRouter
import com.bettertalker.app.data.voice.prepareForSpeech
import com.bettertalker.app.domain.planning.GenerationProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ação visual de um turno (chip/botão no chat global). */
enum class GlobalTurnAction { CONFIGURE_AI, RETRY, OPEN_LIBRARY }

/** Turno efêmero do assistente global (sem Room, sem áudio). */
data class GlobalTurn(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val local: Boolean = false,
    val action: GlobalTurnAction? = null,
    val error: Boolean = false,
    val sources: List<GlobalLibrarySource> = emptyList(),
)

/** Estado do reader de fonte aberto a partir do chip “Fontes”. */
sealed interface GlobalReaderState {
    data class Loading(val source: GlobalLibrarySource) : GlobalReaderState
    data class Content(
        val source: GlobalLibrarySource,
        val content: GlobalLibraryReaderContent,
    ) : GlobalReaderState

    data class Error(
        val source: GlobalLibrarySource,
        val message: String,
    ) : GlobalReaderState
}

/** Eventos de navegação que a rota do Assistente executa. */
sealed interface GlobalAssistantEvent {
    data class StartTrain(val noteId: String) : GlobalAssistantEvent
    data class OpenPresent(val noteId: String) : GlobalAssistantEvent
    data object ConfigureAi : GlobalAssistantEvent
    data object OpenCreateSheet : GlobalAssistantEvent
    data object OpenLibrary : GlobalAssistantEvent
    data object OpenPrepare : GlobalAssistantEvent
    data object OpenMyOutlines : GlobalAssistantEvent
    data object OpenTrain : GlobalAssistantEvent
    data object OpenPresentRoot : GlobalAssistantEvent
}

/**
 * Assistente global “Alexa” — estado efêmero da rota do Assistente.
 *
 * Comandos locais são resolvidos por [GlobalIntentRouter] sem LLM. Texto
 * livre usa o provider só quando há chave; sem chave, resposta local honesta.
 * Nenhum áudio/histórico é persistido e nenhuma nota é lida sem vínculo.
 */
class GlobalAssistantViewModel(
    private val app: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val notesRepo: NotesRepository,
    private val voiceRouter: VoiceRouter,
    private val libraryTool: GlobalLibraryTool = GlobalLibraryTool.from(db),
) : ViewModel() {

    private val _turns = MutableStateFlow<List<GlobalTurn>>(emptyList())
    val turns: StateFlow<List<GlobalTurn>> = _turns.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pending = MutableStateFlow<GlobalPending?>(null)
    val pending: StateFlow<GlobalPending?> = _pending.asStateFlow()

    private val _events = MutableSharedFlow<GlobalAssistantEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<GlobalAssistantEvent> = _events.asSharedFlow()

    private val _reader = MutableStateFlow<GlobalReaderState?>(null)
    val reader: StateFlow<GlobalReaderState?> = _reader.asStateFlow()

    val notes: StateFlow<List<GlobalNote>> = db.noteDao().observe(null, "")
        .map { rows ->
            rows.map { note ->
                GlobalNote(note.id, note.title.ifBlank { "Sem título" })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val boundNoteId: StateFlow<String?> = settings.lastNoteId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val speaking: StateFlow<Boolean> = voiceRouter.speaking
    val ttsReady: StateFlow<Boolean> = voiceRouter.ready

    private var generationJob: Job? = null
    private var readerJob: Job? = null

    fun send(text: String, fromVoice: Boolean = false) {
        val clean = text.trim()
        if (clean.isBlank() || _busy.value) return
        _turns.value = _turns.value + GlobalTurn(
            id = newId("gt"),
            text = clean,
            fromMe = true,
        )
        val route = GlobalIntentRouter.route(
            text = clean,
            notes = notes.value,
            boundNoteId = boundNoteId.value,
            pending = _pending.value,
        )
        _pending.value = null
        handleRoute(route)
    }

    fun bindNote(noteId: String) {
        _pending.value = null
        viewModelScope.launch { settings.setLastNoteId(noteId) }
    }

    fun clearBinding() {
        _pending.value = null
        viewModelScope.launch { settings.clearLastNoteId() }
    }

    fun retryLast() {
        val lastUser = _turns.value.lastOrNull { it.fromMe } ?: return
        if (!_busy.value) send(lastUser.text)
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _busy.value = false
    }

    fun speak(text: String) {
        voiceRouter.speak(prepareForSpeech(text))
    }

    fun stopSpeaking() = voiceRouter.stop()

    fun openSource(source: GlobalLibrarySource) {
        readerJob?.cancel()
        _reader.value = GlobalReaderState.Loading(source)
        readerJob = viewModelScope.launch {
            _reader.value = try {
                GlobalReaderState.Content(source, libraryTool.read(source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GlobalReaderState.Error(
                    source,
                    e.message?.take(160) ?: "Não consegui abrir esta fonte.",
                )
            }
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        readerJob = null
        _reader.value = null
    }

    /** Erro de voz vira um turno local (sem derrubar o composer). */
    fun onVoiceError(message: String) {
        reply(message, error = true)
    }

    private fun handleRoute(route: GlobalRoute) {
        when (route) {
            GlobalRoute.ListNotes -> reply(notesReply(notes.value))
            is GlobalRoute.OpenNote -> {
                _pending.value = GlobalPending.OpenNote(route.note)
                reply("Encontrei o esboço “${route.note.title}”. Quer que eu abra?")
            }
            is GlobalRoute.AskWhichNote -> {
                _pending.value = GlobalPending.ChooseNote(
                    candidates = route.candidates,
                    action = route.action,
                )
                reply(whichReply(route.candidates))
            }
            is GlobalRoute.NotFound -> reply(notFoundReply(route.recent))
            GlobalRoute.OpenLibrary -> {
                _events.tryEmit(GlobalAssistantEvent.OpenLibrary)
                reply("Abrindo a Biblioteca.")
            }
            GlobalRoute.OpenPrepare -> {
                _events.tryEmit(GlobalAssistantEvent.OpenPrepare)
                reply("Abrindo o Preparo.")
            }
            GlobalRoute.OpenMyOutlines -> {
                _events.tryEmit(GlobalAssistantEvent.OpenMyOutlines)
                reply("Abrindo Meus esboços.")
            }
            GlobalRoute.CreateNote -> {
                _events.tryEmit(GlobalAssistantEvent.OpenCreateSheet)
                reply("Escolha o tipo de esboço para começar.")
            }
            is GlobalRoute.StartTrain -> execute(GlobalPending.StartTrain(route.note))
            is GlobalRoute.OpenPresent -> execute(GlobalPending.OpenPresent(route.note))
            is GlobalRoute.AskNoteForAction -> reply(askNoteReply(route.action))
            GlobalRoute.ConfigureAi -> reply(
                message = "A chave de IA fica em Modelo IA. Toque em Configurar IA para abrir.",
                action = GlobalTurnAction.CONFIGURE_AI,
            )
            GlobalRoute.Help -> reply(HELP_REPLY)
            is GlobalRoute.Chat -> chat(route.text)
            is GlobalRoute.Confirm -> execute(route.pending)
            GlobalRoute.Cancel -> reply("Tudo bem.")
        }
    }

    private fun execute(pending: GlobalPending) {
        when (pending) {
            is GlobalPending.OpenNote -> {
                bindNote(pending.note.id)
                _events.tryEmit(GlobalAssistantEvent.OpenPrepare)
                reply("Abrindo “${pending.note.title}” no Preparo.")
            }
            is GlobalPending.StartTrain -> {
                viewModelScope.launch {
                    settings.setTrainNoteId(pending.note.id)
                    settings.setLastNoteId(pending.note.id)
                    _events.emit(GlobalAssistantEvent.StartTrain(pending.note.id))
                }
            }
            is GlobalPending.OpenPresent -> {
                viewModelScope.launch {
                    settings.setPresentNoteId(pending.note.id)
                    settings.setLastNoteId(pending.note.id)
                    _events.emit(GlobalAssistantEvent.OpenPresent(pending.note.id))
                }
            }
            is GlobalPending.ChooseNote -> {
                _pending.value = pending
                reply(whichReply(pending.candidates))
            }
        }
    }

    private fun createNote() {
        viewModelScope.launch {
            val id = notesRepo.newNote(null)
            val note = GlobalNote(id, "Nova nota")
            _pending.value = GlobalPending.OpenNote(note)
            reply("Criei o esboço “Nova nota”. Quer que eu abra?")
        }
    }

    private fun chat(text: String) {
        _busy.value = true
        generationJob = viewModelScope.launch {
            try {
                val library = withContext(Dispatchers.IO) { libraryTool.search(text) }
                val local = localLibraryReply(library)
                if (local != null) {
                    reply(
                        message = local,
                        action = localLibraryAction(library),
                        sources = emptyList(),
                    )
                    return@launch
                }

                val remote = ProviderFactory.resolveRemote(settings, app)
                if (!ProviderFactory.useRemoteRoute(remote)) {
                    reply(
                        message = localNoKeyReply(library),
                        action = GlobalTurnAction.CONFIGURE_AI,
                        sources = library.sources,
                    )
                    return@launch
                }
                val provider = ProviderFactory.createFor(
                    remote,
                    app,
                    DeepSeekProvider.CHAT_REASONING_EFFORT,
                )
                val history = _turns.value
                    .dropLast(1)
                    .takeLast(6)
                    .map { GlobalPromptTurn(it.fromMe, it.text) }
                val prompt = GlobalAssistantPrompt.build(
                    userText = text,
                    recentTitles = notes.value.take(6).map { it.title },
                    sources = library.sources,
                    libraryNote = library.message,
                    history = history,
                )
                var answer = withContext(Dispatchers.IO) {
                    provider.generate(
                        LlmRequest(
                            text = prompt,
                            action = LlmAction.CHAT,
                            message = prompt,
                            generationProfile = GenerationProfile.GLOBAL_ASSISTANT,
                            maxOutputTokens = 512,
                            maxOutputTokensCap = 512,
                        )
                    )
                }.text.trim()
                if (answer.isBlank()) {
                    reply(ERROR_REPLY, GlobalTurnAction.RETRY, error = true)
                    return@launch
                }
                if (library.sources.isNotEmpty()) {
                    val firstCheck = CitationContract.check(
                        answer = answer,
                        sourceCount = library.sources.size,
                        requireCitation = true,
                        sourceAvailable = true,
                    )
                    if (!firstCheck.ok) {
                        val retryPrompt = buildString {
                            append(prompt)
                            appendLine()
                            appendLine()
                            appendLine("CORRIJA A RESPOSTA ANTERIOR.")
                            appendLine(
                                "Violações: ${firstCheck.violations.joinToString("; ")}."
                            )
                            append(
                                "Responda de novo em 4 ou 5 frases, cite [Fonte N] " +
                                    "válida e não diga que não tem acesso."
                            )
                        }
                        val retry = withContext(Dispatchers.IO) {
                            provider.generate(
                                LlmRequest(
                                    text = retryPrompt,
                                    action = LlmAction.CHAT,
                                    message = retryPrompt,
                                    generationProfile = GenerationProfile.GLOBAL_ASSISTANT,
                                    maxOutputTokens = 512,
                                    maxOutputTokensCap = 512,
                                )
                            )
                        }.text.trim()
                        val retryCheck = CitationContract.check(
                            answer = retry,
                            sourceCount = library.sources.size,
                            requireCitation = true,
                            sourceAvailable = true,
                        )
                        if (retryCheck.ok) {
                            answer = retry
                        } else {
                            reply(
                                message = localSourceFallback(library.sources),
                                sources = library.sources,
                            )
                            return@launch
                        }
                    }
                }
                reply(answer, sources = library.sources)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reply(ERROR_REPLY, GlobalTurnAction.RETRY, error = true)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun localLibraryReply(result: GlobalLibraryResult): String? = when (result.status) {
        GlobalLibraryStatus.MISSING -> result.message
        GlobalLibraryStatus.INDEXING -> result.message
        GlobalLibraryStatus.AMBIGUOUS -> ambiguousReply(result)
        GlobalLibraryStatus.NO_MATCH ->
            if (result.libraryIntent) result.message else null
        else -> null
    }

    private fun localLibraryAction(result: GlobalLibraryResult): GlobalTurnAction? =
        when (result.status) {
            GlobalLibraryStatus.MISSING,
            GlobalLibraryStatus.INDEXING,
            -> GlobalTurnAction.OPEN_LIBRARY
            else -> null
        }

    private fun ambiguousReply(result: GlobalLibraryResult): String = buildString {
        append(result.message ?: "Qual edição?")
        result.candidates.take(4).forEachIndexed { index, candidate ->
            appendLine()
            append("${index + 1}) ${candidate.title}")
        }
        if (result.candidates.isNotEmpty()) {
            appendLine()
            append("Qual delas? Diga a edição (por exemplo, “Sentinela de julho de 2025”).")
        }
    }

    private fun localNoKeyReply(result: GlobalLibraryResult): String =
        if (result.sources.isEmpty()) {
            NO_KEY_REPLY
        } else {
            val n = result.sources.size
            "Encontrei $n fonte${if (n == 1) "" else "s"} no acervo. " +
                "Abra “Fontes ($n)” para ler. Para conversar sobre o conteúdo, " +
                "configure a chave em Modelo IA."
        }

    private fun localSourceFallback(sources: List<GlobalLibrarySource>): String {
        val first = sources.first()
        val unit = first.unitLabel?.let { " · $it" }.orEmpty()
        val location = first.locationLabel.takeIf { it.isNotBlank() }
            ?.let { " · $it" }.orEmpty()
        val more = if (sources.size > 1) {
            " Há mais ${sources.size - 1} fonte(s) em “Fontes”."
        } else {
            ""
        }
        return "Segundo [Fonte 1] (${first.publicationTitle}$unit$location): " +
            "“${first.snippet.take(240)}”.$more"
    }

    private fun reply(
        message: String,
        action: GlobalTurnAction? = null,
        error: Boolean = false,
        sources: List<GlobalLibrarySource> = emptyList(),
    ) {
        _turns.value = _turns.value + GlobalTurn(
            id = newId("gt"),
            text = message,
            fromMe = false,
            local = true,
            action = action,
            error = error,
            sources = sources,
        )
    }

    private fun notesReply(notes: List<GlobalNote>): String {
        if (notes.isEmpty()) {
            return "Você ainda não tem esboços. Crie o primeiro na aba Esboços."
        }
        return "Seus esboços:\n" + notes.take(8).joinToString("\n") { "• ${it.title}" }
    }

    private fun whichReply(candidates: List<GlobalNote>): String = buildString {
        appendLine("Encontrei mais de um:")
        candidates.take(6).forEachIndexed { index, note ->
            appendLine("${index + 1}) ${note.title}")
        }
        append("Qual deles?")
    }

    private fun notFoundReply(recent: List<GlobalNote>): String {
        if (recent.isEmpty()) return "Não achei esse esboço e você ainda não tem notas recentes."
        return "Não achei esse esboço. Seus recentes são: " +
            recent.take(6).joinToString(", ") { it.title } + "."
    }

    private fun askNoteReply(action: GlobalNoteAction): String {
        val what = when (action) {
            GlobalNoteAction.TRAIN -> "treinar"
            GlobalNoteAction.PRESENT -> "apresentar no Palco"
            GlobalNoteAction.OPEN -> "abrir"
        }
        val recent = notes.value.take(6)
        if (recent.isEmpty()) return "Qual esboço você quer $what? Você ainda não tem esboços."
        return "Qual esboço você quer $what? Seus recentes são: " +
            recent.joinToString(", ") { it.title } + "."
    }

    override fun onCleared() {
        generationJob?.cancel()
        readerJob?.cancel()
        voiceRouter.stop()
        super.onCleared()
    }

    companion object {
        const val NO_KEY_REPLY =
            "Posso abrir esboços, listar notas e levar ao Treino/Palco. " +
                "Para conversar livremente, configure a chave em Modelo IA."
        const val ERROR_REPLY =
            "Não consegui falar com a IA agora. Verifique a conexão e tente novamente."
        const val HELP_REPLY =
            "Posso listar e abrir esboços, criar um esboço novo, iniciar Treino/Palco " +
                "e tirar dúvidas de oratória. Fale ou digite, por exemplo: " +
                "“quais esboços tenho?”, “abrir o esboço Esperança” ou “iniciar treino”."
    }

    class Factory(
        private val app: Context,
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val voiceRouter: VoiceRouter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GlobalAssistantViewModel(
                app.applicationContext,
                db,
                settings,
                NotesRepository(app.applicationContext, db),
                voiceRouter,
            ) as T
    }
}
