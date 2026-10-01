// DEMO 3.2.5b — REMOVER APÓS VALIDAÇÃO
// Fixture realista do SectionCardEditor, baseada no S-34-T N.º 35:
// 1 INTRO + 5 BODY (5/5/6/9/3 sub-pontos = 28) + 1 CONCLUSION.
// Editores vivos = 1 (INTRO) + 28 (sub-pontos) + 1 (CONCLUSION) = 30.
package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint

private const val DEMO_NOTE = "note-demo-325b"

/** Constrói a fixture da demo (pura — testável sem Compose). */
fun buildDemoSections(): List<SectionUiState> {
    val out = mutableListOf<SectionUiState>()

    out += SectionUiState(
        section = SpeechSection(
            id = "demo-intro", noteId = DEMO_NOTE, order = 0, role = SectionRole.INTRO,
            title = "Introdução", minutes = 1,
            contentHtml = "<p>“Ajude a assistência a meditar em como vai ser maravilhoso " +
                "viver para sempre.” Incentive todos a fazer o que for necessário para " +
                "ganhar a vida eterna.</p>",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0L, updatedAt = 0L,
        ),
        subPoints = emptyList(),
    )

    listOf(
        "demo-b1" to Triple("FOMOS CRIADOS PARA VIVER PARA SEMPRE", 5, listOf(
            "Os esforços humanos de interromper o processo de envelhecimento não resolvem o problema." to listOf("Sal 90:10"),
            "Jeová Deus providenciou a solução para o problema por meio do resgate." to listOf("Jo 3:16"),
            "Jesus, de vontade própria, deu sua vida para que pudéssemos viver para sempre." to listOf("Mt 20:28"),
            "Muitos vão viver para sempre em um paraíso na Terra." to listOf("Sal 37:29"),
            "Milhões vão sobreviver ao fim deste atual sistema de coisas." to listOf("Ap 7:9"),
        )),
        "demo-b2" to Triple("COMO A VIDA ETERNA FOI PERDIDA", 4, listOf(
            "Adão e Eva escolheram desobedecer a Jeová e perderam a perspectiva de viver para sempre." to listOf("Gên 3:6"),
            "O pecado de Adão trouxe a morte a toda a humanidade." to listOf("Rm 5:12"),
            "Todos nós herdamos o pecado e a imperfeição." to listOf("Sal 51:5"),
            "A morte é um inimigo que será eliminado por completo." to listOf("1 Cor 15:26"),
            "A Bíblia explica como a morte será eliminada." to listOf("Isa 25:8"),
        )),
        "demo-b3" to Triple("COMO É POSSÍVEL TER VIDA ETERNA", 9, listOf(
            "Jesus é o meio pelo qual recebemos vida eterna." to listOf("Jo 3:36"),
            "A fé em Jesus é essencial para a salvação." to listOf("At 4:12"),
            "Conhecimento exato de Deus leva à vida eterna." to listOf("Jo 17:3"),
            "O resgate abre a porta para a vida eterna." to listOf("Mt 20:28"),
            "Precisamos aceitar o amoroso presente de Deus." to listOf("Rm 6:23"),
            "A ressurreição mostra o poder de Jesus sobre a morte." to listOf("Jo 11:25"),
        )),
        "demo-b4" to Triple("SERÁ QUE A VIDA ETERNA VAI SER AGRADÁVEL?", 8, listOf(
            "A Terra será transformada em um paraíso." to listOf("Luc 23:43"),
            "Comeremos o fruto do nosso próprio trabalho." to listOf("Isa 65:21"),
            "As doenças e a dor não existirão mais." to listOf("Isa 33:24"),
            "Não haverá mais luto, nem clamor, nem dor." to listOf("Ap 21:4"),
            "O homem justo viverá em paz e segurança." to listOf("Sal 37:11"),
            "Animais e humanos viverão em harmonia." to listOf("Isa 11:6"),
            "A família e os amigos se reencontrarão." to listOf("Jo 5:28, 29"),
            "Cada dia trará alegria e novas descobertas." to listOf("Ecl 3:11"),
            "Jeová dará a vida eterna aos fiéis." to listOf("Sal 133:3"),
        )),
        "demo-b5" to Triple("VOCÊ VAI VIVER PARA SEMPRE?", 4, listOf(
            "Continue a estudar a Bíblia regularmente." to listOf("Jo 17:3"),
            "Assista às reuniões da congregação." to listOf("Heb 10:25"),
            "Compartilhe as boas novas com outros." to listOf("Mt 24:14"),
        )),
    ).forEachIndexed { i, (sectionId, spec) ->
        val (title, minutes, pointSpecs) = spec
        val section = SpeechSection(
            id = sectionId, noteId = DEMO_NOTE, order = i + 1, role = SectionRole.BODY,
            title = title, minutes = minutes, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0L, updatedAt = 0L,
        )
        val subPoints = pointSpecs.mapIndexed { p, (text, refs) ->
            SubPoint(
                id = "$sectionId-p${p + 1}", sectionId = sectionId, order = p,
                outlineText = text, bibleRefs = refs, publicationRefs = emptyList(),
                instruction = if (p == 0 && i % 2 == 0) "[Leia ${refs.first()}.]" else null,
                developedHtml = "", createdAt = 0L, updatedAt = 0L,
            )
        }
        out += SectionUiState(section = section, subPoints = subPoints)
    }

    out += SectionUiState(
        section = SpeechSection(
            id = "demo-conclusion", noteId = DEMO_NOTE, order = 6, role = SectionRole.CONCLUSION,
            title = "Conclusão", minutes = 1,
            contentHtml = "<p>Jeová promete vida eterna a quem exercer fé no resgate. " +
                "Faça com que a coisa mais importante da sua vida seja obter o conhecimento " +
                "que vai levar você à vida eterna. (Jo 17:3)</p>",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0L, updatedAt = 0L,
        ),
        subPoints = emptyList(),
    )

    return out
}
