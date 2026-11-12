package com.bettertalker.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * “Novidades do 6.0” para quem já usava o BT antes do 6.0.
 *
 * Mostrado uma vez no primeiro build 71/72 (ou manualmente pela Ajuda).
 * Explica a navegação, o Copilot com fontes/reader, o sync opt-in e a
 * Biblioteca/TNM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingV6UpgradeSheet(
    onDismiss: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(
                "Novidades do 6.0",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "O BT cresceu. Estes são os quatro lugares que mais mudaram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            V6Card(
                title = "1. Nova navegação",
                body = "Assistente · Biblioteca · Preparo · Treino · Palco. " +
                    "“Meus esboços” organiza as notas e o Mapa mental substitui " +
                    "a antiga Tribuna.",
            )
            V6Card(
                title = "2. Copilot confiável",
                body = "As fontes são tocáveis: veja o texto integral da referência, " +
                    "o trecho usado na resposta e o selo “o Copilot viu X de Y”. " +
                    "O foco pode ser o tópico atual ou o discurso inteiro.",
            )
            V6Card(
                title = "3. Sincronização opcional",
                body = "Ative a nuvem só se quiser: notas, S-34 e imagens. " +
                    "Chat, histórico do Copilot, chave de IA e binários de " +
                    "publicações nunca sobem. A conta permite apagar a nuvem ou " +
                    "só este aparelho.",
            )
            V6Card(
                title = "4. Biblioteca e TNM",
                body = "A TNM e as publicações importadas abrem a faixa completa: " +
                    "w17.10 23 §§9-10 mostra os dois parágrafos inteiros; " +
                    "jr 29 é a página 29; Je 29 continua Bíblia.",
            )

            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onDismiss) { Text("Agora não") }
                Button(
                    onClick = onOpenHelp,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Ver ajuda") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun V6Card(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
