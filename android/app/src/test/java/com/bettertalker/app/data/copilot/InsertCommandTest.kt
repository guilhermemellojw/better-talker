package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8c (GAP-9): o reconhecedor do "insira isso" aceita paráfrases
 * (verbo + referência) e bloqueia hedges — oração por oração.
 */
class InsertCommandTest {

    @Test
    fun insiraIsso_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("insira isso"))
    }

    @Test
    fun colocaIssoNoCard_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("coloca isso no card"))
    }

    @Test
    fun vamosColocar_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("vamos colocar isso no card"))
    }

    @Test
    fun hedgeAntesDaVirgula_comandoDepois_reconhece() {
        assertTrue(
            ChatRouter.isInsertCommand("acho que fechamos, vamos colocar isso no card")
        )
    }

    @Test
    fun podeInserirIssoAi_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("pode inserir isso aí"))
    }

    @Test
    fun vouColocar_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("vou colocar isso no card"))
    }

    @Test
    fun adicionaIssoNoTopico_reconhece() {
        assertTrue(ChatRouter.isInsertCommand("adiciona isso no tópico"))
    }

    @Test
    fun hedgeNaMesmaOracao_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("acho que devia colocar isso no card"))
    }

    @Test
    fun talvez_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("talvez a gente coloque isso no card"))
    }

    @Test
    fun semVerbo_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("isso está bom"))
    }

    @Test
    fun semReferencia_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("vou pensar"))
    }

    @Test
    fun textoPuroSemAnafora_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("coloca o texto"))
    }

    @Test
    fun verboSemReferencia_naoReconhece() {
        assertFalse(ChatRouter.isInsertCommand("insira bem"))
    }
}
