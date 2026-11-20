package com.bettertalker.app.data.s34

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import kotlinx.coroutines.CancellationException

/**
 * TRUST-2B3B-B0 — adaptador experimental de identidade do índice persistido (Room).
 *
 * Verifica se uma unidade bíblica reivindicada corresponde realmente a um
 * registro persistido no índice local da TNM. Consulta [AttachmentDao] e
 * [PassageDao] restritos a um `attachmentId` específico; não usa consultas
 * globais para comprovar identidade.
 *
 * Componente experimental: puro (sem UI, Room migration, LLM ou rede),
 * não conectado a produção e sem promoção factual.
 *
 * Limitações:
 * - Não prova autenticidade oficial da TNM.
 * - Não promove conteúdo a `VERIFIED_LOCAL`.
 * - A verificação de texto contra o EPUB é feita pelo [NwtDocumentIdentityVerifier].
 */
object NwtRoomIndexIdentityAdapter {

    suspend fun verify(
        attachmentId: String,
        canonicalRef: String,
        claim: NwtIndexedUnit? = null,
        fileSha256: String? = null,
        attachmentDao: AttachmentDao,
        passageDao: PassageDao,
    ): NwtRoomIndexIdentityResult {
        val issues = mutableListOf<NwtIdentityIssue>()

        // 1. attachmentId obrigatório
        if (attachmentId.isBlank()) {
            issues += issue(NwtIssueCode.ROOM_ATTACHMENT_MISSING, "attachmentId vazio")
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH,
                attachmentId = attachmentId,
                attachment = null,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 2. anexo real (com tratamento de cancelamento)
        val attachment: AttachmentEntity? = try {
            attachmentDao.get(attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            issues += issue(NwtIssueCode.ROOM_READ_FAILED, "falha ao consultar anexo: ${e.message}")
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.READ_FAILED,
                attachmentId = attachmentId,
                attachment = null,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        if (attachment == null) {
            issues += issue(NwtIssueCode.ROOM_ATTACHMENT_MISSING, "anexo não encontrado: $attachmentId")
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH,
                attachmentId = attachmentId,
                attachment = null,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 3. anexo pronto e indexado
        if (!attachment.indexed) {
            issues += issue(NwtIssueCode.ATTACHMENT_NOT_INDEXED, "anexo não indexado")
        }
        if (attachment.status != "ready") {
            issues += issue(NwtIssueCode.ATTACHMENT_STATUS_NOT_READY, "status=${attachment.status}")
        }
        if (!attachment.indexed || attachment.status != "ready") {
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.INDEX_NOT_READY,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 4. anexo é TNM (EPUB)
        var attachmentMismatch = false
        if (!attachment.kind.equals("epub", ignoreCase = true)) {
            issues += issue(NwtIssueCode.ATTACHMENT_KIND_MISMATCH, "kind=${attachment.kind}")
            attachmentMismatch = true
        }
        if (attachment.baseSlot != "nwt") {
            issues += issue(NwtIssueCode.ATTACHMENT_SLOT_MISMATCH, "baseSlot=${attachment.baseSlot}")
            attachmentMismatch = true
        }
        if (attachment.symbol != null && attachment.symbol != "nwt") {
            issues += issue(NwtIssueCode.ATTACHMENT_SYMBOL_MISMATCH, "symbol=${attachment.symbol}")
            attachmentMismatch = true
        }
        if (attachmentMismatch) {
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 4b. compatibilidade do arquivo com o índice (opcional)
        if (fileSha256 != null && !attachment.sha256.isNullOrBlank() && attachment.sha256 != fileSha256) {
            issues += issue(
                NwtIssueCode.ROOM_METADATA_MISMATCH,
                "hash do arquivo difere do registrado: atual=$fileSha256 registrado=${attachment.sha256}",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.METADATA_MISMATCH,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 5. claim pertence ao mesmo anexo (se fornecido)
        if (claim != null && claim.attachmentId.isNotBlank() && claim.attachmentId != attachmentId) {
            issues += issue(
                NwtIssueCode.ATTACHMENT_MISMATCH,
                "unidade pertence a outro anexo: ${claim.attachmentId}",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 6. consulta restrita ao anexo + ref (sem LIMIT 1, para detectar duplicatas)
        val records: List<PassageEntity> = try {
            passageDao.findByRefsIn(attachmentId, listOf(canonicalRef))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            issues += issue(NwtIssueCode.ROOM_READ_FAILED, "falha ao consultar índice: ${e.message}")
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.READ_FAILED,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        if (records.isEmpty()) {
            issues += issue(
                NwtIssueCode.ROOM_RECORD_MISSING,
                "nenhum registro persistido para ref=$canonicalRef no anexo $attachmentId",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.RECORD_MISSING,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = 0,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }
        if (records.size > 1) {
            issues += issue(
                NwtIssueCode.ROOM_AMBIGUOUS_RECORD,
                "registros duplicados para ref=$canonicalRef: ${records.size}",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.AMBIGUOUS_RECORD,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = null,
                recordCount = records.size,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        val passage = records.first()

        // 7. referência canônica
        if (passage.ref.isNotBlank() && passage.ref != canonicalRef) {
            issues += issue(
                NwtIssueCode.ROOM_REFERENCE_MISMATCH,
                "ref persistido=${passage.ref} != $canonicalRef",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.REFERENCE_MISMATCH,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = passage,
                recordCount = 1,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 8. comparação com o claim (se fornecido) — não confiar cegamente
        var metadataMismatch = false
        if (claim != null) {
            if (!claim.passageId.isNullOrBlank() && claim.passageId != passage.id) {
                issues += issue(
                    NwtIssueCode.ROOM_METADATA_MISMATCH,
                    "passageId: persistido=${passage.id} != reivindicado=${claim.passageId}",
                )
                metadataMismatch = true
            }
            if (claim.ord != null && claim.ord != passage.ord) {
                issues += issue(
                    NwtIssueCode.ROOM_METADATA_MISMATCH,
                    "ord: persistido=${passage.ord} != reivindicado=${claim.ord}",
                )
                metadataMismatch = true
            }
            if (!claim.section.isNullOrBlank() && claim.section != passage.section) {
                issues += issue(
                    NwtIssueCode.ROOM_METADATA_MISMATCH,
                    "section: persistido=${passage.section} != reivindicado=${claim.section}",
                )
                metadataMismatch = true
            }
            if (claim.text.isNotBlank() && claim.text != passage.text) {
                issues += issue(
                    NwtIssueCode.ROOM_METADATA_MISMATCH,
                    "text: persistido difere do reivindicado",
                )
                metadataMismatch = true
            }
        }
        if (metadataMismatch) {
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.METADATA_MISMATCH,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = passage,
                recordCount = 1,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 9. ambiguidade entre anexos (mesmo ref em outro attachment)
        val global: PassageEntity? = try {
            passageDao.findByRef(canonicalRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (global != null && global.attachmentId != attachmentId) {
            issues += issue(
                NwtIssueCode.ROOM_AMBIGUOUS_RECORD,
                "mesmo ref em outro anexo: ${global.attachmentId}",
            )
            return NwtRoomIndexIdentityResult(
                state = NwtRoomIndexIdentityState.AMBIGUOUS_RECORD,
                attachmentId = attachmentId,
                attachment = attachment,
                passage = passage,
                recordCount = 1,
                unit = null,
                issues = issues.distinctIssues(),
            )
        }

        // 10. ambiguidade de texto dentro do mesmo anexo (mesmo texto em >1 unidade)
        if (claim != null && claim.text.isNotBlank()) {
            val all: List<PassageEntity> = try {
                passageDao.forAttachment(attachmentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val sameTextCount = all.count { it.text == claim.text }
            if (sameTextCount > 1) {
                issues += issue(
                    NwtIssueCode.ROOM_AMBIGUOUS_RECORD,
                    "mesmo texto em $sameTextCount unidades do anexo $attachmentId",
                )
                return NwtRoomIndexIdentityResult(
                    state = NwtRoomIndexIdentityState.AMBIGUOUS_RECORD,
                    attachmentId = attachmentId,
                    attachment = attachment,
                    passage = passage,
                    recordCount = 1,
                    unit = null,
                    issues = issues.distinctIssues(),
                )
            }
        }

        // 11. sucesso: construir NwtIndexedUnit exclusivamente a partir do registro persistido
        val unit = NwtIndexedUnit(
            attachmentId = attachment.id,
            passageId = passage.id,
            canonicalRef = passage.ref.ifBlank { canonicalRef },
            ord = passage.ord,
            text = passage.text,
            section = passage.section.takeIf { it.isNotBlank() },
        )

        return NwtRoomIndexIdentityResult(
            state = NwtRoomIndexIdentityState.RECORD_MATCH,
            attachmentId = attachment.id,
            attachment = attachment,
            passage = passage,
            recordCount = 1,
            unit = unit,
            issues = issues.distinctIssues(),
        )
    }

    private fun issue(code: NwtIssueCode, detail: String): NwtIdentityIssue =
        NwtIdentityIssue(code, detail)

    private fun List<NwtIdentityIssue>.distinctIssues(): List<NwtIdentityIssue> =
        distinctBy { it.code to it.detail }
}