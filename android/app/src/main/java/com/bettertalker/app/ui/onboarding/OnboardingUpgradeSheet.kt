package com.bettertalker.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/** “Novidades do 5.0” para quem já tinha notas antes do onboarding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingUpgradeSheet(
    onDismiss: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(
                "Novidades do 5.0",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text("• O BT agora conduz o primeiro uso e abre a ajuda em qualquer tela.")
            Text("• Ajuda local com 12 tópicos, sem chave e sem internet.")
            Text("• Dicas contextuais para notas, fontes, IA, Treino e Palco.")
            Spacer(Modifier.height(16.dp))
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
