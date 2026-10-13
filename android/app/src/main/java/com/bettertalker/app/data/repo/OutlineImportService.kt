package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.OutlineConversion
import com.bettertalker.app.domain.speech.SpeechSectionValidator

/**
 * Serviço de persistência transacional do esboço convertido.
 *
 * Recebe um [OutlineConversion] já pronto (gerado pelo OutlineConverter) e
 * persiste seções + sub-pontos de forma ATÔMICA (via [TransactionRunner]).
 * Se algo falhar no meio, nada fica no banco.
 *
 * Não cria nota (o caller decide). Não toca em OutlineEntity (coexistência
 * com o fluxo de chat).
 *
 * Invariantes aplicadas:
 * - A lista completa de seções (intro + bodies + conclusion) é validada via
 *   [SpeechSectionValidator] (≥1 BODY, ≤1 INTRO, ≤1 CONCLUSION, minutes > 0).
 * - 3.2.5f-pre: a validação é INFORMATIVA, não bloqueante — o editor
 *   revalida ao observar o banco e mostra o alerta (F5c: o retorno passou a
 *   ser a CONTAGEM de seções persistidas, para avisos com número real).
 *
 * @param transactionRunner runner injetável (Room em produção, fake em teste)
 * @param sectionDao DAO de seções
 * @param subPointDao DAO de sub-pontos
 */
class OutlineImportService(
    private val transactionRunner: TransactionRunner,
    private val sectionDao: SpeechSectionDao,
    private val subPointDao: SubPointDao,
) {

    /**
     * Persiste [conversion], substituindo qualquer conteúdo existente da nota.
     *
     * Todas as escritas (delete + inserts) acontecem dentro de uma única
     * transação. A validação usa `conversion.discourseType` por default
     * (intro/conclusion nulos nos tipos curtos são pulados, não validados);
     * o parâmetro permite override explícito.
     *
     * NOTA (3.2.5f-pre): a validação via [SpeechSectionValidator] é
     * informativa, não bloqueante. Se a estrutura é inválida (ex: 2 BODYs
     * num MINISTRY_PART), o dado é persistido mesmo assim — o usuário é
     * dono do documento. A UI deve mostrar o alerta quando apropriado.
     *
     * @return o total de seções persistidas (intro + bodies + conclusão),
     *   para o chamador compor avisos com a contagem real (F5c).
     */
    suspend fun persist(
        conversion: OutlineConversion,
        discourseType: DiscourseType = conversion.discourseType,
    ): Int {
        // 1. Lista completa de seções (intro? + bodies + conclusion?).
        val allSections = buildList {
            conversion.intro?.let { add(it) }
            conversion.bodies.forEach { add(it.section) }
            conversion.conclusion?.let { add(it) }
        }

        // 2. Validação de domínio (regras do [discourseType]) — informativa:
        //    roda para consistência; o editor revalida ao observar o banco.
        SpeechSectionValidator.validate(allSections, discourseType)

        // 3. Sub-pontos de todos os bodies.
        val allSubPoints = conversion.bodies.flatMap { it.subPoints }

        // 4. Persistência atômica.
        // noteId: intro nos S-34; nos tipos curtos, do único body.
        val noteId = conversion.intro?.noteId
            ?: conversion.bodies.firstOrNull()?.section?.noteId
            ?: conversion.conclusion?.noteId
            ?: error("OutlineConversion sem seções")
        transactionRunner.run {
            // Deleção explícita dos sub-pontos antigos (além do FK CASCADE,
            // garante comportamento idêntico em fakes e produção).
            val oldSections = sectionDao.forNote(noteId)
            sectionDao.deleteForNote(noteId)
            oldSections.forEach { subPointDao.deleteForSection(it.id) }

            sectionDao.upsertAll(allSections.map { it.toEntity() })
            if (allSubPoints.isNotEmpty()) {
                subPointDao.upsertAll(allSubPoints.map { it.toEntity() })
            }
        }
        return allSections.size
    }
}
