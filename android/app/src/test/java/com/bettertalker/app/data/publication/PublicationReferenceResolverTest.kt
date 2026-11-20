package com.bettertalker.app.data.publication

import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.RefDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationReferenceResolverTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

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
    fun formatosDiferentesSemProvaDeEquivalenciaSaoAmbiguos() = runBlocking {
        // Nome-base igual entre PDF/JWPUB não comprova o mesmo conteúdo.
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

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
        assertEquals(
            2,
            (out as PublicationReferenceResolution.Ambiguous).candidates.size,
        )
    }

    @Test
    fun mesmoFormatoComHashesDiferentesEhAmbiguo() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-1", "lff_T.jwpub", sha256 = shaA),
                attachment("a-lff-2", "lff_T.jwpub", sha256 = shaB),
            ),
            passages = lmdRows("a-lff-1") + lmdRows("a-lff-2"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
        assertEquals(
            setOf("a-lff-1", "a-lff-2"),
            (out as PublicationReferenceResolution.Ambiguous)
                .candidates.map { it.attachmentId }.toSet(),
        )
    }

    @Test
    fun hashInvalidoNaoPresumeEquivalencia() = runBlocking {
        // "abc" não é SHA-256 hexadecimal de 64 dígitos.
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-1", "lff_T.jwpub", sha256 = "abc"),
                attachment("a-lff-2", "lff_T.jwpub", sha256 = "abc"),
            ),
            passages = lmdRows("a-lff-1") + lmdRows("a-lff-2"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
    }

    @Test
    fun hashAusenteNaoPresumeEquivalencia() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-1", "lff_T.jwpub", sha256 = null),
                attachment("a-lff-2", "lff_T.pdf", sha256 = null),
            ),
            passages = lmdRows("a-lff-1") + lmdRows("a-lff-2"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Ambiguous)
    }

    @Test
    fun arquivoUnicoSemHashContinuaResolvendo() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff", "lff_T.jwpub", sha256 = null),
            ),
            passages = lmdRows("a-lff"),
        ).resolve(occurrence("lff lição 3 ponto 4"))

        assertTrue(out is PublicationReferenceResolution.Available)
        assertEquals(
            "a-lff",
            (out as PublicationReferenceResolution.Available).target.expectedAttachmentId,
        )
    }

    @Test
    fun duplicataBinariaNaoViraAmbiguidade() = runBlocking {
        val out = resolver(
            attachments = listOf(
                attachment("a-lff-1", "lff_T.jwpub", sha256 = shaA),
                attachment("a-lff-2", "lff_copy.jwpub", sha256 = shaA),
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
    fun licao1NaoResolveLicao10() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p10",
                    att,
                    "1 Texto da licao dez.",
                    section = "Lição 10: Outro assunto",
                    paragraph = 1,
                ),
            ),
            units = listOf(
                unit(
                    id = "u10",
                    attachmentId = att,
                    kind = "LESSON",
                    number = "10",
                    title = "Lição 10: Outro assunto",
                ),
            ),
        ).resolve(occurrence("lmd lição 1 ponto 1"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun capituloAbreviadoCasaUnidadeIndexada() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto do capitulo dois.",
                    section = "Capítulo 2: Como estudar",
                    paragraph = 1,
                ),
            ),
            units = listOf(
                unit(
                    id = "u2",
                    attachmentId = att,
                    kind = "CHAPTER",
                    number = "2",
                    title = "Capítulo 2: Como estudar",
                ),
            ),
        ).resolve(occurrence("lmd cap. 2 ponto 1"))

        assertTrue(
            out is PublicationReferenceResolution.Available ||
                out is PublicationReferenceResolution.Partial,
        )
        val target = when (out) {
            is PublicationReferenceResolution.Available -> out.target
            is PublicationReferenceResolution.Partial -> out.target
            else -> error("resolução inesperada: $out")
        }
        assertEquals("2", target.unit?.number)
    }

    @Test
    fun licao2NaoResolveLicao20() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p20",
                    att,
                    "1 Texto da licao vinte.",
                    section = "Lição 20: Outro assunto",
                    paragraph = 1,
                ),
            ),
            units = listOf(
                unit(
                    id = "u20",
                    attachmentId = att,
                    kind = "LESSON",
                    number = "20",
                    title = "Lição 20: Outro assunto",
                ),
            ),
        ).resolve(occurrence("lmd lição 2 ponto 1"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun ponto1NaoEhRotuladoComoPonto11() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p11",
                    att,
                    "11 Texto do ponto onze.",
                    section = "Lição 1: Como estudar",
                    page = 10,
                    paragraph = 11,
                ),
            ),
        ).resolve(occurrence("lmd página 10 ponto 1"))

        assertFalse(out is PublicationReferenceResolution.Available)
        if (out is PublicationReferenceResolution.Partial) {
            assertEquals(1, out.target.paragraph)
            assertTrue(out.target.partial)
        }
    }

    @Test
    fun unidadeInexistenteComIndiceRetornaTextNotFound() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = emptyList(),
            units = listOf(
                unit(
                    id = "u10",
                    attachmentId = att,
                    kind = "LESSON",
                    number = "10",
                    title = "Lição 10: Outro assunto",
                ),
            ),
        ).resolve(occurrence("lmd lição 1 ponto 1"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun semUnitDaoLicao1NaoResolveLicao10() = runBlocking {
        val att = "a-lmd"
        val attachmentDao = FakeAttachmentDao(listOf(attachment(att, "lmd_T.jwpub")))
        val passageDao = FakePassageDao(
            listOf(
                passage(
                    "p10",
                    att,
                    "1 Texto da licao dez.",
                    section = "Lição 10: Outro assunto",
                    paragraph = 1,
                ),
            ),
        )
        val out = PublicationReferenceResolver(
            attachmentDao = attachmentDao,
            passageDao = passageDao,
            unitDao = null,
            referenceResolver = RoomReferenceResolver(passageDao, attachmentDao),
        ).resolve(occurrence("lmd lição 1 ponto 1"))

        assertTrue(out is PublicationReferenceResolution.TextNotFound)
    }

    @Test
    fun semUnitDaoLicao1ExataResolve() = runBlocking {
        val att = "a-lmd"
        val attachmentDao = FakeAttachmentDao(listOf(attachment(att, "lmd_T.jwpub")))
        val passageDao = FakePassageDao(
            listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto da licao um.",
                    section = "Lição 1: Como estudar",
                    paragraph = 1,
                ),
            ),
        )
        val out = PublicationReferenceResolver(
            attachmentDao = attachmentDao,
            passageDao = passageDao,
            unitDao = null,
            referenceResolver = RoomReferenceResolver(passageDao, attachmentDao),
        ).resolve(occurrence("lmd lição 1 ponto 1"))

        assertTrue(
            out is PublicationReferenceResolution.Available ||
                out is PublicationReferenceResolution.Partial,
        )
    }

    @Test
    fun resultadoDaBuscaRejeitadoPelaValidacaoEstrutural() = runBlocking {
        val att = "a-lmd"
        val attachmentDao = FakeAttachmentDao(listOf(attachment(att, "lmd_T.jwpub")))
        val passageDao = FakePassageDao(
            listOf(
                passage(
                    "p10",
                    att,
                    "1 Texto da licao dez.",
                    section = "Lição 10: Outro assunto",
                    paragraph = 1,
                ),
            ),
        ).also { it.bySectionIgnoresBoundary = true }
        val out = PublicationReferenceResolver(
            attachmentDao = attachmentDao,
            passageDao = passageDao,
            unitDao = FakePublicationUnitDao(
                listOf(
                    unit(
                        id = "u10",
                        attachmentId = att,
                        kind = "LESSON",
                        number = "10",
                        title = "Lição 10: Outro assunto",
                    ),
                ),
            ),
            referenceResolver = RoomReferenceResolver(passageDao, attachmentDao),
        ).resolve(occurrence("lmd lição 1 ponto 1"))

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
    fun ocorrenciaSemDeteccaoNaoUsaPaginaFabricada() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage(
                    "p999",
                    att,
                    "Texto sintetico da pagina 999.",
                    page = 999,
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = "lmd",
                start = 0,
                endExclusive = 3,
                publicationKey = "lmd",
                titleHint = null,
                editionKey = "book|lmd",
                page = 999,
                detection = null,
            ),
        )

        assertFalse(out is PublicationReferenceResolution.Available)
        assertFalse(out is PublicationReferenceResolution.Partial)
    }

    @Test
    fun deteccaoIncompativelNaoUsaPaginaFabricada() = runBlocking {
        val att = "a-lmd"
        val forged = RefDetector.DetectedRef(
            raw = "lmd",
            kind = RefDetector.Kind.BOOK,
            pubKey = "lmd",
            editionKey = "book|lmd",
            label = "Ame as Pessoas",
            page = 999,
        )
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = listOf(
                passage("p999", att, "Texto sintetico da pagina 999.", page = 999),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = "lmd",
                start = 0,
                endExclusive = 3,
                publicationKey = "lmd",
                titleHint = null,
                editionKey = "book|lmd",
                detection = forged,
            ),
        )

        assertFalse(out is PublicationReferenceResolution.Available)
        assertFalse(out is PublicationReferenceResolution.Partial)
    }

    @Test
    fun deteccaoIncompativelRevalidaCapituloNoRaw() = runBlocking {
        val att = "a-lmd"
        val forged = RefDetector.DetectedRef(
            raw = "lmd lição 3",
            kind = RefDetector.Kind.BOOK,
            pubKey = "lmd",
            editionKey = "book|lmd",
            label = "Ame as Pessoas",
            chapter = "lição 999",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = lmdRows(att),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = "lmd lição 3",
                start = 0,
                endExclusive = "lmd lição 3".length,
                publicationKey = "lmd",
                titleHint = null,
                editionKey = "book|lmd",
                chapter = "lição 999",
                detection = forged,
            ),
        )

        assertTrue(out is PublicationReferenceResolution.Partial)
        val canonical = (out as PublicationReferenceResolution.Partial).target.canonicalRef.orEmpty()
        assertTrue(canonical.contains("lição 3"))
        assertFalse(canonical.contains("999"))
    }

    @Test
    fun deteccaoDeTituloCompartilhadoNaoResolve() = runBlocking {
        val att = "a-lff"
        val source = "Veja Seja Feliz para Sempre, lição 5."
        val start = source.indexOf("Seja Feliz para Sempre")
        val title = "Seja Feliz para Sempre"
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "lff",
            editionKey = "book|lff",
            label = "Seja Feliz para Sempre!",
            chapter = "licao 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "lff_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto sintetico.",
                    section = "Lição 5: Outro assunto",
                    paragraph = 1,
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "lff",
                titleHint = title,
                editionKey = "book|lff",
                chapter = "licao 5",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertFalse(out is PublicationReferenceResolution.Available)
        assertFalse(out is PublicationReferenceResolution.Partial)
        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun deteccaoDeTituloComUnidadeContinuaValida() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 5."
        val start = source.indexOf("Achegue-se a Jeová")
        val title = "Achegue-se a Jeová"
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto do capitulo cinco.",
                    section = "Capítulo 5: Como estudar",
                    paragraph = 1,
                ),
            ),
            units = listOf(
                unit(
                    id = "u5",
                    attachmentId = att,
                    kind = "CHAPTER",
                    number = "5",
                    title = "Capítulo 5: Como estudar",
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                chapter = "capitulo 5",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertTrue(
            out is PublicationReferenceResolution.Available ||
                out is PublicationReferenceResolution.Partial,
        )
        val target = when (out) {
            is PublicationReferenceResolution.Available -> out.target
            is PublicationReferenceResolution.Partial -> out.target
            else -> error("resolução inesperada: $out")
        }
        assertEquals("5", target.unit?.number)
    }

    @Test
    fun tituloComCapituloFabricadoUsaCapituloDoTextoOriginal() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 4."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val forged = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto do capitulo quatro.",
                    section = "Capítulo 4: Como estudar",
                    paragraph = 1,
                ),
            ),
            units = listOf(
                unit(
                    id = "u4",
                    attachmentId = att,
                    kind = "CHAPTER",
                    number = "4",
                    title = "Capítulo 4: Como estudar",
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = forged,
            ),
            sourceText = source,
        )

        assertTrue(
            out is PublicationReferenceResolution.Available ||
                out is PublicationReferenceResolution.Partial,
        )
        val target = when (out) {
            is PublicationReferenceResolution.Available -> out.target
            is PublicationReferenceResolution.Partial -> out.target
            else -> error("resolução inesperada: $out")
        }
        assertEquals("4", target.unit?.number)
        assertFalse(target.canonicalRef.orEmpty().contains("5"))
    }

    @Test
    fun tituloComPaginaFabricadaUsaPaginaDoTextoOriginal() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, página 12."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val forged = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            page = 999,
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = listOf(
                passage("p12", att, "Texto da pagina doze.", page = 12),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = forged,
            ),
            sourceText = source,
        )

        assertTrue(out is PublicationReferenceResolution.Partial)
        assertEquals(12, (out as PublicationReferenceResolution.Partial).target.page)
    }

    @Test
    fun tituloSemTextoOriginalNaoUsaUnidade() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 5."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = emptyList(),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
        )

        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun tituloComMetadadoDivergenteNaoResolve() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 4."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto do capitulo quatro.",
                    section = "Capítulo 4: Como estudar",
                    paragraph = 1,
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                chapter = "capitulo 5",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun tituloComEditionKeyDivergenteNaoResolve() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 5."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|lff",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = emptyList(),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|lff",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun tituloComDeteccaoRawDivergenteNaoUsaUnidade() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 5."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val detection = RefDetector.DetectedRef(
            raw = "Outro título",
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = listOf(
                passage(
                    "p1",
                    att,
                    "1 Texto do capitulo cinco.",
                    section = "Capítulo 5: Como estudar",
                    paragraph = 1,
                ),
            ),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                chapter = "capitulo 5",
                validationStart = start,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertFalse(out is PublicationReferenceResolution.Available)
        assertFalse(out is PublicationReferenceResolution.Partial)
    }

    @Test
    fun tituloComProvaDeOutraOcorrenciaNaoResolve() = runBlocking {
        val att = "a-cl"
        val source = "Veja Achegue-se a Jeová, capítulo 5."
        val title = "Achegue-se a Jeová"
        val start = source.indexOf(title)
        val detection = RefDetector.DetectedRef(
            raw = title,
            kind = RefDetector.Kind.BOOK,
            pubKey = "cl",
            editionKey = "book|cl",
            label = title,
            chapter = "capitulo 5",
        )
        val out = resolver(
            attachments = listOf(attachment(att, "cl_T.jwpub")),
            passages = emptyList(),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = title,
                start = start,
                endExclusive = start + title.length,
                publicationKey = "cl",
                titleHint = title,
                editionKey = "book|cl",
                validationStart = start + 1,
                validationEndExclusive = source.length,
                detection = detection,
            ),
            sourceText = source,
        )

        assertTrue(out is PublicationReferenceResolution.NotRecognized)
    }

    @Test
    fun ocorrenciaSemDeteccaoRevalidaParagrafoNoTextoOriginal() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = lmdRows(att),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = "lmd lição 3 ponto 4",
                start = 0,
                endExclusive = "lmd lição 3 ponto 4".length,
                publicationKey = "lmd",
                titleHint = null,
                editionKey = "book|lmd",
                paragraph = 999,
                detection = null,
            ),
        )

        assertTrue(out is PublicationReferenceResolution.Available)
        assertEquals(
            4,
            (out as PublicationReferenceResolution.Available).target.paragraph,
        )
    }

    @Test
    fun ocorrenciaSemDeteccaoRevalidaCapituloNoTextoOriginal() = runBlocking {
        val att = "a-lmd"
        val out = resolver(
            attachments = listOf(attachment(att, "lmd_T.jwpub")),
            passages = lmdRows(att),
        ).resolve(
            PublicationReferenceOccurrence(
                raw = "lmd lição 3",
                start = 0,
                endExclusive = "lmd lição 3".length,
                publicationKey = "lmd",
                titleHint = null,
                editionKey = "book|lmd",
                chapter = "lição 999",
                detection = null,
            ),
        )

        assertTrue(out is PublicationReferenceResolution.Partial)
        val canonical = (out as PublicationReferenceResolution.Partial).target.canonicalRef.orEmpty()
        assertTrue(canonical.contains("lição 3"))
        assertFalse(canonical.contains("999"))
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
