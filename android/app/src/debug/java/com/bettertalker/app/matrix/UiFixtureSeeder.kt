package com.bettertalker.app.matrix

import android.content.Context
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.data.util.PastedNote
import com.bettertalker.app.data.util.PastedSection
import com.bettertalker.app.data.util.PastedSubPoint
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Semeia uma nota S-34-like com subpontos para as capturas do card
 * colapsado/expandido. Debug-only; não toca em notas do usuário.
 */
object UiFixtureSeeder {
    const val TITLE = "MATRIZ C2.6 UI"

    suspend fun seed(ctx: Context, db: AppDatabase): String {
        val note = PastedNote(
            title = TITLE,
            discourseType = DiscourseType.S34_DISCOURSE,
            plainText = "Nota de fixture para as capturas de acessibilidade da matriz C2.6.",
            sections = listOf(
                PastedSection(
                    role = SectionRole.INTRO,
                    title = "Introdução",
                    minutes = 1,
                    contentHtml = "<p>Você já parou para observar o céu numa noite limpa? " +
                        "Milhares de estrelas, cada uma em seu lugar. O que essa ordem nos ensina?</p>",
                ),
                PastedSection(
                    role = SectionRole.BODY,
                    title = "PONTO 1 — A ordem revela o Criador",
                    minutes = 5,
                    contentHtml = "",
                    subPoints = listOf(
                        PastedSubPoint("Observe a precisão dos astros e o que ela revela.", listOf("Sal 19:1")),
                        PastedSubPoint("Jeová é um Deus de ordem, não de acaso.", listOf("1 Cor 14:33")),
                        PastedSubPoint("A criação visível ensina sobre o Criador.", listOf("Rom 1:20")),
                        PastedSubPoint("A ordem nos céus convida à meditação.", listOf("Sal 8:3, 4")),
                        PastedSubPoint("Como isso muda a nossa adoração?", emptyList()),
                    ),
                ),
                PastedSection(
                    role = SectionRole.BODY,
                    title = "PONTO 2 — A ordem nos ensina a confiar",
                    minutes = 5,
                    contentHtml = "",
                    subPoints = listOf(
                        PastedSubPoint("Se Jeová mantém os céus em ordem, pode cuidar de nós.", listOf("Mat 6:26")),
                        PastedSubPoint("A confiança cresce quando observamos o que ele já fez.", listOf("Sal 37:5")),
                        PastedSubPoint("A esperança é a âncora em tempos difíceis.", listOf("Heb 6:19")),
                        PastedSubPoint("Aplicação: entregue suas preocupações a Jeová.", listOf("1 Ped 5:7")),
                        PastedSubPoint("Convide a assistência a confiar no Criador.", emptyList()),
                    ),
                ),
                PastedSection(
                    role = SectionRole.CONCLUSION,
                    title = "Conclusão",
                    minutes = 1,
                    contentHtml = "<p>A ordem dos céus não é acaso. Ela nos convida a confiar " +
                        "em Jeová e a viver com esperança. Que todos nós sejamos motivados a " +
                        "observar a criação e a adorar o Criador.</p>",
                ),
            ),
        )
        return NotesRepository(ctx, db).createPastedNote(null, note)
    }
}
