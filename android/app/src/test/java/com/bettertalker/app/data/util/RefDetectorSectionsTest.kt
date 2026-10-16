package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #24-fix (b) — refs de revista com §/§§ + range e dedupe com granularidade:
 * as 3 citações da mesma edição ("w25.07 22 §§ 8-9", "w25.07 20 § 2",
 * "w25.07 24 §§ 13-14") são DISTINTAS; duplicata real cai; livro bíblico
 * com cap:vers não vira publicação.
 */
class RefDetectorSectionsTest {

    @Test
    fun wCode_segundoParagrafoComRange() {
        val ref = RefDetector.detect("(Jer. 45:3; w25.07 22 §§ 8-9)").single()
        assertEquals("w|2025|7", ref.editionKey)
        assertEquals(22, ref.page)
        assertEquals(8, ref.paragraph)
        assertEquals(9, ref.paragraphEnd)
    }

    @Test
    fun wCode_paragrafoUnicoSemRange() {
        val ref = RefDetector.detect("(w25.07 20 § 2)").single()
        assertEquals(20, ref.page)
        assertEquals(2, ref.paragraph)
        assertNull(ref.paragraphEnd)
    }

    @Test
    fun wCode_segundoRangeDaMesmaEdicao() {
        val ref = RefDetector.detect("(Jer. 45:5; w25.07 24 §§ 13-14)").single()
        assertEquals(24, ref.page)
        assertEquals(13, ref.paragraph)
        assertEquals(14, ref.paragraphEnd)
    }

    @Test
    fun tresCitacoesDaMesmaEdicao_saoDistintas() {
        val refs = RefDetector.detect(
            "Tópico 1: Parece que Baruque não estava satisfeito. (Jer. 45:3; w25.07 22 §§ 8-9)\n" +
                "Tópico 2: Sem contentamento, podemos perder a alegria. (w25.07 20 § 2)\n" +
                "Tópico 3: Jeová ajudou Baruque a mudar o ponto de vista. (Jer. 45:5; w25.07 24 §§ 13-14)"
        ).filter { it.pubKey == "w" }
        assertEquals(3, refs.size)
        assertEquals(listOf(22, 20, 24), refs.map { it.page })
        assertEquals(8, refs[0].paragraph)
        assertEquals(2, refs[1].paragraph)
        assertEquals(13, refs[2].paragraph)
    }

    @Test
    fun citacaoDuplicadaReal_cai() {
        val refs = RefDetector.detect("w25.07 22 §§ 8-9 e de novo w25.07 22 §§ 8-9")
            .filter { it.pubKey == "w" }
        assertEquals(1, refs.size)
    }

    @Test
    fun livroBiblicoCapVers_naoViraPublicacao() {
        // "Jer. 45:3" sozinho já não casava; o phantom "jr" vinha do texto
        // natural com o nome por extenso (matcher de título do catálogo).
        assertEquals(emptyList<RefDetector.DetectedRef>(), RefDetector.detect("Jer. 45:3"))
        assertEquals(
            emptyList<RefDetector.DetectedRef>(),
            RefDetector.detect("Baruque estava desanimado, e isso fica claro no relato de Jeremias 45:3.")
        )
    }

    @Test
    fun capversAposTituloLegitimo_continuaDetectando() {
        // Regressão do B4: "livro X cap. 5" continua sendo citação de publicação.
        val ref = RefDetector.detect("veja o livro Beneficie-se cap. 5").single()
        assertEquals("be", ref.pubKey)
    }

    @Test
    fun jsonRoundTrip_preservaRange() {
        val refs = RefDetector.detect("w25.07 22 §§ 8-9")
        val back = RefDetector.detectedFromJson(RefDetector.detectedToJson(refs))
        assertEquals(1, back.size)
        assertEquals(22, back.single().page)
        assertEquals(8, back.single().paragraph)
        assertEquals(9, back.single().paragraphEnd)
    }
}
