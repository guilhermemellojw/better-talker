package com.bettertalker.app.ui.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private enum class OnboardingStep { WELCOME, PATHS, TOUR }

/**
 * Onboarding local da v5.0. Texto-first; nenhuma chamada de rede/LLM.
 * Os fluxos de importar/colar/criar pertencem às telas existentes — aqui só
 * entregamos a intenção pelo callback.
 */
@Composable
fun OnboardingScreen(
    replay: Boolean,
    onSkip: () -> Unit,
    onImport: (Uri) -> Unit,
    onPaste: () -> Unit,
    onCreate: () -> Unit,
    onExplore: () -> Unit = {},
    onFinish: (openLibrary: Boolean) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport(uri) }

    BackHandler { onSkip() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (replay) "Primeiros passos" else "Bem-vindo ao BT",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextButton(onClick = onSkip) { Text("Agora não") }
            }
            Spacer(Modifier.height(18.dp))
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onStart = { step = OnboardingStep.PATHS },
                    onSkip = onSkip,
                )
                OnboardingStep.PATHS -> PathsStep(
                    onImport = { picker.launch(arrayOf("*/*")) },
                    onPaste = onPaste,
                    onCreate = onCreate,
                    onExplore = {
                        onExplore()
                        step = OnboardingStep.TOUR
                    },
                )
                OnboardingStep.TOUR -> TourStep(
                    onOpenLibrary = { onFinish(true) },
                    onAskBt = { onFinish(false) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(54.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Oi! Eu sou o BT.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Vou te ajudar a preparar, treinar e apresentar sua parte. " +
                "O que você quer fazer primeiro?",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Começar")
        }
        TextButton(onClick = onSkip) { Text("Só quero explorar sozinho") }
    }
}

@Composable
private fun PathsStep(
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onCreate: () -> Unit,
    onExplore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Escolha um caminho",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Todos funcionam sem chave e sem internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathCard(
            icon = Icons.Default.Description,
            title = "Importar esboço S-34",
            description = "Traga o arquivo do esboço e o BT monta as seções.",
            onClick = onImport,
        )
        PathCard(
            icon = Icons.Default.LibraryBooks,
            title = "Colar parte da reunião V&M",
            description = "Cole o texto da parte e confira a estrutura.",
            onClick = onPaste,
        )
        PathCard(
            icon = Icons.Default.Create,
            title = "Criar do zero",
            description = "Escolha tipo e duração para nascer com a estrutura.",
            onClick = onCreate,
        )
        PathCard(
            icon = Icons.Default.Train,
            title = "Só explorar",
            description = "Um tour de 3 cartões pelas áreas do app.",
            onClick = onExplore,
        )
    }
}

@Composable
private fun TourStep(onOpenLibrary: () -> Unit, onAskBt: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Em 3 lugares",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TourCard(
            title = "Assistente",
            text = "Fale ou digite com o BT. Ele abre esboços, tira dúvidas e " +
                "ajuda a preparar a parte.",
        )
        TourCard(
            title = "Esboços",
            text = "Sua biblioteca de notas. Importe, cole ou crie um esboço novo.",
        )
        TourCard(
            title = "Treino e Palco",
            text = "Treine com tempo e feedback. No Palco, apresente com cues, " +
                "imagens e checkpoint.",
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir Esboços")
        }
        OutlinedButton(onClick = onAskBt, modifier = Modifier.fillMaxWidth()) {
            Text("Falar com o BT")
        }
    }
}

@Composable
private fun PathCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TourCard(title: String, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
