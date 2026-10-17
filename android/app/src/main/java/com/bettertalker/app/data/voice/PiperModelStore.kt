package com.bettertalker.app.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * #23-fix-b: TTS premium 100% offline — o modelo Piper (voz Jeff) e o subset
 * pt-BR do espeak-ng-data vêm EMBUTIDOS em `assets/piper/jeff/` e são
 * copiados para [modelDir] no primeiro uso (lazy, quando o toggle é ativado).
 * O download via HuggingFace morreu: instalação limpa não depende de rede.
 *
 * `.part` + rename atômico por arquivo; progresso via [state].
 */
class PiperModelStore(
    val modelDir: File,
    private val openAsset: (String) -> InputStream,
) {
    data class State(
        val downloaded: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(downloaded = ready()))
    val state: StateFlow<State> = _state.asStateFlow()

    /** True se os 3 artefatos (modelo, tokens e espeak pt-BR) estão em disco. */
    fun ready(): Boolean {
        val model = File(modelDir, "model.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val espeak = File(modelDir, "espeak-ng-data")
        return model.exists() && model.length() > 1_000_000 &&
            tokens.exists() && tokens.length() > 0 &&
            ESPEAK_FILES.all { File(espeak, it).exists() }
    }

    /**
     * Copia os assets para [modelDir] (idempotente: no-op se [ready]).
     * Sem rethrow de falha de cópia — o erro fica no [state] (a UI mostra).
     * Cancelamento (botão Cancelar) volta o estado ao valor real de [ready].
     */
    suspend fun ensureProvisioned() = withContext(Dispatchers.IO) {
        if (ready()) return@withContext
        _state.value = State(downloaded = ready(), downloading = true)
        try {
            modelDir.mkdirs()
            ASSET_FILES.forEachIndexed { index, path ->
                currentCoroutineContext().ensureActive()
                val dest = File(modelDir, path)
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, "${dest.name}.part")
                openAsset("$ASSET_ROOT/$path").use { input ->
                    part.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (!part.renameTo(dest)) error("rename de $path falhou")
                _state.value = State(
                    downloading = true,
                    progress = (index + 1).toFloat() / ASSET_FILES.size,
                )
            }
            _state.value = State(downloaded = true)
        } catch (e: CancellationException) {
            _state.value = State(downloaded = ready())
            throw e
        } catch (e: Exception) {
            _state.value = State(
                downloaded = ready(),
                error = e.message ?: "Falha ao preparar a voz",
            )
        }
    }

    /** Remove o modelo + espeak (para "trocar de voz" no futuro). */
    fun delete() {
        runCatching { File(modelDir, "model.onnx").delete() }
        runCatching { File(modelDir, "tokens.txt").delete() }
        runCatching { File(modelDir, "espeak-ng-data").deleteRecursively() }
        runCatching { File(modelDir, "model.onnx.part").delete() }
        runCatching { File(modelDir, "tokens.txt.part").delete() }
        _state.value = State(downloaded = ready())
    }

    companion object {
        const val ASSET_ROOT = "piper/jeff"

        /** Ordem importa só para o progresso (o modelo é o maior). */
        internal val ASSET_FILES = listOf(
            "model.onnx",
            "tokens.txt",
            "espeak-ng-data/phondata",
            "espeak-ng-data/phonindex",
            "espeak-ng-data/phontab",
            "espeak-ng-data/intonations",
            "espeak-ng-data/pt_dict",
            "espeak-ng-data/lang/roa/pt",
            "espeak-ng-data/lang/roa/pt-BR",
        )

        /** Arquivos cuja ausência invalida o [ready] (subset pt-BR do espeak). */
        internal val ESPEAK_FILES = listOf(
            "phondata", "phonindex", "phontab", "intonations", "pt_dict",
        )
    }
}
