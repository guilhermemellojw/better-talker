package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageEntity
import java.net.URLEncoder

/**
 * Detecta referências a publicações no texto da nota (português natural)
 * e verifica se a EDIÇÃO EXATA está baixada localmente.
 * Textos bíblicos (Gên 1:26) são ignorados por decisão de escopo.
 *
 * Símbolos de livro reconhecidos (3.2.3a-fix4), validados contra
 * [PubCatalog]: pe, rs, re, dp, dg, bh, jv, kj, it-1/it-2 (página/range),
 * mrt (artigo N), ifi (lição N [ponto M]) — além das rotas legadas por
 * palavra-guia e por título integral. Sentinela por nome ("Sentinela
 * n.º X de YYYY", "Sentinela de <mês> de YYYY") e por data
 * ("Sentinela 01/04/09", "Sentinela 01/20").
 *
 * DÉBITO (3.2.3a-fix4): publicações fora do PubCatalog só entram por
 * título integral/busca; novos símbolos exigem entrada no catálogo.
 * `chapterOf` cobre "cap./capítulo/lição/estudo/parágrafo/§/pág./página".
 */
object RefDetector {

    enum class Kind { MAGAZINE, BOOK }

    data class DetectedRef(
        val raw: String,
        val kind: Kind,
        /** g | gn | w | wp | be | th | nome-normalizado */
        val pubKey: String,
        /**
         * chave de edição exata:
         * "g|2013|8" (mensal antiga), "gn|2024|1" (numerada nova),
         * "w|2024|12" (estudo mensal), "w|1999|5|1" (antiga dia/mês),
         * "wp|2019|3" (pública numerada), "book|be"
         */
        val editionKey: String,
        val label: String,
        /** T2 (refs): artigo/verbete citado ("Gedalias") — estudo por nome. */
        val article: String? = null,
        /** T2 (refs): lição/capítulo/estudo citado ("lição 3", "cap. 5"). */
        val chapter: String? = null,
        /** T2 (refs): página citada ("pág. 52"). */
        val page: Int? = null,
        /** C2.2: fim da faixa de páginas ("pág. 26-28" → 28). */
        val pageEnd: Int? = null,
        /** T2 (refs): parágrafo numerado da citação ("n.° 4" / "§ 4"). */
        val paragraph: Int? = null,
        /** #24-fix: parágrafo FINAL do range ("§§ 8-9" → 9). */
        val paragraphEnd: Int? = null,
        /**
         * C2.5: referência de revista ambígua ("g 1/16" = janeiro/2016 ou
         * edição 1/2016). Nunca é resolvida em silêncio: [matchEdition]
         * devolve null e a UI explica o motivo.
         */
        val ambiguous: Boolean = false,
        /** Explicação da ambiguidade (ex.: "g 1/16 pode ser ..."). */
        val ambiguityNote: String? = null,
        /** C2.6: frase do esboço que contém a citação (fallback por contexto). */
        val context: String? = null,
    )

    data class RefStatus(
        val ref: DetectedRef,
        val resolved: Boolean,
        val fileName: String? = null,
        val downloadUrl: String = "",
        val hint: String = "",
        /** true = página da edição; false = página de busca/revistas */
        val exact: Boolean = true,
        /** arquivo direto via API oficial (download em 1 toque); null = só página */
        val apiPub: String? = null,
        val apiIssue: String? = null,
        /** numerada sem código derivável: sonda meses via API no toque */
        val probePub: String? = null,
        val probeYear: Int? = null,
        /**
         * T3 — citação sustentada pelo corpus (≠ [resolved], que continua
         * significando "edição baixada/indexada"). Default false: sem corpus
         * pesquisado não há veredito.
         */
        val contentResolved: Boolean = false,
        /** C2.6: trecho localizado por página/contexto, não por § exato. */
        val contentPartial: Boolean = false,
        /** C2.6: motivo curto ("página localizada", "contexto do esboço"). */
        val contentNote: String? = null,
        /** Trecho do corpus que sustenta a citação (quando [contentResolved]). */
        val snippet: String? = null,
        /**
         * C2.8: texto completo que o prompt deve receber. Pode ser maior que
         * [snippet] (a UI mostra um recorte; o Copilot recebe a unidade).
         * Preenchido pelo MESMO resolvedor que define o status da UI.
         */
        val promptText: String? = null,
        /**
         * 6.0a: texto integral da faixa (o prompt pode usar [promptText],
         * limitado pelo orçamento; o reader usa este campo/DB).
         */
        val fullText: String? = null,
        /** Aviso quando a citação não tem fonte no acervo pesquisado. */
        val contentWarning: String? = null
    )

    /**
     * T3 — resultado da verificação de CONTEÚDO de uma citação contra o
     * corpus recuperado. `corpusAvailable=false` = sem corpus: sem veredito
     * (não acusa). Puro/testável.
     */
    data class ContentCheck(
        val resolved: Boolean,
        val snippet: String? = null,
        val warning: String? = null,
        /** C2.6: localizado por página/contexto, sem § exato. */
        val partial: Boolean = false,
        val note: String? = null,
    )

    const val CONTENT_WARNING = "⚠️ citação sem fonte no acervo"

    /**
     * A edição está no acervo, mas o trecho citado não foi localizado.
     * Diferente de [CONTENT_WARNING]: não diz que a publicação inteira falta.
     */
    const val TEXT_NOT_FOUND_WARNING = "⚠️ edição no acervo; trecho não encontrado"

    /**
     * T1 (refs por página) — aviso específico para citação SÓ por página
     * (`page != null`, sem artigo/capítulo/lição/estudo): o acervo não tem
     * paginação (JWPUB/EPUB são reflowable), então a página sozinha nunca
     * resolve — mas o § ou capítulo junto resolve. UNRESOLVED continua.
     */
    const val PAGE_ONLY_WARNING =
        "⚠️ refs por página não resolvem no acervo — cite o § ou capítulo junto"

    // Despertai! 08/13 | Despertai!, 8/2013 (mensal antiga)
    private val AWAKE_RE = Regex(
        """Despertai!?,?\s*(\d{1,2})/(\d{2,4})""",
        RegexOption.IGNORE_CASE
    )
    // Despertai! N.º 1 2024 (numerada nova)
    private val AWAKE_NUM_RE = Regex(
        """Despertai!?,?\s*N\.?(?:º|o|°)?\s*(\d{1,2})\s*(?:de\s*)?(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // Sentinela número 3 de 2019 | A Sentinela, n.º 2 de 2017 (pública numerada)
    private val WATCHTOWER_RE = Regex(
        """(?:A\s+)?Sentinela,?\s+(?:n\.?(?:º|o|°)?\s*)?(?:n[úu]mero\s+)?(\d{1,2})\s+de\s+(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // Sentinela de março de 2019 (estudo mensal por extenso)
    private val WATCH_MONTH_RE = Regex(
        """(?:A\s+)?Sentinela\s+de\s+(janeiro|fevereiro|mar[cç]o|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\s+de\s+(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // w24.12 | w 24/12 (estudo mensal moderna) — #8a: tail opcional de
    // página/§ ("w25.07 5 § 14"); #24-fix: §/§§ + range ("w25.07 22 §§ 8-9");
    // C2a: prefixo de página opcional ("p.", "p.22", "pág.", "página", "pg.")
    // antes do número. O `\b` final impede phantom "w24.12" dentro de
    // "w24.125" (o tail não pode terminar no meio de um número).
    private val W_CODE_RE = Regex(
        """\bw\s*(\d{2})[./](\d{1,2})""" +
            """(?:\s+(?:(?:p[áa]g(?:ina)?|pg|p)\.?\s*)?(\d{1,3})""" +
            """(?:\s*[-–]\s*(\d{1,3}))?""" +
            """(?:\s*§{1,2}\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?)?\b""",
        RegexOption.IGNORE_CASE
    )
    // #8a: apostila Vida e Ministério (mwb24.05 | mwb 24.05).
    private val MWB_CODE_RE = Regex("""\bmwb\s*(\d{2})[./](\d{1,2})\b""")
    // w99 1/5 | w99 15/5 (antiga dia/mês: dia 1º = pública, 15 = estudo)
    // Aceita também ponto como separador (w82 15.3) — 3.2.3a-fix3.
    // C2.2: faixa de página e §§ que seguem a edição ("w11 1/7 26-28 §§ 3-5").
    private val W_DAY_RE = Regex(
        """\bw\s*(\d{2})\s+(\d{1,2})[./](\d{1,2})""" +
            """(?:\s+(?:(?:p[áa]g(?:ina)?|pg|p)\.?\s*)?(\d{1,3})""" +
            """(?:\s*[-–]\s*(\d{1,3}))?""" +
            """(?:\s*§{1,2}\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?)?\b""",
        RegexOption.IGNORE_CASE
    )
    // g93 8/1 | g82 22.3 (Despertai quinzenal antiga, dia/mês) — 3.2.3a-fix3
    private val G_OLD_RE = Regex(
        """\bg\s*(\d{2})\s+(\d{1,2})[./](\d{1,2})""" +
            """(?:\s+(?:(?:p[áa]g(?:ina)?|pg|p)\.?\s*)?(\d{1,3})""" +
            """(?:\s*[-–]\s*(\d{1,3}))?""" +
            """(?:\s*§{1,2}\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?)?\b""",
        RegexOption.IGNORE_CASE
    )
    // wp24 N.º 1 | wp19.3 (pública por código)
    private val WP_CODE_RE = Regex("""\bwp\s*(\d{2})(?:[./](\d{1,2})|\s*(?:N\.?(?:º|o|°)?|n[úu]mero)\s*(\d{1,2}))?""")
    // g 1/24 | g1/24
    private val G_CODE_RE = Regex("""\bg\s*(\d{1,2})/(\d{2,4})\b""")
    // C2.5: Despertai numerada (g16.1 | g19.03) — issue, não mês.
    private val G_ISSUE_RE = Regex("""\bg\s*(\d{2})[.](\d{1,2})\b""", RegexOption.IGNORE_CASE)
    // citação por sigla do catálogo: lff cap. 5 | be pág. 52 | th lição 3
    // (sigla validada contra PubCatalog; "na" excluído por colidir com preposição)
    // T2: captura o número da unidade (cap./lição/estudo/pág./parág./n.°) e o
    // § que o segue ("lmd lição 3 § 4") ou "ponto N" ("lff lição 24 ponto 3").
    private val SYMBOL_REF_RE = Regex(
        """\b([A-Za-z]{2,4}(?:-[1-3])?)\s+(cap[íi]tulo|cap\.?|li[cç][aã]o|p[áa]gina|p[áa]g\.?|p\.?|""" +
            """par[áa]grafo|par[áa]g\.?|estudo|n[úu]mero|n\.?(?:º|o|°)?)\s*(\d{1,3})?""" +
            """(?:\s*(?:§\s*(\d{1,3})|ponto\s+(\d{1,3})))?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Livros citados por PÁGINA + § (não por estudo/capítulo).
     * `jr` entrou no 6.0a: "jr 29" é a página 29 do livro Jeremias — Uma
     * Mensagem de Deus para Nós; "jr estudo 29" continua estudo (rota com
     * palavra-guia explícita). `Je 29` continua Bíblia.
     */
    private val PAGE_RANGE_BOOKS =
        setOf("cl", "dn", "ed", "gl", "hb", "jd", "jr", "lc", "ml", "rr")

    /** Siglas curtas de livro aceitas mesmo sem parênteses ("jr 29"). */
    private val BARE_BOOK_SYMBOLS = setOf(
        "cl", "dn", "ed", "gl", "hb", "jd", "jr", "lc", "ml", "rr",
    )
    // 3.2.3a-fix4: livros de estudo com página (1-4 dígitos) ou range.
    // Roda antes das rotas legadas; cobre "(it-1 813)", "(kj 264-5)",
    // "(it-2 1050)" e o volume unificado "(it 813)". O `it` nu vem DEPOIS
    // de `it-[12]` (senão "it-1" casaria parcial); "it-3" segue vazio
    // (exige \s+ após o símbolo, e "-" não casa).
    // 3.2.3a-fix4c: o `\b` após o grupo do número + `(?!\s*:)` rejeitam
    // refs bíblicas cap:vers ("Re 15:3b"). Sem o `\b`, o regex engine recua
    // para "1" e o lookahead passaria ("5:3b" não começa com ":") — phantom
    // "Re 1". Com `\b`, "15" não recua para "1" (não há fronteira), então o
    // lookahead é avaliado corretamente e rejeita.
    private val STUDY_BOOK_RE = Regex(
        """\b(it-[12]|it|rsg|pe|rs|re|dp|dg|bh|jv|kj|mrt|ifi|bt|kr|lvs)\s+(\d{1,4})""" +
            """(?:\s*[-–]\s*(\d{1,4}))?""" +
            """(?:\s*§{1,2}\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?""" +
            """\b(?!\s*:)""",
        RegexOption.IGNORE_CASE
    )
    // 3.2.3a-fix4: mrt por artigo ("mrt artigo 32")
    /**
     * v2.7.3: S-38 é o manual "Instruções para a reunião Nossa Vida e
     * Ministério Cristão", organizado por PARTES (COMENTÁRIOS INICIAIS,
     * TESOUROS DA PALAVRA DE DEUS, FAÇA SEU MELHOR NO MINISTÉRIO, NOSSA VIDA
     * CRISTÃ, COMENTÁRIOS FINAIS) — não por lições. Captura a parte como
     * `chapter` para o resolvedor achar a seção exata.
     */
    private val S38_PART_RE = Regex(
        """\bS-38(?:-T)?\s+([A-ZÀ-Ú][A-ZÀ-Ú\s]{3,45}?)(?=\s*[;.)]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val MRT_ARTICLE_RE = Regex(
        """\bmrt\s+artigo\s+(\d+)""",
        RegexOption.IGNORE_CASE
    )
    // 3.2.3a-fix4: ifi por lição/ponto ("ifi lição 24 ponto 3")
    private val IFI_LESSON_RE = Regex(
        """\bifi\s+li[çc][aã]o\s+(\d+)(?:\s+ponto\s+(\d+))?""",
        RegexOption.IGNORE_CASE
    )
    // A1 (confiança): lmd por apêndice ("lmd apêndice A ponto 20").
    // O ponto é opcional; a letra do apêndice é preservada no article/chapter.
    private val LMD_APPENDIX_RE = Regex(
        """\blmd\s+ap[êe]ndice\s+([A-Z])(?:\s*[,;—-]?\s*(?:ponto|n\.?\s*[º°]?)\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    // C2.1: "lmd" literal (sem unidade) — só quando é citação, não prosa
    // ("Texto livre com lmd e (4 min)" continua sem ref). Aceita guia
    // ("veja/consulte lmd"), parênteses ou pontuação de lista. O coletor
    // remove a genérica quando há unidade específica.
    private val BARE_LMD_RE = Regex(
        """(?:\b(?:veja|consulte|leia|conforme)\s+lmd\b|\(\s*lmd\s*\)|\blmd\b(?=\s*[;,.):]))""",
        RegexOption.IGNORE_CASE,
    )
    // Forma natural: "apêndice A da brochura Ame as Pessoas". A unidade vem
    // ANTES do título por extenso; o ponto, quando existe, vem logo após a
    // letra. Não captura o "ponto 5" de uma ref lmd lição 9 separada.
    private val APPENDIX_TITLE_RE = Regex(
        """ap[êe]ndice\s+([A-Z])(?:\s*[,;—-]?\s*(?:ponto|n\.?\s*[º°]?|§)\s*(\d{1,3}))?""",
        RegexOption.IGNORE_CASE,
    )
    // T2 (refs): estudo por ARTIGO + parágrafo: it "Gedalias" n.° 4,
    // rsg “Mispá, Mispé” § 5, it-1 ‘Gedalias’ n.º 4. Aspas opcionais
    // (retas/curvas); aceita "n.°"/"n.º"/"número"/"§"/"parág.". O artigo
    // começa com letra (não confunde com "bh 109-110 §§ 10-11").
    private val ARTICLE_REF_RE = Regex(
        // Aceita também o formato persistido pelo app ("it — Presunção § 10"),
        // que usa travessão entre o símbolo e o artigo.
        """\b(it-[12]|it|rsg|pe|rs|re|dp|dg|bh|jv|kj)\s+(?:[—–-]\s*)?["“'‘]?([A-Za-zÀ-ÿ][^"”'’\n()§]{1,69}?)["”'’]?\s*""" +
            """(?:n\.\s*[ºo°]?|n[úu]mero|§§?|par[áa]g\.?|par[áa]grafo)\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE
    )
    // 3.2.3a-fix4: Sentinela nomeada com data (esboços S-31-T antigos):
    // "Sentinela 01/04/09" (DD/MM/AA) e "Sentinela 01/20" (MM/AA).
    // Disjunta de WATCHTOWER_RE (`/` vs ` de `).
    private val SENTINELA_DATE_RE = Regex(
        """(?:A\s+)?Sentinela\s+(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""",
        RegexOption.IGNORE_CASE
    )
    // #24-fix B4: "cap:vers" logo após a ocorrência de um título
    // ("…de Jeremias 45:3") é citação bíblica — não é menção ao título
    // da publicação (matava o phantom "jr" em "Jeremias 45:3").
    // O matcher roda no texto NORMALIZADO: "45:3" chega como "45 3".
    private val BIBLE_CHAPVER_AFTER_RE = Regex("""^\s*\(?\d{1,3}\s*:?\s*\d{1,3}\b""")

    private val PT_MONTHS = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )

    /** Slug de mês para URL (site usa sem acento: marco). */
    private fun monthSlug(m: Int): String = when (m) {
        3 -> "marco"
        else -> PT_MONTHS[m - 1]
    }

    /** Livros/brochuras por nome (normalizado) -> chave. */
    private val BOOK_NAMES = listOf(
        "beneficie se da escola do ministerio teocratico" to "be",
        "beneficie se" to "be",
        "escola do ministerio teocratico" to "be",
        "melhore sua leitura e seu ensino" to "th",
        "melhore a sua leitura e o seu ensino" to "th",
        "leitura e ensino" to "th",
        "entenda a biblia" to "bhs"
    )

    const val MAGAZINES_LANDING = "https://www.jw.org/pt/biblioteca/revistas/"
    private const val JW_SEARCH = "https://www.jw.org/pt/busca/?q="

    private fun fullYear(yy: Int): Int = if (yy >= 50) 1900 + yy else 2000 + yy

    /** Ano de 2 dígitos expandido; anos de 3-4 dígitos passam direto. */
    private fun expandYear(value: Int, digits: Int): Int =
        if (digits >= 3) value else fullYear(value)

    /**
     * C2.5 — normaliza a edição de revista com [MagazineEditionParser].
     *
     * O detector continua achando o trecho e capturando página/§; o parser
     * define a chave canônica e sinaliza ambiguidades conhecidas ("g 1/16").
     * O fallback só é usado quando o parser não reconhece o formato — nunca
     * inventa uma edição.
     */
    private fun magazineRef(
        raw: String,
        pubKey: String,
        fallbackEditionKey: String,
        label: String,
        page: Int? = null,
        pageEnd: Int? = null,
        paragraph: Int? = null,
        paragraphEnd: Int? = null,
        tail: MagazineTail = MagazineTail(),
    ): DetectedRef {
        val parsed = MagazineEditionParser.parse(raw)
        val ambiguous = parsed?.isAmbiguous == true
        val note = parsed?.note
        val tailLabel = tail.label()
        val fullLabel = if (tailLabel.isBlank()) label else "$label • $tailLabel"
        val finalPage = page ?: tail.page
        return DetectedRef(
            raw = raw,
            kind = Kind.MAGAZINE,
            pubKey = pubKey,
            editionKey = parsed?.editionKey() ?: fallbackEditionKey,
            label = if (ambiguous && note != null) "$fullLabel — ambíguo: $note" else fullLabel,
            page = finalPage,
            pageEnd = fullPageEnd(finalPage, pageEnd ?: tail.pageEnd),
            paragraph = paragraph ?: tail.paragraph,
            paragraphEnd = paragraphEnd ?: tail.paragraphEnd,
            ambiguous = ambiguous,
            ambiguityNote = note,
        )
    }

    fun detect(rawText: String): List<DetectedRef> {
        // DOCX/PDF usam NBSP ("pág.\u00A0 6") e zero-width em ranges
        // ("22-\u200B23"); normalizar a entrada evita perder a unidade.
        val text = rawText
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace(Regex("""§\s*¶"""), "§")
            .replace('¶', '§')
        val out = mutableListOf<DetectedRef>()
        val seen = mutableSetOf<String>()

        fun add(ref: DetectedRef) {
            // Referência = publicação + UNIDADE. A mesma edição com unidades
            // diferentes (lmd apêndice A × lmd lição 9 ponto 5) são citações
            // distintas; a mesma unidade repetida cai. Antes, livros eram
            // deduplicados só por editionKey e a segunda unidade desaparecia.
            val key = buildString {
                append(ref.editionKey)
                append('|').append(normalizeText(ref.article.orEmpty()))
                append('|').append(normalizeText(ref.chapter.orEmpty()))
                append('|').append(ref.page ?: -1)
                append('|').append(ref.pageEnd ?: -1)
                append('|').append(ref.paragraph ?: -1)
                append('|').append(ref.paragraphEnd ?: -1)
                // C2.5: a versão ambígua não colapsa com a versão exata.
                if (ref.ambiguous) append("|amb")
            }
            if (seen.add(key)) out += ref
        }

        AWAKE_RE.findAll(text).forEach { m ->
            val month = m.groupValues[1].toInt()
            var year = m.groupValues[2].toInt()
            if (year < 100) year = fullYear(year)
            add(
                magazineRef(
                    m.value.trim(), "g",
                    "g|$year|$month", "Despertai! $month/$year",
                    tail = parseMagazineTail(text, m.range.last),
                )
            )
        }
        AWAKE_NUM_RE.findAll(text).forEach { m ->
            val num = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(
                magazineRef(
                    m.value.trim(), "gn",
                    "gn|$year|$num", "Despertai! N.º $num $year",
                    tail = parseMagazineTail(text, m.range.last),
                )
            )
        }
        WATCHTOWER_RE.findAll(text).forEach { m ->
            val num = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(
                magazineRef(
                    m.value.trim(), "wp",
                    "wp|$year|$num", "A Sentinela N.º $num $year (pública)",
                    tail = parseMagazineTail(text, m.range.last),
                )
            )
        }
        WATCH_MONTH_RE.findAll(text).forEach { m ->
            val monthName = m.groupValues[1].lowercase().replace("marco", "março")
            val month = PT_MONTHS.indexOf(monthName) + 1
            val year = m.groupValues[2].toInt()
            if (month > 0) {
                add(
                    magazineRef(
                        m.value.trim(), "w",
                        "w|$year|$month", "A Sentinela (estudo), $monthName de $year",
                        tail = parseMagazineTail(text, m.range.last),
                    )
                )
            }
        }
        // 3.2.3a-fix4: Sentinela nomeada por data (S-31-T antigo).
        SENTINELA_DATE_RE.findAll(text).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val yy = m.groupValues[3]
            if (yy.isEmpty()) {
                // MM/AA — estudo mensal moderna ("Sentinela 01/20" → w|2020|1)
                if (a in 1..12) {
                    val year = expandYear(b, 2)
                    add(
                        magazineRef(
                            m.value.trim(), "w",
                            "w|$year|$a",
                            "A Sentinela (estudo), ${PT_MONTHS[a - 1]} de $year",
                            tail = parseMagazineTail(text, m.range.last),
                        )
                    )
                }
            } else {
                // DD/MM/AA(AA) — quinzenal antiga ("Sentinela 01/04/09")
                val day = a
                val month = b
                val year = expandYear(yy.toInt(), yy.length)
                if (month in 1..12 && day in 1..31) {
                    val edition = if (day == 1) "pública" else "estudo"
                    add(
                        magazineRef(
                            m.value.trim(), "w",
                            "w|$year|$month|$day",
                            "A Sentinela, ${day}º de ${PT_MONTHS[month - 1]} de $year ($edition)",
                            tail = parseMagazineTail(text, m.range.last),
                        )
                    )
                }
            }
        }
        W_DAY_RE.findAll(text).forEach { m ->
            // formato antigo dia/mês: w99 1/5 (pública, dia 1º), w99 15/5 (estudo, dia 15)
            val year = fullYear(m.groupValues[1].toInt())
            val day = m.groupValues[2].toInt()
            val month = m.groupValues[3].toInt()
            if (month in 1..12 && day in 1..31) {
                val edition = if (day == 1) "pública" else "estudo"
                val monthName = PT_MONTHS[month - 1]
                val page = m.groupValues[4].toIntOrNull()
                val pageEnd = m.groupValues[5].toIntOrNull()
                val par = m.groupValues[6].toIntOrNull()
                val parEnd = m.groupValues[7].toIntOrNull()
                val extra = listOfNotNull(
                    page?.let {
                        if (pageEnd != null && pageEnd != it) "pág. $it-$pageEnd" else "pág. $it"
                    },
                    par?.let {
                        if (parEnd != null && parEnd != it) "§§ $it-$parEnd" else "§ $it"
                    },
                ).joinToString(" ")
                add(
                    magazineRef(
                        m.value.trim(), "w",
                        "w|$year|$month|$day",
                        "A Sentinela, ${day}º de $monthName de $year ($edition)" +
                            (if (extra.isNotEmpty()) " • $extra" else ""),
                        page = page,
                        pageEnd = pageEnd,
                        paragraph = par,
                        paragraphEnd = parEnd,
                    )
                )
            }
        }
        WP_CODE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val num = m.groupValues[2].ifEmpty { m.groupValues[3] }.toIntOrNull()
            if (num != null) {
                add(
                    magazineRef(
                        m.value.trim(), "wp",
                        "wp|$year|$num", "A Sentinela N.º $num $year (pública)",
                        tail = parseMagazineTail(text, m.range.last),
                    )
                )
            }
        }
        G_OLD_RE.findAll(text).forEach { m ->
            // Despertai quinzenal antiga: g93 8/1 = 8 de janeiro (dia/mês)
            val year = fullYear(m.groupValues[1].toInt())
            val day = m.groupValues[2].toInt()
            val month = m.groupValues[3].toInt()
            if (month in 1..12 && day in 1..31) {
                val page = m.groupValues[4].toIntOrNull()
                val pageEnd = m.groupValues[5].toIntOrNull()
                val par = m.groupValues[6].toIntOrNull()
                val parEnd = m.groupValues[7].toIntOrNull()
                val extra = listOfNotNull(
                    page?.let {
                        if (pageEnd != null && pageEnd != it) "pág. $it-$pageEnd" else "pág. $it"
                    },
                    par?.let {
                        if (parEnd != null && parEnd != it) "§§ $it-$parEnd" else "§ $it"
                    },
                ).joinToString(" ")
                add(
                    magazineRef(
                        m.value.trim(), "g",
                        "g|$year|$month|$day",
                        "Despertai! $day/$month/$year" +
                            (if (extra.isNotEmpty()) " • $extra" else ""),
                        page = page,
                        pageEnd = pageEnd,
                        paragraph = par,
                        paragraphEnd = parEnd,
                    )
                )
            }
        }
        W_CODE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val num = m.groupValues[2].toInt()
            // #8a: tail opcional — página/§ logo após a edição ("w25.07 5 § 14").
            val page = m.groupValues[3].toIntOrNull()
            val pageEnd = m.groupValues[4].toIntOrNull()
            val par = m.groupValues[5].toIntOrNull()
            // #24-fix: §§ N-M preserva o fim do range.
            val parEnd = m.groupValues[6].toIntOrNull()
            val extra = listOfNotNull(
                page?.let {
                    if (pageEnd != null && pageEnd != it) "pág. $it-$pageEnd" else "pág. $it"
                },
                par?.let { if (parEnd != null) "§§ $it-$parEnd" else "§ $it" },
            ).joinToString(" ")
            add(
                magazineRef(
                    m.value.trim(), "w",
                    "w|$year|$num", "A Sentinela $num/$year" +
                        (if (extra.isNotEmpty()) " • $extra" else ""),
                    page = page, pageEnd = pageEnd, paragraph = par, paragraphEnd = parEnd,
                )
            )
        }
        // #8a: apostila Vida e Ministério ("mwb24.05" | "mwb 24.05").
        MWB_CODE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val num = m.groupValues[2].toInt()
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "mwb",
                    "mwb|$year|$num", "Apostila Vida e Ministério $num/$year"
                )
            )
        }
        G_CODE_RE.findAll(text).forEach { m ->
            // evita casar "Gên 1:26" etc — exige ausência de ':' logo após
            val after = text.substring(m.range.last + 1).trimStart()
            if (after.startsWith(":")) return@forEach
            val month = m.groupValues[1].toInt()
            var year = m.groupValues[2].toInt()
            if (year < 100) year = fullYear(year)
            add(
                magazineRef(
                    m.value.trim(), "g",
                    "g|$year|$month", "Despertai! $month/$year",
                    tail = parseMagazineTail(text, m.range.last),
                )
            )
        }
        G_ISSUE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val issue = m.groupValues[2].toInt()
            if (issue in 1..12) {
                add(
                    magazineRef(
                        m.value.trim(), "gn", "gn|$year|$issue",
                        "Despertai! N.º $issue/$year",
                        tail = parseMagazineTail(text, m.range.last),
                    )
                )
            }
        }
        val norm = normalizeText(text)
        val bookKeys = mutableSetOf<String>()
        // 3.2.3a-fix4b: símbolos canônicos.
        // Esboços podem usar formas não oficiais ("ifi") que são resolvidas via
        // PubCatalog.resolveSymbol para o canônico ("ia"). Isso garante:
        // - dedupe correto (book|ifi ≠ book|ia sem canonicalização)
        // - link do finder correto (pub=ia funciona; pub=ifi daria 404)
        // O `raw` preserva o símbolo como veio no esboço.
        // it-1/it-2 são volumes VÁLIDOS do Estudo Perspicaz (o site só oferece
        // o arquivo unificado `it`; o matching resolve via PubCatalog.unifiedOf).
        STUDY_BOOK_RE.findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            // v5.3.1: símbolo que também é abreviação bíblica + capítulo
            // plausível ("Re 1", "Je 29") é Bíblia, não estudo/página.
            if (isBibleSymbol(sym) &&
                isBibleChapterForSymbol(sym, m.groupValues[2].toIntOrNull()) &&
                m.groupValues[3].isEmpty()
            ) {
                return@forEach
            }
            val canonical = PubCatalog.resolveSymbol(sym) ?: sym
            val page = m.groupValues[2]
            val end = m.groupValues[3]
            val par = m.groupValues[4].toIntOrNull()
            val parEnd = m.groupValues[5].toIntOrNull()
            val pageInt = page.toIntOrNull()
            val pageEndInt = fullPageEnd(pageInt, end.toIntOrNull())
            val pageLabel = if (pageEndInt == null || pageEndInt == pageInt) {
                page
            } else {
                "$page-$pageEndInt"
            }
            val unitLabel = listOfNotNull(
                "pág. $pageLabel",
                par?.let { if (parEnd != null && parEnd != it) "§§ $it-$parEnd" else "§ $it" },
            ).joinToString(" ")
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical",
                    "${PubCatalog.titleOf(canonical) ?: canonical} ($unitLabel)",
                    // T1 (refs por página): o número nu é página — expõe no
                    // campo para o aviso específico (UNRESOLVED continua).
                    page = pageInt,
                    pageEnd = pageEndInt,
                    paragraph = par,
                    paragraphEnd = parEnd,
                )
            )
        }
        // 3.2.3a-fix4: mrt por artigo ("mrt artigo 32")
        MRT_ARTICLE_RE.findAll(text).forEach { m ->
            val canonical = PubCatalog.resolveSymbol("mrt") ?: "mrt"
            val article = "artigo ${m.groupValues[1]}"
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical",
                    "${PubCatalog.titleOf(canonical) ?: canonical} ($article)",
                    article = article,
                    chapter = article,
                )
            )
        }
        // 3.2.3a-fix4: ifi por lição/ponto ("ifi lição 24 ponto 3") —
        // alias resolvido para "ia" (fix4b).
        IFI_LESSON_RE.findAll(text).forEach { m ->
            val li = m.groupValues[1]
            val ponto = m.groupValues[2]
            val canonical = PubCatalog.resolveSymbol("ifi") ?: "ifi"
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical",
                    "${PubCatalog.titleOf(canonical) ?: canonical} (lição $li" +
                        (if (ponto.isNotEmpty()) " ponto $ponto" else "") + ")",
                    chapter = "lição $li",
                    paragraph = ponto.toIntOrNull(),
                )
            )
        }
        // A1 (confiança): lmd apêndice A ponto N — o "apêndice A" é a unidade
        // (article/chapter) e o ponto vira paragraph para o resolvedor.
        LMD_APPENDIX_RE.findAll(text).forEach { m ->
            val letter = m.groupValues[1].uppercase()
            val point = m.groupValues[2].toIntOrNull()
            val canonical = PubCatalog.resolveSymbol("lmd") ?: "lmd"
            bookKeys += canonical
            add(
                DetectedRef(
                    raw = m.value.trim(),
                    kind = Kind.BOOK,
                    pubKey = canonical,
                    editionKey = "book|$canonical",
                    label = "${PubCatalog.titleOf(canonical) ?: canonical} — Apêndice $letter" +
                        (point?.let { ", ponto $it" } ?: ""),
                    article = "Apêndice $letter",
                    chapter = "Apêndice $letter",
                    paragraph = point,
                )
            )
        }
        // C2.1: menção genérica "lmd" (sem unidade) para o inventário.
        BARE_LMD_RE.findAll(text).forEach { m ->
            val canonical = PubCatalog.resolveSymbol("lmd") ?: "lmd"
            bookKeys += canonical
            add(
                DetectedRef(
                    raw = m.value.trim(),
                    kind = Kind.BOOK,
                    pubKey = canonical,
                    editionKey = "book|$canonical",
                    label = PubCatalog.titleOf(canonical) ?: canonical,
                )
            )
        }
        // Alias por título por extenso: "apêndice A da brochura Ame as
        // Pessoas". Só cria a unidade quando o título ao redor é lmd; a
        // menção genérica não é duplicada depois (bookKeys).
        APPENDIX_TITLE_RE.findAll(text).forEach { m ->
            val before = text.substring((m.range.first - 12).coerceAtLeast(0), m.range.first)
            if (Regex("""\blmd\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(before)) {
                return@forEach // LMD_APPENDIX_RE já cobre "lmd apêndice A".
            }
            val start = (m.range.first - 100).coerceAtLeast(0)
            val end = (m.range.last + 101).coerceAtMost(text.length)
            val window = text.substring(start, end)
            val normWindow = normalizeText(window)
            val title = PubCatalog.titleIndex()
                .filter { (t, sym) ->
                    sym == "lmd" && t.length >= 4 && normWindow.contains(t)
                }
                .maxByOrNull { it.first.length }
                ?: return@forEach
            val canonical = PubCatalog.resolveSymbol(title.second) ?: "lmd"
            val letter = m.groupValues[1].uppercase()
            val point = m.groupValues[2].toIntOrNull()
            val unit = "Apêndice $letter"
            bookKeys += canonical
            add(
                DetectedRef(
                    raw = "$canonical $unit" + (point?.let { " ponto $it" } ?: ""),
                    kind = Kind.BOOK,
                    pubKey = canonical,
                    editionKey = "book|$canonical",
                    label = "${PubCatalog.titleOf(canonical) ?: canonical} — $unit" +
                        (point?.let { ", ponto $it" } ?: ""),
                    article = unit,
                    chapter = unit,
                    paragraph = point,
                )
            )
        }
        // v2.7.3: S-38 por PARTE (manual da reunião; não tem "lição N").
        S38_PART_RE.findAll(text).forEach { m ->
            val part = m.groupValues[1].trim()
            if (part.length < 4) return@forEach
            val canonical = PubCatalog.resolveSymbol("s38") ?: "s-38"
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical|p:${normalizeText(part)}",
                    "${PubCatalog.titleOf(canonical) ?: canonical} — $part",
                    chapter = part,
                )
            )
        }
        // T2 (refs): estudo por ARTIGO + parágrafo: it "Gedalias" n.° 4,
        // rsg “Mispá, Mispé” § 5, it-1 ‘Gedalias’ n.º 4.
        ARTICLE_REF_RE.findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            if (!PubCatalog.isSymbol(sym)) return@forEach
            val canonical = PubCatalog.resolveSymbol(sym) ?: sym
            val article = m.groupValues[2].trim().trim('"', '“', '”', '\'', '‘', '’', '.', ',').trim()
            if (article.length < 2) return@forEach
            val paragraph = m.groupValues[3].toInt()
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical|a:${normalizeText(article)}|$paragraph",
                    "${PubCatalog.titleOf(canonical) ?: canonical} — “$article” §$paragraph",
                    article = article,
                    paragraph = paragraph,
                )
            )
        }
        SYMBOL_REF_RE.findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            val keyword = m.groupValues[2].lowercase()
            val k = normalizeText(keyword)
            // `na` é excluído do catálogo para não casar "na página/lição";
            // com unidade abreviada explícita ("na p. 5", "na cap. 1") vale
            // como a brochura Nome Divino.
            val known = PubCatalog.isSymbol(sym) ||
                (sym == "na" && (k == "p" || k.startsWith("cap")))
            if (known) {
                val canonical = PubCatalog.resolveSymbol(sym) ?: sym
                val num = m.groupValues[3].toIntOrNull()
                // #8a: § ou "ponto N" (lff lição 24 ponto 3).
                val par = m.groupValues[4].toIntOrNull() ?: m.groupValues[5].toIntOrNull()
                // T2: separa a unidade citada (capítulo/lição/estudo × página
                // × parágrafo) para o resolvedor achar o trecho exato.
                // `cl` (Achegue-se a Jeová) cita página + §, não "estudo N":
                // "cl 225 §15" é a página 225, não o estudo 225.
                val pageBook = canonical == "cl"
                val pageUnit = pageBook || k.startsWith("pag") || k == "p"
                val chapter = if (!pageUnit && num != null &&
                    (k.startsWith("cap") || k.startsWith("li") || k.startsWith("est"))
                ) {
                    "$keyword $num"
                } else {
                    null
                }
                val page = if (num != null && pageUnit) num else null
                val paragraph = par ?: if (num != null && (k.startsWith("par") ||
                        k.startsWith("n"))) num else null
                bookKeys += canonical
                val title = PubCatalog.titleOf(canonical) ?: canonical
                val label = when {
                    pageBook && num != null -> "$title (pág. $num)"
                    page != null -> "$title — p. $page"
                    else -> title
                }
                add(
                    DetectedRef(
                        m.value.trim(), Kind.BOOK, canonical,
                        "book|$canonical",
                        label,
                        chapter = chapter, page = page, paragraph = paragraph,
                    )
                )
            }
        }
        // livro + número + §: "jr 27 § 22" (validado no catálogo).
        // Livros de página (cl, jr, dn, ed...) citam página + §; os demais
        // continuam "estudo N" + §.
        Regex("""\b([a-z]{2,4})\s+(\d{1,3})\s*§\s*(\d{1,3})\b""").findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            if (PubCatalog.isSymbol(sym)) {
                val canonical = PubCatalog.resolveSymbol(sym) ?: sym
                val num = m.groupValues[2].toIntOrNull()
                val par = m.groupValues[3].toIntOrNull()
                val pageBook = canonical in PAGE_RANGE_BOOKS
                val title = PubCatalog.titleOf(canonical) ?: canonical
                bookKeys += canonical
                add(
                    DetectedRef(
                        m.value.trim(), Kind.BOOK, canonical,
                        "book|$canonical",
                        if (pageBook && num != null) {
                            "$title — p. $num" + (par?.let { " §$it" } ?: "")
                        } else {
                            "$title (estudo ${m.groupValues[2]})"
                        },
                        chapter = if (pageBook) null else "estudo ${m.groupValues[2]}",
                        page = if (pageBook) num else null,
                        paragraph = par,
                    )
                )
            }
        }
        // livro com faixa de páginas + §§: "rr 37-38 §§ 18-19".
        // `rr` e `cl` citam página impressa, não número de estudo.
        Regex(
            """\b([a-z]{2,4})\s+(\d{1,3})\s*-\s*(\d{1,3})\s*§{1,2}\s*(\d{1,3})""" +
                """(?:\s*-\s*(\d{1,3}))?"""
        ).findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            if (!PubCatalog.isSymbol(sym)) return@forEach
            val canonical = PubCatalog.resolveSymbol(sym) ?: sym
            if (canonical !in PAGE_RANGE_BOOKS) return@forEach
            val page = m.groupValues[2].toIntOrNull()
            val pageEnd = m.groupValues[3].toIntOrNull()
            val par = m.groupValues[4].toIntOrNull()
            val parEnd = m.groupValues[5].toIntOrNull()
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical",
                    "${PubCatalog.titleOf(canonical) ?: canonical} " +
                        "(p. $page-$pageEnd §§ $par-${parEnd ?: par})",
                    page = page,
                    pageEnd = pageEnd,
                    paragraph = par,
                    paragraphEnd = parEnd,
                )
            )
        }
        // jr por faixa de PÁGINAS: "jr 140-142" (o mesmo jr 29 sem faixa é
        // página, tratado na rota de número avulso). Faixa de 3-4 dígitos
        // evita confundir "jr 27-28" (estudos) com páginas.
        Regex(
            """\b(jr)\s+(\d{3,4})\s*[-–]\s*(\d{1,4})""" +
                """(?:\s*§{1,2}\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?"""
        ).findAll(text).forEach { m ->
            val canonical = PubCatalog.resolveSymbol("jr") ?: "jr"
            val page = m.groupValues[2].toIntOrNull()
            val pageEnd = m.groupValues[3].toIntOrNull()
            val par = m.groupValues[4].toIntOrNull()
            val parEnd = m.groupValues[5].toIntOrNull()
            bookKeys += canonical
            add(
                DetectedRef(
                    m.value.trim(), Kind.BOOK, canonical,
                    "book|$canonical",
                    "${PubCatalog.titleOf(canonical) ?: canonical} " +
                        "(p. $page-$pageEnd" +
                        (par?.let { " §§ $it-${parEnd ?: it}" } ?: "") + ")",
                    page = page,
                    pageEnd = pageEnd,
                    paragraph = par,
                    paragraphEnd = parEnd,
                )
            )
        }
        // sigla + número avulsos: "lff 27", "(jy 15)", "jr 29" — fora códigos de revista
        // (lookahead final: \b impediria consumir o ")" e quebraria o balanceamento)
        Regex("""\(?\b([a-z]{2,4}(?:-[12])?)\s+(\d{1,3})\)?(?![a-z0-9])""",
            RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val matched = m.value.trim()
            // "(" só vale se o fechamento correspondente veio no match. Um
            // ")" solto pertence ao grupo externo: "(Jer. 52:1; jr 29)".
            if (matched.startsWith("(") && !matched.endsWith(")")) return@forEach
            val raw = if (!matched.startsWith("(") && matched.endsWith(")")) {
                matched.dropLast(1).trim()
            } else {
                matched
            }
            val sym = m.groupValues[1].lowercase()
            if (sym in setOf("w", "g", "wp", "gn")) return@forEach
            val after = text.substring(m.range.last + 1).trimStart()
            // v5.3.1: cap:vers explícito é Bíblia mesmo para alias não
            // oficial ("Re 1:1", "Cl 1:1"); não cria publicação.
            if (isBibleSymbol(sym) && after.startsWith(":")) return@forEach
            // v5.3.1: "(Re 1:1)" e "(Je 29)" são Bíblia, não publicação.
            if (isBibleSymbol(sym) &&
                isBibleChapterForSymbol(sym, m.groupValues[2].toIntOrNull())
            ) {
                return@forEach
            }
            if (sym.length < 3 && !raw.startsWith("(") &&
                sym !in BARE_BOOK_SYMBOLS
            ) {
                return@forEach
            }
            if (!PubCatalog.isSymbol(sym)) return@forEach
            val canonical = PubCatalog.resolveSymbol(sym) ?: sym
            val num = m.groupValues[2].toIntOrNull()
            val pageBook = canonical in PAGE_RANGE_BOOKS
            val unitPage = if (pageBook) num else null
            val unitChapter = if (pageBook) null else "estudo $num"
            // Já existe uma ref desta publicação com a MESMA unidade (ex.:
            // "cl 225 §15" antes de "cl 225"; "jr 27 §22" antes de "jr 27").
            // Unidades diferentes continuam válidas: "jr 29" (página) e
            // "jr estudo 29" (capítulo) são duas citações.
            if (out.any {
                    it.pubKey == canonical &&
                        ((unitPage != null && it.page == unitPage) ||
                            (unitChapter != null && it.chapter == unitChapter))
                }
            ) {
                return@forEach
            }
            val title = PubCatalog.titleOf(canonical) ?: canonical
            bookKeys += canonical
            add(
                DetectedRef(
                    raw, Kind.BOOK, canonical,
                    "book|$canonical",
                    if (pageBook) "$title — p. $num" else "$title (estudo $num)",
                    page = if (pageBook) num else null,
                    chapter = if (pageBook) null else "estudo $num",
                )
            )
        }
        // título integral: vale com palavra-guia colada antes ("veja o livro X")
        // ou sufixo de estudo colado depois ("X, capítulo 5").
        // Frase comum ("viver para sempre") ou instrução ("obter conhecimento")
        // sozinhas não viram citação; "(5 min)" é duração, não estudo.
        for ((normTitle, sym) in PubCatalog.titleIndex()) {
            if (sym in bookKeys) continue
            val bibleTitle = isBibleBookTitle(normTitle)
            // qualquer ocorrência guiada/estudada vale
            val titleMatch = Regex("""\b$normTitle\b""").findAll(norm).firstOrNull { m ->
                val before = norm.substring((m.range.first - 20).coerceAtLeast(0), m.range.first)
                val after = norm.substring(
                    (m.range.last + 1).coerceAtMost(norm.length),
                    (m.range.last + 21).coerceAtMost(norm.length)
                )
                // #24-fix B4: "Jeremias 45:3" é citação bíblica (livro + cap:vers),
                // não menção a título de publicação — não vira ref "jr".
                if (BIBLE_CHAPVER_AFTER_RE.containsMatchIn(after)) {
                    return@firstOrNull false
                }
                // v5.3.1: título que também é livro bíblico + capítulo/faixa
                // ("Jeremias 52", "Jeremias 51-52") é Bíblia, não publicação.
                // Usa o total real de capítulos do livro.
                if (bibleTitle) {
                    val chapter = Regex("""^\s*\(?(\d{1,3})""")
                        .find(after)?.groupValues?.get(1)?.toIntOrNull()
                    val book = BIBLE_TITLE_TO_BOOK[normTitle]
                    if (isBibleChapterNumber(book, chapter)) {
                        return@firstOrNull false
                    }
                }
                Regex(
                    """(livro|brochura|publicacao|obra|revista|apostila|ver|veja|ler|leia|estud\w*|consult\w*)\s*$"""
                ).containsMatchIn(before) ||
                    Regex(
                        """^[,;:]?\s*\(?(cap|licao|pags?|paginas?|parag|paragrafos?|[0-9]+(?!\s*min))"""
                    ).containsMatchIn(after)
            } ?: continue
            val canonical = PubCatalog.resolveSymbol(sym) ?: sym
            bookKeys += canonical
            // T2: unidade citada logo após o título ("... cap. 5 parág. 10-11").
            val tail = norm.substring(
                (titleMatch.range.last + 1).coerceAtMost(norm.length),
                (titleMatch.range.last + 61).coerceAtMost(norm.length)
            )
            val chap = Regex("""\b(cap|capitulo|licao|estudo)\s*\.?\s*(\d{1,3})""").find(tail)
            // C2.7: páginas/§ após o título ("Beneficie-se, páginas 52-55",
            // "Criador, páginas 8-9"). O tail normalizado perde o hífen da
            // faixa; usa o título ORIGINAL para preservar "52-55".
            val rawTitle = PubCatalog.titleOf(canonical) ?: canonical
            val rawIdx = rawText.indexOf(rawTitle, ignoreCase = true)
            val unit = if (rawIdx >= 0) {
                parseMagazineTail(rawText, rawIdx + rawTitle.length - 1)
            } else {
                parseMagazineTail(tail, -1)
            }
            add(
                DetectedRef(
                    PubCatalog.titleOf(canonical) ?: canonical, Kind.BOOK, canonical,
                    "book|$canonical", PubCatalog.titleOf(canonical) ?: canonical,
                    chapter = chap?.let { "${it.groupValues[1]} ${it.groupValues[2]}" },
                    page = unit.page,
                    pageEnd = fullPageEnd(unit.page, unit.pageEnd),
                    paragraph = unit.paragraph,
                    paragraphEnd = unit.paragraphEnd,
                )
            )
        }
        BOOK_NAMES.forEach { (name, key) ->
            if (key in bookKeys) return@forEach // sigla já detectou
            if (norm.contains(name)) {
                val title = BASE_PUBS.firstOrNull { it.slot == key }?.title
                    ?: PubCatalog.titleOf(key)
                    ?: name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                add(DetectedRef(name, Kind.BOOK, key, "book|$key", title))
            }
        }
        return dedupUnitRefs(out)
    }

    private fun DetectedRef.hasUnit(): Boolean =
        !article.isNullOrBlank() || !chapter.isNullOrBlank() ||
            page != null || paragraph != null || paragraphEnd != null

    /**
     * Matchers sobrepostos podem produzir a mesma publicação uma vez com
     * unidade (it-1 813) e outra genérica ((it-1 813)). Quando há a versão
     * específica, a genérica não vira linha própria; unidades diferentes
     * continuam vivas (lmd apêndice A × lmd lição 9 ponto 5).
     */
    private fun dedupUnitRefs(refs: List<DetectedRef>): List<DetectedRef> {
        val pageStudies = refs.filter { it.page != null }.map {
            Triple(it.editionKey, it.page, it.paragraph)
        }.toSet()
        val editionsWithSpecific = refs.filter { it.hasUnit() }
            .map { it.editionKey }
            .toSet()
        return refs.filter { ref ->
            // "lvs 194 §18" também casava o fallback "estudo 194 §18":
            // quando existe a ref por página, a variante de estudo é ruído.
            // MAS "jr estudo 29" é uma citação explícita: o usuário escreveu
            // "estudo", então não pode ser engolida pelo "jr 29" da mesma
            // frase. Só remove a variante quando o raw NÃO diz "estudo".
            val explicitStudy = normalizeText(ref.raw).contains("estudo")
            val studyNumber = ref.chapter
                ?.takeIf { it.startsWith("estudo ") }
                ?.substringAfter("estudo ")
                ?.toIntOrNull()
            // Qualquer ref por PÁGINA da mesma edição/número já cobre o
            // número; a variante "estudo N" sintetizada pelo matcher avulso
            // é ruído (ex.: "lvs 8-9 §§ 8-9" + "lvs 8"). Um "jr estudo 29"
            // EXPLÍCITO (raw contém "estudo") nunca é removido.
            val studyNoise = !explicitStudy && ref.page == null &&
                studyNumber != null &&
                pageStudies.any { it.first == ref.editionKey && it.second == studyNumber }
            !studyNoise && (ref.hasUnit() || ref.editionKey !in editionsWithSpecific)
        }
    }

    /**
     * Meses (MM) candidatos para uma edição numerada da Sentinela pública (wp),
     * pois o nome do arquivo traz ano+mês (ex: wp_T_201909 = N.º 3 de 2019).
     * Calendário: 2016-2017 bimestral (6/ano: jan,mar,mai,jul,set,nov);
     * 2018-2021 trimestral (3/ano: jan,mai,set); 2022+ anual (N.º 1).
     * Retorna meses de início e fim do bimestre/trimestre quando conhecidos.
     */
    fun wpIssueMonths(year: Int, num: Int): List<String> {
        val starts: List<Int> = when {
            year in 2016..2017 && num in 1..6 -> listOf(2 * num - 1)
            year in 2018..2021 && num in 1..3 -> listOf(listOf(1, 5, 9)[num - 1])
            year >= 2022 && num == 1 -> (1..12).toList()
            else -> return emptyList()
        }
        return starts.flatMap { s -> listOf(s, (s + 1).coerceAtMost(12)) }
            .distinct()
            .map { it.toString().padStart(2, '0') }
    }

    /**
     * Casa anexo com a edição exata da referência.
     *
     * [includePending] = true também considera placeholders baixando/indexando
     * (indexed=false). O chamador decide se aprovou o anexo pelo status.
     */
    fun matchEdition(
        ref: DetectedRef,
        attachments: List<AttachmentEntity>,
        includePending: Boolean = false,
    ): AttachmentEntity? {
        // C2.5: ambiguidade conhecida nunca escolhe uma edição sozinha.
        if (ref.ambiguous) return null
        if (ref.kind == Kind.BOOK) {
            // T2: símbolo canônico (aliases: dx→rsg) + edição unificada
            // (it-1/it-2 → it; o site só oferece o arquivo unificado).
            val keys = listOfNotNull(
                PubCatalog.resolveSymbol(ref.pubKey),
                PubCatalog.unifiedOf(ref.pubKey),
            ).distinct()
            // slot base primeiro, depois título no nome do arquivo
            attachments.firstOrNull {
                it.baseSlot in keys && (includePending || it.indexed)
            }?.let { return it }
            return attachments.firstOrNull { a ->
                if (!includePending && !a.indexed) return@firstOrNull false
                val nf = normalizeText(a.fileName)
                val tokens = nf.split(" ").toSet()
                keys.any { key ->
                    val normKey = normalizeText(key)
                    val titleNorm = PubCatalog.titleOf(key)?.let { normalizeText(it) }.orEmpty()
                    // "Seja Feliz para Sempre.pdf" (sem sigla no nome)
                    val titleHit = titleNorm.length >= 4 &&
                        (if (' ' in titleNorm) nf.contains(titleNorm) else tokens.contains(titleNorm))
                    tokens.contains(key) || // lff_T.pdf, bhs_T.epub…
                        // C2.6: símbolo de 2 letras não pode casar por substring
                        // ("lv" dentro de "lvs_T.jwpub"). Título cobre nomes longos.
                        (normKey.length >= 3 && nf.contains(normKey)) ||
                        titleHit ||
                        (key == "be" && matchBaseSlot(a.fileName) == "be") ||
                        (key == "th" && matchBaseSlot(a.fileName) == "th")
                }
            }
        }
        // revista: código + ano + número/mês (+ dia, se houver) no nome do arquivo
        val parts = ref.editionKey.split("|")
        if (parts.size < 3) return null
        val code = parts[0].trimEnd('n') // gn -> g para matching de arquivo
        val year = parts[1]
        val num = parts[2]
        val day = parts.getOrNull(3)
        val year2 = (year.toIntOrNull() ?: return null) % 100
        val matches = attachments.filter { a ->
            if (!includePending && !a.indexed) return@filter false
            val n = normalizeText(a.fileName)
            val tokens = n.split(" ").toSet()
            // C2.6: quando o arquivo traz um ano de 4 dígitos, ele precisa ser
            // o ano da edição. Sem isto, "w_T_20121115" casava com w|2011|12|15
            // só porque "12/11" aparecia no meio do ano (76 falsos positivos
            // na matriz). Nomes legados de 2 dígitos seguem no fallback.
            val fullYearInName = Regex("""(?:19|20)\d{2}""").find(n)?.value
            if (fullYearInName != null && fullYearInName != year) return@filter false
            // Nome oficial da API (`w_T_20150115`, `g_T_201207`): exige o
            // ano+mês (e o dia, quando houver) exatos. Os fallbacks de 2
            // dígitos só valem para nomes legados/título.
            val officialApiName = Regex("""_T_(?:19|20)\d{6,8}""", RegexOption.IGNORE_CASE)
                .containsMatchIn(a.fileName)
            // título da revista no nome ("A Sentinela N.º 3 2019.pdf")
            val titleHit = when (parts[0]) {
                "w", "wp" -> n.contains("sentinela")
                "g", "gn" -> n.contains("despertai")
                "mwb" -> n.contains("mwb") || n.contains("apostila")
                else -> false
            }
            val hasCode = titleHit ||
                tokens.any { it == code || it == parts[0] || it.startsWith(code) }
            val mm = num.toIntOrNull()?.toString()?.padStart(2, '0') ?: num
            val yy = year2.toString().padStart(2, '0')
            // ns sem espaços: "wp19_3" -> "wp193" casa ano2+número
            val ns = n.replace(" ", "")
            // wp pública: o arquivo traz ano+mês da edição (ex: wp_T_201909 = N.º 3/2019)
            val wpMonths = if (parts[0] == "wp") {
                wpIssueMonths(year.toIntOrNull() ?: 0, num.toIntOrNull() ?: 0)
            } else emptyList()
            var hasYear = if (officialApiName) {
                n.contains(year + mm) ||
                    wpMonths.any { m -> n.contains(year + m) } ||
                    (titleHit && n.contains(year))
            } else {
                tokens.any { it == year || it == yy } ||
                    n.contains(year + mm) || n.contains(mm + yy) ||
                    ns.contains(yy + num) || ns.contains(yy + mm) ||
                    wpMonths.any { m -> n.contains(year + m) || ns.contains(yy + m) } ||
                    (code == "g" && n.contains(year + mm))
            }
            if (day != null) {
                // edição datada antiga: exige o dia no nome (w19990501, w99 15-5…)
                val dd = day.toIntOrNull()?.toString()?.padStart(2, '0') ?: day
                hasYear = hasYear && (n.contains(year + mm + dd) || n.contains(dd) ||
                    tokens.any { it == day || it == dd })
            }
            val hasNum = tokens.any { it == num || it == num.toIntOrNull()?.toString() } || hasYear
            hasCode && hasYear && hasNum
        }
        // v2.7.3: duplicatas (PDF baixado antes do JWPUB) — prefere o formato
        // estruturado, que tem page/paragraph e casa a citação exata.
        return matches.maxByOrNull { formatRank(it.fileName) }
    }

    private fun formatRank(name: String): Int = when {
        name.endsWith(".jwpub", true) -> 3
        name.endsWith(".epub", true) -> 2
        name.endsWith(".pdf", true) -> 1
        else -> 0
    }

    private const val REVISTAS = "https://www.jw.org/pt/biblioteca/revistas/"

    /**
     * URL oficial de download para uma referência faltante.
     * Retorna (url, dica, exata): exata=false usa página genérica (botão "Procurar").
     * Todos os padrões exatos foram verificados no ar.
     */
    fun downloadUrl(ref: DetectedRef): Triple<String, String, Boolean> {
        if (ref.kind == Kind.BOOK) {
            // it-1/it-2 são volumes rótulo do mesmo arquivo `it`; o finder
            // não tem página própria para os volumes. Aliases (dx→rsg) também
            // precisam ir para o símbolo canônico, nunca para o antigo.
            val unified = PubCatalog.unifiedOf(ref.pubKey)
            val base = BASE_PUBS.firstOrNull {
                it.slot == ref.pubKey || (unified != null && it.slot == unified)
            }
            if (base != null) {
                return Triple(base.landingUrl, "Baixe o livro na página oficial.", true)
            }
            val symbol = unified
                ?: PubCatalog.resolveSymbol(ref.pubKey)
                ?: ref.pubKey
            if (PubCatalog.entryOf(symbol) != null) {
                // finder resolve a sigla (verificado); página tem todos os formatos
                return Triple(
                    "https://www.jw.org/finder?wtlocale=T&pub=$symbol&srcid=share",
                    "Abre a página da publicação no site oficial.", true
                )
            }
            val q = URLEncoder.encode(ref.label, "UTF-8")
            return Triple("$JW_SEARCH$q", "Busque a publicação no site oficial.", false)
        }
        val parts = ref.editionKey.split("|")
        if (parts.size < 3) {
            return Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
        }
        return when (parts[0]) {
            // Despertai! mensal antiga: g201308 (verificado)
            "g" -> {
                val mm = parts[2].toIntOrNull()?.toString()?.padStart(2, '0') ?: parts[2]
                Triple(
                    REVISTAS + "g" + parts[1] + mm + "/",
                    "Abre a edição ${ref.label} com download.", true
                )
            }
            // Despertai! numerada nova: despertai-no1-2024 (verificado)
            "gn" -> Triple(
                "${REVISTAS}despertai-no${parts[2]}-${parts[1]}/",
                "Abre a edição ${ref.label} com download.", true
            )
            // Sentinela estudo mensal: sentinela-estudo-dezembro-2024 (verificado 2024 e 2019)
            // Sentinela antiga datada dia/mês: wpYYYYMMDD (dia 1, pública)
            // ou wYYYYMMDD (dia 15, estudo) — validado no A34 em 2026-10-25.
            "w" -> {
                val m = parts[2].toIntOrNull() ?: 0
                val d = parts.getOrNull(3)?.toIntOrNull() ?: 0
                if (parts.size == 4 && m in 1..12 && d in 1..31) {
                    // Edição antiga dia/mês: dia 1 é a Sentinela PÚBLICA (wp);
                    // dia 15 é a de ESTUDO (w). O prefixo "w" para o dia 1
                    // caía em 404 no site (validado no A34 em 2026-10-25).
                    val code = if (d == 1) "wp" else "w"
                    Triple(
                        "${REVISTAS}${code}${parts[1]}" +
                            "${m.toString().padStart(2, '0')}" +
                            "${d.toString().padStart(2, '0')}/",
                        "Abre a edição ${ref.label} com download.",
                        true,
                    )
                }
                else if (parts.size == 3 && m in 1..12) {
                    // 2017 é a exceção de slug no site: a edição de estudo
                    // usa o prefixo "a-" (validado no ar).
                    val prefix = if (parts[1] == "2017") "a-" else ""
                    Triple(
                        REVISTAS + prefix + "sentinela-estudo-" +
                            monthSlug(m) + "-" + parts[1] + "/",
                        "Abre a edição ${ref.label} com download.", true
                    )
                }
                else Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
            }
            // Sentinela pública numerada recente: sentinela-no1-2024 (verificado)
            // Antigas (no3-2019-set-out) têm sufixo inderivável -> fallback honesto
            "wp" -> {
                val y = parts[1].toIntOrNull() ?: 0
                if (y >= 2022) Triple(
                    "${REVISTAS}sentinela-no${parts[2]}-$y/",
                    "Abre a edição ${ref.label} com download.", true
                )
                else Triple(
                    MAGAZINES_LANDING,
                    "Edições antigas têm endereço próprio: procure ${ref.label} e baixe em PDF ou EPUB.", false
                )
            }
            else -> Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
        }
    }

    fun checkAll(text: String, attachments: List<AttachmentEntity>): List<RefStatus> =
        resolve(detect(text), attachments)

    /** Une refs da nota + do esboço sem duplicar edição. Puro/testável. */
    fun unionRefs(a: List<DetectedRef>, b: List<DetectedRef>): List<DetectedRef> {
        val seen = mutableSetOf<String>()
        return (a + b).filter { seen.add(it.editionKey) }
    }

    /**
     * Livro bíblico normalizado -> rótulo (para detecção de versículos).
     *
     * Chaves incluem formas TNM 2015 (Pr, He, Tg, Ap, Na, Za, ...) além das
     * formas longas (Provérbios, Hebreus, Tiago...). Adicionadas na 3.2.3a-fix.
     *
     * T5 (acesso bíblico): "Jó" (ACENTUADO) é o livro de Jó, distinguido de
     * "Jo"/"João" pelo token CRU em [detectBible] — `normalizeText` apagaria
     * o acento e colidiria na chave "jo". "Jo" SEM acento continua João (não
     * há como distinguir de "Jó" sem acento).
     */
    private val BIBLE_BOOKS: Map<String, String> = mapOf(
        "gen" to "Gênesis", "genesis" to "Gênesis",
        // 3.5a: abreviatura antiga de Gênesis usada em esboços ("Gê 3:6").
        "ge" to "Gênesis",
        "ex" to "Êxodo", "exodo" to "Êxodo",
        "lev" to "Levítico", "levitico" to "Levítico",
        "num" to "Números", "numeros" to "Números",
        "deut" to "Deuteronômio", "deuteronomio" to "Deuteronômio",
        "jos" to "Josué", "josue" to "Josué",
        "jz" to "Juízes", "juizes" to "Juízes",
        "rt" to "Rute", "ru" to "Rute", "rute" to "Rute",
        "1sm" to "1 Samuel", "1sa" to "1 Samuel", "1samuel" to "1 Samuel",
        "2sm" to "2 Samuel", "2sa" to "2 Samuel", "2samuel" to "2 Samuel",
        "1rs" to "1 Reis", "1reis" to "1 Reis",
        "2rs" to "2 Reis", "2reis" to "2 Reis",
        "1cr" to "1 Crônicas", "1cronicas" to "1 Crônicas",
        "2cr" to "2 Crônicas", "2cronicas" to "2 Crônicas",
        "ed" to "Esdras", "esd" to "Esdras", "esdras" to "Esdras",
        "ne" to "Neemias", "neemias" to "Neemias",
        "est" to "Ester", "ester" to "Ester",
        "jo" to "João", "joao" to "João",
        "sal" to "Salmos", "salmos" to "Salmos", "salmo" to "Salmos",
        "pro" to "Provérbios", "pr" to "Provérbios", "proverbios" to "Provérbios",
        "ecl" to "Eclesiastes", "ec" to "Eclesiastes", "eclesiastes" to "Eclesiastes",
        "cant" to "Cânticos", "can" to "Cânticos", "cantares" to "Cânticos",
        "is" to "Isaías", "isa" to "Isaías", "isaias" to "Isaías",
        "je" to "Jeremias", "jeremias" to "Jeremias",
        // T1 (refs): abreviações comuns de esboços antigos ("Jer.", "1 Cor.").
        "jer" to "Jeremias",
        "lam" to "Lamentações", "la" to "Lamentações", "lamentacoes" to "Lamentações",
        "ez" to "Ezequiel", "ezequiel" to "Ezequiel",
        "da" to "Daniel", "dn" to "Daniel", "daniel" to "Daniel",
        "os" to "Oseias", "oseias" to "Oseias",
        "jl" to "Joel", "joel" to "Joel",
        "am" to "Amós", "amos" to "Amós",
        "ob" to "Obadias", "obadias" to "Obadias",
        "jn" to "Jonas", "jon" to "Jonas", "jonas" to "Jonas",
        "mq" to "Miqueias", "miq" to "Miqueias", "miqueias" to "Miqueias",
        "na" to "Naum", "nau" to "Naum", "naum" to "Naum",
        "hc" to "Habacuque", "hab" to "Habacuque", "habacuque" to "Habacuque",
        "sof" to "Sofonias", "sofonias" to "Sofonias",
        "ag" to "Ageu", "age" to "Ageu", "ageu" to "Ageu",
        "zc" to "Zacarias", "za" to "Zacarias", "zacarias" to "Zacarias",
        "ml" to "Malaquias", "mal" to "Malaquias", "malaquias" to "Malaquias",
        "mt" to "Mateus", "mat" to "Mateus", "mateus" to "Mateus",
        "mc" to "Marcos", "mar" to "Marcos", "marcos" to "Marcos",
        "lc" to "Lucas", "lu" to "Lucas", "luc" to "Lucas", "lucas" to "Lucas",
        "at" to "Atos", "atos" to "Atos",
        "rm" to "Romanos", "rom" to "Romanos", "romanos" to "Romanos",
        // T2 (refs): formas TNM curtas usadas em esboços ("Ro 5:19", "Jui 6:1").
        "ro" to "Romanos", "jui" to "Juízes", "eze" to "Ezequiel", "dan" to "Daniel",
        "ose" to "Oseias", "obad" to "Obadias", "zac" to "Zacarias", "efe" to "Efésios",
        "1tes" to "1 Tessalonicenses", "2tes" to "2 Tessalonicenses",
        "1tim" to "1 Timóteo", "2tim" to "2 Timóteo", "tia" to "Tiago",
        "1ped" to "1 Pedro", "2ped" to "2 Pedro", "jud" to "Judas",
        "apo" to "Apocalipse", "nee" to "Neemias",
        "1co" to "1 Coríntios", "1corintios" to "1 Coríntios", "1cor" to "1 Coríntios",
        "2co" to "2 Coríntios", "2corintios" to "2 Coríntios", "2cor" to "2 Coríntios",
        "gl" to "Gálatas", "gal" to "Gálatas", "galatas" to "Gálatas",
        "ef" to "Efésios", "efesios" to "Efésios",
        "fp" to "Filipenses", "fil" to "Filipenses", "filipenses" to "Filipenses",
        "col" to "Colossenses", "colossenses" to "Colossenses",
        "cl" to "Colossenses",
        "1ts" to "1 Tessalonicenses", "1te" to "1 Tessalonicenses", "1tessalonicenses" to "1 Tessalonicenses",
        "2ts" to "2 Tessalonicenses", "2te" to "2 Tessalonicenses", "2tessalonicenses" to "2 Tessalonicenses",
        "1tm" to "1 Timóteo", "1ti" to "1 Timóteo", "1timoteo" to "1 Timóteo",
        "2tm" to "2 Timóteo", "2ti" to "2 Timóteo", "2timoteo" to "2 Timóteo",
        "tt" to "Tito", "tit" to "Tito", "tito" to "Tito",
        "fm" to "Filemom", "flm" to "Filemom", "filemom" to "Filemom",
        "hb" to "Hebreus", "he" to "Hebreus", "hebreus" to "Hebreus", "heb" to "Hebreus",
        "tg" to "Tiago", "tiago" to "Tiago",
        "1pe" to "1 Pedro", "1pedro" to "1 Pedro",
        "2pe" to "2 Pedro", "2pedro" to "2 Pedro",
        "1jo" to "1 João", "1joao" to "1 João",
        "2jo" to "2 João", "2joao" to "2 João",
        "3jo" to "3 João", "3joao" to "3 João",
        "jd" to "Judas", "ju" to "Judas", "judas" to "Judas",
        "ap" to "Apocalipse", "apocalipse" to "Apocalipse",
        // 3.2.3a-fix4c: esboços antigos usam "Re" (abreviação de Apocalipse)
        "re" to "Apocalipse"
    )

    /** Títulos que são nomes de livros bíblicos (nunca são publicações). */
    private val BIBLE_TITLE_TO_BOOK: Map<String, String> by lazy {
        BIBLE_BOOKS.values.associateBy { normalizeText(it) }
    }

    private val BIBLE_BOOK_TITLES: Set<String> by lazy {
        BIBLE_TITLE_TO_BOOK.keys
    }

    /**
     * v5.3.1: capítulos reais por livro (66). O guard de símbolo/título só
     * trata como Bíblia quando o número cabe no livro; "Re 89" e "Cl 5" são
     * páginas de publicação, não capítulos inexistentes.
     */
    private val BIBLE_CHAPTER_COUNTS: Map<String, Int> = mapOf(
        "Gênesis" to 50, "Êxodo" to 40, "Levítico" to 27, "Números" to 36,
        "Deuteronômio" to 34, "Josué" to 24, "Juízes" to 21, "Rute" to 4,
        "1 Samuel" to 31, "2 Samuel" to 24, "1 Reis" to 22, "2 Reis" to 25,
        "1 Crônicas" to 29, "2 Crônicas" to 36, "Esdras" to 10,
        "Neemias" to 13, "Ester" to 10, "Jó" to 42, "Salmos" to 150,
        "Provérbios" to 31, "Eclesiastes" to 12, "Cânticos" to 8,
        "Isaías" to 66, "Jeremias" to 52, "Lamentações" to 5,
        "Ezequiel" to 48, "Daniel" to 12, "Oseias" to 14, "Joel" to 3,
        "Amós" to 9, "Obadias" to 1, "Jonas" to 4, "Miqueias" to 7,
        "Naum" to 3, "Habacuque" to 3, "Sofonias" to 3, "Ageu" to 2,
        "Zacarias" to 14, "Malaquias" to 4, "Mateus" to 28, "Marcos" to 16,
        "Lucas" to 24, "João" to 21, "Atos" to 28, "Romanos" to 16,
        "1 Coríntios" to 16, "2 Coríntios" to 13, "Gálatas" to 6,
        "Efésios" to 6, "Filipenses" to 4, "Colossenses" to 4,
        "1 Tessalonicenses" to 5, "2 Tessalonicenses" to 3,
        "1 Timóteo" to 6, "2 Timóteo" to 4, "Tito" to 3, "Filemom" to 1,
        "Hebreus" to 13, "Tiago" to 5, "1 Pedro" to 5, "2 Pedro" to 3,
        "1 João" to 5, "2 João" to 1, "3 João" to 1, "Judas" to 1,
        "Apocalipse" to 22,
    )

    private fun isBibleBookTitle(normTitle: String): Boolean =
        normTitle in BIBLE_BOOK_TITLES

    /** Símbolo que também é abreviação bíblica (ex.: re, je, cl, dn). */
    private fun isBibleSymbol(symbol: String): Boolean =
        BIBLE_BOOKS.containsKey(symbol.lowercase())

    /** Abreviações oficiais da TNM: capítulo solto é Bíblia. */
    private val BIBLE_OFFICIAL_SYMBOLS = setOf(
        "col", "da", "esd", "gal", "he", "ju", "je", "jl", "la",
        "lu", "mal", "na", "pr", "ap",
    )

    /** Aliases não oficiais: capítulo solto é publicação; cap:vers é Bíblia. */
    private val BIBLE_UNOFFICIAL_ALIASES = setOf(
        "cl", "dn", "ed", "gl", "hb", "jd", "lc", "ml", "re",
    )

    private fun isOfficialBibleSymbol(symbol: String): Boolean =
        symbol.lowercase() in BIBLE_OFFICIAL_SYMBOLS

    private fun isUnofficialBibleAlias(symbol: String): Boolean =
        symbol.lowercase() in BIBLE_UNOFFICIAL_ALIASES

    /** Número de capítulos do livro canônico (null = não é livro). */
    private fun bibleChapterCount(book: String?): Int? =
        book?.let { BIBLE_CHAPTER_COUNTS[it] }

    /** O número cabe como capítulo do livro bíblico indicado. */
    private fun isBibleChapterNumber(book: String?, number: Int?): Boolean {
        val max = bibleChapterCount(book) ?: return false
        return number != null && number in 1..max
    }

    /** O número cabe como capítulo do símbolo bíblico (re, cl, dn...). */
    private fun isBibleChapterForSymbol(symbol: String, number: Int?): Boolean {
        if (!isOfficialBibleSymbol(symbol)) return false
        return isBibleChapterNumber(BIBLE_BOOKS[symbol.lowercase()], number)
    }

    /** Menção a versículo bíblico (ex: "Gên 1:26"). Textos bíblicos não viram publicação. */
    data class BibleRef(val bookNorm: String, val label: String, val chapter: Int, val verse: Int)
    /** Detecta menções a versículos (puro/testável). */
    fun detectBible(text: String): List<BibleRef> {
        val out = mutableListOf<BibleRef>()
        val seen = mutableSetOf<String>()
        val lower = text.lowercase()
        // 3.2.3a-fix4c: aceita sufixo de letra no versículo ("15:3b", "10:16a")
        // T1 (refs): aceita ponto na abreviação ("Jer. 41:1", "1 Cor. 15:3").
        val re = Regex("""\b((?:[1-3]\s*)?[a-zà-ÿ]+)\.?\s+(\d{1,3})\s*:\s*(\d{1,3})(?:[a-z])?\b""")
        for (m in re.findAll(lower)) {
            // T5: "jó" (acentuado) é o livro de Jó; "jo" é João. A decisão usa
            // o token cru (já minúsculo) ANTES da normalização sem acentos.
            val rawKey = m.groupValues[1].trim()
            val key = normalizeText(m.groupValues[1]).replace(" ", "")
            val label = if (rawKey == "jó") "Jó" else (BIBLE_BOOKS[key] ?: continue)
            fun add(ch: Int, verse: Int) {
                if (ch <= 0 || verse <= 0) return
                if (seen.add("$label|$ch|$verse")) out += BibleRef(key, label, ch, verse)
            }
            var chapter = m.groupValues[2].toInt()
            var last = m.groupValues[3].toInt()
            add(chapter, last)
            var idx = m.range.last + 1
            while (idx < lower.length) {
                // T1b: ";" + N:M é NOVO capítulo do mesmo livro
                // ("Gên 3:19, 22, 23; 5:5").
                val nextChap = Regex("""^\s*;\s*(\d{1,3})\s*:\s*(\d{1,3})(?:[a-z])?\b""")
                    .find(lower.substring(idx))
                if (nextChap != null) {
                    chapter = nextChap.groupValues[1].toInt()
                    last = nextChap.groupValues[2].toInt()
                    add(chapter, last)
                    idx += nextChap.range.last + 1
                    continue
                }
                // T1: continuação de lista/faixa ("41:1, 2" → vv. 1 e 2;
                // "41:1-3" → 1..3). O lookahead rejeita "41:1, 2 Reis 25:22"
                // (o "2" pertence a outro livro, não é versículo).
                val sep = Regex("""^\s*([,;]|[–—-])\s*(\d{1,3})\b(?!\s*[a-zà-ÿ])""")
                    .find(lower.substring(idx)) ?: break
                val num = sep.groupValues[2].toInt()
                if (num <= 0 || num > 200) break
                val s = sep.groupValues[1]
                if (s == "-" || s == "–" || s == "—") {
                    if (num <= last || num - last > 50) break
                    for (v in (last + 1)..num) add(chapter, v)
                } else {
                    add(chapter, num)
                }
                last = num
                idx += sep.range.last + 1
            }
        }
        return out
    }

    /** Capítulo bíblico inteiro (ex.: "Jeremias 52", "Salmo 23", "1 João 4"). */
    data class BibleChapterRef(val bookNorm: String, val label: String, val chapter: Int)

    /**
     * v5.2.1: detecta capítulos inteiros e faixas de capítulos. A regex exige
     * `Livro N` e rejeita `N:V` (o lookahead após `\b` impede o recuo de "52"
     * para "5" em "Jeremias 52:2"). O livro é validado contra [BIBLE_BOOKS],
     * então publicações como "w25.07" não entram. Jó × João usa o texto cru.
     */
    fun detectBibleChapters(text: String): List<BibleChapterRef> {
        val out = mutableListOf<BibleChapterRef>()
        val seen = mutableSetOf<String>()
        val lower = text.lowercase()
        val re = Regex(
            """\b((?:[1-3]\s*)?[a-zà-ÿ]+)\.?\s+(\d{1,3})""" +
                """(?:\s*[-–—]\s*(\d{1,3}))?\b(?!\s*:)"""
        )
        for (m in re.findAll(lower)) {
            val rawKey = m.groupValues[1].trim()
            val key = normalizeText(m.groupValues[1]).replace(" ", "")
            val label = if (rawKey == "jó") "Jó" else (BIBLE_BOOKS[key] ?: continue)
            // v5.3.1: alias não oficial (re/cl/dn/...) só é Bíblia com
            // cap:vers; capítulo solto prefere a publicação.
            if (key in BIBLE_UNOFFICIAL_ALIASES) continue
            val maxChapter = bibleChapterCount(label) ?: continue
            val start = m.groupValues[2].toInt()
            val end = m.groupValues[3].toIntOrNull() ?: start
            if (start <= 0 || end < start || end - start > 50) continue
            if (start > maxChapter || end > maxChapter) continue
            for (chapter in start..end) {
                if (seen.add("$label|$chapter")) {
                    out += BibleChapterRef(key, label, chapter)
                }
            }
        }
        return out
    }

    /** Capítulo/lição/estudo citado na menção (ex: "lff cap. 5" -> ("cap", 5)). Puro/testável. */
    data class ChapterRef(val kind: String, val number: Int)

    // 3.2.3a-fix4: inclui abreviações "pág."/"parág." (normalizadas p/ pag/parag)
    private val CHAPTER_RE = Regex(
        """\b(capitulo|cap|licao|estudo|paragrafo|parag|pagina|pag)\s*\.?\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE
    )

    fun chapterOf(raw: String): ChapterRef? {
        // "§" não sobrevive à normalização: trata no texto cru
        // #24-fix: aceita "§§ N-N" (o chapter é o parágrafo inicial).
        Regex("""§{1,2}\s*(\d{1,3})""").find(raw)?.let {
            return ChapterRef("paragrafo", it.groupValues[1].toInt())
        }
        // T2: "n.° 4" / "n.º 4" / "n. 4" (parágrafo numerado do estudo).
        Regex("""\bn\.\s*[ºo°]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(raw)?.let {
            return ChapterRef("paragrafo", it.groupValues[1].toInt())
        }
        // T2: "número 4".
        Regex("""\bn[úu]mero\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(raw)?.let {
            return ChapterRef("paragrafo", it.groupValues[1].toInt())
        }
        val t = normalizeText(raw)
        val m = CHAPTER_RE.find(t) ?: return null
        val kind = when {
            m.groupValues[1].startsWith("cap") -> "cap"
            m.groupValues[1].startsWith("lic") -> "licao"
            m.groupValues[1].startsWith("est") -> "estudo"
            m.groupValues[1].startsWith("pag") -> "pagina"
            else -> "paragrafo"
        }
        return ChapterRef(kind, m.groupValues[2].toInt())
    }
    /** Resolve refs já detectadas (ex: salvas no esboço) contra os anexos atuais. */
    fun resolve(detected: List<DetectedRef>, attachments: List<AttachmentEntity>): List<RefStatus> {
        return detected.map { ref ->
            val hit = matchEdition(ref, attachments)
            if (hit != null) {
                RefStatus(ref, true, hit.fileName)
            } else if (ref.ambiguous) {
                // C2.5: sem link/probe — a edição não pode ser escolhida sozinha.
                RefStatus(
                    ref = ref,
                    resolved = false,
                    downloadUrl = "",
                    hint = "Referência ambígua: " +
                        (ref.ambiguityNote ?: "confirme a edição (mês/ano ou número)."),
                    exact = false,
                )
            } else {
                val (url, hint, exact) = downloadUrl(ref)
                val api = JwMediaApi.apiQuery(ref.editionKey)
                val probe = probeNeeded(ref)
                RefStatus(ref, false, null, url, hint, exact, api?.first, api?.second,
                    probe?.first, probe?.second)
            }
        }
    }

    /**
     * T3 — verifica se a CITAÇÃO existe no corpus recuperado (trechos da
     * edição/TNM), sem tocar em banco: recebe os passages já buscados.
     *
     * - [corpusAvailable] falso (nada baixado/indexado) => sem veredito:
     *   não acusa nem resolve (não confundir "não baixado" com "inventado").
     * - corpus presente + match em `ref`/`section`/texto => resolved + trecho.
     * - corpus presente + sem match => aviso [CONTENT_WARNING].
     *
     * Não altera `resolved` (edição baixada). Puro/testável.
     */
    /**
     * C2.2: número do parágrafo no começo do texto quando o índice não trouxe
     * a coluna `paragraph` (bhs/epub antigo). Evita linhas curtas de citação
     * bíblica ("1 João 4:8") — exige corpo com pelo menos 20 chars.
     */
    fun leadingParagraphNumber(text: String): Int? {
        val trimmed = text.trim()
        // Títulos de § podem ser curtos ("15 Nossas ações"); o regex abaixo
        // já exige número + palavra maiúscula. O piso antigo de 20 chars
        // escondia justamente os títulos usados para recortar faixas.
        if (trimmed.length < 4) return null
        // Linha de citação bíblica ("1 João 4:8", "1 Coríntios 14:33") não é
        // parágrafo numerado — o "1" é o número do livro.
        if (Regex("""^\d{1,3}\s+\S+\s+\d{1,3}:\d{1,3}""").containsMatchIn(trimmed)) {
            return null
        }
        return Regex("""^(\d{1,3})[.)]?\s+[A-ZÀ-Ú]""")
            .find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Expande faixas abreviadas de página: "142-4" → 142..144,
     * "749-51" → 749..751, "140-1" → 140..141. Faixas normais
     * ("8-11", "52-55") ficam intactas. Devolve [end] quando não é
     * possível expandir com segurança (evita inventar faixas absurdas).
     */
    fun fullPageEnd(page: Int?, end: Int?): Int? {
        if (page == null || end == null) return end
        if (end >= page) return end
        val pageText = page.toString()
        val endText = end.toString()
        if (endText.length >= pageText.length) return end
        val expanded = (pageText.dropLast(endText.length) + endText)
            .toIntOrNull() ?: return end
        return if (expanded > page && expanded - page <= 99) expanded else end
    }

    fun resolveContent(
        ref: DetectedRef,
        passages: List<PassageEntity>,
        corpusAvailable: Boolean
    ): ContentCheck {
        if (!corpusAvailable) return ContentCheck(resolved = false)
        // T6: o artigo/lição citados também são agulhas — sem eles, uma citação
        // por nome ("it “Gedalias” n.° 4") nunca batia no corpus.
        val needles = listOfNotNull(ref.raw, ref.label, ref.article, ref.chapter)
            .map { normalizeText(it) }
            .filter { it.length >= 3 }
        // exp/chat-quality (2026-10-21): citação com página/parágrafo casa pelo
        // par ESTRUTURADO (page/paragraph do índice T4) — a comparação por
        // string não acha, porque a ref do acervo guarda a posição
        // sequencial ("§605") e não o § da revista.
        val parStart = ref.paragraph
        val parEnd = ref.paragraphEnd ?: parStart
        val pageStart = ref.page
        val pageEnd = ref.pageEnd?.takeIf { p -> pageStart != null && p > pageStart }
            ?: pageStart
        val structuredHits = passages.filter { p ->
            val pParagraph = p.paragraph ?: leadingParagraphNumber(p.text)
            if (pParagraph == null) return@filter false
            val pageOk = pageStart == null || (p.page != null && p.page in pageStart..pageEnd!!)
            val parOk = parStart == null || (pParagraph in parStart..(parEnd ?: parStart))
            pageOk && parOk
        }
        if (structuredHits.isNotEmpty()) {
            // O trecho citado é o parágrafo INTEIRO (título + corpo), não só a
            // primeira linha — é o que o usuário precisa ver.
            val snippet = structuredHits.joinToString(" ") { it.text }.trim().take(600)
            return ContentCheck(resolved = true, snippet = snippet, warning = null)
        }
        // C2.6: página sem § — texto real da página. Página COM § mas sem o
        // § citado no índice (numeração local do artigo, edição reindexada,
        // typo) também é fonte real: devolve a página como PARTIAL com um
        // aviso de que o § exato não está no índice. O fallback global de §
        // continua bloqueado — nunca troca o § citado por outro artigo.
        val pageRows = if (pageStart != null) {
            passages.filter { p -> p.page != null && p.page in pageStart..pageEnd!! }
        } else {
            emptyList()
        }
        if (pageRows.isNotEmpty()) {
            val contextMatch = ref.context
                ?.takeIf { it.isNotBlank() }
                ?.let { ctx -> bestContextWindow(pageRows.map { it.text }, ctx, window = 3) }
            val focused = if (contextMatch != null && ref.paragraph != null) {
                val startIndex = contextMatch.index
                val endIndex = (startIndex + 2).coerceAtMost(pageRows.lastIndex)
                pageRows.subList(startIndex, endIndex + 1)
            } else {
                pageRows
            }
            val snippet = focused.joinToString(" ") { it.text }.trim().take(600)
            return ContentCheck(
                resolved = true,
                snippet = snippet,
                warning = null,
                partial = true,
                note = if (ref.paragraph == null) {
                    "página localizada"
                } else {
                    "página localizada; § citado fora do índice"
                },
            )
        }
        // C2.6: PDF/EPUB sem página/§ — frase do esboço acima do threshold.
        val context = ref.context?.takeIf { it.isNotBlank() }
        if (context != null) {
            val match = bestContextMatch(passages.map { it.text }, context)
            if (match != null) {
                return ContentCheck(
                    resolved = true,
                    snippet = passages[match.index].text.take(600).trim(),
                    warning = null,
                    partial = true,
                    note = "trecho localizado pelo contexto",
                )
            }
        }
        val hit = passages.firstOrNull { p ->
            val refNorm = normalizeText(p.ref)
            val refMatch = refNorm.length >= 3 &&
                needles.any { n -> refNorm.contains(n) || n.contains(refNorm) }
            if (refMatch) return@firstOrNull true
            val textNorm = p.normalized.ifBlank { normalizeText(p.text) }
            needles.any { n -> n.length >= 6 && textNorm.contains(n) }
        }
        return if (hit != null) {
            ContentCheck(resolved = true, snippet = hit.text.take(200).trim(), warning = null)
        } else {
            // T1 (refs por página): só-página não tem unidade textual para
            // buscar — orienta a citar §/capítulo em vez do aviso genérico.
            // #8a: um § presente já é unidade textual — "w25.07 5 § 14"
            // (page+paragraph) não é só-página.
            val pageOnly = ref.page != null && ref.paragraph == null &&
                ref.article == null && ref.chapter == null
            ContentCheck(
                resolved = false,
                snippet = null,
                warning = if (pageOnly) PAGE_ONLY_WARNING else CONTENT_WARNING
            )
        }
    }

    /** Numeradas (wp/gn) sem código de edição derivável: (pub, ano) para sondar via API. */
    private fun probeNeeded(ref: DetectedRef): Pair<String, Int>? {
        val p = ref.editionKey.split("|")
        if (ref.kind != Kind.MAGAZINE || p.size != 3) return null
        val year = p[1].toIntOrNull() ?: return null
        return when (p[0]) {
            "wp" -> "wp" to year
            "gn" -> "g" to year
            else -> null
        }
    }

    /** Serializa refs detectadas para guardar no esboço (JSON manual, sem org.json). */
    fun detectedToJson(refs: List<DetectedRef>): String {
        return refs.joinToString(",", "[", "]") {
            "{\"r\":\"${esc(it.raw)}\",\"k\":\"${it.kind.name}\"," +
                "\"p\":\"${esc(it.pubKey)}\",\"e\":\"${esc(it.editionKey)}\",\"l\":\"${esc(it.label)}\"," +
                "\"a\":\"${esc(it.article.orEmpty())}\",\"c\":\"${esc(it.chapter.orEmpty())}\"," +
                "\"pg\":${it.page ?: -1}" +
                (it.pageEnd?.let { pe -> ",\"pge\":$pe" } ?: "") +
                ",\"g\":${it.paragraph ?: -1}" +
                // #24-fix: fim do range ("§§ 8-9" → 9) — ausente quando null
                // (compatível com o formato anterior).
                (it.paragraphEnd?.let { pe -> ",\"ge\":$pe" } ?: "") +
                (it.context?.takeIf { c -> c.isNotBlank() }
                    ?.let { c -> ",\"ctx\":\"${esc(c)}\"" } ?: "") + "}"
        }
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    fun detectedFromJson(json: String): List<DetectedRef> {
        return try {
            val re = Regex(
                """\{"r":"((?:[^"\\]|\\.)*)","k":"(MAGAZINE|BOOK)","p":"((?:[^"\\]|\\.)*)",""" +
                    """"e":"((?:[^"\\]|\\.)*)","l":"((?:[^"\\]|\\.)*)"""" +
                    """(?:,"a":"((?:[^"\\]|\\.)*)","c":"((?:[^"\\]|\\.)*)","pg":(-?\d+)(?:,"pge":(-?\d+))?,"g":(-?\d+)(?:,"ge":(-?\d+))?)?(?:,"ctx":"((?:[^"\\]|\\.)*)")?\}"""
            )
            re.findAll(json).map { m ->
                fun un(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
                val raw = un(m.groupValues[1])
                val kind = Kind.valueOf(m.groupValues[2])
                // C2.5: o JSON antigo não guarda ambiguous; recompõe do raw.
                val parsed = if (kind == Kind.MAGAZINE) {
                    MagazineEditionParser.parse(raw)
                } else null
                val jsonPage = m.groupValues[8].toIntOrNull()?.takeIf { it >= 0 }
                val jsonPageEnd = fullPageEnd(
                    jsonPage,
                    m.groupValues[9].toIntOrNull()?.takeIf { it >= 0 },
                )
                DetectedRef(
                    raw,
                    kind,
                    un(m.groupValues[3]), un(m.groupValues[4]), un(m.groupValues[5]),
                    article = un(m.groupValues[6]).ifBlank { null },
                    chapter = un(m.groupValues[7]).ifBlank { null },
                    page = jsonPage,
                    pageEnd = jsonPageEnd,
                    paragraph = m.groupValues[10].toIntOrNull()?.takeIf { it >= 0 },
                    paragraphEnd = m.groupValues[11].toIntOrNull()?.takeIf { it >= 0 },
                    ambiguous = parsed?.isAmbiguous == true,
                    ambiguityNote = parsed?.takeIf { it.isAmbiguous }?.note,
                    context = un(m.groupValues[12]).ifBlank { null },
                )
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
