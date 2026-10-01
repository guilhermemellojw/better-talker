package com.bettertalker.app.domain.planning

/**
 * Classifica texto bruto (esboço ou frase solta) em um dos quatro [InputLevel].
 *
 * Puro: sem I/O, sem coroutines, sem dependências de Android.
 * Determinístico: mesmo input produz sempre o mesmo output.
 * Stateless: nenhum estado mutável de instância; seguro para reuso.
 *
 * Ordem de prioridade (retorna o primeiro que casar):
 * 1. FULL_OUTLINE — contém marcador de tempo total ("TEMPO TOTAL", "A SER
 *    ABRANGIDO EM" ou "DURAÇÃO", case-insensitive) e >= 3 títulos de seção.
 * 2. PARTIAL_OUTLINE — >= 1 título de seção e >= 2 referências bíblicas.
 * 3. THEME_WITH_TEXTS — >= 1 referência bíblica.
 * 4. THEME_ONLY — fallback (inclui vazio/whitespace).
 *
 * Títulos de seção (1.1e): linhas planas em caixa alta (ou terminando em
 * "?"/"!") e linhas com marcador markdown (`**`, `*`, `#`) que tenham sufixo
 * "(N min)" — o título principal do esboço não tem minutos e é ignorado.
 *
 * DÉBITO (1.1e):
 * - Nota ao orador ("Nota ao orador:") é detectada como parágrafo comum,
 *   não como bloco especial. Pode virar metadado na 3.4.
 * - Rodapé "N.° 84-T 11/03" não é extraído como metadado.
 * - Título principal sem "(N min)" é rejeitado (correto), mas a heurística
 *   depende do sufixo de minutos para linhas com marcador.
 */
class InputLevelClassifier {

    // Regex thread-safe para findAll. private val de instância (não companion) para permitir múltiplas instâncias em testes.
    // Cobre abreviações em português e nomes por extenso via sufixo opcional: "Heb" e "Hebreus", "Sal" e "Salmos".
    // Formas curtas oficiais da TNM 2015 (Pr, Jz, Na, Za, He, Tg, Flm, Ap) incluídas como alternativas.
    // Cobre também as abreviações curtas da TNM 2015 (PT): "Pro", "1Te"/"2Te", "1Ti"/"2Ti" (alternativas separadas após as formas longas).
    // 1.1e: formas restantes do catálogo TNM (Le, De, Ru, Ne, Ec, Je, La, Ez, Da, Os, Jl, Am, Ob, Ag, Mt, Mr, Lu, Jo, At, Ro, Tit, 1Pe, 2Pe, Ju)
    // e a forma antiga "Is" (Isaías, usada em esboços de 1995+). Trade-off documentado: "De"/"Da"/"Os" podem casar
    // texto comum seguido de "N:N" (raro); aceito como falso positivo tolerável no MVP.
    // Decisão MVP: lista conservadora-expandida documentada; expansão futura = acrescentar alternativa.
    // Limitações conhecidas (aceitas no MVP, não corrigir aqui):
    // - "João 5:28,29" conta como 1 match (o sufixo (?:[-,]\d+)* consome ",29").
    // - "Gên 3:19, 22, 23" (espaço após vírgula) casa só "Gên 3:19".
    // - Livros numerados só casam sem espaço ("1Sa", "1João"); "1 Samuel 3:1" não casa.
    private val bibleRefRegex = Regex(
        """\b(?:Gên(?:esis)?|Êx(?:odo)?|Lev(?:ítico)?|Le|Núm(?:eros)?|Deut(?:eronômio)?|De|Jos(?:ué)?|Juí(?:zes)?|Jz|Rute|Ru|1Sa(?:muel)?|2Sa(?:muel)?|1Rs|2Rs|1Cr(?:ônicas)?|2Cr(?:ônicas)?|Esd(?:ras)?|Nee(?:mias)?|Ne|Est(?:er)?|Jó|Sal(?:mos)?|Prov(?:érbios)?|Pro|Pr|Ecl(?:esiastes)?|Ec|Cân(?:ticos)?|Isa(?:ías)?|Is|Jer(?:emias)?|Je|Lam(?:entações)?|La|Eze(?:quiel)?|Ez|Dan(?:iel)?|Da|Ose(?:ias)?|Os|Joe(?:l)?|Jl|Amo(?:s)?|Am|Oba(?:dias)?|Ob|Jon(?:as)?|Miq(?:ueias)?|Nau(?:m)?|Na|Hab(?:acuque)?|Sof(?:onias)?|Age(?:u)?|Ag|Zac(?:arias)?|Za|Mal(?:aquias)?|Mat(?:eus)?|Mt|Mar(?:cos)?|Mr|Luc(?:as)?|Lu|João|Jo|Atos|At|Rom(?:anos)?|Ro|1Co(?:ríntios)?|2Co(?:ríntios)?|Gál(?:atas)?|Ef(?:ésios)?|Fil(?:ipenses)?|Col(?:ossenses)?|1Tes(?:salonicenses)?|1Te|2Tes(?:salonicenses)?|2Te|1Tim(?:óteo)?|1Ti|2Tim(?:óteo)?|2Ti|Tito|Tit|Filê(?:mon)?|Flm|Heb(?:reus)?|He|Tia(?:go)?|Tg|1Ped(?:ro)?|1Pe|2Ped(?:ro)?|2Pe|1Jo(?:ão)?|2Jo(?:ão)?|3Jo(?:ão)?|Jud(?:as)?|Ju|Apo(?:calipse)?|Ap)\s\d+:\d+(?:[-,]\d+)*""",
        RegexOption.IGNORE_CASE,
    )

    /** Marcadores de tempo total aceitos (moderno + antigo). */
    private val TOTAL_TIME_PATTERNS = listOf(
        "TEMPO TOTAL",
        "A SER ABRANGIDO EM",
        "DURAÇÃO",
    )

    /** Sufixo de duração no fim da linha: "(5 min)", "(10 minutos)", "(3 min.)" etc. */
    private val MINUTES_SUFFIX = Regex("""\(\s*\d+\s*min(?:utos?)?\.?\s*\)\s*$""")

    /**
     * Classifica [rawInput] em um [InputLevel].
     *
     * @param rawInput texto bruto do usuário (esboço, tema ou frase solta).
     * @return o nível detectado, seguindo a ordem de prioridade documentada na classe.
     */
    fun classify(rawInput: String): InputLevel {
        if (rawInput.isBlank()) return InputLevel.THEME_ONLY
        val sectionCount = countSectionTitles(rawInput)
        val refCount = countBibleRefs(rawInput)
        if (hasTotalTime(rawInput) && sectionCount >= 3) {
            return InputLevel.FULL_OUTLINE
        }
        if (sectionCount >= 1 && refCount >= 2) {
            return InputLevel.PARTIAL_OUTLINE
        }
        if (refCount >= 1) {
            return InputLevel.THEME_WITH_TEXTS
        }
        return InputLevel.THEME_ONLY
    }

    /** Marcadores de tempo total: moderno ("TEMPO TOTAL") e antigo (1.1e). */
    private fun hasTotalTime(text: String): Boolean =
        TOTAL_TIME_PATTERNS.any { text.contains(it, ignoreCase = true) }

    private fun countSectionTitles(rawInput: String): Int {
        return rawInput.lines().count { isSectionTitle(it) }
    }

    private fun countBibleRefs(rawInput: String): Int {
        return bibleRefRegex.findAll(rawInput).count()
    }

    private fun isSectionTitle(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false

        val hadMinutes = MINUTES_SUFFIX.containsMatchIn(trimmed)

        // Remove o sufixo "(N min)" antes dos marcadores — em "**TÍTULO** (5 min)"
        // o sufixo real é "(5 min)", não "**".
        var base = MINUTES_SUFFIX.replace(trimmed, "").trim()

        // Marcadores de markdown (#, *, **) — 1.1e: esboços reais antigos e modernos.
        val hadMarker = base.startsWith("**") || base.startsWith("*") ||
            base.startsWith("##") || base.startsWith("#")
        base = base.removePrefix("##").removePrefix("#")
            .removePrefix("**").removePrefix("*")
            .removeSuffix("**").removeSuffix("*")
            .trim()

        // Título com marcador só conta como seção se tiver "(N min)":
        // o título principal do esboço (ex. "**ESCAPARÁ DO DESTINO...?**")
        // não tem minutos e não pode inflar a contagem.
        if (hadMarker && !hadMinutes) return false

        if (base.length < 8) return false
        if (base.first().isLetter().not()) return false
        // Títulos interrogativos/exclamativos contam (1.1c: esboços reais CO-tk26-T,
        // S-312-tk26-T); ":" e "." finais continuam desqualificando.
        val last = base.last()
        if (!(last.isLetter() || last == '?' || last == '!')) return false
        if (base.any { it.isLetter() }.not()) return false
        val body = if (last == '?' || last == '!') base.dropLast(1) else base
        return body.all { it.isUpperCase() || it.isWhitespace() }
    }
}
