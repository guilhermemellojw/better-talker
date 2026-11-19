package com.bettertalker.app.ui.copilot

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 1C.2.1B2C — diálogo Compose: ações explícitas, estados e acessibilidade. */
@RunWith(AndroidJUnit4::class)
class DecisionSupersessionDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun decision(
        id: String,
        text: String,
        createdAt: Long,
    ): DecisionRecord = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = SectionLink.VALID,
        applicationState = ApplicationState.NOT_APPLIED,
        text = text,
        source = DecisionSource.STRUCTURE_ACCEPT,
        sectionId = "s1",
        originalSectionId = "s1",
        sectionTitleSnapshot = "Ponto 1",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 1,
        sectionContentHashSnapshot = "hash-s1",
        originalSectionTitleSnapshot = "Ponto 1",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-s1",
        subjectKey = DecisionSubject.SECTION_APPROACH.key,
        sourceProposalId = "prop-$id",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun prompt(
        isSubmitting: Boolean = false,
        outcome: DecisionSupersessionOutcome? = null,
        message: String? = null,
    ): DecisionSupersessionPrompt = DecisionSupersessionPrompt(
        confirmationId = "supconf-b-a",
        noteId = "n1",
        previous = decision("a", "Começar com uma pergunta.", 10L),
        replacement = decision("b", "Começar com uma experiência.", 20L),
        isSubmitting = isSubmitting,
        outcome = outcome,
        message = message,
    )

    private fun setDialog(
        prompt: DecisionSupersessionPrompt,
        onKeepBoth: () -> Unit = {},
        onReplace: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDismissOutcome: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                DecisionSupersessionDialog(
                    prompt = prompt,
                    onKeepBoth = onKeepBoth,
                    onReplace = onReplace,
                    onCancel = onCancel,
                    onRefresh = onRefresh,
                    onRetry = onRetry,
                    onDismissOutcome = onDismissOutcome,
                )
            }
        }
    }

    @Test
    fun perguntaMostraTextosReaisEOpcoes() {
        var keep = 0
        var replace = 0
        var cancel = 0
        setDialog(
            prompt(),
            onKeepBoth = { keep++ },
            onReplace = { replace++ },
            onCancel = { cancel++ },
        )

        composeRule.onNodeWithText("Quer substituir uma decisão anterior?").assertIsDisplayed()
        composeRule.onNodeWithText("Antes: “Começar com uma pergunta.”").assertIsDisplayed()
        composeRule.onNodeWithText("Agora: “Começar com uma experiência.”").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Essa escolha organiza suas decisões salvas.",
            substring = true,
        ).assertIsDisplayed()
        // A memória ainda não é injetada no prompt do Mentor: não prometer isso.
        composeRule.onNodeWithText("Mentor lembra", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Manter as duas").performClick()
        composeRule.onNodeWithText("Usar a nova no lugar da anterior").performClick()
        composeRule.onNodeWithText("Agora não").performClick()

        composeRule.runOnIdle {
            assertEquals(1, keep)
            assertEquals(1, replace)
            assertEquals(1, cancel)
        }
    }

    @Test
    fun duranteSalvamentoNaoOfereceAcoesNemFecha() {
        setDialog(prompt(isSubmitting = true))

        composeRule.onNodeWithText("Salvando sua escolha…").assertIsDisplayed()
        composeRule.onNodeWithText("Manter as duas").assertDoesNotExist()
        composeRule.onNodeWithText("Usar a nova no lugar da anterior").assertDoesNotExist()
        composeRule.onNodeWithText("Agora não").assertDoesNotExist()
    }

    @Test
    fun dadosDesatualizadosOferecemAtualizarSemSubstituir() {
        var refresh = 0
        var cancel = 0
        var replace = 0
        setDialog(
            prompt(
                outcome = DecisionSupersessionOutcome.STALE,
                message = "A decisão anterior mudou desde que esta pergunta apareceu. " +
                    "Atualize os dados e tente de novo.",
            ),
            onRefresh = { refresh++ },
            onCancel = { cancel++ },
            onReplace = { replace++ },
        )

        composeRule.onNodeWithText("Atualizar").performClick()
        composeRule.onNodeWithText("Agora não").performClick()
        composeRule.onNodeWithText("Usar a nova no lugar da anterior").assertDoesNotExist()
        composeRule.onNodeWithText("Manter as duas").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, refresh)
            assertEquals(1, cancel)
            assertEquals(0, replace)
        }
    }

    @Test
    fun falhaInesperadaOfereceTentarNovamenteSemPrometerSubstituicao() {
        var retry = 0
        var cancel = 0
        setDialog(
            prompt(
                outcome = DecisionSupersessionOutcome.ERROR,
                message = "Não consegui concluir agora. As duas decisões continuam salvas.",
            ),
            onRetry = { retry++ },
            onCancel = { cancel++ },
        )

        composeRule.onNodeWithText(
            "Não consegui concluir agora. As duas decisões continuam salvas.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Tentar novamente").performClick()
        composeRule.onNodeWithText("Agora não").performClick()

        composeRule.runOnIdle {
            assertEquals(1, retry)
            assertEquals(1, cancel)
        }
    }

    @Test
    fun sucessoExplicaQueSoAMemoriaMudou() {
        var dismissed = 0
        setDialog(
            prompt(
                outcome = DecisionSupersessionOutcome.SUCCESS,
                message = "Pronto. A nova decisão agora fica no lugar da anterior " +
                    "nas decisões salvas. O texto do discurso não foi alterado.",
            ),
            onDismissOutcome = { dismissed++ },
        )

        composeRule.onNodeWithText(
            "Pronto. A nova decisão agora fica no lugar da anterior " +
                "nas decisões salvas. O texto do discurso não foi alterado.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun todoControleTemRotuloParaLeitorDeTela() {
        setDialog(prompt())

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
    }

    @Test
    fun mudancaDeEstadoEhAnunciadaComoRegiaoViva() {
        val message = "Pronto. A nova decisão agora vale no lugar da anterior."
        setDialog(
            prompt(outcome = DecisionSupersessionOutcome.SUCCESS, message = message),
        )

        composeRule.onNodeWithText(message, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fonteAmpliadaMantemTodasAsOpcoesDisponiveis() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    DecisionSupersessionDialog(
                        prompt = prompt(),
                        onKeepBoth = {},
                        onReplace = {},
                        onCancel = {},
                        onRefresh = {},
                        onRetry = {},
                        onDismissOutcome = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Quer substituir uma decisão anterior?").assertExists()
        composeRule.onNodeWithText("Manter as duas").assertExists()
        composeRule.onNodeWithText("Usar a nova no lugar da anterior").assertExists()
        composeRule.onNodeWithText("Agora não").assertExists()
    }
}
