package com.bettertalker.app.data.s34

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.util.S34Detector

/**
 * Fase 19-B.6 — hook de importação: S-34 bruto → OutlineDocument persistido.
 *
 * Chamado pelo indexador logo após a extração de texto, com a identidade
 * persistível do attachment já existente. O parser (B.2) é a única
 * autoridade de estrutura; aqui só orquestra detectar → parsear → salvar.
 *
 * Estados explícitos (§6 B.6): documento comum segue o fluxo normal
 * (`NotS34`), S-34 insuficiente NÃO vira estrutura falsa (`ParseFailed`).
 * Idempotente: salvar de novo substitui pelo `sourceAttachmentId` (B.3).
 */
object S34ImportHook {

    sealed interface Outcome {
        /** Estrutura persistida. */
        data class Saved(
            val outlineId: String,
            val sections: Int,
            val references: Int
        ) : Outcome

        /** Documento comum (ou sem sinais suficientes): fluxo legado. */
        data object NotS34 : Outcome

        /** Detectado como S-34, mas o parse não produziu estrutura utilizável. */
        data class ParseFailed(val reason: String) : Outcome
    }

    /**
     * Executa o hook. Nunca lança (exceto cancelamento): falha de importação
     * não pode derrubar a indexação do acervo.
     *
     * [log] é injetável para testes JVM (android.util.Log não existe lá).
     * Registra só diagnóstico — nunca texto do documento nem credenciais.
     */
    suspend fun onExtracted(
        dao: S34Dao,
        attachmentId: String,
        rawText: String,
        log: (String, String) -> Unit = { level, msg -> logAndroid(level, msg) },
        fileName: String? = null,
    ): Outcome {
        return try {
            if (!S34Detector.isS34(rawText, fileName)) return Outcome.NotS34
            val doc = S34Parser.parseS34(rawText)
            if (doc.sections.isEmpty()) {
                val reason = "sem pontos reconhecidos"
                log("w", "S34 parse failed attachment=$attachmentId reason=$reason")
                return Outcome.ParseFailed(reason)
            }
            S34OutlineRepository(dao).save(doc, attachmentId)
            val refs = doc.references.size
            log(
                "i",
                "S34 detected attachment=$attachmentId outline=${doc.id} " +
                    "sections=${doc.sections.size} references=$refs"
            )
            Outcome.Saved(doc.id, doc.sections.size, refs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.javaClass.simpleName
            log("w", "S34 parse failed attachment=$attachmentId reason=$reason")
            Outcome.ParseFailed(reason)
        }
    }

    private fun logAndroid(level: String, msg: String) {
        when (level) {
            "i" -> android.util.Log.i("S34Import", msg)
            else -> android.util.Log.w("S34Import", msg)
        }
    }
}
