package com.bettertalker.app.data.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-f — canonicalização nos dois lados do verificador: abreviação de
 * livro, versículo individual dentro de range e página por extenso deixam de
 * ser falso-positivo; invenção real continua removida. Puro.
 */
class GroundednessCanonicalTest {

    private val fontes = listOf("S-34: (1Co 14:33, 40; Jó 38:31-33; w11 1/7 26-28)")

    @Test
    fun livroPorExtensoComAbreviacaoNoCorpusNaoEhRemovido() {
        val texto = "〈sugestão〉Paulo escreveu em 1 Coríntios 14:33 sobre a paz.〈/sugestão〉"
        val r = GroundednessVerifier.verify(texto, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun versiculoIndividualDentroDoRangeNaoEhRemovido() {
        val texto = "〈sugestão〉Em Jó 38:32, Jeová pergunta sobre a constelação.〈/sugestão〉"
        val r = GroundednessVerifier.verify(texto, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun paginaPorExtensoComRangeNoCorpusNaoEhRemovido() {
        val texto = "Como mostra a referência w11 1/7, páginas 26 a 28, o tema é central."
        val r = GroundednessVerifier.verify(texto, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun versiculoInventadoContinuaRemovido() {
        val texto = "〈sugestão〉Leia Gênesis 1:1 para ver a criação.〈/sugestão〉"
        val r = GroundednessVerifier.verify(texto, fontes)
        assertTrue(r.hasRemovals)
    }
}
