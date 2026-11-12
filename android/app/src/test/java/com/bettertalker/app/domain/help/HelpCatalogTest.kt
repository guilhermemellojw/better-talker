package com.bettertalker.app.domain.help

import com.bettertalker.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpCatalogTest {

    @Test
    fun catalogoTemQuatorzeTopicosComAcoes() {
        assertEquals(14, HelpCatalog.topics.size)
        assertEquals(14, HelpCatalog.topics.map { it.id }.distinct().size)
        assertTrue(HelpCatalog.topics.all { it.question.isNotBlank() })
        assertTrue(HelpCatalog.topics.all { it.shortAnswer.isNotBlank() })
        assertTrue(HelpCatalog.topics.all { it.steps.size in 2..4 })
    }

    @Test
    fun filtroPorRotaMostraEspecificosEGeraIs() {
        val home = HelpEngine.topicsFor(
            AssistantHelpContext(route = Routes.HOME, source = HelpSource.HELP_BUTTON)
        )
        assertTrue(home.any { it.id == "importar-s34" })
        assertTrue(home.any { it.id == "primeira-nota" })
        val train = HelpEngine.topicsFor(
            AssistantHelpContext(route = Routes.TRAIN_HOME, source = HelpSource.HELP_BUTTON)
        )
        assertTrue(train.any { it.id == "treino" })
        assertTrue(train.none { it.id == "importar-s34" })
    }

    @Test
    fun buscaEhLocalEIgnoraAcentosEMaiusculas() {
        val found = HelpEngine.topicsFor(
            AssistantHelpContext(route = Routes.HOME, source = HelpSource.HELP_BUTTON),
            query = "IMAGENS",
        )
        assertTrue(found.any { it.id == "imagens" })
        val accented = HelpEngine.topicsFor(
            AssistantHelpContext(route = Routes.HOME, source = HelpSource.HELP_BUTTON),
            query = "referencias",
        )
        assertTrue(accented.any { it.id == "fontes" })
    }

    @Test
    fun topicosDeNotaDeclaramDependencia() {
        assertTrue(HelpCatalog.topic("imagens")?.requiresNote == true)
        assertTrue(HelpCatalog.topic("tribuna")?.requiresNote == true)
        assertTrue(HelpCatalog.topic("primeira-nota")?.requiresNote == false)
    }

    @Test
    fun topicosNovosDoSeisApontamParaContaEDados() {
        assertEquals(
            HelpAction.OPEN_ACCOUNT,
            HelpCatalog.topic("sincronizacao-privacidade")?.action,
        )
        assertEquals(
            HelpAction.OPEN_DATA_STORAGE,
            HelpCatalog.topic("backup-restauracao")?.action,
        )
        assertTrue(HelpCatalog.topic("fontes")?.keywords?.contains("leitor") == true)
    }

    @Test
    fun buscaPorLeitorEncontraTopicosDeFontes() {
        val found = HelpEngine.topicsFor(
            AssistantHelpContext(route = Routes.EDITOR, source = HelpSource.HELP_BUTTON),
            query = "leitor",
        )
        assertTrue(found.any { it.id == "fontes" })
    }

    @Test
    fun erroDeChavePriorizaTopicoDeChaveMesmoEmOutraRota() {
        val topics = HelpEngine.topicsFor(
            AssistantHelpContext(
                route = Routes.PRESENT_HOME,
                errorKind = HelpErrorKind.NO_KEY,
                source = HelpSource.ERROR_ACTION,
            )
        )
        assertEquals("chave-ia", topics.first().id)
    }
}
