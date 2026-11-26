package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOTFIX P0 — espelho JVM do risco de runtime Android.
 *
 * O motor ICU do Android aceita apenas as flags embutidas `ismwx`; `U`
 * (UNICODE_CHARACTER_CLASS) e `u` (UNICODE_CASE) do Java lançam
 * `PatternSyntaxException` em `PedagogicalIntegrationKt.<clinit>`.
 * Este teste falha no baseline `02b89856` (7 ocorrências de `(?iuU)`)
 * e passa quando a implementação usa apenas construtos portáveis.
 */
class PedagogicalAndroidPatternCompatibilityTest {

    private fun check(value: Any?, seen: MutableSet<Any> = mutableSetOf()) {
        if (value == null || !seen.add(value)) return
        when (value) {
            is Regex -> assertFalse(
                "flag embutido incompatível com ICU/Android: ${value.pattern}",
                Regex("""\(\?[a-zA-Z-]+\)""").containsMatchIn(value.pattern),
            )
            is Pair<*, *> -> {
                check(value.first, seen)
                check(value.second, seen)
            }
            is Iterable<*> -> value.forEach { check(it, seen) }
            is Map<*, *> -> value.forEach { check(it.key, seen); check(it.value, seen) }
        }
    }

    @Test
    fun p0_todasAsExpressoesSemFlagsEmbutidos() {
        val classe = Class.forName("com.bettertalker.app.ui.copilot.PedagogicalIntegrationKt")
        val campos = classe.declaredFields.filter {
            it.type == Regex::class.java || List::class.java.isAssignableFrom(it.type)
        }
        assertTrue("nenhum campo Regex encontrado", campos.isNotEmpty())
        campos.forEach { campo ->
            campo.isAccessible = true
            check(campo.get(null))
        }
    }
}
