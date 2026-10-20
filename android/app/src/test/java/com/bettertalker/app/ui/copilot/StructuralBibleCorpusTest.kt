package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.copilot.OutlineStructureContext
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceResolver
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 2 — versículos do S-34 no corpus do verifier. Puro/testável. */
class StructuralBibleCorpusTest {

    private fun ref(type: S34RefType, raw: String) =
        OutlineStructureContext.StructuralReference(
            type = type, rawText = raw, ownerId = "sec-1", sourceLine = 1,
        )

    private fun turn(vararg refs: OutlineStructureContext.StructuralReference): ChatTurnContext {
        val section = OutlineStructureContext.CurrentSection(
            id = "sec-1",
            order = 1,
            title = "Ponto",
            content = "",
            subsections = emptyList(),
            references = refs.toList(),
        )
        val structural = OutlineStructureContext(
            outlineId = "out-1",
            title = "Discurso",
            objective = null,
            orderedSections = emptyList(),
            currentSection = section,
            focusState = OutlineStructureContext.FocusState.SECTION,
        )
        return ChatTurnContext(
            pack = ContextPack(emptyList(), emptyList()),
            prompt = "",
            evidence = emptyList(),
            structural = structural,
        )
    }

    private class FakeResolver(private val texts: Map<String, String?>) : ReferenceResolver {
        override suspend fun resolveBible(ref: String): ResolvedReference {
            val text = texts[ref]
            return ResolvedReference(
                original = ref,
                canonicalRef = ref,
                text = text,
                passageId = if (text != null) "p1" else null,
                status = if (text != null) ReferenceStatus.RESOLVED else ReferenceStatus.UNRESOLVED,
            )
        }

        override suspend fun resolvePublication(
            ref: PublicationRef,
        ): ResolvedReference = ResolvedReference(
            ref.symbol, ref.symbol, null, null, ReferenceStatus.MISSING_CORPUS,
        )
    }

    @Test
    fun refsDoS34SoBibliaSemDuplicar() {
        val ctx = turn(
            ref(S34RefType.BIBLE, "Jer. 45:3"),
            ref(S34RefType.PUBLICATION, "w25.07 22 §§ 8-9"),
            ref(S34RefType.BIBLE, "Jer. 45:3"),
            ref(S34RefType.BIBLE, "Jer. 45:5"),
        )
        assertEquals(listOf("Jer. 45:3", "Jer. 45:5"), structuralBibleRefs(ctx))
    }

    @Test
    fun resolveBibleRefLinesInjetaTextoEOmiteNaoResolvido() = runBlocking {
        val resolver = FakeResolver(
            mapOf(
                "Jer. 45:3" to "Bendito o homem que confia em Jeová.",
                "w25.07" to null,
            )
        )
        val lines = resolveBibleRefLines(listOf("Jer. 45:3", "w25.07"), resolver)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("[Jer. 45:3]"))
        assertTrue(lines.single().contains("Bendito o homem"))
    }
}
