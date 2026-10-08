package com.bettertalker.app

import com.bettertalker.app.data.domain.HybridRetrieval
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.pubKeyOf
import com.bettertalker.app.data.domain.titlePrefixHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun norm(s: String): String = s.lowercase()
    .replace(Regex("[àáâãä]"), "a").replace(Regex("[éêë]"), "e")
    .replace(Regex("[íîï]"), "i").replace(Regex("[óôõö]"), "o")
    .replace(Regex("[úûü]"), "u").replace(Regex("[ç]"), "c")
    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun passage(
    id: String,
    pub: String,
    text: String,
    type: SourceType = SourceType.CONTENT,
    ref: String = "",
    section: String = "",
    cat: TrainingCategory = TrainingCategory.UNKNOWN
) = Passage(id, pub, text, norm(text), ref, section, null, null, 0, type, null, cat)

class HybridRetrievalTest {

    private val content = listOf(
        passage("p1", "w", "Confiar em Jeová ajuda a enfrentar problemas graves com coragem."),
        passage("p2", "w", "A oração sincera fortalece a amizade com Deus todos os dias.", ref = "w24 Oração §5"),
        passage("p3", "w", "Estudar a Bíblia em família cria momentos de alegria.")
    )
    private val titles = mapOf("w" to "A Sentinela")

    @Test
    fun exactMatchRanksFirst() {
        val hits = HybridRetrieval.rank(
            "Confiar em Jeová ajuda a enfrentar problemas graves com coragem.",
            content, titles, 5
        )
        assertEquals("p1", hits.first().passage.id)
        assertTrue(hits.first().foundBy.contains("lexical"))
        assertTrue(hits.first().matchedTerms.isNotEmpty())
    }

    @Test
    fun scoresInRangeAndDeterministic() {
        val a = HybridRetrieval.rank("oração Jeová Deus", content, titles, 5)
        val b = HybridRetrieval.rank("oração Jeová Deus", content, titles, 5)
        assertEquals(a.map { it.passage.id }, b.map { it.passage.id })
        assertEquals(a.map { it.finalScore }, b.map { it.finalScore })
        for (h in a) {
            assertTrue(h.finalScore in 0.0..1.0)
            assertTrue(h.lexicalScore in 0.0..1.0)
            assertTrue(h.metadataScore in 0.0..1.0)
        }
    }

    @Test
    fun emptyQueryOrCandidatesGivesEmpty() {
        assertTrue(HybridRetrieval.rank("", content, titles, 5).isEmpty())
        assertTrue(HybridRetrieval.rank("oração", emptyList(), titles, 5).isEmpty())
        assertTrue(HybridRetrieval.rank("oração", content, titles, 0).isEmpty())
    }

    @Test
    fun metadataSymbolBoosts() {
        val cands = listOf(
            passage("a", "x", "Texto genérico sobre vários assuntos."),
            passage("b", "w24", "Texto genérico sobre vários assuntos.", ref = "w24 Seção §1")
        )
        val hits = HybridRetrieval.rank("oração w24", cands, mapOf("x" to "Avulso", "w24" to "Revista"), 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("b", hits.first().passage.id)
        assertTrue(hits.first().metadataScore > 0.0)
    }

    @Test
    fun categoryBoostReordersWithoutTouchingScores() {
        fun cand(id: String, score: Double) = RetrievalCandidate(
            passage("p-$id", "be", "t $id", SourceType.TRAINING, cat = TrainingCategory.ILLUSTRATION),
            null, score, 0.0, score, emptyList(), listOf("lexical")
        )
        val other = cand("other", 0.80).copy(
            passage = passage("p-other", "be", "t other", SourceType.TRAINING, cat = TrainingCategory.DELIVERY)
        )
        val hits = HybridRetrieval.applyCategoryBoost(
            listOf(other, cand("illus", 0.70)),
            TrainingCategory.ILLUSTRATION
        ) { it.passage.trainingCategory }
        // 0.70 + 0.15 > 0.80: intenção vence; scores intactos.
        assertEquals("p-illus", hits.first().passage.id)
        assertEquals(0.70, hits.first().finalScore, 0.0)
        assertEquals(0.80, hits[1].finalScore, 0.0)
    }

    @Test
    fun categoryMismatchKeepsItemsAfter() {
        fun cand(id: String, score: Double, cat: TrainingCategory) = RetrievalCandidate(
            passage("p-$id", "be", "t", SourceType.TRAINING, cat = cat),
            null, score, 0.0, score, emptyList(), listOf("lexical")
        )
        val hits = HybridRetrieval.applyCategoryBoost(
            listOf(cand("a", 0.9, TrainingCategory.DELIVERY), cand("b", 0.5, TrainingCategory.ILLUSTRATION)),
            TrainingCategory.ILLUSTRATION
        ) { it.passage.trainingCategory }
        // 0.5+0.15=0.65 < 0.9: fora da categoria continua depois, não some.
        assertEquals(listOf("p-a", "p-b"), hits.map { it.passage.id })
    }

    // ---------- T2: boost por título no metadataScore ----------

    private fun pubPassage(fileName: String, symbol: String? = null) =
        passage(fileName, fileName, "conteudo generico do trecho").copy(symbol = symbol)

    private fun metaOf(query: String, fileName: String, symbol: String? = null): Double {
        val cands = listOf(pubPassage(fileName, symbol))
        val hits = HybridRetrieval.rank(query, cands, mapOf(fileName to fileName), 5)
        return hits.firstOrNull()?.metadataScore ?: 0.0
    }

    @Test
    fun pubKeyOf_simboloOuArquivo() {
        assertEquals("lff", pubKeyOf("lff", "lff_T.jwpub"))
        assertEquals("lff", pubKeyOf(null, "lff_T.jwpub"))
        assertEquals("it", pubKeyOf(null, "it_T.jwpub"))
        assertEquals("rsg", pubKeyOf(null, "rsg_T.jwpub"))
        // Revista fica fora do catálogo de títulos: sem chave (sem boost).
        assertEquals(null, pubKeyOf(null, "wp_T_201909.jwpub"))
        assertEquals(null, pubKeyOf(null, null))
    }

    @Test
    fun titlePrefixHit_casosEArmadilhas() {
        assertTrue(titlePrefixHit("o que seja feliz diz sobre o perdao", "Seja Feliz para Sempre!"))
        assertTrue(titlePrefixHit("o que estudo perspicaz diz", "Estudo Perspicaz das Escrituras"))
        assertTrue(titlePrefixHit("o que o guia de pesquisa diz", "Guia de Pesquisa"))
        assertTrue(titlePrefixHit("guia de pesquisa", "Guia de Pesquisa"))
        // Armadilha: "para sempre" é genérico e NÃO é prefixo do título.
        assertFalse(titlePrefixHit("vida para sempre", "Seja Feliz para Sempre!"))
        // 1 token só vale quando distintivo (≥8 chars): "melhore" (7) não.
        assertFalse(titlePrefixHit("melhore este ponto", "Melhore"))
    }

    @Test
    fun metadataScore_simboloOuTitulo_semDuplaContagem() {
        // Só o símbolo: 0.45 (boost) + 0.20 (overlap do símbolo no nome).
        assertEquals(0.65, metaOf("lff", "lff_T.jwpub"), 0.01)
        // Só o título: 0.45.
        assertEquals(0.45, metaOf("seja feliz", "lff_T.jwpub"), 0.01)
        // Ambos juntos: UM único +0.45 (+ overlap 1/3) — não 0.90.
        assertEquals(0.5167, metaOf("lff seja feliz", "lff_T.jwpub"), 0.01)
    }

    @Test
    fun metadataScore_titulosCompletosEPartes() {
        assertTrue(metaOf("o que o guia de pesquisa diz sobre isso?", "rsg_T.jwpub") >= 0.45)
        assertTrue(metaOf("o que estudo perspicaz das escrituras diz?", "it_T.jwpub") >= 0.45)
        assertTrue(metaOf("o que o livro ame as pessoas diz?", "lmd_T.jwpub") >= 0.45)
        // Título não citado: sem boost de título.
        assertEquals(0.0, metaOf("o que isso diz?", "lff_T.jwpub"), 0.0)
    }

    @Test
    fun rank_tituloPriorizaAPublicacaoCerta() {
        val cands = listOf(
            pubPassage("lff_T.jwpub"),
            pubPassage("wp_T_201909.jwpub"),
        )
        val hits = HybridRetrieval.rank(
            "o que o Seja Feliz diz sobre o perdao?",
            cands,
            mapOf("lff_T.jwpub" to "lff_T.jwpub", "wp_T_201909.jwpub" to "wp_T_201909.jwpub"),
            5,
        )
        assertEquals("lff_T.jwpub", hits.first().passage.id)
        assertTrue(hits.first().metadataScore >= 0.45)
        assertTrue(hits.first().foundBy.contains("metadata"))
    }
}
