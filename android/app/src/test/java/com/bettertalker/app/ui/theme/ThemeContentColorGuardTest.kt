package com.bettertalker.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * exp/chat-contrast (2026-10-22) — guarda de regressão do bug sistêmico:
 * o Compose usa `Color.Black` como `LocalContentColor` padrão. As telas do app
 * usam `Scaffold(containerColor = Color.Transparent)` (textura do fundo) e
 * `Surface` com cor translúcida/custom — nenhum define content color. Sem o
 * provider no tema, TODO texto sem cor explícita fica PRETO no dark
 * (ex.: "Orientações do orador" = 1,00:1, invisível).
 *
 * Teste de fonte (o JVM test não roda Compose): trava a invariante.
 */
class ThemeContentColorGuardTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            val app = File(dir, "android/app/$relative")
            if (app.exists()) return app.readText()
            dir = dir.parentFile
        }
        return ""
    }

    @Test
    fun temaProvidenciaLocalContentColor() {
        val src = source("src/main/java/com/bettertalker/app/ui/theme/Theme.kt")
        assertTrue("Theme.kt não encontrado", src.isNotEmpty())
        assertTrue(
            "o tema precisa prover LocalContentColor = onSurface " +
                "(senão texto sem cor explícita fica preto no dark)",
            src.contains("LocalContentColor provides scheme.onSurface"),
        )
    }

    @Test
    fun cardDeOrientacoesTemCorExplicita() {
        val src = source("src/main/java/com/bettertalker/app/ui/editor/EditorScreen.kt")
        assertTrue("EditorScreen.kt não encontrado", src.isNotEmpty())
        assertTrue(
            "o título 'Orientações do orador' precisa de cor explícita",
            src.contains("\"Orientações do orador\"") &&
                src.contains("color = MaterialTheme.colorScheme.onSurface"),
        )
    }
}
