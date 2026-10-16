package com.bettertalker.app.ui.tribuna

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #20-fix-b: a Tribuna — leitura do mapa de memória por seção. Cards
 * empilhados com inline edit (autosave) e TTS só na frase-âncora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TribunaScreen(vm: TribunaViewModel, onBack: () -> Unit) {
    val cards by vm.cards.collectAsState()
    val fontSp by vm.fontSp.collectAsState()
    val speakingId by vm.speakingId.collectAsState()

    LaunchedEffect(Unit) { vm.load() }
    // #20-fix-b: sair da tela para a leitura em voz (o orador pode voltar).
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tribuna") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    // Fonte: mesmo reuso do Ensaio (stageFontSp).
                    TextButton(onClick = { vm.setFontSp(fontSp - 2) }) {
                        Text("A−", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { vm.setFontSp(fontSp + 2) }) {
                        Text("A+", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { pad ->
        if (cards.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nenhuma Tribuna gerada. Volte ao editor e toque em ⋮ → Gerar Tribuna.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.sectionId }) { card ->
                    TribunaCard(
                        card = card,
                        fontSp = fontSp,
                        speaking = speakingId == card.sectionId,
                        onMainIdea = { vm.updateMainIdea(card.sectionId, it) },
                        onBibleRef = { vm.updateBibleRef(card.sectionId, it) },
                        onAnchor = { vm.updateAnchorPhrase(card.sectionId, it) },
                        onTransition = { vm.updateTransition(card.sectionId, it) },
                        onSpeak = {
                            if (speakingId == card.sectionId) vm.stopSpeaking()
                            else vm.speakAnchor(card.sectionId)
                        },
                    )
                }
            }
        }
    }
}

/** Card de UMA seção: header + os 4 campos (ou o placeholder sem cápsula). */
@Composable
private fun TribunaCard(
    card: TribunaViewModel.TribunaCardUi,
    fontSp: Int,
    speaking: Boolean,
    onMainIdea: (String) -> Unit,
    onBibleRef: (String) -> Unit,
    onAnchor: (String) -> Unit,
    onTransition: (String) -> Unit,
    onSpeak: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${card.order + 1}. ${card.sectionTitle} · ${card.minutes} min",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val capsule = card.capsule
            if (capsule == null) {
                Text(
                    "Tribuna não gerada para esta seção.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TribunaField("💡 IDEIA CENTRAL", capsule.mainIdea, fontSp, onMainIdea)
                TribunaField("📖 TEXTO-CHAVE", capsule.bibleRef.orEmpty(), fontSp, onBibleRef)
                TribunaField("🎯 FRASE-ÂNCORA", capsule.anchorPhrase, fontSp, onAnchor, bold = true)
                Row {
                    // TTS SÓ da âncora: o orador ouve a frase-chave e fala o resto.
                    TextButton(onClick = onSpeak) {
                        Text(if (speaking) "■ Parar" else "▶ Ouvir")
                    }
                }
                TribunaField("➡ TRANSIÇÃO (opcional)", capsule.transition.orEmpty(), fontSp, onTransition)
            }
        }
    }
}

@Composable
private fun TribunaField(
    label: String,
    value: String,
    fontSp: Int,
    onChange: (String) -> Unit,
    bold: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSp.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
