package com.bettertalker.app.ui.copilot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 1C.2.2A — conteúdo Compose somente leitura, estados e acessibilidade. */
@RunWith(AndroidJUnit4::class)
class DecisionMemorySheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun item(
        id: String,
        text: String,
        status: String,
        tone: DecisionMemoryTone = DecisionMemoryTone.ACTIVE,
        section: String = "Ponto 1",
        origin: String = "Aceite de estrutura",
        application: String = "Texto do discurso não alterado",
        replacesText: String? = null,
        replacedByText: String? = null,
    ) = DecisionMemoryItemUi(
        id = id,
        createdAt = 10L,
        text = text,
        sectionLabel = section,
        statusLabel = status,
        tone = tone,
        originLabel = origin,
        dateLabel = "19/11/2026",
        applicationLabel = application,
        replacesText = replacesText,
        replacedByText = replacedByText,
    )

    private fun state(
        active: List<DecisionMemoryItemUi> = emptyList(),
        attention: List<DecisionMemoryItemUi> = emptyList(),
        history: List<DecisionMemoryItemUi> = emptyList(),
        loading: Boolean = false,
        error: String? = null,
    ) = DecisionMemoryUiState(
        active = active,
        attention = attention,
        history = history,
        isLoading = loading,
        error = error,
    )

    private fun setContent(
        state: DecisionMemoryUiState,
        noteTitle: String = "Meu discurso",
        onClose: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    DecisionMemoryContent(
                        state = state,
                        noteTitle = noteTitle,
                        onClose = onClose,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    @Test
    fun vazioExplicaSemDecisoesEMostraLimitacaoHonesta() {
        setContent(state())

        composeRule.onNodeWithText("Memória do Mentor").assertIsDisplayed()
        composeRule.onNodeWithText("Meu discurso").assertIsDisplayed()
        composeRule.onNodeWithText("Ainda não há decisões salvas para este discurso.")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "As decisões salvas aparecem aqui.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun listaAtivasAtencaoEHistoricoComRotulosEmTexto() {
        setContent(
            state(
                active = listOf(
                    item("a", "Começar com uma pergunta.", "Ativa"),
                ),
                attention = listOf(
                    item(
                        "b",
                        "Usar uma ilustração.",
                        "Sem seção",
                        tone = DecisionMemoryTone.ATTENTION,
                        section = "Seção removida",
                    ),
                ),
                history = listOf(
                    item(
                        "c",
                        "Começar com uma história.",
                        "Substituída",
                        tone = DecisionMemoryTone.HISTORY,
                        replacedByText = "Começar com uma pergunta.",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Ativas (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Precisam de atenção (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Histórico (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Começar com uma pergunta.").assertIsDisplayed()
        composeRule.onNodeWithText("Sem seção").assertIsDisplayed()
        composeRule.onNodeWithText("Substituída").assertIsDisplayed()
    }

    @Test
    fun detalheMostraTextoOrigemAplicacaoERelacaoDeSubstituicao() {
        setContent(
            state(
                history = listOf(
                    item(
                        "a",
                        "Começar com uma história.",
                        "Substituída",
                        tone = DecisionMemoryTone.HISTORY,
                        replacedByText = "Começar com uma pergunta.",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Começar com uma história.").performClick()

        composeRule.onNodeWithText("Detalhes da decisão").assertIsDisplayed()
        composeRule.onNodeWithText("Registrada em").assertIsDisplayed()
        composeRule.onNodeWithText("19/11/2026").assertIsDisplayed()
        composeRule.onNodeWithText("Substituída por").assertIsDisplayed()
        composeRule.onNodeWithText("Começar com uma pergunta.").assertIsDisplayed()

        composeRule.onNodeWithText("Fechar").performClick()
        composeRule.onNodeWithText("Detalhes da decisão").assertDoesNotExist()
    }

    @Test
    fun origemLegadaEEstadoDeAtencaoAparecemSemInventarUsuario() {
        setContent(
            state(
                attention = listOf(
                    item(
                        "a",
                        "Registro antigo.",
                        "Aplicação desatualizada",
                        tone = DecisionMemoryTone.ATTENTION,
                        origin = "Registro anterior",
                        application = "O texto mudou depois da aplicação",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Registro anterior • 19/11/2026").assertIsDisplayed()
        composeRule.onNodeWithText("O texto mudou depois da aplicação").assertIsDisplayed()
        composeRule.onNodeWithText("Aplicação desatualizada").assertIsDisplayed()
    }

    @Test
    fun textoLongoNaoQuebraALista() {
        val longText = "Uma decisão muito longa ".repeat(40)
        setContent(
            state(active = listOf(item("a", longText, "Ativa"))),
        )

        composeRule.onNodeWithText(longText).assertExists()
    }

    @Test
    fun naoExistemAcoesDeEscrita() {
        setContent(
            state(active = listOf(item("a", "Começar com uma pergunta.", "Ativa"))),
        )

        for (label in listOf(
            "Corrigir",
            "Remover",
            "Remover da memória",
            "Reconfirmar",
            "Restaurar",
            "Limpar memória",
            "Aplicar decisão",
        )) {
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun controlesTemRotuloParaLeitorDeTela() {
        setContent(
            state(active = listOf(item("a", "Começar com uma pergunta.", "Ativa"))),
        )

        val controls = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(controls.isNotEmpty())
        controls.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            val descriptions =
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue(
                "controle sem rótulo acessível: $node",
                texts.isNotEmpty() || descriptions.isNotEmpty(),
            )
        }
        composeRule.onNodeWithContentDescription("Fechar memória").assertExists()
    }

    @Test
    fun fecharNaoAlteraEstado() {
        var closed = 0
        setContent(
            state(active = listOf(item("a", "Começar com uma pergunta.", "Ativa"))),
            onClose = { closed++ },
        )

        composeRule.onNodeWithContentDescription("Fechar memória").performClick()

        composeRule.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun erroPermiteTentarNovamenteSemEscrita() {
        var retries = 0
        setContent(
            state(error = "Não consegui carregar as decisões agora."),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("Não consegui carregar as decisões agora.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Tentar novamente").performClick()

        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun recomposicaoAtualizaListaSemAcaoDeEscrita() {
        var current by mutableStateOf(state())
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    DecisionMemoryContent(
                        state = current,
                        noteTitle = "Meu discurso",
                        onClose = {},
                        onRetry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Ainda não há decisões salvas para este discurso.")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            current = state(active = listOf(item("a", "Começar com uma pergunta.", "Ativa")))
        }
        composeRule.onNodeWithText("Começar com uma pergunta.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fonteAmpliadaMantemConteudoUtilizavel() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        DecisionMemoryContent(
                            state = state(
                                active = listOf(item("a", "Começar com uma pergunta.", "Ativa")),
                                history = listOf(
                                    item(
                                        "b",
                                        "Começar com uma história.",
                                        "Substituída",
                                        tone = DecisionMemoryTone.HISTORY,
                                    ),
                                ),
                            ),
                            noteTitle = "Meu discurso",
                            onClose = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Memória do Mentor").assertExists()
        composeRule.onNodeWithText("Começar com uma pergunta.").assertExists()
        composeRule.onNodeWithText("Ativas (1)").assertExists()
    }
}
