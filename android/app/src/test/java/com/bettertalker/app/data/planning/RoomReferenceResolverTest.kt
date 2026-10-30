package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.repo.publicationHitOf
import com.bettertalker.app.data.repo.publicationRefOf
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5a: testes do [RoomReferenceResolver] com fakes in-memory
 * (padrão do projeto; sem Android/Room).
 */
class RoomReferenceResolverTest {

    private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
        override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
        override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId }
        override suspend fun deleteForAttachment(attachmentId: String) {
            rows = rows.filter { it.attachmentId != attachmentId }
        }
        override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.normalized.contains(norm) }.take(limit)
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
        override suspend fun indexedAttachmentIds(): List<String> =
            rows.map { it.attachmentId }.distinct()
        override suspend fun findByRef(ref: String): PassageEntity? =
            rows.firstOrNull { it.ref == ref }
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> =
            rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> =
            forAttachments(ids).take(limit)
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
                .sortedBy { it.ord }.take(limit)
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
            rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
                .minByOrNull { it.ord }
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.section.contains(needle, ignoreCase = true) }
                .sortedBy { it.ord }.take(limit)
        override suspend fun byPageParagraph(
            attachmentId: String,
            page: Int,
            start: Int,
            end: Int,
        ): List<PassageEntity> =
            rows.filter {
                it.attachmentId == attachmentId && it.page == page &&
                    it.paragraph?.let { p -> p in start..end } == true
            }.sortedWith(compareBy({ it.paragraph }, { it.ord }))
        override suspend fun byPage(
            attachmentId: String,
            start: Int,
            end: Int,
            limit: Int,
        ): List<PassageEntity> =
            rows.filter {
                it.attachmentId == attachmentId &&
                    it.page?.let { p -> p in start..end } == true
            }.sortedWith(compareBy({ it.page }, { it.ord })).take(limit)
        override suspend fun byParagraphRange(
            attachmentId: String,
            start: Int,
            end: Int,
            limit: Int,
        ): List<PassageEntity> =
            rows.filter {
                it.attachmentId == attachmentId &&
                    it.paragraph?.let { p -> p in start..end } == true
            }.sortedBy { it.ord }.take(limit)
    }

    private class FakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
        override fun observe(): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override suspend fun all(): List<AttachmentEntity> = rows
        override suspend fun get(id: String): AttachmentEntity? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun update(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun setIndexed(id: String, indexed: Boolean) {}
        override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {}
        override suspend fun setBaseSlot(id: String, slot: String?) {}
        override suspend fun setNote(id: String, noteId: String?) {}
        override suspend fun baseReady(slot: String): AttachmentEntity? = null
        override suspend fun delete(id: String) { rows = rows.filter { it.id != id } }
        override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {}
    }

    private fun passage(
        id: String,
        att: String,
        ref: String,
        text: String,
        normalized: String,
        section: String = "",
        ord: Int = 0,
        paragraph: Int? = null,
        page: Int? = null,
    ) = PassageEntity(
        id, att, text, normalized, section = section, ref = ref,
        paragraph = paragraph, ord = ord, page = page,
    )

    private fun attachment(id: String, symbol: String?, indexed: Boolean = true) =
        AttachmentEntity(id, null, "$id.pdf", "pdf", 1L, "/x", indexed, 1L, baseSlot = symbol, symbol = symbol)

    private fun resolver(
        passages: List<PassageEntity> = emptyList(),
        attachments: List<AttachmentEntity> = emptyList(),
    ) = RoomReferenceResolver(FakePassageDao(passages), FakeAttachmentDao(attachments))

    // ---------- Bible: RESOLVED ----------

    @Test
    fun resolveBible_tnmExact_returnsResolved() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto de gen 3 6", "texto de gen 3 6")))
        val out = r.resolveBible("Gên 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
        assertEquals("texto de gen 3 6", out.text)
        assertEquals("p1", out.passageId)
    }

    @Test
    fun resolveBible_fullName_normalizesAndFinds() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto", "texto")))
        val out = r.resolveBible("Gênesis 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
    }

    @Test
    fun resolveBible_oldAbbreviation_normalizesAndFinds() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto", "texto")))
        val out = r.resolveBible("Gê 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
    }

    @Test
    fun resolveBible_withLetterSuffix_works() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Ap 15:3", "texto ap", "texto ap")))
        val out = r.resolveBible("Re 15:3b")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Ap 15:3", out.canonicalRef)
    }

    @Test
    fun resolveBible_jobAcentuado_resolveComoJo() = runBlocking {
        // T5 (acesso bíblico): "Jó" acentuado não cai mais em João ("Jo") —
        // canônico "Jó 33:24" acha o versículo (João só tem 21 capítulos).
        val r = resolver(listOf(passage("p1", "nwt", "Jó 33:24", "texto de jó", "texto de jo")))
        val out = r.resolveBible("Jó 33:24")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Jó 33:24", out.canonicalRef)
        assertEquals("texto de jó", out.text)
    }

    @Test
    fun resolveBible_jobSemAcento_vaiParaJoao() = runBlocking {
        // Sem acento é indistinguível: "Jo 33:24" é João (inexistente).
        val r = resolver(listOf(passage("p1", "nwt", "Jó 33:24", "texto", "texto")))
        val out = r.resolveBible("Jo 33:24")
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertEquals("Jo 33:24", out.canonicalRef)
    }

    // ---------- Bible: UNRESOLVED / MISSING_CORPUS ----------

    @Test
    fun resolveBible_notInCorpus_returnsUnresolved() = runBlocking {
        val out = resolver().resolveBible("Gên 3:6")
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
        assertNull(out.text)
        assertNull(out.passageId)
    }

    @Test
    fun resolveBible_invalidFormat_returnsMissingCorpus() = runBlocking {
        val out = resolver().resolveBible("XYZ 3:6")
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.canonicalRef)
    }

    // ---------- Publication: T3 (artigo/lição + parágrafo) ----------

    private fun itAtt() = attachment("it", null)
        .copy(fileName = "it_T.jwpub", symbol = null, baseSlot = null)

    private fun itRows() = listOf(
        passage("t1", "it", "# Gedalias", "# Gedalias", "gedalias", ord = 10),
        passage("t2", "it", "GEDALIAS", "GEDALIAS", "gedalias", ord = 11),
        passage("p1", "it", "it §1", "Cantor levita que serviu no templo.", "cantor levita", ord = 12, paragraph = 1),
        passage("p4", "it", "it §4", "Filho de Aicão, filho de Safã.", "filho de aicao", ord = 15, paragraph = 4),
        passage("t3", "it", "# Gederotaim", "# Gederotaim", "gederotaim", ord = 20),
        passage("x1", "it", "it §1", "outro verbete qualquer", "outro verbete qualquer", ord = 21),
    )

    @Test
    fun resolvePublication_artigoComParagrafo_returnsResolved() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Filho de Aicão, filho de Safã.", out.text)
        assertEquals("it “Gedalias” §4", out.canonicalRef)
        assertEquals("p4", out.passageId)
    }

    @Test
    fun resolvePublication_artigoSemParagrafoIndexado_returnsPartial() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "Gedalias"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.text!!.contains("Cantor levita"))
        // A fatia termina no próximo título ("# Gederotaim").
        assertFalse(out.text!!.contains("outro verbete"))
    }

    @Test
    fun resolvePublication_it1_casaArquivoUnificado() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it-1", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
    }

    @Test
    fun resolvePublication_licao_resolvePorTitulo() = runBlocking {
        val atts = listOf(
            attachment("lmd", null).copy(fileName = "lmd_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("l1", "lmd", "# Lição 3: Como estudar", "# Lição 3: Como estudar", "licao 3 como estudar", ord = 0),
            passage("l2", "lmd", "lmd §1", "Texto da lição três.", "texto da licao tres", ord = 1, paragraph = 1),
            passage("l3", "lmd", "# Lição 4: Outra", "# Lição 4: Outra", "licao 4 outra", ord = 2),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("lmd", chapter = "lição 3", paragraph = 1))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("Texto da lição três"))
        assertFalse(out.text!!.contains("Lição 4"))
    }

    @Test
    fun resolvePublication_licao_resolvePorSecao() = runBlocking {
        val atts = listOf(
            attachment("lmd", null).copy(fileName = "lmd_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("l1", "lmd", "lmd §1", "Texto da lição três.", "texto da licao tres", section = "Lição 3", ord = 0, paragraph = 1),
            passage("l2", "lmd", "lmd §2", "Mais da lição três.", "mais da licao tres", section = "Lição 3", ord = 1, paragraph = 2),
            passage("l3", "lmd", "lmd §1", "Outra lição.", "outra licao", section = "Lição 4", ord = 2, paragraph = 1),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("lmd", chapter = "lição 3", paragraph = 1))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("Texto da lição três"))
        assertFalse(out.text!!.contains("Outra lição"))
    }

    @Test
    fun resolvePublication_capituloAbreviado_casaComPalavraCheia() = runBlocking {
        // "cap. 5" deve casar com a seção "Capítulo 5" (variante T4).
        val atts = listOf(
            attachment("rr", null).copy(fileName = "rr_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("r1", "rr", "rr §1", "Texto do capítulo cinco.", "texto do capitulo cinco", section = "Capítulo 5", ord = 0),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("rr", chapter = "cap. 5"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.text!!.contains("Texto do capítulo cinco"))
    }

    @Test
    fun resolvePublication_paragraphNoInicioDoTexto_casaSemColunaParagraph() = runBlocking {
        // bhs/epub antigo: seção "Capítulo 5", coluna paragraph NULL, mas o
        // texto do §10 começa com "10 ". O resolvedor deve achar o parágrafo.
        val atts = listOf(
            attachment("bhs", null).copy(fileName = "bhs_T.epub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage(
                "b1", "bhs", "bhs Capítulo 5 §1985",
                "10 O que Jeová fez para nos libertar",
                "10 o que jeova fez para nos libertar",
                section = "Capítulo 5", ord = 1985,
            ),
            passage(
                "b2", "bhs", "bhs Capítulo 5 §1986",
                "Ele enviou seu Filho para dar a vida por nós.",
                "ele enviou seu filho para dar a vida por nos",
                section = "Capítulo 5", ord = 1986,
            ),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("bhs", chapter = "cap 5", paragraph = 10))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("10 O que Jeová fez"))
    }

    @Test
    fun resolvePublication_artigoInexistente_returnsUnresolved() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "NaoExiste"))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_soPagina_semUnidade_returnsUnresolved() = runBlocking {
        // "it 813" (só página): sem unidade identificável — nunca trecho aleatório.
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", page = 813))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_semAnexo_returnsMissingCorpus() = runBlocking {
        val out = resolver()
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_anexoNaoIndexado_returnsMissingCorpus() = runBlocking {
        // Arquivo presente mas não indexado = sem corpus pesquisável.
        val out = resolver(itRows(), listOf(itAtt().copy(indexed = false)))
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
    }

    // ---------- RC1: página + parágrafos (§§ 8-9) sem artigo/capítulo ----------

    private fun wRows() = listOf(
        passage(
            "w8", "w25.07", "w25.07 p22 §8", "Texto do parágrafo oito.",
            "texto do paragrafo oito", ord = 0, paragraph = 8, page = 22,
        ),
        passage(
            "w9", "w25.07", "w25.07 p22 §9", "Texto do parágrafo nove.",
            "texto do paragrafo nove", ord = 1, paragraph = 9, page = 22,
        ),
        passage(
            "w10", "w25.07", "w25.07 p22 §10", "Outro parágrafo da página.",
            "outro paragrafo da pagina", ord = 2, paragraph = 10, page = 22,
        ),
    )

    @Test
    fun resolvePublication_paginaEParagrafos_resolvePeloIndice() = runBlocking {
        val out = resolver(wRows(), listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(PublicationRef("w25.07", page = 22, paragraph = 8, paragraphEnd = 9))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("parágrafo oito"))
        assertTrue(out.text!!.contains("parágrafo nove"))
        assertFalse("fora do range não entra", out.text!!.contains("Outro parágrafo"))
        assertEquals("w25.07 pág. 22 §8-9", out.canonicalRef)
        assertEquals("w8", out.passageId)
    }

    @Test
    fun resolvePublication_paragrafoUnico_resolveSemRange() = runBlocking {
        val out = resolver(wRows(), listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(PublicationRef("w25.07", page = 22, paragraph = 9))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("parágrafo nove"))
        assertFalse(out.text!!.contains("parágrafo oito"))
        assertEquals("w25.07 pág. 22 §9", out.canonicalRef)
    }

    @Test
    fun perguntaComPrefixoDePagina_detectaEResolveUmTrecho() = runBlocking {
        val detected = RefDetector.detect("o que w25.07 p. 22 §8 diz?").single()
        assertEquals(22, detected.page)
        assertEquals(8, detected.paragraph)

        val resolved = resolver(wRows(), listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(publicationRefOf(detected))
        assertEquals(ReferenceStatus.RESOLVED, resolved.status)
        assertTrue(resolved.text!!.contains("parágrafo oito"))
        assertEquals("w25.07 pág. 22 §8", resolved.canonicalRef)

        val hits = listOfNotNull(
            publicationHitOf(resolved, resolved.canonicalRef ?: detected.label)
        )
        assertEquals(1, hits.size)
        assertTrue(hits.single().passage.text.contains("parágrafo oito"))
    }

    @Test
    fun resolvePublication_paginaSemIndice_returnsUnresolved() = runBlocking {
        // Página sem linhas indexadas: nunca devolve trecho aleatório.
        val out = resolver(emptyList(), listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(PublicationRef("w25.07", page = 22, paragraph = 8))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_semPaginaNemUnidade_returnsUnresolved() = runBlocking {
        val out = resolver(wRows(), listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(PublicationRef("w25.07"))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_indiceSemPagina_paragrafoResolveParcial() = runBlocking {
        // C2.6: § sem página cai no byParagraphRange e devolve PARTIAL com o
        // texto real — antes ficava UNRESOLVED e o Copilot não via o trecho.
        val rows = listOf(
            passage("n8", "w25.07", "w25.07 §8", "Parágrafo oito sem página.",
                "paragrafo oito sem pagina", ord = 0, paragraph = 8),
            passage("n9", "w25.07", "w25.07 §9", "Parágrafo nove sem página.",
                "paragrafo nove sem pagina", ord = 1, paragraph = 9),
        )
        val out = resolver(rows, listOf(attachment("w25.07", "w25.07")))
            .resolvePublication(PublicationRef("w25.07", page = 22, paragraph = 8, paragraphEnd = 9))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.text.orEmpty().contains("Parágrafo oito"))
        assertTrue(out.text.orEmpty().contains("Parágrafo nove"))
        assertTrue(out.canonicalRef.orEmpty().contains("§8-9"))
    }

    @Test
    fun resolvePublication_paginaSemParagrafo_parcialComTextoDaPagina() = runBlocking {
        val rows = listOf(
            passage("p6a", "g_T_201308", "g 08/13 pág. 6", "Primeira linha da página seis.",
                "primeira linha da pagina seis", ord = 0, page = 6),
            passage("p6b", "g_T_201308", "g 08/13 pág. 6", "Segunda linha da página seis.",
                "segunda linha da pagina seis", ord = 1, page = 6),
        )
        val out = resolver(rows, listOf(attachment("g_T_201308", "g")))
            .resolvePublication(PublicationRef("Despertai! 08/13", page = 6))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertEquals("Despertai! 08/13 pág. 6", out.canonicalRef)
        assertTrue(out.text.orEmpty().contains("Primeira linha"))
        assertTrue(out.text.orEmpty().contains("Segunda linha"))
    }

    @Test
    fun resolvePublication_paginaFaixaSemParagrafo_parcial() = runBlocking {
        val rows = listOf(
            passage("p6", "wp_T_201903", "Sentinela 3/2019 pág. 6", "Texto da página seis.",
                "texto da pagina seis", ord = 0, page = 6),
            passage("p7", "wp_T_201903", "Sentinela 3/2019 pág. 7", "Texto da página sete.",
                "texto da pagina sete", ord = 1, page = 7),
        )
        val out = resolver(rows, listOf(attachment("wp_T_201903", "wp")))
            .resolvePublication(
                PublicationRef("Sentinela número 3 de 2019", page = 6, pageEnd = 7),
            )
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertEquals("Sentinela número 3 de 2019 pág. 6-7", out.canonicalRef)
        assertTrue(out.text.orEmpty().contains("página seis"))
        assertTrue(out.text.orEmpty().contains("página sete"))
    }

    @Test
    fun resolvePublication_contextoDoEsboco_parcial() = runBlocking {
        val rows = listOf(
            passage(
                "ctx", "g_T_201308", "g 08/13 pág. 6",
                "O tempo passa muito rápido e a vida parece muito curta. " +
                    "Temos o desejo de continuar vivendo e nunca morrer.",
                "o tempo passa muito rapido e a vida parece muito curta " +
                    "temos o desejo de continuar vivendo e nunca morrer",
                ord = 0, page = null,
            ),
        )
        val out = resolver(rows, listOf(attachment("g_T_201308", "g")))
            .resolvePublication(
                PublicationRef(
                    "Despertai! 08/13",
                    context = "O tempo passa muito rápido e a vida parece muito curta. " +
                        "Temos o desejo de continuar vivendo e nunca morrer.",
                ),
            )
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.canonicalRef.orEmpty().contains("contexto"))
        assertTrue(out.text.orEmpty().contains("vida parece muito curta"))
    }

    @Test
    fun resolvePublication_contextoFraco_naoDevolveTrechoAleatorio() = runBlocking {
        val rows = listOf(
            passage("x", "g_T_201308", "g 08/13 pág. 6", "Texto sem relação com a frase.",
                "texto sem relacao com a frase", ord = 0, page = null),
        )
        val out = resolver(rows, listOf(attachment("g_T_201308", "g")))
            .resolvePublication(
                PublicationRef(
                    "Despertai! 08/13",
                    context = "O tempo passa muito rápido e a vida parece muito curta.",
                ),
            )
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_g1_16_ambiguo_naoEscolheEdicao() = runBlocking {
        // C2.5: mesmo com o arquivo da edição no acervo, "g 1/16" pode ser
        // janeiro/2016 ou edição 1/2016 — não resolve sozinho.
        val out = resolver(
            attachments = listOf(attachment("g_T_201601", "g")),
        ).resolvePublication(PublicationRef("g 1/16"))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.text)
    }
}
