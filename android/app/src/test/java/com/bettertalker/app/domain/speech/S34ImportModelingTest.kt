package com.bettertalker.app.domain.speech

import com.bettertalker.app.data.util.OutlineParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3.x — modelagem da importação S-34: a NOTA inicial (e as orientações
 * finais) NÃO são introdução/conclusão; são orientações do orador. Texto
 * antes do primeiro tópico nunca vira INTRO automaticamente.
 *
 * Caso 6 (regressão real): usa o texto do `S-34_T_035.docx`.
 */
class S34ImportModelingTest {

    private fun convert(text: String): OutlineConversion {
        val parsed = OutlineParser.parse(text, "S-34_T_035.docx")
        return OutlineConverter().convert(parsed, noteId = "n1")
    }

    // ---------- Caso 1: NOTA inicial ----------

    @Test
    fun notaInicial_isSpeakerNotes_notIntro() {
        val text = """
            N.º 35 — É possível viver para sempre? O que você precisa fazer?

            NOTA: Ajude a assistência a meditar em como vai ser maravilhoso viver para sempre.

            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Precisamos estar vivos para ter esperança.
        """.trimIndent()
        val conv = convert(text)

        // T5: a NOTA continua fora da intro — que agora é um card vazio.
        assertEquals("Introdução", conv.intro?.title)
        assertTrue(conv.intro?.contentHtml.isNullOrEmpty())
        assertFalse(conv.intro?.title?.contains("NOTA") == true)
        assertTrue(
            "speakerNotes deve conter a NOTA",
            conv.speakerNotes.contains("NOTA: Ajude a assistência a meditar"),
        )
        assertEquals(1, conv.bodies.size)
        assertEquals("FOMOS CRIADOS PARA VIVER PARA SEMPRE", conv.bodies.single().section.title)
    }

    // ---------- Caso 2: instrução dentro do tópico ----------

    @Test
    fun instrucaoDentroDoTopico_isInstruction_notNewTopic() {
        val text = """
            Tema: Teste

            TÓPICO 1 (5 min)

            Ponto principal. [Leia Eclesiastes 3:11.]

            Outro ponto.
        """.trimIndent()
        val conv = convert(text)

        assertEquals(1, conv.bodies.size) // não criou novo tópico
        val subs = conv.bodies.single().subPoints
        assertTrue(subs.any { it.instruction?.contains("Leia Eclesiastes 3:11.") == true })
        // o texto discursivo NÃO contém a instrução
        assertTrue(subs.none { it.outlineText.contains("[Leia") })
        // T5: intro existe, mas vazia (instrução ficou no sub-ponto).
        assertTrue(conv.intro?.contentHtml.isNullOrEmpty())
    }

    // ---------- Caso 3: imagem ----------

    @Test
    fun imagem_isInstruction_notDiscourseText() {
        val text = """
            Tema: Teste

            TÓPICO 1 (5 min)

            Por conta própria, Adão e Eva escolheram desobedecer a Deus. [Imagem 1]
        """.trimIndent()
        val conv = convert(text)

        val sub = conv.bodies.single().subPoints.single()
        assertEquals("Imagem 1", sub.instruction)
        assertFalse(sub.outlineText.contains("Imagem"))
        assertEquals("Por conta própria, Adão e Eva escolheram desobedecer a Deus.", sub.outlineText)
    }

    // ---------- Caso 4: orientação final ----------

    @Test
    fun orientacaoFinal_goesToSpeakerNotes_notConclusionNorSubPoint() {
        val text = """
            Tema: Teste

            TÓPICO 1 (5 min)

            Ponto principal.

            [Siga de perto o material do esboço e observe o tempo.]

            TEMPO TOTAL: 5 MINUTOS

            © 2020 Watch Tower Bible and Tract Society
        """.trimIndent()
        val conv = convert(text)

        // T5: a orientação final continua fora da conclusion (card vazio).
        assertTrue(conv.conclusion?.contentHtml.isNullOrEmpty())
        assertTrue(conv.speakerNotes.contains("Siga de perto o material do esboço"))
        // não virou sub-ponto do tópico
        assertTrue(conv.bodies.single().subPoints.none {
            it.outlineText.contains("Siga de perto") || (it.instruction?.contains("Siga de perto") == true)
        })
    }

    // ---------- Caso 6: S-34 completo (regressão real) ----------

    @Test
    fun fullS34_correctTree() {
        val conv = convert(FULL_S34)

        // T5: cards vazios emolduram os 5 tópicos (orders 0..6).
        assertEquals("Introdução", conv.intro?.title)
        assertEquals(0, conv.intro?.order)
        assertEquals("Conclusão", conv.conclusion?.title)
        assertEquals(6, conv.conclusion?.order)

        // 5 tópicos principais, na ordem e com os tempos do documento.
        val titles = conv.bodies.map { it.section.title }
        assertEquals(
            listOf(
                "FOMOS CRIADOS PARA VIVER PARA SEMPRE",
                "COMO A VIDA ETERNA FOI PERDIDA",
                "COMO É POSSÍVEL TER VIDA ETERNA",
                "SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL?",
                "VOCÊ VAI VIVER PARA SEMPRE?",
            ),
            titles,
        )
        assertEquals(listOf(5, 4, 9, 8, 4), conv.bodies.map { it.section.minutes })

        // Orientações gerais do orador: NOTA inicial + fechamento.
        assertTrue(conv.speakerNotes.contains("NOTA: Ajude a assistência"))
        assertTrue(conv.speakerNotes.contains("Siga de perto o material do esboço"))
        assertTrue(conv.speakerNotes.contains("Convide os novos"))

        // Rodapé/metadados não viram conteúdo.
        val allText = conv.bodies.flatMap { b -> b.subPoints.map { it.outlineText } }
        assertTrue("rodapé não pode ser conteúdo", allText.none { it.contains("Watch Tower") })
        assertTrue("rodapé não pode ser conteúdo", allText.none { it.contains("S-34-T") })
        assertTrue("orientação final não pode ser conteúdo", allText.none { it.contains("Siga de perto") })

        // Instruções inline preservadas como instrução (não como texto).
        val allInstr = conv.bodies.flatMap { b -> b.subPoints.mapNotNull { it.instruction } }
        assertTrue(allInstr.any { it.contains("Leia Eclesiastes 3:11.") })
        assertTrue(allInstr.any { it.contains("Imagem 1") })

        // Refs continuam extraídas.
        assertTrue(conv.bodies[0].subPoints.any { it.bibleRefs.any { r -> r.contains("3:11") } })
    }

    // ---------- Caso 5: discurso sem NOTA não ganha speakerNotes ----------

    @Test
    fun noNota_hasEmptySpeakerNotes() {
        val text = """
            Tema: Simples

            TÓPICO 1 (5 min)

            Apenas conteúdo.
        """.trimIndent()
        val conv = convert(text)
        assertTrue(conv.speakerNotes.isBlank())
        // T5: mesmo sem NOTA, os cards vazios existem.
        assertEquals("Introdução", conv.intro?.title)
        assertEquals(1, conv.bodies.size)
    }

    private companion object {
        val FULL_S34 = """
            N.º 35		É possível viver para sempre? O que você precisa fazer?

            NOTA: Ajude a assistência a meditar em como vai ser maravilhoso viver para sempre. Incentive todos a fazer o que for necessário para ganhar a vida eterna. Se você quiser, pode usar as imagens fornecidas para ajudar a explicar um ponto.

            FOMOS CRIADOS PARA VIVER PARA SEMPRE (5 min)

            Precisamos estar vivos para ter esperança, fazer planos para o futuro e aproveitar as coisas que gostamos.

            O tempo passa muito rápido e a vida parece muito curta. (Despertai! 08/13 pág. 6)

            Temos o desejo de continuar vivendo e nunca morrer. [Leia Eclesiastes 3:11.] (Despertai! 08/13 pág. 8 parág. 1-2)

            Deus criou os humanos para viver uma vida perfeita, eterna, na Terra. (Gên 1:26, 31; Sentinela número 3 de 2019 pág. 6-7)

            Adão e Eva poderiam ter vivido para sempre se tivessem obedecido a Deus. (Gên 2:16, 17)

            COMO A VIDA ETERNA FOI PERDIDA (4 min)

            Por conta própria, Adão e Eva escolheram desobedecer a Deus. (Gên 3:6) [Imagem 1]

            Eles foram expulsos do jardim do Éden e, com o tempo, morreram. (Gên 3:19, 22, 23; 5:5)

            Adão transmitiu o pecado, a imperfeição e a morte a todos os seus descendentes.
            [Leia Romanos 5:12.]

            Apesar de Adão e Eva terem desobedecido a Deus, o propósito Dele para a humanidade não mudou. (Despertai! 12/08 pág. 7)

            Então, como é possível que humanos imperfeitos tenham vida eterna?

            COMO É POSSÍVEL TER VIDA ETERNA (9 min)

            Os esforços humanos de interromper o processo de envelhecimento não vão trazer vida eterna.

            Os avanços na medicina e nos cuidados com a higiene aumentaram a expectativa de vida de muitas pessoas, mas a duração da vida humana basicamente não mudou. (Sal 90:10; Sentinela número 3 de 2019 pág. 5 parág. 3-4)

            Jeová Deus providenciou a solução para o problema do pecado e da morte herdados.
            [Leia João 3:16.]

            Jesus, de vontade própria, deu sua vida para resgatar os descendentes de Adão. (Mt 20:28; Ro 5:19; Entenda a Bíblia cap. 5 parág. 10-11) [Imagem 2]

            Muitos vão viver para sempre em um paraíso na Terra, como é a vontade de Deus. (Sal 37:29)

            Milhões vão sobreviver ao fim deste atual sistema; outros bilhões vão ser ressuscitados dentre os mortos. (Ap 7:9, 14; 20:13)

            SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL? (8 min)

            No futuro, Deus vai acabar com os problemas que tornam a vida difícil hoje.

            Toda a maldade vai deixar de existir. (Sal 37:10, 11)

            Serão eliminadas a doença e a morte, e também a tristeza que elas causam. (Is 25:8; 33:24; Ap 21:3, 4)

            Deus vai reverter o processo de envelhecimento; os idosos vão ser jovens novamente.
            (Jó 33:24, 25)

            A humanidade vai viver em paz e ter boa qualidade de vida. (Sal 72:7, 16)

            A vida eterna não vai ser entediante.

            Nós vamos ter infinitas oportunidades de estudar sobre a criação, viajar, aprender novas habilidades e desenvolver amizades.

            Imagine a alegria de nos encontrar com nossos antepassados que forem ressuscitados.

            E o mais importante, vamos continuar aprendendo sobre Jeová e nos achegar cada vez mais a ele. (Ro 11:33)

            VOCÊ VAI VIVER PARA SEMPRE? (4 min)

            Jeová e seu Filho tornaram possível que nós vivêssemos para sempre, mas cada um de nós deve escolher se vai aceitar o amoroso presente que é o resgate.

            Deus promete dar vida eterna aos que exercem fé no resgate.
            (Jo 3:36; Sentinela número 2 de 2017 pág. 7 parág. 1)

            Faça com que a coisa mais importante da sua vida seja obter o conhecimento que vai levar você à vida eterna. (Jo 17:3)

            [Convide os novos a obter conhecimento exato da Bíblia por assistirem regularmente às reuniões da congregação e por aceitarem um estudo da Bíblia.]

            [Siga de perto o material do esboço e observe o tempo que cada seção deve ter. Não há necessidade de ler todos os textos citados nem de fazer comentários sobre eles. Veja o livro Beneficie-se, páginas 52-55, 166-169.]

            TEMPO TOTAL: 30 MINUTOS

            © 2020 Watch Tower Bible and Tract Society of Pennsylvania

            S-34-T N.º 35 5/20
        """.trimIndent()
    }
}
