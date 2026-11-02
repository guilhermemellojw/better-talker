package com.bettertalker.app.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bettertalker.app.ui.present.PresentReadiness

/**
 * Aba "Palco": esboço atual + prontidão da Tribuna + retomada.
 *
 * A preparação (Tribuna) continua sendo o artefato; o Palco é o ao vivo.
 * Sem Tribuna → "Gerar Tribuna"; sem esboço atual → empty state + picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentHomeScreen(
    vm: HubViewModel,
    onPresent: (String) -> Unit,
    onOpenTribuna: (String) -> Unit,
    onGenerateTribuna: (String) -> Unit,
    onPickNote: () -> Unit,
    onGoToLibrary: () -> Unit,
) {
    val card by vm.presentCard.collectAsState()
    val all by vm.cards.collectAsState()
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Palco") },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val current = card
            when {
                all.isEmpty() -> HubEmptyState(
                    text = "Crie seu primeiro esboço para apresentar.",
                    actionLabel = "Ir para Esboços",
                    onAction = onGoToLibrary,
                )
                current == null -> HubEmptyState(
                    text = "Escolha um esboço para apresentar.",
                    actionLabel = "Escolher esboço",
                    onAction = onPickNote,
                )
                else -> {
                    val wording = if (current.isDemonstration) "Roteiro" else "Tribuna"
                    val readiness = current.presentReadiness ?: PresentReadiness.MISSING
                    val status = when (readiness) {
                        PresentReadiness.READY -> "$wording pronta"
                        PresentReadiness.PARTIAL -> "$wording incompleta"
                        PresentReadiness.MISSING -> "Falta gerar a $wording"
                    }
                    val primaryLabel = when (readiness) {
                        PresentReadiness.READY -> "Apresentar"
                        PresentReadiness.PARTIAL -> "Abrir $wording"
                        PresentReadiness.MISSING -> "Gerar $wording"
                    }
                    val onPrimary = when (readiness) {
                        PresentReadiness.READY -> ({ onPresent(current.id) })
                        PresentReadiness.PARTIAL -> ({ onOpenTribuna(current.id) })
                        PresentReadiness.MISSING -> ({ onGenerateTribuna(current.id) })
                    }
                    HubCurrentNoteCard(
                        title = current.title,
                        badge = if (current.isDemonstration) "Demonstração" else "Discurso",
                        minutes = current.minutes,
                        sectionsCount = current.sectionsCount,
                        status = status,
                        statusOk = readiness == PresentReadiness.READY,
                        detail = current.presentResumeLabel,
                        primaryLabel = primaryLabel,
                        onPrimary = onPrimary,
                        secondaryLabel = "Trocar esboço",
                        onSecondary = onPickNote,
                        tertiaryLabel = if (readiness == PresentReadiness.READY) {
                            "Abrir $wording"
                        } else {
                            null
                        },
                        onTertiary = if (readiness == PresentReadiness.READY) {
                            { onOpenTribuna(current.id) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
