// DEMO 3.2.5b — REMOVER APÓS VALIDAÇÃO
// Demo do SectionCardEditor com fixture realista do S-34-T N.º 35:
// 1 INTRO + 5 BODY (28 sub-pontos) + 1 CONCLUSION = 30 editores vivos.
// Mini-toolbar global (2 botões) só para validar a decisão A no device.
package com.bettertalker.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bettertalker.app.domain.speech.SectionRole
import com.mohamedrejeb.richeditor.model.RichTextState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionCardEditorDemoScreen(onBack: () -> Unit) {
    val sectionsState = remember {
        mutableStateListOf<SectionUiState>().apply { addAll(buildDemoSections()) }
    }
    var activeSectionId by remember { mutableStateOf<String?>(null) }
    var activeEditorKey by remember { mutableStateOf<String?>(null) }
    var activeEditorState by remember { mutableStateOf<RichTextState?>(null) }

    val editorCount = sectionsState.sumOf {
        if (it.section.role == SectionRole.BODY) it.subPoints.size else 1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Demo 3.2.5b — SectionCardEditor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            // Mini-toolbar global (decisão A): opera o editor focado.
            MiniToolbar(
                enabled = activeEditorState != null,
                richState = activeEditorState,
            )
            Text(
                "Seções: ${sectionsState.size} · Editores: $editorCount · " +
                    "Ativa: ${activeSectionId ?: "nenhuma"} · " +
                    "Editor: ${activeEditorKey ?: "nenhum"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            sectionsState.forEachIndexed { i, sectionState ->
                SectionCardEditor(
                    state = sectionState,
                    isActiveSection = sectionState.section.id == activeSectionId,
                    onActivate = { activeSectionId = sectionState.section.id },
                    onTitleChange = { t ->
                        sectionsState.update(i) { it.copy(section = it.section.copy(title = t)) }
                    },
                    onMinutesChange = { m ->
                        sectionsState.update(i) { it.copy(section = it.section.copy(minutes = m)) }
                    },
                    onContentChange = { html ->
                        sectionsState.update(i) { it.copy(section = it.section.copy(contentHtml = html)) }
                    },
                    onSubPointChange = { spId, html ->
                        sectionsState.update(i) { cur ->
                            cur.copy(subPoints = cur.subPoints.map { sp ->
                                if (sp.id == spId) sp.copy(developedHtml = html) else sp
                            })
                        }
                    },
                    onActiveEditorChange = { key, richState ->
                        activeEditorKey = key
                        activeEditorState = richState
                    },
                    // 3.2.5e: demo não precisa de seleção/inserts (defaults).
                    onSelectionChange = {},
                )
            }
        }
    }
}

@Composable
private fun MiniToolbar(enabled: Boolean, richState: RichTextState?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        IconButton(
            enabled = enabled,
            onClick = { richState?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
        ) { Icon(Icons.Default.FormatBold, "Negrito (editor ativo)") }
        IconButton(
            enabled = enabled,
            onClick = { richState?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
        ) { Icon(Icons.Default.FormatItalic, "Itálico (editor ativo)") }
    }
}

private fun MutableList<SectionUiState>.update(
    index: Int,
    transform: (SectionUiState) -> SectionUiState,
) {
    this[index] = transform(this[index])
}
