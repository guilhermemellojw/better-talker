package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

/**
 * Adapters puros entre a camada de UI/estado e o contrato do domínio.
 *
 * Extraídos para serem testáveis sem VM/Room. O `DossierBuilder` continua
 * puro (não importa `ui/`).
 */

/**
 * Converte os campos crus do SelectionContext (UI) para o contrato puro.
 */
fun selectionContractOf(
    sectionId: String,
    subPointId: String?,
    selectedText: String,
    fullContentHtml: String,
): SelectionContextContract = SelectionContextContract(
    sectionId = sectionId,
    subPointId = subPointId,
    selectedText = selectedText,
    fullContentHtml = fullContentHtml,
)

/**
 * Converte uma seção + sub-pontos (par) para o container do domínio.
 */
fun sectionWithSubPointsOf(
    section: SpeechSection,
    subPoints: List<SubPoint>,
): SectionWithSubPoints = SectionWithSubPoints(section, subPoints)

/**
 * Orquestra a construção do dossiê a partir do estado do VM.
 *
 * Pura (só depende de [DossierBuilder]). Testável sem VM/Room.
 *
 * @return dossiê, ou null se não há seleção válida ou seção não encontrada
 */
suspend fun buildDossierFrom(
    sections: List<SectionWithSubPoints>,
    sectionId: String?,
    subPointId: String?,
    selectedText: String,
    fullContentHtml: String,
    builder: DossierBuilder,
): Dossier? {
    if (sectionId == null) return null
    if (sections.none { it.section.id == sectionId }) return null
    val context = selectionContractOf(
        sectionId = sectionId,
        subPointId = subPointId,
        selectedText = selectedText,
        fullContentHtml = fullContentHtml,
    )
    return builder.build(context, sections)
}
