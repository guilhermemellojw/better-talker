package com.bettertalker.app.data.library

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.RetrievalResult
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.RetrievalRepository
import com.bettertalker.app.data.repo.TrainingRepository
import com.bettertalker.app.data.util.normalizeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class GlobalLibraryToolTest {

    private fun attachment(
        id: String,
        fileName: String,
        symbol: String? = null,
        baseSlot: String? = null,
        sourceType: String = "content",
        indexed: Boolean = true,
        status: String = "ready",
    ) = AttachmentEntity(
        id = id,
        noteId = null,
        fileName = fileName,
        kind = fileName.substringAfterLast('.').lowercase(),
        sizeBytes = 1,
        appPath = "/tmp/$fileName",
        indexed = indexed,
        addedAt = 0,
        baseSlot = baseSlot,
        status = status,
        sourceType = sourceType,
        symbol = symbol,
    )

    private fun unit(
        id: String,
        kind: String,
        title: String,
        ord: Int,
        startOrd: Int,
        endOrd: Int,
        number: String? = null,
        startPage: Int? = null,
        endPage: Int? = null,
    ) = PublicationUnitEntity(
        id = id,
        attachmentId = "att",
        kind = kind,
        number = number,
        title = title,
        canonicalKey = "$id-key",
        ord = ord,
        startOrd = startOrd,
        endOrd = endOrd,
        startPage = startPage,
        endPage = endPage,
    )

    private fun hit(
        pubId: String,
        ord: Int,
        text: String,
        section: String = "",
        page: Int? = null,
        paragraph: Int? = null,
    ): RetrievalCandidate {
        val passage = Passage(
            id = "$pubId-p$ord",
            pubId = pubId,
            text = text,
            normalizedText = normalizeText(text),
            ref = "",
            section = section,
            page = page,
            paragraph = paragraph,
            order = ord,
            sourceType = SourceType.CONTENT,
            symbol = null,
            trainingCategory = TrainingCategory.UNKNOWN,
        )
        return RetrievalCandidate(
            passage = passage,
            publicationTitle = pubId,
            lexicalScore = 0.8,
            metadataScore = 0.2,
            finalScore = 0.9,
            matchedTerms = listOf("texto"),
            foundBy = listOf("lexical"),
        )
    }

    private fun passage(
        pubId: String,
        ord: Int,
        text: String,
        page: Int? = null,
    ) = PassageEntity(
        id = "$pubId-p$ord",
        attachmentId = pubId,
        text = text,
        normalized = normalizeText(text),
        section = "",
        page = page,
        ord = ord,
    )

    private class FakeRetrieval(
        private val handler: (List<String>) -> List<RetrievalCandidate>,
    ) : RetrievalRepository {
        override suspend fun retrieve(
            query: String,
            scope: RetrievalScope,
            limit: Int,
        ): RetrievalResult =
            RetrievalResult(RetrievalStatus.OK, handler(scope.contentSourceIds).take(limit))
    }

    private class FakeTraining(
        private val hits: List<RetrievalCandidate>,
    ) : TrainingRepository {
        override suspend fun retrieveTraining(
            query: String,
            category: TrainingCategory?,
            scope: RetrievalScope,
            limit: Int,
        ): RetrievalResult =
            if (scope.trainingSourceIds.isEmpty()) {
                RetrievalResult(RetrievalStatus.INSUFFICIENT_SCOPE, emptyList())
            } else {
                RetrievalResult(RetrievalStatus.OK, hits.take(limit))
            }
    }

    private class FakeUnits(
        private val unitsById: Map<String, List<PublicationUnitEntity>> = emptyMap(),
        private val passagesById: Map<String, List<PassageEntity>> = emptyMap(),
    ) : GlobalLibraryUnitLookup {
        override suspend fun ensure(attachmentId: String) = Unit
        override suspend fun unitAt(attachmentId: String, ord: Int): PublicationUnitEntity? =
            unitsById[attachmentId].orEmpty().firstOrNull { ord in it.startOrd..it.endOrd }

        override suspend fun units(attachmentId: String): List<PublicationUnitEntity> =
            unitsById[attachmentId].orEmpty()

        override suspend fun passages(
            attachmentId: String,
            startOrd: Int,
            endOrd: Int,
            limit: Int,
        ): List<PassageEntity> =
            passagesById[attachmentId].orEmpty()
                .filter { it.ord in startOrd..endOrd }
                .take(limit)
    }

    private fun tool(
        attachments: List<AttachmentEntity>,
        retrieval: RetrievalRepository,
        training: TrainingRepository = FakeTraining(emptyList()),
        units: FakeUnits = FakeUnits(),
    ) = GlobalLibraryTool(
        attachments = { attachments },
        retrieval = retrieval,
        training = training,
        units = units,
    )

    @Test
    fun livroResolveEdicaoEUnidade() = runBlocking {
        val cl = attachment("cl", "cl_T.jwpub", symbol = "cl")
        val units = FakeUnits(
            unitsById = mapOf(
                "cl" to listOf(
                    unit("cl-u1", "CHAPTER", "Achegue-se a Jeová", 0, 0, 20, "2", 10, 20),
                )
            )
        )
        val result = tool(
            attachments = listOf(cl),
            retrieval = FakeRetrieval { ids ->
                if (ids == listOf("cl")) {
                    listOf(hit("cl", 4, "Jeová se achega aos que o buscam.", page = 12, paragraph = 3))
                } else emptyList()
            },
            units = units,
        ).search("O que o livro Achegue-se a Jeová diz sobre buscar a Deus?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertEquals("Achegue-se a Jeová", result.publicationTitle)
        assertEquals("Capítulo 2", result.sources.single().unitLabel)
        assertEquals(12, result.sources.single().page)
        assertEquals(3, result.sources.single().paragraph)
    }

    @Test
    fun revistaResolveEdicaoEPagina() = runBlocking {
        val wp = attachment("wp", "wp_T_201709.jwpub", symbol = "wp17.09")
        val units = FakeUnits(
            unitsById = mapOf(
                "wp" to listOf(
                    unit("wp-u1", "ARTICLE", "A verdade sobre os anjos", 0, 0, 20),
                )
            )
        )
        val result = tool(
            attachments = listOf(wp),
            retrieval = FakeRetrieval { ids ->
                if (ids == listOf("wp")) {
                    listOf(hit("wp", 5, "Os anjos são mensageiros de Deus.", page = 4, paragraph = 2))
                } else emptyList()
            },
            units = units,
        ).search("O que a Sentinela N.º 5 de 2017 diz sobre os anjos?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertTrue(result.publicationTitle.orEmpty().contains("A Sentinela"))
        assertTrue(result.publicationTitle.orEmpty().contains("2017"))
        assertEquals("Artigo 1", result.sources.single().unitLabel)
        assertEquals("A verdade sobre os anjos", result.sources.single().unitTitle)
    }

    @Test
    fun unidadeExplicitaRestringeBusca() = runBlocking {
        val wp = attachment("wp", "wp_T_201709.jwpub", symbol = "wp17.09")
        val units = FakeUnits(
            unitsById = mapOf(
                "wp" to listOf(
                    unit("wp-u1", "ARTICLE", "Primeiro artigo", 0, 0, 10),
                    unit("wp-u2", "ARTICLE", "Segundo artigo", 1, 11, 20),
                )
            ),
            passagesById = mapOf(
                "wp" to listOf(
                    passage("wp", 2, "Os anjos ajudam o povo de Deus."),
                    passage("wp", 12, "Outro assunto sobre anjos."),
                )
            ),
        )
        val result = tool(
            attachments = listOf(wp),
            retrieval = FakeRetrieval { ids ->
                if (ids == listOf("wp")) {
                    listOf(hit("wp", 12, "Outro assunto sobre anjos."))
                } else emptyList()
            },
            units = units,
        ).search("O que o artigo 1 da Sentinela N.º 5 de 2017 diz sobre anjos?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertEquals("Artigo 1", result.sources.single().unitLabel)
        assertEquals(0, result.sources.single().startOrd)
        assertEquals(10, result.sources.single().endOrd)
    }

    @Test
    fun edicaoEspecificaEscolheARevistaCerta() = runBlocking {
        val jul = attachment("jul", "w_T_202507.jwpub", symbol = "w25.07")
        val jun = attachment("jun", "w_T_202506.jwpub", symbol = "w25.06")
        val result = tool(
            attachments = listOf(jul, jun),
            retrieval = FakeRetrieval { ids ->
                if (ids == listOf("jul")) {
                    listOf(hit("jul", 1, "Conteúdo de julho sobre esperança."))
                } else emptyList()
            },
            units = FakeUnits(
                unitsById = mapOf(
                    "jul" to listOf(unit("jul-u1", "ARTICLE", "Esperança", 0, 0, 10))
                )
            ),
        ).search("O que a Sentinela de julho de 2025 diz sobre esperança?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertEquals("jul", result.publicationId)
    }

    @Test
    fun semEdicaoEscolhidaEhAmbiguo() = runBlocking {
        val jul = attachment("jul", "w_T_202507.jwpub", symbol = "w25.07")
        val jun = attachment("jun", "w_T_202506.jwpub", symbol = "w25.06")
        val result = tool(
            attachments = listOf(jul, jun),
            retrieval = FakeRetrieval { emptyList() },
        ).search("O que a Sentinela diz sobre esperança?")

        assertEquals(GlobalLibraryStatus.AMBIGUOUS, result.status)
        assertTrue(result.candidates.size >= 2)
        assertTrue(result.candidates.any { it.title.contains("w_T_202507") })
    }

    @Test
    fun artigoUsaIndiceParaNaoCairNaCapa() = runBlocking {
        val wp = attachment("wp", "wp_T_201709.jwpub", symbol = "wp17.09")
        val units = FakeUnits(
            unitsById = mapOf(
                "wp" to listOf(
                    unit("toc", "ARTICLE", "Artigos", 0, 0, 3),
                    unit("capa", "ARTICLE", "A verdade sobre os anjos", 1, 4, 10),
                    unit("a1", "ARTICLE", "Será que os anjos podem nos ajudar?", 2, 11, 20),
                    unit("a2", "ARTICLE", "A verdade sobre os anjos", 3, 21, 30),
                )
            ),
            passagesById = mapOf(
                "wp" to listOf(
                    passage("wp", 0, "3 Será que os anjos podem nos ajudar"),
                    passage("wp", 1, "4 A verdade sobre os anjos"),
                    passage("wp", 7, "Texto de capa."),
                    passage("wp", 12, "Os anjos podem nos ajudar hoje."),
                    passage("wp", 22, "A verdade sobre os anjos."),
                )
            ),
        )
        val result = tool(
            attachments = listOf(wp),
            retrieval = FakeRetrieval { emptyList() },
            units = units,
        ).search("O que o artigo 1 da Sentinela N.º 5 de 2017 diz sobre anjos?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertEquals("Artigo 1", result.sources.single().unitLabel)
        assertEquals("Será que os anjos podem nos ajudar?", result.sources.single().unitTitle)
        assertEquals(11, result.sources.single().startOrd)
    }

    @Test
    fun searchQueryRemovePublicacaoEUnidade() {
        val q = GlobalLibraryResolver.searchQuery(
            question = "O que o livro Achegue-se a Jeová diz sobre refúgio?",
            ref = null,
            unit = null,
            publicationTitle = "Achegue-se a Jeová",
        )
        assertFalse(q.contains("Achegue-se", ignoreCase = true))
        assertFalse(q.contains("livro", ignoreCase = true))
        assertTrue(q.contains("refúgio", ignoreCase = true))
    }

    @Test
    fun publicacaoAusenteRetornaMissing() = runBlocking {
        val result = tool(
            attachments = emptyList(),
            retrieval = FakeRetrieval { emptyList() },
        ).search("O que a Sentinela N.º 1 de 2030 diz sobre o Reino?")

        assertEquals(GlobalLibraryStatus.MISSING, result.status)
        assertTrue(result.message.orEmpty().contains("Não está no acervo"))
    }

    @Test
    fun publicacaoIndexandoRetornaIndexing() = runBlocking {
        val wp = attachment("wp", "wp_T_201709.jwpub", symbol = "wp17.09", indexed = false, status = "indexing")
        val result = tool(
            attachments = listOf(wp),
            retrieval = FakeRetrieval { emptyList() },
        ).search("O que a Sentinela N.º 5 de 2017 diz sobre os anjos?")

        assertEquals(GlobalLibraryStatus.INDEXING, result.status)
        assertTrue(result.message.orEmpty().contains("ainda está indexando"))
    }

    @Test
    fun buscaForaDaPublicacaoInformaOndeAchou() = runBlocking {
        val cl = attachment("cl", "cl_T.jwpub", symbol = "cl")
        val jr = attachment("jr", "jr_T.jwpub", symbol = "jr")
        val result = tool(
            attachments = listOf(cl, jr),
            retrieval = FakeRetrieval { ids ->
                when {
                    ids == listOf("cl") -> emptyList()
                    "jr" in ids -> listOf(hit("jr", 3, "Jeová é o nosso refúgio."))
                    else -> emptyList()
                }
            },
            units = FakeUnits(
                unitsById = mapOf(
                    "jr" to listOf(unit("jr-u1", "CHAPTER", "Refúgio", 0, 0, 10))
                )
            ),
        ).search("O que o livro Achegue-se a Jeová diz sobre refúgio?")

        assertEquals(GlobalLibraryStatus.READY, result.status)
        assertTrue(result.searchedElsewhere)
        assertTrue(result.message.orEmpty().contains("Não encontrei em"))
        assertEquals("jr", result.sources.single().attachmentId)
    }

    @Test
    fun beUsaTrilhoTrainingENaoDoutrina() = runBlocking {
        val be = attachment("be", "be_T.jwpub", symbol = "be", baseSlot = "be", sourceType = "training")
        val result = tool(
            attachments = listOf(be),
            retrieval = FakeRetrieval { emptyList() },
            training = FakeTraining(
                listOf(hit("be", 2, "Fale com naturalidade e contato visual."))
            ),
            units = FakeUnits(
                unitsById = mapOf(
                    "be" to listOf(unit("be-u1", "CHAPTER", "Leitura", 0, 0, 10))
                )
            ),
        ).search("O que o livro Beneficie-se diz sobre leitura em público?")

        assertEquals(GlobalLibraryStatus.TRAINING_ONLY, result.status)
        assertTrue(result.trainingOnly)
        assertTrue(result.sources.single().training)
    }

    @Test
    fun semHitNaPublicacaoNemNoGeralRetornaNoMatch() = runBlocking {
        val cl = attachment("cl", "cl_T.jwpub", symbol = "cl")
        val result = tool(
            attachments = listOf(cl),
            retrieval = FakeRetrieval { emptyList() },
        ).search("O que o livro Achegue-se a Jeová diz sobre um assunto inexistente?")

        assertEquals(GlobalLibraryStatus.NO_MATCH, result.status)
        assertTrue(result.message.orEmpty().contains("Não encontrei"))
    }

    @Test
    fun perguntaGeralNaoViraErroDeAcervo() = runBlocking {
        val result = tool(
            attachments = emptyList(),
            retrieval = FakeRetrieval { emptyList() },
        ).search("Como melhorar a introdução de um discurso?")

        assertEquals(GlobalLibraryStatus.NO_MATCH, result.status)
        assertFalse(result.libraryIntent)
        assertEquals(null, result.message)
    }

    @Test
    fun readerLeFaixaDaUnidade() = runBlocking {
        val source = GlobalLibrarySource(
            index = 1,
            attachmentId = "cl",
            publicationTitle = "Achegue-se a Jeová",
            unitLabel = "Capítulo 2",
            unitTitle = "Busque a Deus",
            page = 10,
            pageEnd = 11,
            paragraph = null,
            snippet = "Trecho inicial.",
            startOrd = 0,
            endOrd = 2,
            unitId = "cl-u1",
        )
        val content = tool(
            attachments = emptyList(),
            retrieval = FakeRetrieval { emptyList() },
            units = FakeUnits(
                passagesById = mapOf(
                    "cl" to listOf(
                        passage("cl", 0, "Primeiro parágrafo.", page = 10),
                        passage("cl", 1, "Segundo parágrafo.", page = 10),
                        passage("cl", 2, "Terceiro parágrafo.", page = 11),
                    )
                )
            ),
        ).read(source)

        assertEquals("Capítulo 2", content.unitLabel)
        assertTrue(content.body.contains("Primeiro parágrafo"))
        assertTrue(content.body.contains("Terceiro parágrafo"))
        assertFalse(content.truncated)
        assertEquals("pág. 10-11", content.locationLabel)
    }
}
