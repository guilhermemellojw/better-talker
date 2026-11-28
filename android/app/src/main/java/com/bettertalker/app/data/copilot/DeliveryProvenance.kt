package com.bettertalker.app.data.copilot

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * BETA-TRUST/P2 — proveniência persistida do turno/card.
 *
 * O vínculo é gravado no payload da própria mensagem (mapa string->string já
 * existente), sem migração de schema. A proveniência sobrevive a restart,
 * novo turno, troca de nota/seção e reabertura do card. O texto gerado nunca
 * é usado como corpus de autoridade: [rewriteFrom] copia o corpus/refs da
 * mensagem-fonte, após conferir o hash do texto-fonte.
 */
data class DeliveryProvenance(
    val messageId: String,
    val turnId: String? = null,
    val noteId: String,
    val scopeId: String? = null,
    val targetKey: String? = null,
    val corpus: List<String> = emptyList(),
    val questionRefs: List<String> = emptyList(),
    val contentSha256: String = "",
    val boundAt: Long = 0L,
    val sourceMessageId: String? = null,
    /** P1-B — conteúdo rejeitado: exibível, mas nunca copiável/inserível. */
    val rejected: Boolean = false,
) {
    fun binds(content: String): Boolean =
        contentSha256.isNotBlank() && contentSha256 == sha256(content)

    fun matches(noteId: String?, targetKey: String?): Boolean =
        this.noteId == noteId && this.targetKey == targetKey

    /** Contrato único de cópia/histórico: nota, escopo de origem e conteúdo. */
    fun authorizesMessage(
        noteId: String?,
        text: String,
        scopeId: String? = null,
    ): Boolean = !rejected && this.noteId == noteId && binds(text) &&
        (this.scopeId == null || scopeId == null || this.scopeId == scopeId)

    /** Contrato único de inserção: nota, origem, alvo, escopo e conteúdo. */
    fun authorizesCard(
        noteId: String?,
        targetKey: String?,
        text: String,
        scopeId: String? = null,
    ): Boolean = !rejected && matches(noteId, targetKey) && binds(text) &&
        (this.scopeId == null || scopeId == null || this.scopeId == scopeId)

    /**
     * Troca de alvo só com autorização explícita do usuário; um mero
     * identificador novo não reautoriza o conteúdo.
     */
    fun withTargetKey(targetKey: String?, authorized: Boolean): DeliveryProvenance? =
        if (!authorized || rejected) null else copy(targetKey = targetKey)

    /**
     * P2.4 — continua a MESMA mensagem: mantém identidade, nota, escopo,
     * alvo, corpus e refs; só o hash e o timestamp são renovados.
     */
    fun continueFrom(existingText: String, mergedText: String, boundAt: Long): DeliveryProvenance? {
        if (rejected || !binds(existingText) || mergedText.isBlank()) return null
        return copy(contentSha256 = sha256(mergedText), boundAt = boundAt)
    }

    /**
     * P5 — revalida o vínculo depois de uma chamada ao provider: identidade,
     * nota, escopo, alvo, autoridade das fontes e hash devem ser idênticos
     * aos capturados antes da geração. Qualquer divergência falha fechado.
     */
    fun stillValidAfterProvider(
        expected: DeliveryProvenance,
        currentPayload: Map<String, String>,
        expectedText: String? = null,
    ): DeliveryProvenance? {
        if (expectedText != null && currentPayload["text"] != expectedText) return null
        val current = fromPayload(currentPayload) ?: return null
        if (current.rejected || current != expected) return null
        return current
    }

    /** Payload persistido na própria ChatEntity; nenhum schema novo. */
    fun toPayload(): Map<String, String> {
        val json = JSONObject().apply {
            put("schema", SCHEMA)
            put("messageId", messageId)
            put("turnId", turnId)
            put("noteId", noteId)
            put("scopeId", scopeId)
            put("targetKey", targetKey)
            put("corpus", JSONArray(corpus))
            put("questionRefs", JSONArray(questionRefs))
            put("contentSha256", contentSha256)
            put("boundAt", boundAt)
            put("sourceMessageId", sourceMessageId)
            put("rejected", rejected)
        }.toString()
        return mapOf(KEY to json, KEY_SHA256 to sha256(json))
    }

    companion object {
        const val SCHEMA = "BT-COORD-DELIVERY-PROVENANCE-V1"
        const val KEY = "deliveryProvenance"
        const val KEY_SHA256 = "deliveryProvenanceSha256"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun newForMessage(
            messageId: String,
            turnId: String?,
            noteId: String,
            scopeId: String?,
            targetKey: String?,
            corpus: List<String>,
            questionRefs: List<String>,
            content: String,
            boundAt: Long,
            rejected: Boolean = false,
        ): DeliveryProvenance = DeliveryProvenance(
            messageId = messageId,
            turnId = turnId,
            noteId = noteId,
            scopeId = scopeId,
            targetKey = targetKey,
            corpus = corpus.filter { it.isNotBlank() }.distinct(),
            questionRefs = questionRefs.filter { it.isNotBlank() }.distinct(),
            contentSha256 = sha256(content),
            boundAt = boundAt,
            rejected = rejected,
        )

        /**
         * Cria a proveniência de uma revisão a partir da proveniência ORIGINAL.
         * O texto revisado nunca vira corpus: [sourceText] é conferido contra
         * o hash da fonte; [revisedText] recebe apenas um novo hash.
         */
        fun rewriteFrom(
            source: DeliveryProvenance,
            sourceText: String,
            revisedText: String,
            messageId: String,
            turnId: String?,
            noteId: String,
            scopeId: String?,
            targetKey: String?,
            boundAt: Long,
            explicitTargetChange: Boolean = false,
        ): DeliveryProvenance? {
            if (source.rejected) return null
            if (!source.binds(sourceText) || revisedText.isBlank()) return null
            if (source.noteId != noteId) return null
            // Revisão não muda escopo implicitamente; troca de alvo exige
            // autorização explícita e revalidação do vínculo.
            if (source.scopeId != scopeId) return null
            if (source.targetKey != targetKey && !explicitTargetChange) return null
            return DeliveryProvenance(
                messageId = messageId,
                turnId = turnId,
                noteId = noteId,
                scopeId = scopeId,
                targetKey = targetKey,
                corpus = source.corpus,
                questionRefs = source.questionRefs,
                contentSha256 = sha256(revisedText),
                boundAt = boundAt,
                sourceMessageId = source.sourceMessageId ?: source.messageId,
                rejected = source.rejected,
            )
        }

        /** Lê e valida a proveniência do payload; qualquer adulteração falha. */
        fun fromPayload(map: Map<String, String>): DeliveryProvenance? {
            val json = map[KEY] ?: return null
            if (map[KEY_SHA256] != sha256(json)) return null
            return try {
                val obj = JSONObject(json)
                if (obj.optString("schema") != SCHEMA) return null
                val messageId = obj.optString("messageId", "")
                val noteId = obj.optString("noteId", "")
                if (messageId.isBlank() || noteId.isBlank()) return null
                val contentSha = obj.optString("contentSha256", "")
                if (!SHA256.matches(contentSha)) return null
                fun optional(key: String): String? =
                    if (obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotBlank() }
                fun strings(key: String): List<String>? {
                    val array = obj.optJSONArray(key) ?: return null
                    return (0 until array.length()).map { array.optString(it) }
                        .filter { it.isNotBlank() }
                }
                DeliveryProvenance(
                    messageId = messageId,
                    turnId = optional("turnId"),
                    noteId = noteId,
                    scopeId = optional("scopeId"),
                    targetKey = optional("targetKey"),
                    corpus = strings("corpus") ?: return null,
                    questionRefs = strings("questionRefs") ?: return null,
                    contentSha256 = contentSha,
                    boundAt = obj.optLong("boundAt", 0L),
                    sourceMessageId = optional("sourceMessageId"),
                    rejected = obj.optBoolean("rejected", false),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Resolve o vínculo persistido para cópia/histórico. Não usa estado vivo do
 * turno, não expira e exige a mesma nota; conteúdo alterado é conferido no
 * call site por [DeliveryProvenance.binds].
 */
fun provenanceForMessage(
    provenance: Map<String, DeliveryProvenance>,
    messageId: String,
    noteId: String?,
): DeliveryProvenance? = provenance[messageId]?.takeIf {
    it.messageId == messageId && it.noteId == noteId
}
