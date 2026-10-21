package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {

    @Test
    fun markdown_incluiSystemPromptEConversaDaSecao() {
        val md = buildChatExportMarkdown(
            noteTitle = "A ordem nos céus",
            sectionTitle = "Ponto 2",
            exportedAt = "2026-10-21 14:30",
            systemPrompt = "SYSTEM PROMPT",
            dossierTruncated = false,
            messages = listOf(
                ChatExportMessage(true, "Me ajude a entender este tópico", "14:25"),
                ChatExportMessage(false, "Aqui está a resposta completa.", "14:25"),
            ),
        )

        assertTrue(md.startsWith("# Conversa — A ordem nos céus / Ponto 2"))
        assertTrue(md.contains("Exportado em: 2026-10-21 14:30"))
        assertTrue(md.contains("## System prompt (injetado antes da conversa)"))
        assertTrue(md.contains("SYSTEM PROMPT"))
        assertTrue(md.contains("## Conversa"))
        assertTrue(md.contains("**Você** (14:25):"))
        assertTrue(md.contains("**Copilot** (14:25):"))
        assertTrue(md.contains("Aqui está a resposta completa."))
    }

    @Test
    fun markdown_semSecao_usaDiscursoInteiro() {
        val md = buildChatExportMarkdown(
            noteTitle = "",
            sectionTitle = null,
            exportedAt = "2026-10-21 14:30",
            systemPrompt = "S",
            dossierTruncated = false,
            messages = emptyList(),
        )
        assertTrue(md.contains("/ Discurso inteiro"))
        assertTrue(md.contains("_Nenhuma mensagem nesta seção._"))
    }

    @Test
    fun cap_sistemaPequeno_naoMuda() {
        val capped = capExportSystemPrompt("curto", null, maxChars = 100) { "nunca" }
        assertEquals("curto", capped.text)
        assertEquals(false, capped.truncated)
    }

    @Test
    fun cap_sistemaGrande_truncaDossieEPreservaBase() {
        val base = "P".repeat(80)
        val dossier = "D".repeat(500)
        val capped = capExportSystemPrompt(
            system = base + dossier,
            dossierBlock = dossier,
            maxChars = 200,
        ) { block -> base + (block ?: "") }

        assertTrue(capped.truncated)
        assertTrue(capped.text.startsWith(base))
        assertTrue(capped.text.contains("Dossiê truncado no export"))
        assertTrue(capped.text.length <= 200)
    }

    @Test
    fun cap_semDossie_truncaSystemEAvisa() {
        val capped = capExportSystemPrompt(
            system = "X".repeat(500),
            dossierBlock = null,
            maxChars = 200,
        ) { it ?: "" }

        assertTrue(capped.truncated)
        assertTrue(capped.text.startsWith("X".repeat(200)))
        assertTrue(capped.text.contains("System prompt truncado no export"))
    }
}
