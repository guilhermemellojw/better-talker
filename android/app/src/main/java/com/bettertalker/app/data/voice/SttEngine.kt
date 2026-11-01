package com.bettertalker.app.data.voice

/**
 * Abstração de motor de reconhecimento de fala (STT).
 *
 * Implementações:
 * - SystemSttEngine: usa SpeechRecognizer nativo (atual)
 * - SherpaSttEngine: usa sherpa-onnx ASR (futuro, modelo sob demanda)
 */
interface SttEngine {
    /** Estado do motor. */
    enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    /** Estado atual do modelo. */
    val modelState: ModelState

    /** Tamanho do modelo em disco (bytes), ou 0 se não baixado. */
    val modelSizeBytes: Long

    /**
     * Inicia a captura de áudio e reconhecimento.
     * @param onPartial callback para resultados parciais
     * @param onFinal callback para resultado final
     * @param onError callback para erros
     */
    fun start(
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    )

    /** Pausa a captura (preserva transcrição). */
    fun pause()

    /** Retoma a captura. */
    fun resume()

    /** Para a captura e finaliza. */
    fun stop()

    /** Cancela sem aproveitar o texto. */
    fun cancel()

    /** Libera recursos. */
    fun release()

    /** Verifica se o motor está disponível. */
    fun isAvailable(): Boolean
}
