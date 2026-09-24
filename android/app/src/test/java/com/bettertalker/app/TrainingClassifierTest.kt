package com.bettertalker.app

import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.TrainingClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingClassifierTest {

    @Test
    fun realCorpusSections() {
        assertEquals(
            TrainingCategory.ILLUSTRATION,
            TrainingClassifier.classify("Ilustrações", "Beneficie-se", "Use ilustrações simples.")
        )
        assertEquals(
            TrainingCategory.TRANSITION,
            TrainingClassifier.classify("Transições", null, "Ligue os pontos.")
        )
        assertEquals(
            TrainingCategory.DELIVERY,
            TrainingClassifier.classify("Entrega", null, "Module a voz.")
        )
    }

    @Test
    fun allCategories() {
        val cases = mapOf(
            "Como fazer uma boa introdução e abertura" to TrainingCategory.INTRODUCTION,
            "Desenvolvimento dos pontos principais e estrutura" to TrainingCategory.DEVELOPMENT,
            "Explicação clara para expor o ensino" to TrainingCategory.EXPLANATION,
            "Uma ilustração com exemplo e analogia" to TrainingCategory.ILLUSTRATION,
            "Aplicação prática da lição" to TrainingCategory.APPLICATION,
            "Transição com ponte para o próximo ponto" to TrainingCategory.TRANSITION,
            "Conclusão para terminar e recapitular" to TrainingCategory.CONCLUSION,
            "Faça uma pergunta de reflexão" to TrainingCategory.QUESTIONS,
            "Clareza simples e direta" to TrainingCategory.CLARITY,
            "Naturalidade com modéstia e sinceridade" to TrainingCategory.NATURALNESS,
            "Entrega com gestos e ritmo no palco" to TrainingCategory.DELIVERY
        )
        for ((text, expected) in cases) {
            assertEquals("$text", expected, TrainingClassifier.classify(null, null, text))
        }
    }

    @Test
    fun sectionBeatsBody() {
        // Corpo menciona "ensino", mas a seção manda: ilustração.
        assertEquals(
            TrainingCategory.ILLUSTRATION,
            TrainingClassifier.classify("Ilustrações", null, "Use para tornar o ensino claro.")
        )
    }

    @Test
    fun unknownWhenUnsafe() {
        assertEquals(TrainingCategory.UNKNOWN, TrainingClassifier.classify(null, null, "Texto genérico variado."))
        assertEquals(TrainingCategory.UNKNOWN, TrainingClassifier.classify("be", "be", null))
        assertEquals(TrainingCategory.UNKNOWN, TrainingClassifier.classify(null, null, null))
        assertEquals(TrainingCategory.UNKNOWN, TrainingClassifier.classify(null, null, ""))
    }

    @Test
    fun effectivePrefersStored() {
        assertEquals(
            TrainingCategory.CONCLUSION,
            TrainingClassifier.effective("conclusion", "Ilustrações", null, "ilustrações")
        )
        assertEquals(
            TrainingCategory.ILLUSTRATION,
            TrainingClassifier.effective(null, "Ilustrações", null, "x")
        )
        assertEquals(
            TrainingCategory.UNKNOWN,
            TrainingClassifier.effective("lixo-qualquer", null, null, null)
        )
    }
}
