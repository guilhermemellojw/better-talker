package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.RefDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1 — histórico fiel: cada kind persistido vira texto legível.
 */
class ChatHistoryTextTest {

    @Test
    fun textoSimplesVoltaComoTexto() {
        val payload = ChatCodec.escMap(mapOf("text" to "Vamos desenvolver o ponto 2."))
        assertEquals("Vamos desenvolver o ponto 2.", readableChatTurn("text", payload))
    }

    @Test
    fun textoCanceladoViraInterrompidoNoHistorico() {
        val payload = ChatCodec.escMap(
            mapOf("text" to "Boa — mas primeiro um ajuste de rota.", "cancelled" to "1"),
        )
        val text = readableChatTurn("text", payload)
        assertTrue(text.startsWith("Copilot (interrompido):"))
        assertTrue(text.contains("Boa — mas primeiro"))
    }

    @Test
    fun textoCanceladoSemParcialNaoViraRespostaVazia() {
        val payload = ChatCodec.escMap(mapOf("text" to "", "cancelled" to "1"))
        assertEquals(
            "Copilot (interrompido antes de gerar texto)",
            readableChatTurn("text", payload),
        )
    }

    @Test
    fun ideiasViramTitulosComMarcador() {
        val cards = listOf(
            IdeaCard("Pergunta inicial", "corpo", "", "", "fonte", "Introdução"),
            IdeaCard("Aplicação prática", "corpo", "", "", "fonte", "Conclusão"),
        )
        val payload = ChatCodec.escMap(mapOf(
            "section" to "Introdução",
            "cards" to ChatCodec.cardsToJson(cards),
        ))
        val text = readableChatTurn("ideas", payload)
        assertTrue(text.contains("Pergunta inicial"))
        assertTrue(text.contains("Aplicação prática"))
        assertTrue(text.contains("(ideias)"))
        assertFalse(text == "ideas")
    }

    @Test
    fun refsViramRotulos() {
        val detected = listOf(
            RefDetector.DetectedRef(
                raw = "w25.07 § 8",
                kind = RefDetector.Kind.MAGAZINE,
                pubKey = "w",
                editionKey = "w|2025|7",
                label = "A Sentinela julho de 2025",
                page = 22,
                paragraph = 8,
            ),
            RefDetector.DetectedRef(
                raw = "lmd lição 7 ponto 4",
                kind = RefDetector.Kind.BOOK,
                pubKey = "lmd",
                editionKey = "book|lmd",
                label = "Apostila",
                chapter = "lição 7",
                paragraph = 4,
            ),
        )
        val payload = ChatCodec.escMap(mapOf(
            "title" to "Referências da nota",
            "detected" to RefDetector.detectedToJson(detected),
        ))
        val text = readableChatTurn("refs", payload)
        assertTrue(text.contains("w25.07 § 8"))
        assertTrue(text.contains("lmd lição 7 ponto 4"))
        assertFalse(text == "refs")
    }

    @Test
    fun sectionsViramTitulos() {
        val payload = ChatCodec.escMap(mapOf(
            "title" to "Esboço — toque em Gerar:",
            "items" to ChatCodec.sectionsToJson(listOf(
                ChatCodec.SecItem("Introdução", 3),
                ChatCodec.SecItem("Desenvolvimento", 10),
            )),
        ))
        val text = readableChatTurn("sections", payload)
        assertTrue(text.contains("Introdução"))
        assertTrue(text.contains("Desenvolvimento"))
        assertTrue(text.contains("(seções)"))
    }

    @Test
    fun draftViraTituloEPrimeirasLinhas() {
        val payload = ChatCodec.draftToJson("Rascunho", listOf(
            ChatCodec.DraftItem(
                title = "Ponto 1",
                minutes = 4,
                included = true,
                body = "Primeira linha do texto.\nSegunda linha.",
            ),
        ))
        val text = readableChatTurn("draft", payload)
        assertTrue(text.contains("Rascunho"))
        assertTrue(text.contains("Ponto 1"))
        assertTrue(text.contains("Primeira linha do texto."))
    }

    @Test
    fun kindDesconhecidoNuncaViraTexto() {
        val payload = ChatCodec.escMap(mapOf("text" to "conteúdo real"))
        assertEquals("conteúdo real", readableChatTurn("kind_novo", payload))
        assertTrue(readableChatTurn("kind_sem_texto", "{}").isEmpty())
    }

    @Test
    fun rotuloDeFocoDistingueTopicoEDiscursoInteiro() {
        assertEquals("Discurso inteiro", chatFocusLabel(null, null))
        assertEquals("Tópico: Introdução", chatFocusLabel("sec-1", "Introdução"))
        assertEquals("Tópico: seção", chatFocusLabel("sec-2", null))
    }

    @Test
    fun turnoRotuladoMantemPerguntaEPermiteContinuidade() {
        val labeled = labelChatTurn(
            "Quer que eu escreva o bloco nessa linha?",
            "sec-1",
            "Ponto 2",
        )
        assertTrue(labeled.startsWith("[Tópico: Ponto 2]"))
        assertTrue(labeled.contains("Quer que eu escreva o bloco nessa linha?"))
    }

    @Test
    fun fioUnicoMantemTurnosDeFocosDiferentes() {
        val turns = listOf(
            labelChatTurn("Pergunta no ponto 1", "s1", "Introdução"),
            labelChatTurn("Resposta no ponto 2", "s2", "Desenvolvimento"),
        )
        assertTrue(turns[0].startsWith("[Tópico: Introdução]"))
        assertTrue(turns[1].startsWith("[Tópico: Desenvolvimento]"))
        assertTrue(turns.joinToString("\n").contains("Pergunta no ponto 1"))
        assertTrue(turns.joinToString("\n").contains("Resposta no ponto 2"))
    }

    // ---------- 1C.1: cards do Mentor no histórico ----------

    private fun reviewReport(
        improvements: List<String> = listOf(
            "Desenvolva o subponto 1.",
            "Explique a ligação entre as ideias.",
            "Inclua uma aplicação concreta.",
        ),
        strengths: List<String> = listOf("O ponto tem 11 subpontos."),
        qualityStatus: PointQualityStatus = PointQualityStatus.NONE,
        quality: PointQualityReview? = null,
    ) = PointReviewReport(
        sectionId = "section-2",
        pointNumber = 2,
        title = "A ORGANIZAÇÃO DE JEOVÁ É UNIDA",
        minutes = 8,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 2 · A ORGANIZAÇÃO DE JEOVÁ É UNIDA · 8 min · 96 palavra(s)",
        strengths = strengths,
        improvements = improvements,
        nextStep = PointReviewNextStep.OpenEditor("section-2", "A ORGANIZAÇÃO DE JEOVÁ É UNIDA", 2),
        generatedAt = 1L,
        quality = quality,
        qualityStatus = qualityStatus,
    )

    private fun reviewPayload(report: PointReviewReport, text: String = "Resumo antigo") =
        ChatCodec.escMap(
            mapOf(
                "report" to PointReviewCodec.encode(report),
                "text" to text,
            )
        )

    private fun coachingReport() = PointCoachingReport(
        sectionId = "section-1",
        pointNumber = 1,
        title = "JEOVÁ É UM DEUS DE ORGANIZAÇÃO",
        minutes = 6,
        overall = DiagnosisLevel.ATTENTION,
        reading = "Ponto 1 · 6 min",
        findings = listOf(
            PointCoachingFinding("Subpontos", DiagnosisLevel.ATTENTION, "Faltam dois subpontos."),
            PointCoachingFinding("Fontes", DiagnosisLevel.OK, "As fontes estão resolvidas."),
        ),
        steps = listOf(
            "Abra o primeiro subponto.",
            "Explique a ordem nos céus.",
            "Feche com uma aplicação prática.",
        ),
        nextStep = PointCoachingNextStep.OpenEditor("section-1", "JEOVÁ É UM DEUS", 1),
        generatedAt = 1L,
    )

    private fun diagnosisReport() = OutlineDiagnosis(
        overall = DiagnosisLevel.ATTENTION,
        headline = "Esboço com pontos de atenção",
        summary = "Resumo do diagnóstico",
        s34Status = DiagnosisS34Status.LINKED,
        s34Detail = "S-34 de teste",
        criteria = emptyList(),
        points = listOf(
            DiagnosisPoint("s1", 1, "JEOVÁ É UM DEUS DE ORGANIZAÇÃO", 6, DiagnosisLevel.OK, "OK", "Abrir"),
            DiagnosisPoint("s2", 2, "A ORGANIZAÇÃO É UNIDA", 8, DiagnosisLevel.ATTENTION, "Falta aplicação", "Abrir"),
        ),
        priorities = listOf("Prioridade 1: desenvolver o ponto 2.", "Prioridade 2: revisar as fontes."),
        nextStep = DiagnosisNextStep.OpenNote("Abrir nota"),
        generatedAt = 1L,
    )

    @Test
    fun revisaoEstruturalViraResumoNumeradoEProtegido() {
        val text = readableChatTurn("review", reviewPayload(reviewReport()))
        assertTrue(text.contains("Card do Mentor — revisão do Ponto 2"))
        assertTrue(text.contains("não fonte factual"))
        assertTrue(text.contains("1) Desenvolva o subponto 1."))
        assertTrue(text.contains("2) Explique a ligação entre as ideias."))
        assertTrue(text.contains("3) Inclua uma aplicação concreta."))
        assertTrue(text.length <= MAX_CARD_HISTORY_CHARS)
        assertFalse(text.trimEnd().endsWith("2)"))
        assertFalse(text.trimEnd().endsWith("3)"))
    }

    @Test
    fun revisaoQualitativaConcluidaUsaSomenteAsObservacoesDoCard() {
        val quality = PointQualityReview(
            positives = listOf(PointQualityPositive("A abertura prende o ouvinte.")),
            improvements = listOf(
                PointQualityImprovement(
                    PointQualityCategory.APLICACAO,
                    "A aplicação ficou genérica.",
                    "O ouvinte não sabe o que fazer.",
                ),
                PointQualityImprovement(
                    PointQualityCategory.ESCRITURAS,
                    "A referência bíblica ficou implícita.",
                    "Falta indicar de onde vem o ensino.",
                ),
            ),
        )
        val text = readableChatTurn(
            "review",
            reviewPayload(
                reviewReport(
                    improvements = listOf("Melhoria estrutural que não deve aparecer."),
                    qualityStatus = PointQualityStatus.COMPLETED,
                    quality = quality,
                )
            ),
        )
        assertTrue(text.contains("2) Escrituras: A referência bíblica ficou implícita."))
        assertTrue(text.contains("1) Aplicação: A aplicação ficou genérica."))
        assertFalse(text.contains("Melhoria estrutural que não deve aparecer."))
        assertFalse(text.contains("3)"))
        assertTrue(text.contains("A abertura prende o ouvinte."))
    }

    @Test
    fun revisaoQualitativaPendenteUsaEstruturalSemAfirmarConclusao() {
        val text = readableChatTurn(
            "review",
            reviewPayload(
                reviewReport(
                    qualityStatus = PointQualityStatus.PENDING,
                    quality = null,
                )
            ),
        )
        assertTrue(text.contains("2) Explique a ligação entre as ideias."))
        assertFalse(text.contains("qualitativa concluída"))
        assertFalse(text.contains("Escrituras:"))
    }

    @Test
    fun revisaoSemMelhoriasInformaSemInventar() {
        val text = readableChatTurn(
            "review",
            reviewPayload(reviewReport(improvements = emptyList(), strengths = emptyList())),
        )
        assertTrue(text.contains("Melhorias: nenhum item registrado."))
        assertFalse(text.contains("1)"))
    }

    @Test
    fun coachingPreservaPassosNumeradosEAchados() {
        val payload = ChatCodec.escMap(
            mapOf(
                "report" to PointCoachingCodec.encode(coachingReport()),
                "text" to "Resumo antigo",
            )
        )
        val text = readableChatTurn("coaching", payload)
        assertTrue(text.contains("Card do Mentor — coaching do Ponto 1"))
        assertTrue(text.contains("1) Abra o primeiro subponto."))
        assertTrue(text.contains("2) Explique a ordem nos céus."))
        assertTrue(text.contains("3) Feche com uma aplicação prática."))
        assertTrue(text.length <= MAX_CARD_HISTORY_CHARS)
    }

    @Test
    fun diagnosticoPreservaPrioridadesEPontos() {
        val payload = ChatCodec.escMap(
            mapOf(
                "report" to OutlineDiagnosisCodec.encode(diagnosisReport()),
                "text" to "Resumo antigo",
            )
        )
        val text = readableChatTurn("diagnosis", payload)
        assertTrue(text.contains("Card do Mentor — diagnóstico do esboço"))
        assertTrue(text.contains("1) Prioridade 1: desenvolver o ponto 2."))
        assertTrue(text.contains("2) Prioridade 2: revisar as fontes."))
        assertTrue(text.contains("Ponto 2: A ORGANIZAÇÃO É UNIDA"))
        assertTrue(text.length <= MAX_CARD_HISTORY_CHARS)
    }

    @Test
    fun ideiasPreservamTituloEConteudo() {
        val cards = listOf(
            IdeaCard("Pergunta inicial", "Comece com uma pergunta que prenda o ouvinte.", "", "", "fonte", "Introdução"),
            IdeaCard("Cena cotidiana", "Use uma cena simples do dia a dia para explicar a união.", "", "", "fonte", "Introdução"),
        )
        val payload = ChatCodec.escMap(
            mapOf(
                "section" to "Introdução",
                "cards" to ChatCodec.cardsToJson(cards),
                "text" to "Resumo antigo",
            )
        )
        val text = readableChatTurn("ideas", payload)
        assertTrue(text.contains("(ideias)"))
        assertTrue(text.contains("1) Pergunta inicial"))
        assertTrue(text.contains("2) Cena cotidiana"))
        assertTrue(text.contains("Use uma cena simples do dia a dia"))
        assertTrue(text.contains("não fonte factual"))
        assertTrue(text.length <= MAX_CARD_HISTORY_CHARS)
    }

    @Test
    fun payloadInvalidoMantemResumoAntigo() {
        val payload = ChatCodec.escMap(
            mapOf("report" to "lixo", "text" to "Resumo antigo do card")
        )
        assertEquals("Resumo antigo do card", readableChatTurn("review", payload))
        assertEquals("Resumo antigo do card", readableChatTurn("coaching", payload))
        assertEquals("Resumo antigo do card", readableChatTurn("diagnosis", payload))
    }

    @Test
    fun resumoDoCardSobreviveAoHistoricoRealComSegundaEMelhorias() {
        val card = readableChatTurn("review", reviewPayload(reviewReport()))
        val turn = ChatTurn(false, labelChatTurn(card, null, null))
        val history = recentHistory(listOf(turn))
        assertTrue(history.contains("2) Explique a ligação entre as ideias."))
        assertTrue(history.contains("3) Inclua uma aplicação concreta."))
        assertTrue(history.contains("revisão do Ponto 2"))
        assertTrue(turn.text.length <= MAX_HISTORY_CHARS)
    }

    @Test
    fun listaLongaDoCopilotPreservaSegundaIdeiaNoHistorico() {
        val text = buildString {
            appendLine("Boa — vamos cuidar da introdução. Só um ajuste de foco antes:")
            appendLine("1.")
            appendLine("Abra com uma pergunta que cria curiosidade sobre a organização.")
            appendLine("2.")
            appendLine("Use o contraste ordem/desordem com 1Co 14:33.")
            appendLine("3.")
            appendLine("Puxe do título do discurso e anuncie os três pontos.")
            appendLine("4.")
            appendLine("Use uma ilustração do corpo humano, marcada como sua.")
            appendLine("5.")
            appendLine("Ancore na criação com Jó 38:31-33.")
            appendLine("Minha recomendação: combine a ideia 3 com a 2.")
            appendLine("X".repeat(200))
        }
        val compact = compactAssistantListForHistory(text)
        assertTrue(compact.length <= MAX_CARD_HISTORY_CHARS)
        assertTrue(compact.contains("1)"))
        assertTrue(compact.contains("2)"))
        assertTrue(compact.contains("contraste ordem/desordem"))
        assertTrue(compact.contains("3)"))
        assertFalse(compact.trimEnd().endsWith("3)"))

        val history = recentHistory(listOf(ChatTurn(false, compact)))
        assertTrue(history.contains("contraste ordem/desordem"))
    }

    @Test
    fun listaMarkdownDoCopilotPreservaSegundaIdeia() {
        // Formato real do caminho remoto no A34: itens em negrito.
        val text = """
            Boa — vamos cuidar da introdução. Só um ajuste de foco antes: a
            introdução é curta (1 min), então ela precisa prender a atenção.

            Aqui vão ideias para você escolher:

            **1. Abra com uma pergunta que cria curiosidade.** 〈sugestão〉Algo como:
            "Você já parou para pensar em quantas coisas precisam funcionar juntas
            para que uma única reunião aconteça?"〈/sugestão〉 Depois mostre a organização.

            **2. Use o contraste ordem/desordem.** Você tem 1Co 14:33 e 14:40 no
            dossiê. Comece descrevendo uma cena de bagunça e pergunte: "Isso parece
            com Jeová?"

            **3. Puxe do título.** 〈sugestão〉"A pergunta que vamos responder hoje é
            simples: você está avançando com a organização unida de Jeová?"〈/sugestão〉

            **4. Ilustração do corpo.** Mencione como o corpo humano funciona.

            **5. Ancore na criação.** Observe que até as estrelas seguem leis.

            Minha recomendação: combine a ideia 3 com a 2, abrindo com a pergunta.
        """.trimIndent()
        val compact = compactAssistantListForHistory(text)
        assertTrue(compact.length <= MAX_CARD_HISTORY_CHARS)
        assertTrue(compact.contains("1)"))
        assertTrue(compact.contains("2)"))
        assertTrue(compact.contains("contraste ordem/desordem"))
        assertTrue(compact.contains("3)"))
        assertFalse(compact.contains("**"))
        assertFalse(compact.contains("sugestão〉"))
        assertFalse(compact.trimEnd().endsWith("3)"))
    }

    @Test
    fun listaCurtaOuSemNumerosNaoMuda() {
        val short = "1. Primeira\n2. Segunda"
        assertEquals(short, compactAssistantListForHistory(short))
        val prose = "A".repeat(1000)
        assertEquals(prose, compactAssistantListForHistory(prose))
        val oneItem = "1. Item único\n" + "A".repeat(600)
        assertEquals(oneItem, compactAssistantListForHistory(oneItem))
    }

    @Test
    fun promptEfetivoRecebeASegundaMelhoriaDoCard() {
        val card = readableChatTurn("review", reviewPayload(reviewReport()))
        val turn = ChatTurn(false, labelChatTurn(card, null, null))
        val prompt = buildChatPrompt(
            message = "Não concordo com a segunda melhoria. Por quê?",
            history = listOf(turn),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "A ORGANIZAÇÃO DE JEOVÁ É UNIDA",
            blockMinutes = 8,
            blockText = "Texto do ponto 2.",
        )
        assertTrue(prompt.contains("CONVERSA ANTERIOR"))
        assertTrue(prompt.contains("2) Explique a ligação entre as ideias."))
        assertTrue(prompt.contains("3) Inclua uma aplicação concreta."))
        assertTrue(prompt.contains("não fonte factual"))
    }

    @Test
    fun titulosEObservacoesLongosNaoCortamItemNumeradoSemConteudo() {
        val report = reviewReport(
            improvements = listOf(
                "A".repeat(300),
                "B".repeat(300),
                "C".repeat(300),
            ),
            strengths = listOf("D".repeat(300), "E".repeat(300)),
        ).copy(title = "T".repeat(300))
        val card = readableChatTurn("review", reviewPayload(report))
        val turn = ChatTurn(false, labelChatTurn(card, null, null))
        val history = recentHistory(listOf(turn))
        assertTrue(history.contains("2)"))
        assertTrue(history.contains("3)"))
        assertFalse(history.trimEnd().endsWith("3)"))
        assertTrue(card.contains("Fortes:"))
        assertTrue(card.contains("DDDD"))
        assertTrue(card.contains("não fonte factual"))
        assertTrue(card.length <= MAX_CARD_HISTORY_CHARS)
        assertTrue(turn.text.length <= MAX_HISTORY_CHARS)
    }

    @Test
    fun listaDeCincoIdeiasPreservaSegundaQuintaEAFecho() {
        val text = buildString {
            appendLine("Aqui vão ideias para você escolher:")
            appendLine("1. Abra com uma pergunta que cria curiosidade.")
            appendLine("2. Use o contraste ordem/desordem com 1Co 14:33.")
            appendLine("3. Puxe do título do discurso.")
            appendLine("4. Use uma ilustração do corpo humano.")
            appendLine("5. Ancore na criação com Jó 38:31-33.")
            appendLine("")
            appendLine("Minha recomendação: combine a ideia 2 com a 5, abrindo pelo contraste.")
            appendLine("X".repeat(200))
        }
        val compact = compactAssistantListForHistory(text)
        assertTrue(compact.length <= MAX_CARD_HISTORY_CHARS)
        assertTrue(compact.contains("2) Use o contraste ordem/desordem"))
        assertTrue(compact.contains("5) Ancore na criação"))
        assertTrue(compact.contains("Minha recomendação"))
        assertTrue(compact.contains("2 com a 5"))

        val history = recentHistory(listOf(ChatTurn(false, compact)))
        assertTrue(history.contains("2) Use o contraste"))
        assertTrue(history.contains("5) Ancore na criação"))
        assertTrue(history.contains("Minha recomendação"))
    }

    @Test
    fun perguntaFinalDoCopilotSobreviveAoResumo() {
        val text = buildString {
            appendLine("Aqui vão sugestões:")
            (1..5).forEach {
                appendLine("$it. Ideia $it com um detalhe prático e uma aplicação concreta para o ouvinte.")
            }
            appendLine("")
            appendLine("Qual dessas você quer usar na introdução?")
        }
        val compact = compactAssistantListForHistory(text)
        assertTrue(compact.contains("Qual dessas você quer usar"))
        assertTrue(compact.trimEnd().endsWith("?"))
        val history = recentHistory(listOf(ChatTurn(false, compact)))
        assertTrue(history.contains("Qual dessas você quer usar"))
    }

    @Test
    fun respostaNumeradaQueNaoEhListaDeIdeiasNaoEhCompactada() {
        val text = buildString {
            appendLine("Plano do discurso em cinco movimentos:")
            (1..5).forEach { appendLine("$it. " + "A".repeat(130)) }
            appendLine("")
            appendLine("Fim do plano.")
        }.trim()
        assertEquals(text, compactAssistantListForHistory(text))
        assertFalse(compactAssistantListForHistory(text).contains("Lista anterior do Copilot"))
        assertFalse(recentHistory(listOf(ChatTurn(false, text))).contains("Lista anterior do Copilot"))
    }

    @Test
    fun cardSeguidoDePerguntaSobreSegundaETerceiraMelhoria() {
        val card = readableChatTurn("review", reviewPayload(reviewReport()))
        val question = "Não concordo com a segunda melhoria. E a terceira?"
        val prompt = buildChatPrompt(
            message = question,
            history = listOf(
                ChatTurn(false, labelChatTurn(card, null, null)),
                ChatTurn(true, question),
            ),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "A ORGANIZAÇÃO DE JEOVÁ É UNIDA",
            blockMinutes = 8,
            blockText = "Texto do ponto 2.",
        )
        assertTrue(prompt.contains("2) Explique a ligação entre as ideias."))
        assertTrue(prompt.contains("3) Inclua uma aplicação concreta."))
        assertTrue(prompt.contains(question))
    }

    @Test
    fun tituloDeFocoLongoNaoCortaOsItensDoCard() {
        val card = readableChatTurn("review", reviewPayload(reviewReport()))
        val title = "A ORGANIZAÇÃO UNIDA DE JEOVÁ E A COOPERAÇÃO COM A ESTRUTURA CELESTIAL"
        val turn = ChatTurn(false, labelChatTurn(card, "sec-long", title))
        val history = recentHistory(listOf(turn))
        assertTrue(history.contains("2) Explique a ligação entre as ideias."))
        assertTrue(history.contains("3) Inclua uma aplicação concreta."))
        assertTrue(history.length <= MAX_HISTORY_CHARS + 200)
        assertTrue(chatFocusLabel("sec-long", title).length <= 44)
    }

    @Test
    fun listaMaiorSinalizaItensOmitidosEPreservaOUltimo() {
        val text = buildString {
            appendLine("Aqui vão ideias:")
            (1..7).forEach { appendLine("$it. Ideia $it com um detalhe.") }
            appendLine("")
            appendLine("Minha recomendação: combine a 2 com a 1.")
            appendLine("X".repeat(400))
        }
        val compact = compactAssistantListForHistory(text)
        assertTrue(compact.contains("itens omitidos"))
        assertTrue(compact.contains("7) Ideia 7"))
        assertTrue(compact.contains("2) Ideia 2"))
        assertTrue(compact.length <= MAX_CARD_HISTORY_CHARS)
    }
}
