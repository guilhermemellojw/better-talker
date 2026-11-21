package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.planning.DossierPromptBuilder
import com.bettertalker.app.domain.planning.Dossier

/**
 * 1C.2.2B0 P6 — resolução pura do contexto oratório para a seção efetivamente
 * resolvida. Garante que o dossiê (system) e as referências autorizadas venham
 * exclusivamente da seção resolvida; em caso de dossiê incompatível/ausente, o
 * contexto dependente do dossiê fica nulo (sem fallback contaminado).
 */
data class OratoryContextResolution(
    val compatible: Boolean,
    val contextBlock: String?,
    val authorizedRefs: List<String>,
)

object OratoryContextResolver {
    fun resolve(
        sectionId: String,
        resolvedDossier: Dossier?,
        includeSpeakerNote: Boolean,
        practiceContext: String?,
        promptBuilder: DossierPromptBuilder,
    ): OratoryContextResolution {
        val compatible = resolvedDossier != null &&
            resolvedDossier.currentSection.id == sectionId
        val contextBlock = if (compatible) {
            val base = promptBuilder.buildContextBlock(resolvedDossier, includeSpeakerNote)
            val practice = practiceContext?.takeIf { it.isNotBlank() }
            listOfNotNull(base, practice).joinToString("\n\n").ifBlank { null }
        } else {
            null
        }
        val authorizedRefs = if (compatible) {
            buildList {
                resolvedDossier.bibleTexts.forEach { add(it.ref) }
                resolvedDossier.publicationTexts.forEach { add(it.ref.symbol) }
            }
        } else {
            emptyList()
        }
        return OratoryContextResolution(
            compatible = compatible,
            contextBlock = contextBlock,
            authorizedRefs = authorizedRefs,
        )
    }
}
