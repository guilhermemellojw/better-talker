package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.ScopedHit

/**
 * Fase 16 — paridade do chat nativo com a F15 (web).
 *
 * Montagem do contexto de um turno: separa trilhas CONTENT/TRAINING, vira
 * ContextPack, deriva a proveniência mostrada em "Fontes e apoio" e entrega o
 * prompt final. Regra inegociável: TRAINING complementa a apresentação, nunca
 * vira prova factual — por isso as duas trilhas nunca são misturadas no mesmo
 * bucket.
 *
 * Puro/testável: sem IO, sem Android.
 */

/** Trilho de uma evidência. Determina o glifo e o aviso anti-exagero. */
enum class EvidenceTrack { CONTENT, TRAINING }

/** Metadado de proveniência exibido ao usuário (§14 F15). */
data class EvidenceMeta(
    val reference: String,
    val relevance: Double,
    val track: EvidenceTrack,
    val category: TrainingCategory? = null
)

/** Glifo por trilho: 📖 conteúdo factual, 🎤 técnica de apresentação. */
fun EvidenceTrack.glyph(): String = if (this == EvidenceTrack.CONTENT) "📖" else "🎤"

/**
 * Aviso explícito de que a relevância é medida de recuperação, não certeza.
 * O web expõe o mesmo cuidado nos tooltips.
 */
fun EvidenceTrack.caveat(): String = if (this == EvidenceTrack.CONTENT)
    "Relevância de recuperação — não é certeza factual"
else "Técnica de apresentação (BE/TH) — não é prova factual"

/** Proveniência legível de um trecho recuperado, sem inventar metadado. */
fun provenanceReference(hit: ScopedHit): String {
    val ref = hit.passage.ref?.trim().orEmpty()
    if (ref.isNotEmpty()) return ref
    val section = hit.passage.section.trim()
    val sec = if (section.isEmpty()) "" else " · ${section.take(40)}"
    return hit.source + sec
}

/** Proveniência de uma evidência de domínio (mesma forma, trilha estruturada). */
fun provenanceReference(e: EvidenceSource): String {
    if (e.reference.isNotBlank()) return e.reference
    val base = e.publication ?: e.id
    val sec = e.section?.let { " · ${it.take(40)}" } ?: ""
    return base + sec
}

/** Deriva a proveniência dos trechos recuperados. Puro/testável. */
fun evidenceMetaFrom(hits: List<ScopedHit>): List<EvidenceMeta> = hits.map { h ->
    val training = isTrainingHit(h)
    EvidenceMeta(
        reference = provenanceReference(h),
        relevance = 0.0,
        track = if (training) EvidenceTrack.TRAINING else EvidenceTrack.CONTENT,
        category = if (training) TrainingCategory.fromSerial(h.passage.trainingCategory) else null
    )
}

/** "3 conteúdo · 1 técnica" — a contagem discreta do cabeçalho. */
fun provenanceSummary(meta: List<EvidenceMeta>): String {
    val c = meta.count { it.track == EvidenceTrack.CONTENT }
    val t = meta.count { it.track == EvidenceTrack.TRAINING }
    return "$c conteúdo · $t técnica"
}

/**
 * Monta o ContextPack de um turno a partir dos trechos recuperados.
 *
 * [contentHits] e [trainingHits] chegam já separados por quem recuperou; aqui
 * a separação é reforçada por construção — um trecho marcado TRAINING nunca
 * entra em [ContextPack.contentSources], mesmo se o chamador misturar as
 * listas. É a garantia de que BE/TH não vira fonte factual.
 *
 * Puro/testável.
 */
fun packFor(
    contentHits: List<ScopedHit>,
    trainingHits: List<ScopedHit> = emptyList(),
    contentLimit: Int = MAX_CONTENT_SOURCES,
    trainingLimit: Int = MAX_TRAINING_SOURCES
): ContextPack {
    val safeContent = contentLimit.coerceAtLeast(0)
    val safeTraining = trainingLimit.coerceAtLeast(0)
    val content = contentHits
        .filter { !isTrainingHit(it) }
        .take(safeContent)
        .map { it.toEvidence() }
    val training = trainingHits
        .filter { isTrainingHit(it) }
        .take(safeTraining)
        .map { it.toEvidence() }
    return ContextPack(contentSources = content, trainingSources = training)
}

/** Trecho indexado como orientação BE/TH? Catálogo preenchido significa sim. */
fun isTrainingHit(hit: ScopedHit): Boolean =
    TrainingCategory.fromSerial(hit.passage.trainingCategory) != TrainingCategory.UNKNOWN

private fun ScopedHit.toEvidence(): EvidenceSource = EvidenceSource(
    id = passage.id,
    reference = provenanceReference(this),
    text = passage.text,
    sourceType = if (isTrainingHit(this)) SourceType.TRAINING else SourceType.CONTENT,
    publication = source,
    section = passage.section.ifBlank { null },
    paragraph = passage.paragraph,
    page = passage.page,
    trainingCategory = TrainingCategory.fromSerial(passage.trainingCategory)
)

/**
 * Contexto de um turno: o que foi recuperado, o que virou prompt e o que o
 * usuário pode conferir em "Fontes e apoio". Agrupa os três para que a UI não
 * precise remontar nada.
 */
data class ChatTurnContext(
    val pack: ContextPack,
    val prompt: String,
    val evidence: List<EvidenceMeta>,
    /** F19-B.5: estrutura do S-34 quando há outline neste turno (null = legado). */
    val structural: OutlineStructureContext? = null
)

/**
 * Pipeline de um turno. Este é o ÚNICO caminho: mensagem livre, quick action e
 * sugestão de continuação entram todos aqui, com a mesma montagem de contexto —
 * é o que garante que um atalho não vire um prompt paralelo.
 *
 * [history] traz a conversa anterior e [isFirstMessage] decide se a linha de
 * continuidade entra. Puro/testável.
 */
fun buildTurnContext(
    message: String,
    history: List<ChatTurn>,
    isFirstMessage: Boolean,
    contentHits: List<ScopedHit>,
    trainingHits: List<ScopedHit> = emptyList(),
    blockTitle: String? = null,
    blockMinutes: Int? = null,
    blockText: String = "",
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null
): ChatTurnContext = buildTurnContextFromPack(
    message = message,
    history = history,
    isFirstMessage = isFirstMessage,
    pack = packFor(contentHits, trainingHits),
    blockTitle = blockTitle,
    blockMinutes = blockMinutes,
    blockText = blockText,
    legacyPassages = legacyPassages,
    structural = structural
)

/**
 * Variante para pack já montado (ex.: RoomContextPackRepository). Mesma
 * montagem de prompt e proveniência — nenhum atalho tem prompt próprio.
 * Puro/testável.
 */
fun buildTurnContextFromPack(
    message: String,
    history: List<ChatTurn>,
    isFirstMessage: Boolean,
    pack: ContextPack,
    blockTitle: String? = null,
    blockMinutes: Int? = null,
    blockText: String = "",
    legacyPassages: List<String> = emptyList(),
    structural: OutlineStructureContext? = null
): ChatTurnContext {
    val prompt = buildChatPrompt(
        message = message,
        history = history,
        isFirstMessage = isFirstMessage,
        pack = pack,
        blockTitle = blockTitle,
        blockMinutes = blockMinutes,
        blockText = blockText,
        legacyPassages = legacyPassages,
        structural = structural
    )
    // Contexto e treinamento entram na proveniência, na ordem em que aparecem.
    val evidence = pack.contentSources.map {
        EvidenceMeta(it.reference, it.score, EvidenceTrack.CONTENT)
    } + pack.trainingSources.map {
        EvidenceMeta(it.reference, it.score, EvidenceTrack.TRAINING, it.trainingCategory)
    }
    return ChatTurnContext(pack, prompt, evidence, structural)
}
