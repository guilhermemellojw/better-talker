package com.bettertalker.app.data.s34

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TRUST-2B3B-B0 — testes do adaptador experimental de identidade do índice Room.
 *
 * Fakes in-memory (sem Room real). A prova de persistência real depende de
 * testes instrumentados, não disponíveis neste ambiente.
 */
class NwtRoomIndexIdentityAdapterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val attachmentId = "att-trust-2b3b-b0"
    private val ref = "Mt 24:14"
    private val hash = "a".repeat(64)

    private fun tnmAttachment(
        id: String = attachmentId,
        indexed: Boolean = true,
        status: String = "ready",
        baseSlot: String? = "nwt",
        symbol: String? = "nwt",
        kind: String = "epub",
        sha256: String? = hash,
    ) = AttachmentEntity(
        id = id,
        noteId = "note-1",
        fileName = "nwt_T.epub",
        kind = kind,
        sizeBytes = 100,
        appPath = "/tmp/nwt.epub",
        indexed = indexed,
        addedAt = 1L,
        baseSlot = baseSlot,
        status = status,
        sha256 = sha256,
    )

    private fun passage(
        id: String = "p-1",
        attachmentId: String = this.attachmentId,
        ref: String = this.ref,
        text: String = "Texto sintético de Mateus 24:14.",
        section: String = "Mateus 24",
        ord: Int = 1,
    ) = PassageEntity(
        id = id,
        attachmentId = attachmentId,
        text = text,
        normalized = text.lowercase(),
        section = section,
        ref = ref,
        ord = ord,
    )

    private fun claim(
        passageId: String? = "p-1",
        canonicalRef: String = ref,
        ord: Int? = 1,
        section: String? = "Mateus 24",
        text: String = "Texto sintético de Mateus 24:14.",
        attachmentId: String = this.attachmentId,
    ) = NwtIndexedUnit(
        attachmentId = attachmentId,
        passageId = passageId,
        canonicalRef = canonicalRef,
        ord = ord,
        text = text,
        section = section,
    )

    private fun newDaos(
        attachment: AttachmentEntity? = tnmAttachment(),
        passages: List<PassageEntity> = listOf(passage()),
        findByRefOverride: PassageEntity? = null,
        throwOnGet: Boolean = false,
        throwOnFindByRefsIn: Boolean = false,
        throwCancellationOnGet: Boolean = false,
    ): Pair<FakeAttachmentDao, FakePassageDao> {
        val aDao = FakeAttachmentDao()
        if (attachment != null) aDao.attachments[attachment.id] = attachment
        aDao.throwOnGet = throwOnGet
        aDao.throwCancellationOnGet = throwCancellationOnGet
        val pDao = FakePassageDao()
        pDao.passages.addAll(passages)
        pDao.findByRefOverride = findByRefOverride
        pDao.throwOnFindByRefsIn = throwOnFindByRefsIn
        return aDao to pDao
    }

    // ---------- 1. Passagem pertencente ao anexo correto ----------
    @Test
    fun recordMatchQuandoPassagemPertenceAoAnexoCorreto() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
        assertEquals("p-1", result.unit!!.passageId)
        assertEquals(ref, result.unit!!.canonicalRef)
        assertEquals(1, result.unit!!.ord)
        assertEquals("Mateus 24", result.unit!!.section)
        assertEquals("Texto sintético de Mateus 24:14.", result.unit!!.text)
        assertTrue(result.issues.isEmpty() || result.issues.none { it.code.name.startsWith("ROOM_") })
    }

    // ---------- 2. Passagem pertencente a outro anexo ----------
    @Test
    fun recordMissingQuandoPassagemPertenceAOutroAnexo() {
        val otherPassage = passage(id = "p-other", attachmentId = "att-other", ref = ref)
        val (aDao, pDao) = newDaos(passages = listOf(otherPassage))
        // attachment att-1 has no passage for ref
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.RECORD_MISSING, result.state)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_RECORD_MISSING })
    }

    // ---------- 3. passageId fabricado ----------
    @Test
    fun metadataMismatchQuandoPassageIdFabricado() {
        val (aDao, pDao) = newDaos()
        val c = claim(passageId = "passage-fabricado")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    // ---------- 4. ord incorreto ----------
    @Test
    fun metadataMismatchQuandoOrdIncorreto() {
        val (aDao, pDao) = newDaos()
        val c = claim(ord = 999)
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    // ---------- 5. section incorreta ----------
    @Test
    fun metadataMismatchQuandoSectionIncorreta() {
        val (aDao, pDao) = newDaos()
        val c = claim(section = "Salmos 37")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    // ---------- 6. Referência canônica incompatível ----------
    @Test
    fun referenceMismatchQuandoRefIncompativel() {
        val p = passage(ref = "Outra ref")
        val (aDao, pDao) = newDaos(passages = listOf(p))
        pDao.findByRefsInOverride = listOf(p)
        val result = runAdapter(aDao, pDao, claim(canonicalRef = ref))
        assertEquals(NwtRoomIndexIdentityState.REFERENCE_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_REFERENCE_MISMATCH })
    }

    // ---------- 7. Texto alterado ----------
    @Test
    fun metadataMismatchQuandoTextoAlterado() {
        val (aDao, pDao) = newDaos()
        val c = claim(text = "Texto diferente do EPUB.")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    // ---------- 8. Passagem ausente ----------
    @Test
    fun recordMissingQuandoPassagemAusente() {
        val (aDao, pDao) = newDaos(passages = emptyList())
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.RECORD_MISSING, result.state)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_RECORD_MISSING })
    }

    // ---------- 9. Anexo removido ----------
    @Test
    fun attachmentMismatchQuandoAnexoRemovido() {
        val (aDao, pDao) = newDaos(attachment = null)
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH, result.state)
        assertNull(result.attachment)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_ATTACHMENT_MISSING })
    }

    // ---------- 10. Anexo ainda não indexado ----------
    @Test
    fun indexNotReadyQuandoAnexoNaoIndexado() {
        val (aDao, pDao) = newDaos(attachment = tnmAttachment(indexed = false))
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.INDEX_NOT_READY, result.state)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ATTACHMENT_NOT_INDEXED })
    }

    // ---------- 11. Duas edições da TNM ----------
    @Test
    fun recordMatchEmDuasEdicoesIndependentes() {
        val attA = tnmAttachment(id = "att-A")
        val attB = tnmAttachment(id = "att-B")
        val pA = passage(id = "p-A", attachmentId = "att-A", ref = ref)
        val pB = passage(id = "p-B", attachmentId = "att-B", ref = ref)
        val (aDaoA, pDaoA) = newDaos(attachment = attA, passages = listOf(pA))
        val rA = runAdapter(aDaoA, pDaoA, claim(attachmentId = "att-A", passageId = "p-A"), attachmentIdOverride = "att-A")
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, rA.state)
        assertEquals("att-A", rA.unit!!.attachmentId)

        val (aDaoB, pDaoB) = newDaos(attachment = attB, passages = listOf(pB))
        val rB = runAdapter(aDaoB, pDaoB, claim(attachmentId = "att-B", passageId = "p-B"), attachmentIdOverride = "att-B")
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, rB.state)
        assertEquals("att-B", rB.unit!!.attachmentId)
    }

    // ---------- 12. Mesmo texto em duas unidades ----------
    @Test
    fun ambiguousRecordQuandoMesmoTextoEmDuasUnidades() {
        val p1 = passage(id = "p-1", text = "Mesmo texto sintético.")
        val p2 = passage(id = "p-2", ref = "Mt 24:15", text = "Mesmo texto sintético.")
        val (aDao, pDao) = newDaos(passages = listOf(p1, p2))
        val c = claim(text = "Mesmo texto sintético.", canonicalRef = ref)
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_AMBIGUOUS_RECORD })
    }

    // ---------- 13. P1 — Duas edições da TNM não produzem falsa ambiguidade ----------
    @Test
    fun recordMatchEmDuasEdicoesSimultaneas() {
        val attA = tnmAttachment(id = "att-A")
        val attB = tnmAttachment(id = "att-B")
        val pA = passage(id = "p-A", attachmentId = "att-A", ref = ref)
        val pB = passage(id = "p-B", attachmentId = "att-B", ref = ref)
        // ambos os registros no MESMO repositório (mesmo fake)
        val aDao = FakeAttachmentDao()
        aDao.attachments[attA.id] = attA
        aDao.attachments[attB.id] = attB
        val pDao = FakePassageDao()
        pDao.passages.addAll(listOf(pA, pB))

        val rA = kotlinx.coroutines.runBlocking {
            NwtRoomIndexIdentityAdapter.verify(
                attachmentId = "att-A",
                canonicalRef = ref,
                claim = claim(attachmentId = "att-A", passageId = "p-A"),
                attachmentDao = aDao,
                passageDao = pDao,
            )
        }
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, rA.state)

        val rB = kotlinx.coroutines.runBlocking {
            NwtRoomIndexIdentityAdapter.verify(
                attachmentId = "att-B",
                canonicalRef = ref,
                claim = claim(attachmentId = "att-B", passageId = "p-B"),
                attachmentDao = aDao,
                passageDao = pDao,
            )
        }
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, rB.state)
    }

    // ---------- 14. Registros duplicados ou ambíguos ----------
    @Test
    fun ambiguousRecordQuandoRegistrosDuplicados() {
        val p1 = passage(id = "p-1", ref = ref)
        val p2 = passage(id = "p-2", ref = ref)
        val (aDao, pDao) = newDaos(passages = listOf(p1, p2))
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
        assertEquals(2, result.recordCount)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_AMBIGUOUS_RECORD })
    }

    // ---------- 15. Índice desatualizado após mudança do arquivo ----------
    @Test
    fun metadataMismatchQuandoArquivoIncompativelComIndice() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, claim(), fileSha256 = "0".repeat(64))
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    // ---------- 16. Falha ao consultar o Room ----------
    @Test
    fun readFailedQuandoConsultaRoomFalha() {
        val (aDao, pDao) = newDaos(throwOnGet = true)
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.READ_FAILED, result.state)
        assertNull(result.unit)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_READ_FAILED })
    }

    // ---------- 17. Cancelamento de coroutine ----------
    @Test
    fun rethrowCancellationException() {
        val (aDao, pDao) = newDaos(throwCancellationOnGet = true)
        try {
            runAdapter(aDao, pDao, claim())
            org.junit.Assert.fail("deveria ter lançado CancellationException")
        } catch (e: CancellationException) {
            // esperado
        }
    }

    // ---------- 18. Arquivo EPUB incompatível com o índice ----------
    @Test
    fun adapterUnitAlimentaVerificadorComMismatchDeEpub() {
        val (aDao, pDao) = newDaos()
        val adapterResult = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, adapterResult.state)
        val unit = adapterResult.unit!!

        val file = temp.newFile("nwt.epub")
        buildMinimalEpub(file, "Texto sintético de Mateus 24:14.")
        val metadata = com.bettertalker.app.data.s34.NwtAttachmentMetadata(
            attachmentId = attachmentId,
            fileName = "nwt_T.epub",
            kind = "epub",
            baseSlot = "nwt",
            symbol = "nwt",
            indexed = true,
            status = "ready",
            sha256 = "0".repeat(64), // hash diferente do arquivo -> incompatibilidade
            sizeBytes = file.length(),
            appPath = file.absolutePath,
        )
        val vResult = NwtDocumentIdentityVerifier.verify(
            metadata = metadata,
            file = file,
            indexedUnit = unit,
        )
        assertEquals(com.bettertalker.app.data.s34.NwtFileIntegrity.HASH_MISMATCH, vResult.integrity)
        // texto coincide, mas hash falha -> nao pode ser UNIT_TEXT_VERIFIED
        assertTrue(
            vResult.unit.state == com.bettertalker.app.data.s34.NwtUnitIdentityState.UNIT_PARTIAL ||
                vResult.unit.state == com.bettertalker.app.data.s34.NwtUnitIdentityState.INDEX_STALE ||
                vResult.unit.state == com.bettertalker.app.data.s34.NwtUnitIdentityState.UNIT_UNVERIFIABLE
        )
        assertTrue(NwtIssueCode.FILE_HASH_MISMATCH in vResult.issues.map { it.code } ||
            NwtIssueCode.HASH_MISMATCH in vResult.issues.map { it.code })
        assertFalse(vResult.promotion.canPromoteUnitToLocalFactual)
    }

    private fun buildMinimalEpub(file: File, text: String) {
        val docId = "1001061144"
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().trimStart().toByteArray()
            )
            zip.closeEntry()
            val manifest = """<item id="doc0" href="${docId}-split24.xhtml" media-type="application/xhtml+xml"/>"""
            val spine = """<itemref idref="doc0"/>"""
            val opf = """
                <?xml version="1.0" encoding="utf-8"?>
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Tradução do Novo Mundo (nwt-T)</dc:title>
                    <dc:language>pt</dc:language>
                    <dc:publisher>Associação Torre de Vigia de Bíblias e Tratados</dc:publisher>
                    <dc:identifier id="BookId">urn:uuid:TRUST-2B3B-B0-TEST</dc:identifier>
                    <meta property="dcterms:modified">2026-03-19T01:01:11Z</meta>
                  </metadata>
                  <manifest>$manifest</manifest>
                  <spine>$spine</spine>
                </package>
            """.trimIndent().trimStart()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
            zip.write(opf.toByteArray())
            zip.closeEntry()
            val xhtml = """
                <html><body class="jwac docId-$docId">
                <span id="chapter24"></span><span id="chapter24_verse14"></span>$text
                </body></html>
            """.trimIndent()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/${docId}-split24.xhtml"))
            zip.write(xhtml.toByteArray())
            zip.closeEntry()
        }
    }

    // ---------- 19. Consulta sem attachmentId ----------
    @Test
    fun attachmentMismatchQuandoConsultaSemAttachmentId() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, claim(), attachmentIdOverride = "")
        assertEquals(NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_ATTACHMENT_MISSING })
    }

    // ---------- 20. Anexo não identificado como TNM ----------
    @Test
    fun attachmentMismatchQuandoAnexoNaoTnm() {
        val (aDao, pDao) = newDaos(attachment = tnmAttachment(baseSlot = "be", symbol = "be", kind = "pdf"))
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH, result.state)
        assertTrue(
            result.issues.any {
                it.code == NwtIssueCode.ATTACHMENT_SLOT_MISMATCH ||
                    it.code == NwtIssueCode.ATTACHMENT_KIND_MISMATCH ||
                    it.code == NwtIssueCode.ATTACHMENT_SYMBOL_MISMATCH
            }
        )
    }

    // ---------- P2 — consulta sem limitação indevida de 500 registros ----------
    @Test
    fun textoUnicoAposMaisDe500Passagens() {
        val passages = (1..600).map { i ->
            passage(id = "p-$i", ref = "Mt 1:$i", text = "Texto $i", ord = i)
        } + passage(id = "p-target", ref = ref, text = "Texto sintético de Mateus 24:14.", ord = 601)
        val (aDao, pDao) = newDaos(passages = passages)
        val c = claim(passageId = "p-target", ord = 601, text = "Texto sintético de Mateus 24:14.")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
    }

    @Test
    fun textoDuplicadoAposPosicao500() {
        val passages = (1..600).map { i ->
            passage(id = "p-$i", ref = "Mt 1:$i", text = "Texto $i", ord = i)
        } + listOf(
            passage(id = "p-a", ref = ref, text = "Texto sintético de Mateus 24:14.", ord = 601),
            passage(id = "p-b", ref = "Mt 24:15", text = "Texto sintético de Mateus 24:14.", ord = 602),
        )
        val (aDao, pDao) = newDaos(passages = passages)
        val c = claim(passageId = "p-a", ord = 601, text = "Texto sintético de Mateus 24:14.")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
    }

    @Test
    fun textoDuplicadoNasPrimeirasPosicoes() {
        val p1 = passage(id = "p-1", ref = ref, text = "Texto sintético de Mateus 24:14.")
        val p2 = passage(id = "p-2", ref = "Mt 24:15", text = "Texto sintético de Mateus 24:14.")
        val (aDao, pDao) = newDaos(passages = listOf(p1, p2))
        val c = claim(passageId = "p-1", text = "Texto sintético de Mateus 24:14.")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.AMBIGUOUS_RECORD, result.state)
    }

    @Test
    fun mesmoTextoEmOutroAnexoNaoInvalidaAnexoSelecionado() {
        val pA = passage(id = "p-A", attachmentId = attachmentId, ref = ref, text = "Texto sintético de Mateus 24:14.")
        val pB = passage(id = "p-B", attachmentId = "att-other", ref = ref, text = "Texto sintético de Mateus 24:14.")
        val aDao = FakeAttachmentDao()
        aDao.attachments[attachmentId] = tnmAttachment(id = attachmentId)
        val pDao = FakePassageDao()
        pDao.passages.addAll(listOf(pA, pB))
        // findByRef global retorna registro do outro anexo (best-effort) — não deve bloquear
        pDao.findByRefOverride = pB
        val result = runAdapter(aDao, pDao, claim(passageId = "p-A"))
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
    }

    // ---------- P3 — falhas de leitura conservadoras ----------
    @Test
    fun readFailedQuandoFindByTextInAttachmentFalha() {
        val (aDao, pDao) = newDaos()
        pDao.throwOnFindByTextInAttachment = true
        val c = claim(text = "Texto sintético de Mateus 24:14.")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.READ_FAILED, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_READ_FAILED })
        assertNull(result.unit)
    }

    @Test
    fun rethrowCancellationNaConsultaDeDuplicatas() {
        val (aDao, pDao) = newDaos()
        pDao.throwCancellationOnFindByTextInAttachment = true
        val c = claim(text = "Texto sintético de Mateus 24:14.")
        try {
            runAdapter(aDao, pDao, c)
            org.junit.Assert.fail("deveria ter lançado CancellationException")
        } catch (e: CancellationException) {
            // esperado
        }
    }

    @Test
    fun nenhumCaminhoDeFalhaProduzRecordMatch() {
        // falha no get do anexo
        val r1 = runAdapter(FakeAttachmentDao().apply { throwOnGet = true }, FakePassageDao(), claim())
        assertTrue(r1.state != NwtRoomIndexIdentityState.RECORD_MATCH)
        // falha no findByRefsIn
        val (a2, p2) = newDaos(throwOnFindByRefsIn = true)
        val r2 = runAdapter(a2, p2, claim())
        assertTrue(r2.state != NwtRoomIndexIdentityState.RECORD_MATCH)
        // falha no findByTextInAttachment
        val (a3, p3) = newDaos()
        p3.throwOnFindByTextInAttachment = true
        val r3 = runAdapter(a3, p3, claim(text = "Texto sintético de Mateus 24:14."))
        assertTrue(r3.state != NwtRoomIndexIdentityState.RECORD_MATCH)
    }

    // ---------- P4 — referência canônica reivindicada ----------
    @Test
    fun metadataMismatchQuandoClaimComOutraReferencia() {
        val (aDao, pDao) = newDaos()
        val c = claim(canonicalRef = "Mt 24:15")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.METADATA_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_METADATA_MISMATCH })
    }

    @Test
    fun referenceMismatchQuandoRefSolicitadaVazia() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, claim(), attachmentIdOverride = attachmentId)
        // canonicalRef override not supported by runAdapter; call adapter directly
        val r = kotlinx.coroutines.runBlocking {
            NwtRoomIndexIdentityAdapter.verify(
                attachmentId = attachmentId,
                canonicalRef = "",
                claim = claim(),
                attachmentDao = aDao,
                passageDao = pDao,
            )
        }
        assertEquals(NwtRoomIndexIdentityState.REFERENCE_MISMATCH, r.state)
        assertTrue(r.issues.any { it.code == NwtIssueCode.ROOM_REFERENCE_MISMATCH })
    }

    @Test
    fun referenceMismatchQuandoRefPersistidaVazia() {
        val p = passage(ref = "")
        val (aDao, pDao) = newDaos(passages = listOf(p))
        pDao.findByRefsInOverride = listOf(p)
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.REFERENCE_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_REFERENCE_MISMATCH })
    }

    // ---------- extras ----------
    @Test
    fun recordMatchConstróiUnitAPartirDoPersistido() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        val u = result.unit!!
        assertEquals("p-1", u.passageId)
        assertEquals(ref, u.canonicalRef)
        assertEquals(1, u.ord)
        assertEquals("Mateus 24", u.section)
        assertEquals("Texto sintético de Mateus 24:14.", u.text)
        assertEquals(attachmentId, u.attachmentId)
    }

    @Test
    fun claimNullAindaVerificaRecordMatch() {
        val (aDao, pDao) = newDaos()
        val result = runAdapter(aDao, pDao, null)
        assertEquals(NwtRoomIndexIdentityState.RECORD_MATCH, result.state)
        assertNotNull(result.unit)
    }

    @Test
    fun metadataMismatchQuandoClaimAttachmentIdDiverge() {
        val (aDao, pDao) = newDaos()
        val c = claim(attachmentId = "att-other")
        val result = runAdapter(aDao, pDao, c)
        assertEquals(NwtRoomIndexIdentityState.ATTACHMENT_MISMATCH, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ATTACHMENT_MISMATCH })
    }

    @Test
    fun readFailedQuandoFindByRefsInFalha() {
        val (aDao, pDao) = newDaos(throwOnFindByRefsIn = true)
        val result = runAdapter(aDao, pDao, claim())
        assertEquals(NwtRoomIndexIdentityState.READ_FAILED, result.state)
        assertTrue(result.issues.any { it.code == NwtIssueCode.ROOM_READ_FAILED })
    }

    // ---------- helpers ----------

    private fun runAdapter(
        aDao: FakeAttachmentDao,
        pDao: FakePassageDao,
        claim: NwtIndexedUnit?,
        attachmentIdOverride: String? = null,
        fileSha256: String? = null,
    ): NwtRoomIndexIdentityResult = kotlinx.coroutines.runBlocking {
        val aid = attachmentIdOverride ?: attachmentId
        NwtRoomIndexIdentityAdapter.verify(
            attachmentId = aid,
            canonicalRef = ref,
            claim = claim,
            fileSha256 = fileSha256,
            attachmentDao = aDao,
            passageDao = pDao,
        )
    }

    // ---------- fakes ----------

    class FakeAttachmentDao : AttachmentDao {
        val attachments = mutableMapOf<String, AttachmentEntity>()
        var throwOnGet: Boolean = false
        var throwCancellationOnGet: Boolean = false

        override suspend fun get(id: String): AttachmentEntity? {
            if (throwCancellationOnGet) throw CancellationException("cancelled")
            if (throwOnGet) throw IllegalStateException("boom")
            return attachments[id]
        }

        override suspend fun all(): List<AttachmentEntity> = attachments.values.toList()
        override fun observe(): Flow<List<AttachmentEntity>> = flowOf(attachments.values.toList())
        override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> =
            flowOf(attachments.values.filter { it.noteId == noteId })

        override suspend fun upsert(a: AttachmentEntity) { attachments[a.id] = a }
        override suspend fun update(a: AttachmentEntity) { attachments[a.id] = a }
        override suspend fun setIndexed(id: String, indexed: Boolean) {
            attachments[id]?.let { attachments[id] = it.copy(indexed = indexed) }
        }

        override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {
            attachments[id]?.let { attachments[id] = it.copy(indexed = indexed, status = status, error = error) }
        }

        override suspend fun setBaseSlot(id: String, slot: String?) {
            attachments[id]?.let { attachments[id] = it.copy(baseSlot = slot) }
        }

        override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {
            attachments[id]?.let { attachments[id] = it.copy(sourceType = sourceType, symbol = symbol) }
        }

        override suspend fun setNote(id: String, noteId: String?) {
            attachments[id]?.let { attachments[id] = it.copy(noteId = noteId) }
        }

        override suspend fun baseReady(slot: String): AttachmentEntity? =
            attachments.values.firstOrNull { it.baseSlot == slot && it.indexed }

        override suspend fun delete(id: String) { attachments.remove(id) }
    }

    class FakePassageDao : PassageDao {
        val passages = mutableListOf<PassageEntity>()
        var findByRefOverride: PassageEntity? = null
        var findByRefsInOverride: List<PassageEntity>? = null
        var findByTextInAttachmentOverride: List<PassageEntity>? = null
        var throwOnFindByRefsIn: Boolean = false
        var throwCancellationOnFindByRefsIn: Boolean = false
        var throwOnFindByTextInAttachment: Boolean = false
        var throwCancellationOnFindByTextInAttachment: Boolean = false

        override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
            passages.filter { it.attachmentId == attachmentId }

        override suspend fun findByRefIn(attachmentId: String, ref: String): PassageEntity? =
            passages.firstOrNull { it.attachmentId == attachmentId && it.ref == ref }

        override suspend fun findByRefsIn(attachmentId: String, refs: List<String>): List<PassageEntity> {
            if (throwCancellationOnFindByRefsIn) throw CancellationException("cancelled")
            if (throwOnFindByRefsIn) throw IllegalStateException("boom")
            findByRefsInOverride?.let { return it }
            return passages.filter { it.attachmentId == attachmentId && it.ref in refs }
        }

        override suspend fun findByTextInAttachment(attachmentId: String, text: String, limit: Int): List<PassageEntity> {
            if (throwOnFindByTextInAttachment) throw IllegalStateException("boom-text")
            if (throwCancellationOnFindByTextInAttachment) throw CancellationException("cancelled-text")
            findByTextInAttachmentOverride?.let { return it.take(limit) }
            return passages.filter { it.attachmentId == attachmentId && it.text == text }.take(limit)
        }

        override suspend fun findByRef(ref: String): PassageEntity? =
            findByRefOverride ?: passages.firstOrNull { it.ref == ref }

        override suspend fun findByRefPrefixIn(attachmentId: String, prefix: String): List<PassageEntity> =
            passages.filter { it.attachmentId == attachmentId && it.ref.startsWith(prefix.removeSuffix("%")) }

        override suspend fun insertAll(items: List<PassageEntity>) { passages.addAll(items) }
        override suspend fun deleteForAttachment(attachmentId: String) {
            passages.removeAll { it.attachmentId == attachmentId }
        }

        override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun indexedAttachmentIds(): List<String> = passages.map { it.attachmentId }.distinct()
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? = null
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun byPageParagraph(attachmentId: String, page: Int, start: Int, end: Int): List<PassageEntity> = emptyList()
        override suspend fun byPage(attachmentId: String, start: Int, end: Int, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun byParagraphRange(attachmentId: String, start: Int, end: Int, limit: Int): List<PassageEntity> = emptyList()
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> = passages.filter { it.attachmentId in ids }
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> = passages.filter { it.attachmentId in ids }.take(limit)
    }
}