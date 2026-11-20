package com.bettertalker.app.data.s34

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TRUST-2B3B-A — testes do verificador experimental.
 *
 * Fixtures são EPUBs sintéticos minúsculos. Nenhum texto protegido da TNM é
 * incorporado ao repositório.
 */
class NwtDocumentIdentityVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val attachmentId = "att-trust-2b3b-a"
    private val identifier = "urn:uuid:TRUST-2B3B-A-TEST"

    private data class DocFixture(
        val book: NwtBook,
        val chapter: Int,
        val verses: Map<Int, String>,
    )

    private fun mateus(verse14: String = "Texto sintético de Mateus 24:14.") = DocFixture(
        book = NWT_BOOKS.getValue("1001061144"),
        chapter = 24,
        verses = mapOf(14 to verse14),
    )

    private fun salmo(verse10: String = "Texto sintético de Salmos 37:10.") = DocFixture(
        book = NWT_BOOKS.getValue("1001061123"),
        chapter = 37,
        verses = mapOf(10 to verse10),
    )

    private fun all66Docs(): List<DocFixture> = NWT_BOOKS.values.map { book ->
        DocFixture(
            book = book,
            chapter = 1,
            verses = mapOf(1 to "Texto sintético ${book.abbrev} 1:1."),
        )
    }

    private fun epub(
        file: File,
        docs: List<DocFixture> = listOf(mateus()),
        title: String = "Tradução do Novo Mundo (nwt-T)",
        language: String = "pt",
        publisher: String = "Associação Torre de Vigia de Bíblias e Tratados",
        identifierValue: String = identifier,
        modified: String = "2026-03-19T01:01:11Z",
        includeContainer: Boolean = true,
        includeOpf: Boolean = true,
        malformedOpf: Boolean = false,
        containerFullPath: String = "OEBPS/content.opf",
        extraSpine: List<String> = emptyList(),
        extraEntries: Map<String, String> = emptyMap(),
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            if (includeContainer) {
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="$containerFullPath" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.trimIndent().trimStart().toByteArray()
                )
                zip.closeEntry()
            }
            if (includeOpf) {
                val manifest = docs.mapIndexed { index, doc ->
                    val href = "${doc.book.docIdBase}-split${doc.chapter}.xhtml"
                    """<item id="doc$index" href="$href" media-type="application/xhtml+xml"/>"""
                } + extraSpine.mapIndexed { index, href ->
                    """<item id="extra$index" href="$href" media-type="application/xhtml+xml"/>"""
                }
                val manifestXml = manifest.joinToString("\n")
                val spine = docs.indices.map { """<itemref idref="doc$it"/>""" } +
                    extraSpine.indices.map { """<itemref idref="extra$it"/>""" }
                val spineXml = spine.joinToString("\n")
                val opf = if (malformedOpf) {
                    """<?xml version="1.0"?><package><metadata><dc:title>$title"""
                } else {
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>$title</dc:title>
                        <dc:language>$language</dc:language>
                        <dc:publisher>$publisher</dc:publisher>
                        <dc:identifier id="BookId">$identifierValue</dc:identifier>
                        <meta property="dcterms:modified">$modified</meta>
                      </metadata>
                      <manifest>
                        $manifestXml
                      </manifest>
                      <spine>
                        $spineXml
                      </spine>
                    </package>
                    """.trimIndent().trimStart()
                }
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(opf.toByteArray())
                zip.closeEntry()
            }
            docs.forEach { doc ->
                val href = "OEBPS/${doc.book.docIdBase}-split${doc.chapter}.xhtml"
                val body = buildString {
                    append("""<span id="chapter${doc.chapter}"></span>""")
                    doc.verses.forEach { (verse, text) ->
                        append("""<span id="chapter${doc.chapter}_verse$verse"></span>""")
                        append(text)
                    }
                }
                val xhtml = """
                    <html><body class="jwac docId-${doc.book.docIdBase}">
                    $body
                    </body></html>
                """.trimIndent()
                zip.putNextEntry(ZipEntry(href))
                zip.write(xhtml.toByteArray())
                zip.closeEntry()
            }
            extraEntries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun metadata(
        file: File,
        sha: String? = sha256(file),
        indexed: Boolean = true,
        status: String = "ready",
        sizeBytes: Long = file.length(),
        kind: String = "epub",
        baseSlot: String? = "nwt",
        symbol: String? = "nwt",
    ) = NwtAttachmentMetadata(
        attachmentId = attachmentId,
        fileName = "nwt_T.epub",
        kind = kind,
        baseSlot = baseSlot,
        symbol = symbol,
        indexed = indexed,
        status = status,
        sha256 = sha,
        sizeBytes = sizeBytes,
        appPath = file.absolutePath,
    )

    private fun unit(
        text: String,
        ref: String = "Mt 24:14",
        attachment: String = attachmentId,
        passageId: String? = "p-passage",
        ord: Int? = 1,
        section: String? = "Mateus 24",
    ) = NwtIndexedUnit(
        attachmentId = attachment,
        passageId = passageId,
        canonicalRef = ref,
        ord = ord,
        text = text,
        section = section,
    )

    private fun codes(result: NwtDocumentIdentity): Set<NwtIssueCode> =
        result.issues.map { it.code }.toSet()

    @Test
    fun epubIntegroMetadadosEsperados() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val text = "Texto sintético de Mateus 24:14."
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(text),
        )

        assertEquals(NwtFileIntegrity.HASH_MATCH, result.integrity)
        assertEquals(NwtEditorialIdentityState.IDENTITY_CONSISTENT, result.editorialState)
        assertEquals(NwtAuthenticityAnchorState.NOT_ANCHORED, result.authenticity)
        assertEquals(NwtUnitIdentityState.UNIT_TEXT_VERIFIED, result.unit.state)
        assertEquals(NwtTextComparison.EXACT, result.unit.comparison)
        assertEquals(NwtCorpusCompleteness.PARTIAL, result.corpus.state)
        assertTrue(result.promotion.integrityVerified)
        assertTrue(result.promotion.editorialConsistencyVerified)
        assertTrue(result.promotion.unitTextVerified)
        assertFalse(result.promotion.unitIndexIdentityVerified)
        assertFalse(result.promotion.anchorProvenanceTrusted)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.NO_TRUSTED_ANCHOR in codes(result))
        assertTrue(NwtIssueCode.INDEX_IDENTITY_UNVERIFIED in codes(result))
    }

    @Test
    fun hashDivergenteNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, sha = "0".repeat(64)),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtFileIntegrity.HASH_MISMATCH, result.integrity)
        assertEquals(NwtUnitIdentityState.UNIT_PARTIAL, result.unit.state)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.HASH_MISMATCH in codes(result))
    }

    @Test
    fun arquivoAusenteNaoLanca() {
        val missing = File(temp.root, "nao-existe.epub")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = NwtAttachmentMetadata(
                attachmentId = attachmentId,
                fileName = "nwt_T.epub",
                kind = "epub",
                baseSlot = "nwt",
                symbol = "nwt",
                indexed = true,
                status = "ready",
                sha256 = null,
                sizeBytes = 0,
                appPath = missing.absolutePath,
            ),
            file = missing,
            indexedUnit = unit("texto"),
        )

        assertEquals(NwtFileIntegrity.FILE_MISSING, result.integrity)
        assertEquals(NwtUnitIdentityState.UNIT_UNVERIFIABLE, result.unit.state)
        assertFalse(result.promotion.integrityVerified)
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.FILE_MISSING in codes(result))
    }

    @Test
    fun hashRegistradoAusenteNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, sha = null),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtFileIntegrity.HASH_MISSING, result.integrity)
        assertFalse(result.promotion.integrityVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.HASH_MISSING in codes(result))
    }

    @Test
    fun tamanhoDivergenteNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, sizeBytes = file.length() + 1),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertFalse(result.attachmentMetadataConsistent)
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.SIZE_MISMATCH in codes(result))
    }

    @Test
    fun opfAusenteNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file, includeOpf = false)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtEditorialIdentityState.OPF_MISSING, result.editorialState)
        assertEquals(NwtUnitIdentityState.UNIT_UNVERIFIABLE, result.unit.state)
        assertFalse(result.epubStructureValid)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun opfInvalidoNaoLanca() {
        val file = temp.newFile("nwt.epub")
        epub(file, malformedOpf = true)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtEditorialIdentityState.OPF_INVALID, result.editorialState)
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun idiomaIncorretoEhInconsistente() {
        val file = temp.newFile("nwt.epub")
        epub(file, language = "en")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtEditorialIdentityState.METADATA_INCONSISTENT, result.editorialState)
        assertTrue(NwtIssueCode.LANGUAGE_MISMATCH in codes(result))
    }

    @Test
    fun tituloIncompativelEhInconsistente() {
        val file = temp.newFile("nwt.epub")
        epub(file, title = "Outra Bíblia")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtEditorialIdentityState.METADATA_INCONSISTENT, result.editorialState)
        assertTrue(NwtIssueCode.TITLE_MISMATCH in codes(result))
    }

    @Test
    fun identificadorIncompativelEhInconsistente() {
        val file = temp.newFile("nwt.epub")
        epub(file, identifierValue = "urn:uuid:OUTRO")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
            expected = NwtExpectedEdition(identifier = identifier),
        )

        assertEquals(NwtEditorialIdentityState.METADATA_INCONSISTENT, result.editorialState)
        assertTrue(NwtIssueCode.IDENTIFIER_MISMATCH in codes(result))
    }

    @Test
    fun modificacaoAposIndexacaoViraIndexStale() {
        val file = temp.newFile("nwt.epub")
        epub(file, docs = listOf(mateus("Texto original.")))
        val oldSha = sha256(file)
        val oldText = "Texto original."
        epub(file, docs = listOf(mateus("Texto alterado.")))
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, sha = oldSha),
            file = file,
            indexedUnit = unit(oldText),
        )

        assertEquals(NwtFileIntegrity.HASH_MISMATCH, result.integrity)
        assertEquals(NwtUnitIdentityState.INDEX_STALE, result.unit.state)
        assertEquals(NwtTextComparison.MISMATCH, result.unit.comparison)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun unidadeIgualEhVerified() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val text = "Texto sintético de Mateus 24:14."
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(text, section = "Mateus 24"),
        )

        assertEquals(NwtUnitIdentityState.UNIT_TEXT_VERIFIED, result.unit.state)
        assertEquals(NwtTextComparison.EXACT, result.unit.comparison)
        assertEquals("Mateus 24", result.unit.section)
        assertEquals(text.length, result.unit.indexedChars)
    }

    @Test
    fun unidadeDiferenteViraIndexStale() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto diferente do EPUB."),
        )

        assertEquals(NwtUnitIdentityState.INDEX_STALE, result.unit.state)
        assertEquals(NwtTextComparison.MISMATCH, result.unit.comparison)
        assertTrue(NwtIssueCode.UNIT_TEXT_MISMATCH in codes(result))
    }

    @Test
    fun unidadeAusenteNaoSelecionaOutraRef() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("texto", ref = "Mt 24:15"),
        )

        assertEquals(NwtUnitIdentityState.UNIT_MISSING, result.unit.state)
        assertTrue(NwtIssueCode.UNIT_NOT_FOUND_IN_EPUB in codes(result))
    }

    @Test
    fun unidadeParcialQuandoPassageIdAusente() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val text = "Texto sintético de Mateus 24:14."
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(text, passageId = null),
        )

        assertEquals(NwtUnitIdentityState.UNIT_PARTIAL, result.unit.state)
        assertEquals(NwtTextComparison.EXACT, result.unit.comparison)
        assertTrue(NwtIssueCode.UNIT_IDENTITY_INCOMPLETE in codes(result))
    }

    @Test
    fun passageIdFabricadoNaoEhComprovadoPeloEpub() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético de Mateus 24:14.",
                passageId = "passage-fabricado",
            ),
        )

        assertEquals(NwtUnitIdentityState.UNIT_TEXT_VERIFIED, result.unit.state)
        assertFalse(result.unit.indexIdentityVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.INDEX_IDENTITY_UNVERIFIED in codes(result))
    }

    @Test
    fun ordIncorretoNaoEhComprovadoPeloEpub() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético de Mateus 24:14.",
                ord = 999,
            ),
        )

        assertEquals(NwtUnitIdentityState.UNIT_TEXT_VERIFIED, result.unit.state)
        assertFalse(result.unit.indexIdentityVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun secaoDeOutraUnidadeViraPartial() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético de Mateus 24:14.",
                section = "Salmos 37",
            ),
        )

        assertEquals(NwtUnitIdentityState.UNIT_PARTIAL, result.unit.state)
        assertEquals(false, result.unit.sectionMatchesEpub)
        assertTrue(NwtIssueCode.SECTION_MISMATCH in codes(result))
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun mesmoTextoEmUnidadesDiferentesViraPartial() {
        val file = temp.newFile("nwt.epub")
        epub(
            file,
            docs = listOf(
                mateus("Mesmo texto sintético."),
                salmo("Mesmo texto sintético."),
            ),
        )
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Mesmo texto sintético."),
        )

        assertEquals(NwtUnitIdentityState.UNIT_PARTIAL, result.unit.state)
        assertTrue(NwtIssueCode.AMBIGUOUS_TEXT_IN_EPUB in codes(result))
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun duasEdicoesComMesmaRefNaoSeMisturam() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("texto", attachment = "outra-edicao"),
        )

        assertEquals(NwtUnitIdentityState.UNIT_MISSING, result.unit.state)
        assertTrue(NwtIssueCode.ATTACHMENT_MISMATCH in codes(result))
    }

    @Test
    fun zipComXhtmlDuplicadoNaoVerificaUnidade() {
        val file = temp.newFile("nwt.epub")
        writeDuplicateEpub(
            file = file,
            firstText = "Texto sintético de Mateus 24:14.",
            secondText = "Texto divergente na mesma entrada.",
        )
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtUnitIdentityState.UNIT_UNVERIFIABLE, result.unit.state)
        assertTrue(result.corpus.state != NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB)
        assertTrue(NwtIssueCode.DUPLICATE_AMBIGUITY in codes(result))
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun epubMalformadoNaoLanca() {
        val file = temp.newFile("nwt.epub")
        file.writeText("isto não é um zip")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("texto"),
        )

        assertEquals(NwtFileIntegrity.NOT_EPUB, result.integrity)
        assertFalse(result.epubStructureValid)
        assertFalse(result.promotion.integrityVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun caminhoOpfInseguroNaoEhAceito() {
        val file = temp.newFile("nwt.epub")
        epub(file, containerFullPath = "../evil.opf")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtEditorialIdentityState.OPF_MISSING, result.editorialState)
        assertTrue(NwtIssueCode.OPF_PATH_UNSAFE in codes(result))
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun xmlComDoctypeNaoResolveEntidadeExterna() {
        val file = temp.newFile("nwt.epub")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val evil = """
                <?xml version="1.0"?>
                <!DOCTYPE container [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <container><rootfiles><rootfile full-path="&xxe;"/></rootfiles></container>
            """.trimIndent()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(evil.toByteArray())
            zip.closeEntry()
        }
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("texto"),
        )

        assertFalse(result.epubStructureValid)
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
    }

    @Test
    fun limiteDeXhtmlImpedeLeituraAbusiva() {
        val file = temp.newFile("nwt.epub")
        epub(file, docs = listOf(mateus("x".repeat(500))))
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("x".repeat(500)),
            limits = NwtVerifierLimits(maxXhtmlBytes = 100),
        )

        assertEquals(NwtUnitIdentityState.UNIT_MISSING, result.unit.state)
        assertTrue(NwtIssueCode.XHTML_TOO_LARGE in codes(result))
    }

    @Test
    fun limiteTotalDoZipImpedeLeituraAbusiva() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
            limits = NwtVerifierLimits(maxTotalUncompressedBytes = 10),
        )

        assertFalse(result.epubStructureValid)
        assertTrue(NwtIssueCode.ZIP_TOO_LARGE in codes(result))
    }

    @Test
    fun completudeIndeterminadaNaoViraComplete() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertEquals(NwtCorpusCompleteness.PARTIAL, result.corpus.state)
        assertFalse(result.promotion.canPromoteCorpusToLocalFactual)
        assertTrue(result.corpus.missingBooks.isNotEmpty())
    }

    @Test
    fun completudeRelativaAoProprioEpub() {
        val file = temp.newFile("nwt.epub")
        epub(file, docs = all66Docs())
        val mateus = NWT_BOOKS.getValue("1001061144")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético ${mateus.abbrev} 1:1.",
                ref = "Mt 1:1",
                section = "Mateus 1",
            ),
        )

        assertEquals(NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB, result.corpus.state)
        assertEquals(66, result.corpus.bookDocuments)
        assertTrue(result.corpus.missingBooks.isEmpty())
        assertEquals(0, result.corpus.failedResources)
        assertFalse(result.promotion.canPromoteCorpusToLocalFactual)
    }

    @Test
    fun spineResourceAusenteNaoViraComplete() {
        val file = temp.newFile("nwt.epub")
        epub(file, docs = all66Docs(), extraSpine = listOf("extra.xhtml"))
        val mateus = NWT_BOOKS.getValue("1001061144")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético ${mateus.abbrev} 1:1.",
                ref = "Mt 1:1",
                section = "Mateus 1",
            ),
        )

        assertTrue(result.corpus.state != NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB)
        assertEquals(1, result.corpus.failedResources)
        assertTrue(NwtIssueCode.SPINE_RESOURCE_UNPROCESSED in codes(result))
        assertFalse(result.promotion.canPromoteCorpusToLocalFactual)
    }

    @Test
    fun spineResourceAcimaDoLimiteNaoViraComplete() {
        val file = temp.newFile("nwt.epub")
        epub(
            file,
            docs = all66Docs(),
            extraSpine = listOf("extra.xhtml"),
            extraEntries = mapOf("OEBPS/extra.xhtml" to "<html>${"x".repeat(2000)}</html>"),
        )
        val mateus = NWT_BOOKS.getValue("1001061144")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético ${mateus.abbrev} 1:1.",
                ref = "Mt 1:1",
                section = "Mateus 1",
            ),
            limits = NwtVerifierLimits(maxXhtmlBytes = 1000),
        )

        assertTrue(result.corpus.state != NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB)
        assertEquals(1, result.corpus.failedResources)
        assertTrue(NwtIssueCode.XHTML_TOO_LARGE in codes(result))
    }

    @Test
    fun spineBookMalformadoNaoViraComplete() {
        val file = temp.newFile("nwt.epub")
        epub(
            file,
            docs = all66Docs(),
            extraSpine = listOf("broken.xhtml"),
            extraEntries = mapOf(
                "OEBPS/broken.xhtml" to
                    """<html><body class="jwac docId-1001061144"><span id="chapter25"></span>sem versículos</body></html>"""
            ),
        )
        val mateus = NWT_BOOKS.getValue("1001061144")
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(
                "Texto sintético ${mateus.abbrev} 1:1.",
                ref = "Mt 1:1",
                section = "Mateus 1",
            ),
        )

        assertTrue(result.corpus.state != NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB)
        assertEquals(1, result.corpus.failedResources)
        assertTrue(NwtIssueCode.PARSER_NO_VERSES in codes(result))
    }

    @Test
    fun metadadosCoerentesSemAncoraNaoPromovem() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val text = "Texto sintético de Mateus 24:14."
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit(text),
        )

        assertEquals(NwtFileIntegrity.HASH_MATCH, result.integrity)
        assertEquals(NwtEditorialIdentityState.IDENTITY_CONSISTENT, result.editorialState)
        assertEquals(NwtAuthenticityAnchorState.NOT_ANCHORED, result.authenticity)
        assertTrue(result.promotion.integrityVerified)
        assertTrue(result.promotion.editorialConsistencyVerified)
        assertTrue(result.promotion.unitTextVerified)
        assertFalse(result.promotion.anchorProvenanceTrusted)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.NO_TRUSTED_ANCHOR in codes(result))
    }

    @Test
    fun ancoraExternaAindaNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val hash = sha256(file)
        val text = "Texto sintético de Mateus 24:14."
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, sha = hash),
            file = file,
            indexedUnit = unit(text),
            expected = NwtExpectedEdition(
                trustedAnchor = NwtTrustedAnchor(identifier = identifier, sha256 = hash)
            ),
        )

        assertEquals(NwtAuthenticityAnchorState.ANCHOR_MATCH, result.authenticity)
        assertEquals(
            NwtAuthenticityAnchorState.ANCHOR_MATCH,
            result.promotion.anchorCorrespondence,
        )
        assertFalse(result.promotion.anchorProvenanceTrusted)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertFalse(result.promotion.canPromoteCorpusToLocalFactual)
        assertTrue(NwtIssueCode.ANCHOR_PROVENANCE_UNVERIFIED in codes(result))
    }

    @Test
    fun ancoraApenasComIdentificadorNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
            expected = NwtExpectedEdition(
                trustedAnchor = NwtTrustedAnchor(identifier = identifier, sha256 = null)
            ),
        )

        assertEquals(NwtAuthenticityAnchorState.ANCHOR_MATCH, result.authenticity)
        assertFalse(result.promotion.anchorProvenanceTrusted)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.ANCHOR_PROVENANCE_UNVERIFIED in codes(result))
    }

    @Test
    fun ancoraDivergenteNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
            expected = NwtExpectedEdition(
                trustedAnchor = NwtTrustedAnchor(
                    identifier = identifier,
                    sha256 = "0".repeat(64),
                )
            ),
        )

        assertEquals(NwtAuthenticityAnchorState.ANCHOR_MISMATCH, result.authenticity)
        assertEquals(
            NwtAuthenticityAnchorState.ANCHOR_MISMATCH,
            result.promotion.anchorCorrespondence,
        )
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.ANCHOR_MISMATCH in codes(result))
    }

    @Test
    fun anexoNaoIndexadoNaoPromove() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file, indexed = false),
            file = file,
            indexedUnit = unit("Texto sintético de Mateus 24:14."),
        )

        assertFalse(result.attachmentMetadataConsistent)
        assertFalse(result.promotion.editorialConsistencyVerified)
        assertFalse(result.promotion.canPromoteUnitToLocalFactual)
        assertTrue(NwtIssueCode.ATTACHMENT_NOT_INDEXED in codes(result))
    }

    @Test
    fun unidadeNormalizadaPorEspacosEhAceitaComComparacaoExplicita() {
        val file = temp.newFile("nwt.epub")
        epub(file)
        val result = NwtDocumentIdentityVerifier.verify(
            metadata = metadata(file),
            file = file,
            indexedUnit = unit("Texto   sintético\nde Mateus 24:14."),
        )

        assertEquals(NwtUnitIdentityState.UNIT_TEXT_VERIFIED, result.unit.state)
        assertEquals(NwtTextComparison.NORMALIZED_WHITESPACE, result.unit.comparison)
        assertNotNull(result.unit.epubTextSha256)
    }

    // ---------- fixture ZIP com entradas duplicadas ----------

    private fun writeDuplicateEpub(file: File, firstText: String, secondText: String) {
        val docId = "1001061144"
        val container = """
            <?xml version="1.0" encoding="utf-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent().trimStart()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Tradução do Novo Mundo (nwt-T)</dc:title>
                <dc:language>pt</dc:language>
                <dc:publisher>Associação Torre de Vigia de Bíblias e Tratados</dc:publisher>
                <dc:identifier id="BookId">$identifier</dc:identifier>
                <meta property="dcterms:modified">2026-03-19T01:01:11Z</meta>
              </metadata>
              <manifest>
                <item id="doc0" href="$docId-split24.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="doc0"/>
              </spine>
            </package>
        """.trimIndent().trimStart()
        fun xhtml(text: String) = """
            <html><body class="jwac docId-$docId">
            <span id="chapter24"></span><span id="chapter24_verse14"></span>$text
            </body></html>
        """.trimIndent()
        writeStoredZip(
            file,
            listOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/$docId-split24.xhtml" to xhtml(firstText),
                "OEBPS/$docId-split24.xhtml" to xhtml(secondText),
            ),
        )
    }

    private fun writeStoredZip(file: File, entries: List<Pair<String, String>>) {
        val body = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { (name, content) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val data = content.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val offset = body.size()

            leInt(body, 0x04034b50)
            leShort(body, 20)
            leShort(body, 0)
            leShort(body, 0)
            leShort(body, 0)
            leShort(body, 0)
            leInt(body, crc)
            leInt(body, data.size)
            leInt(body, data.size)
            leShort(body, nameBytes.size)
            leShort(body, 0)
            body.write(nameBytes)
            body.write(data)

            leInt(central, 0x02014b50)
            leShort(central, 20)
            leShort(central, 20)
            leShort(central, 0)
            leShort(central, 0)
            leShort(central, 0)
            leShort(central, 0)
            leInt(central, crc)
            leInt(central, data.size)
            leInt(central, data.size)
            leShort(central, nameBytes.size)
            leShort(central, 0)
            leShort(central, 0)
            leShort(central, 0)
            leShort(central, 0)
            leInt(central, 0)
            leInt(central, offset)
            central.write(nameBytes)
        }
        val centralOffset = body.size()
        body.write(central.toByteArray())
        leInt(body, 0x06054b50)
        leShort(body, 0)
        leShort(body, 0)
        leShort(body, entries.size)
        leShort(body, entries.size)
        leInt(body, central.size())
        leInt(body, centralOffset)
        leShort(body, 0)
        file.writeBytes(body.toByteArray())
    }

    private fun leShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun leInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }
}
