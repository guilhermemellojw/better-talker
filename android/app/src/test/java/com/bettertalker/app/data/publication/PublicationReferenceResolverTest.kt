package com.bettertalker.app.data.publication

import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.util.EditionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationReferenceResolverTest {

    private fun resolver(
        attachments: List<com.bettertalker.app.data.db.AttachmentEntity>,
        passages: List<com.bettertalker.app.data.db.PassageEntity> = emptyList(),
        units: List<com.bettertalker.app.data.db.PublicationUnitEntity> = emptyList(),
        failAll: Boolean = false,
    ): PublicationReferenceResolver {
        val attachmentDao = FakeAttachmentDao(attachments).also { it.failAll = failAll }
        val passageDao = FakePassageDao(passages)
        return PublicationReferenceResolver(
            attachmentDao = attachmentDao,
            passageDao = passageDao,
            unitDao = FakePublicationUnitDao(units),
            referenceResolver = RoomReferenceResolver(passageDao, attachmentDao),
        )
    }

    private fun lmdRows(att: String = "a-lmd") = listOf(
        passage(
            id = "$att-p0",
            attachmentId = att,
            text = "# Lição 3: Como estudar",
            section = "Lição 3: Como estudar",
            ord = 0,
        ),
        passage(
            id = "$att-p4",
            attachmentId = att,
            text = "4 Texto real do ponto quatro.",
            section = "Lição 3: Como estudar",
            paragraph = 4,
            ord = 1,
        ),
    )

    @Test
    fun edicaoExataEUnidadeExataRetornaAvailable() = runBlocking {
        val att = attachment("a-lmd", "lmd_T.jwpub")
        val out = resolver(
            attachments = listOf(att),
            passages = lmdRows(),
            units = listOf(
                unit(
                    id = "a-lmd-u0",
                    attachmentId = "a-lmd",
                    kind = "LESSON",
                    number = "3",
                    title = "Lição 3: Como estudar",
                ),
            ),
        ).resolve(occurrence("lmd lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Available)
        val target = (out as PublicationReferenceResolution.Available).target
        assertEquals("a-lmd", target.expectedAttachmentId)
        assertEquals("lmd_T.jwpub", target.fileName)
        assertEquals("lmd", target.publicationKey)
        assertEquals(4, target.paragraph)
        assertEquals("a-lmd-p4", target.sourcePassageId)
        assertFalse(target.partial)
        assertFalse(target.truncated)
        assertNull(target.evidenceId)
        assertNull(target.usedText)
        assertNotNull(target.unit)
        assertEquals(PublicationUnitKind.LESSON, target.unit?.kind)
        assertEquals("3", target.unit?.number)
    }

    @Test
    fun edicaoAusenteRetornaNotImported() = runBlocking {
        val out = resolver(emptyList()).resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.NotImported)
        assertEquals(
            "Ame as Pessoas",
            (out as PublicationReferenceResolution.NotImported).publicationTitle,
        )
    }

    @Test
    fun importadaSemIndiceRetornaNotReadyIndexing() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lmd", "lmd_T.jwpub", indexed = false, status = "indexing")),
        ).resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.NotReady)
        assertEquals(
            EditionStatus.INDEXING,
            (out as PublicationReferenceResolution.NotReady).edition,
        )
    }

    @Test
    fun falhaDeIndexacaoRetornaNotReadyIndexFailed() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lmd", "lmd_T.jwpub", indexed = false, status = "failed")),
        ).resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.NotReady)
        assertEquals(
            EditionStatus.INDEX_FAILED,
            (out as PublicationReferenceResolution.NotReady).edition,
        )
    }

    @Test
    fun formatoNaoSuportadoRetornaNotReadyUnsupported() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lmd", "lmd_T.bin", indexed = false, status = "failed")),
        ).resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.NotReady)
        assertEquals(
            EditionStatus.UNSUPPORTED,
            (out as PublicationReferenceResolution.NotReady).edition,
        )
    }

    @Test
    fun arquivoAusenteRetornaNotReadyMissing() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lmd", "lmd_T.jwpub", indexed = false, status = "missing")),
        ).resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.NotReady)
        assertEquals(
            EditionStatus.MISSING,
            (out as PublicationReferenceResolution.NotReady).edition,
        )
    }

    @Test
    fun duasEdicoesReaisDoMesmoSimboloSaoAmbiguas() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff", "lff_T.jwpub"),
                attachment("a-lff-2019", "lff_2019_T.jwpub"),
            ),
            passages = lmdRows("a-lff") + lmdRows("a-lff-2019"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
        assertEquals(
            2,
            (out as PublicationReferenceResolution.Ambiguous).candidates.size,
        )
    }

    @Test
    fun formatosDaMesmaEdicaoEscolhemJwpubSemAmbiguidade() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-pdf", "lff_T.pdf"),
                attachment("a-lff-jw", "lff_T.jwpub"),
            ),
            passages = lmdRows("a-lff-jw"),
            units = listOf(
                unit(
                    id = "a-lff-jw-u0",
                    attachmentId = "a-lff-jw",
                    kind = "LESSON",
                    number = "3",
                    title = "Lição 3: Como estudar",
                ),
            ),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Available)
        assertEquals(
            "a-lff-jw",
            (out as PublicationReferenceResolution.Available).target.expectedAttachmentId,
        )
    }

    @Test
    fun duplicataBinariaNaoViraAmbiguidade() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-1", "lff_T.jwpub", sha256 = "abc"),
                attachment("a-lff-2", "lff_copy.jwpub", sha256 = "abc"),
            ),
            passages = lmdRows("a-lff-1"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Available)
        assertEquals(
            "a-lff-1",
            (out as PublicationReferenceResolution.Available).target.expectedAttachmentId,
        )
    }

    @Test
    fun publicacaoSemelhanteNaoEhUsada() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lffi", "lffi_T.jwpub")),
            passages = lmdRows("a-lffi"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.NotImported)
    }

    @Test
    fun jwpubEEpubComNomesDiferentesNaoSaoEquivalentes() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-jw", "lff_T.jwpub"),
                attachment("a-lff-epub", "lff_2019_T.epub"),
            ),
            passages = lmdRows("a-lff-jw") + lmdRows("a-lff-epub"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
    }

    @Test
    fun paginaEParagrafoExatosResolvem() = runBlocking {
        val att = "a-w"
        val out = resolver(
            attachments = listOf(attachment(att, "w_T_202507.jwpub")),
            passages = listOf(
                passage("p8", att, "8 Texto do oito.", section = "Artigo", page = 22, paragraph = 8),
                passage("p9", att, "9 Texto do nove.", section = "Artigo", page = 22, paragraph = 9),
            ),
        ).resolve(occurrence("w25.07 22 §§ 8-9"))

        assertTrue(out is PublicationReferenceResolution.Available)
        val target = (out as PublicationReferenceResolution.Available).target
        assertEquals(22, target.page)
        assertEquals(8, target.paragraph)
        assertEquals(9, target.paragraphEnd)
        assertEquals("p8", target.sourcePassageId)
    }

    @Test
    fun paginaSemParagrafoEhPartial() = runBlocking {
        val att = "a-jr"
        val out = resolver(
            attachments = listOf(attachment(att, "jr_T.jwpub")),
            passages = listOf(
                passage("p29", att, "Texto real da página 29.", page = 29),
            ),
        ).resolve(occurrence("jr 29"))

        assertTrue(out is PublicationReferenceResolution.Partial)
        val target = (out as PublicationReferenceResolution.Partial).target
        assertTrue(target.partial)
        assertNull(target.evidenceId)
        assertNull(target.usedText)
    }

    @Test
    fun paragrafoNaoEncontradoMasUnidadeRealEhPartial() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto real do ponto um.",
                    section = "Lição 3: Como estudar",
                    paragraph = 1,
                ),
            ),
        ).resolve(occurrence("lmd lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Partial)
    }

    @Test
    fun paginaSemMetadadoConfiavelNaoEhAvailable() = runBlocking {
        val att = "a-lff"
        val out = resolver(
            attachments = listOf(attachment(att, "lff_T.jwpub")),
            passages = listOf(passage("p1", att, "Texto sem página indexada.")),
        ).resolve(occurrence("lff página 12"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun edicaoEncontradaSemTextoNaoEhAvailable() = runBlocking {
        val out = resolver(
            listOf(attachment("a-lmd", "lmd_T.jwpub")),
            passages = emptyList(),
        ).resolve(occurrence("lmd lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun edicaoMudadaAntesDaAberturaRetornaEditionChanged() = runBlocking {
        val resolver = resolver(
            listOf(attachment("a-lmd", "lmd_T.jwpub")),
            passages = lmdRows(),
        )
        val occ = occurrence("lmd lição 3 ponto 4")
        val first = resolver.resolve(occ)
        assertTrue(first is PublicationReferenceResolution.Available)

        val changed = resolver.resolve(occ, expectedAttachmentId = "arquivo-removido")
        assertTrue(changed is PublicationReferenceResolution.EditionChanged)
        assertEquals(
            "arquivo-removido",
            (changed as PublicationReferenceResolution.EditionChanged).expectedAttachmentId,
        )
    }

    @Test
    fun anexoDeOutraEdicaoNaoEhAceitoComoPin() = runBlocking {
        val resolver = resolver(
            attachments = listOf(
                attachment("a-lmd", "lmd_T.jwpub"),
                attachment("a-lff", "lff_T.jwpub"),
            ),
            passages = lmdRows(),
        )
        val out = resolver.resolve(
            occurrence("lmd lição 3 ponto 4"),
            expectedAttachmentId = "a-lff",
        )
        assertTrue(out is PublicationReferenceResolution.EditionChanged)
    }

    @Test
    fun falhaDeLeituraRetornaReadFailed() = runBlocking {
        val out = resolver(emptyList(), failAll = true)
            .resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.ReadFailed)
    }

    @Test
    fun referenciaNaoReconhecidaNaoInventaPublicacao() = runBlocking {
        val out = resolver(emptyList()).resolve(
            PublicationReferenceOccurrence(
                raw = "xyz",
                start = 0,
                endExclusive = 3,
                publicationKey = null,
                titleHint = null,
                editionKey = null,
            ),
        )
        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun simboloIsoladoSemUnidadeNaoRetornaAvailable() = runBlocking {
        val out = resolver(
            listOf(attachment("a-it", "it_T.jwpub")),
            passages = listOf(passage("p1", "a-it", "Texto de verbete.")),
        ).resolve(occurrence("it-1"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun anexoRemovidoNaLeituraFixaRetornaEditionChanged() = runBlocking {
        val attDao = FakeAttachmentDao(listOf(attachment("a-lmd", "lmd_T.jwpub")))
            .also { it.getReturnsNull = true }
        val passageDao = FakePassageDao(lmdRows())
        val resolver = PublicationReferenceResolver(
            attachmentDao = attDao,
            passageDao = passageDao,
            unitDao = FakePublicationUnitDao(),
            referenceResolver = RoomReferenceResolver(passageDao, attDao),
        )
        val out = resolver.resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.EditionChanged)
    }

    @Test
    fun arquivoComTituloCompartilhadoNaoEhEscolhidoPorSemelhanca() = runBlocking {
        // "Seja Feliz para Sempre!" é título de lff e lffi; o nome do
        // arquivo, sozinho, não prova qual publicação é.
        val out = resolver(
            attachments = listOf(
                attachment("a-titulo", "Seja Feliz para Sempre.pdf"),
            ),
            passages = lmdRows("a-titulo"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.NotImported)
    }

    @Test
    fun textoNoTetoDoResolvedorMarcaTruncated() = runBlocking {
        val att = "a-lmd-big"
        val big = "Texto sintetico sem conteudo protegido. ".repeat(250)
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    id = "big",
                    attachmentId = att,
                    text = big,
                    section = "Lição 3: Como estudar",
                    paragraph = 4,
                ),
            ),
        ).resolve(occurrence("lmd lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Available)
        assertTrue(
            (out as PublicationReferenceResolution.Available).target.truncated,
        )
    }

    @Test
    fun resolucaoNaoEscreveNoBanco() = runBlocking {
        val attDao = FakeAttachmentDao(listOf(attachment("a-lmd", "lmd_T.jwpub")))
        val passageDao = FakePassageDao(lmdRows())
        val resolver = PublicationReferenceResolver(
            attachmentDao = attDao,
            passageDao = passageDao,
            unitDao = FakePublicationUnitDao(),
            referenceResolver = RoomReferenceResolver(passageDao, attDao),
        )
        val out = resolver.resolve(occurrence("lmd lição 3 ponto 4"))
        assertTrue(out is PublicationReferenceResolution.Available)
        assertEquals(0, attDao.writeCount)
        assertEquals(0, passageDao.writeCount)
    }

    @Test
    fun referenciaAmbiguaNaoEscolheEdicao() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-g16", "g_T_201601.jwpub"),
                attachment("a-gn16", "gn_T_201601.jwpub"),
            ),
            passages = lmdRows("a-g16") + lmdRows("a-gn16"),
        ).resolve(occurrence("g 1/16"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
        assertTrue(
            (out as PublicationReferenceResolution.Ambiguous).candidates.isNotEmpty(),
        )
    }
}
