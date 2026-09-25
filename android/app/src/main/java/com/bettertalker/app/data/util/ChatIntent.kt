package com.bettertalker.app.data.util

/**
 * Roteador determinístico de intenção do chat (100% offline, sem LLM).
 * Ordem importa: ações explícitas primeiro, saudação por último.
 */
object ChatIntent {

    sealed interface Intent {
        /** Pergunta livre sobre tema -> resposta com citações. */
        data class Ask(val topic: String) : Intent
        /** "ideias [para X]" -> gera; X casa com seção do esboço se houver. */
        data class Ideas(val sectionHint: String?) : Intent
        object Summarize : Intent
        object CheckRefs : Intent
        /** "refs do esboço" -> referências citadas no esboço vinculado. */
        object OutlineRefs : Intent
        /** "mostre as seções" -> lista as seções do esboço. */
        object Sections : Intent
        /** "sincronizar esboço" -> atualiza o vínculo pelo texto da nota. */
        object Sync : Intent
        /** "reinserir esqueleto" -> reinsere o esqueleto na nota. */
        object Skeleton : Intent
        /** "desvincular esboço" -> desvincula o esboço da nota. */
        object Unlink : Intent
        /** "baixar as bases" -> mostra as bases faltantes. */
        object Bases : Intent
        /** "insere a 2", "coloca a primeira" -> índice 0-based ou null. */
        data class Insert(val index: Int?) : Intent
        /** "exemplo [para X]", "como introduzir", "ilustração" -> modelo prático. */
        data class Example(val kind: String?, val sectionHint: String?) : Intent
        /** "desenvolva X", "me ajude com X" -> rascunho redigido da parte. */
        data class Develop(val sectionHint: String?) : Intent
        /** "compor/redigir com IA" -> rascunho via modelo local (RAG). */
        data class Compose(val sectionHint: String?) : Intent
        /** "me guie", "passo a passo" -> desenvolve seção por seção. */
        object Guided : Intent
        object Help : Intent
        object Thanks : Intent
    }

    private val ordinals = mapOf(
        "primeir" to 0, "segund" to 1, "terceir" to 2, "quart" to 3,
        "quint" to 4, "sext" to 5
    )

    fun parseNumber(text: String): Int? {
        val n = normalizeText(text)
        Regex("""\b([1-9]|10)\b""").find(n)?.let { return it.groupValues[1].toInt() - 1 }
        for ((k, v) in ordinals) {
            if (Regex("""\b$k[ao]s?\b""").containsMatchIn(n)) return v
        }
        return null
    }

    /** Pedido de continuação ("mais", "outra", "fala mais") — usa o contexto. */
    fun isFollowUpMore(raw: String): Boolean {
        val t = normalizeText(raw)
        if (t.isBlank()) return false
        return Regex("""\b(mais|outra|outro|detalh\w*|continu\w*|prossig\w*|exemplos?)\b""")
            .containsMatchIn(t)
    }

    /** Tira as palavras de continuação para extrair o tópico restante ("fala mais sobre fé" -> "sobre fe"). */
    fun stripMoreWords(raw: String): String {
        val t = normalizeText(raw)
        return t.replace(
            Regex("""\b(mais|outra|outro|fala|fale|conta|mand[ae]|me|por favor|pfv|detalh\w*|continu\w*|prossig\w*|exemplos?|ideias?|desenvolv\w*|escrev\w*|redig\w*|redija)\b"""),
            " "
        ).replace(Regex("""\s+"""), " ").trim()
    }

    /** "desenvolva", "escreva", "redija" — pedido de rascunho (não só ideias). */
    fun hasDevelopVerbs(raw: String): Boolean {
        val t = normalizeText(raw)
        fun has(vararg ws: String) = ws.any { w -> t.contains(w) }
        return has("desenvolv", "escrev", "redig", "redija")
    }

    /** Casa texto com um título de seção do esboço (mesma regra do Ideas). */
    fun matchSectionTitle(raw: String, sectionTitles: List<String>): String? =
        matchSection(raw, sectionTitles.map { SectionRef(it) })

    /**
     * "parte 1", "seção 2", "tópico 3" -> título pelo índice.
     * Puro/testável.
     */
    fun matchSectionNumber(raw: String, titles: List<String>): String? {
        val t = normalizeText(raw)
        if (!Regex("""\b(parte|secao|seccao|topico|numero|item|ponto)\b""").containsMatchIn(t)) {
            return null
        }
        val idx = parseNumber(raw) ?: return null
        return titles.getOrNull(idx)
    }

    /**
     * Pedido explícito de exemplo ("exemplo", "ilustração", "como introduzir/concluir").
     * Retorna "intro"|"conclusion"|"question"|"illustration"|"any"|null.
     */
    fun exampleKindHint(raw: String): String? {
        val t = normalizeText(raw)
        fun has(vararg ws: String) = ws.any { w -> t.contains(w) }
        val mentionsExample = has("exemplo", "ilustr", "cena", "historia")
        val howTo = t.contains("como") &&
            has("introduz", "abrir", "comec", "inici", "conclu", "termin", "fech")
        if (!mentionsExample && !howTo && !has("pergunta inicial")) return null
        if (has("pergunta")) return "question"
        if (has("introduz", "introduc", "abrir", "comec", "inici", "abertura")) return "intro"
        if (has("conclu", "conclus", "termin", "fech", "encerr", "final")) return "conclusion"
        if (has("ilustr", "cena", "historia")) return "illustration"
        if (mentionsExample) return "any"
        return "illustration"
    }

    /** Alvo com título (+ corpo opcional) para casamento. */
    data class SectionRef(val title: String, val body: String = "")

    /**
     * Casa seção por título/corpo e, por último, por número ("parte 1").
     * Puro/testável.
     */
    fun matchSectionAny(raw: String, sections: List<SectionRef>): String? =
        matchSection(raw, sections)
            ?: matchSectionNumber(raw, sections.map { it.title })

    /** "sim", "ok", "entendi" soltos (sem pergunta pendente) -> ajuda, não Ask. */
    fun isAck(raw: String): Boolean {
        val t = normalizeText(raw)
        if (t.isBlank() || t.length >= 12) return false
        return Regex("""\b(sim|ok|certo|entendi|beleza|aham|uhum|hmm|exato|isso|ta|blz)\b""")
            .containsMatchIn(t)
    }

    /**
     * Resposta "sim" / "não" para confirmações pendentes ("Adiciono X?").
     * Puro/testável. "não pode" não conta como sim.
     */
    fun isYes(raw: String): Boolean {
        val t = normalizeText(raw)
        return Regex("""\b(sim|isso|confirm\w*|ok|beleza|fechad\w*|vai|faz|positivo|combinado)\b""")
            .containsMatchIn(t)
    }

    fun isNo(raw: String): Boolean {
        val t = normalizeText(raw)
        return Regex("""\b(nao|n|cancel\w*|deixa|depois|melhor nao|agora nao)\b""")
            .containsMatchIn(t)
    }

    /**
     * Casa seção por relevância: título integral contido vale ouro;
     * senão, score = 2×overlap no título + overlap no corpo, e vence o maior.
     * Palavras funcionais não contam (não viciam o match) e 1 palavra comum
     * no corpo não basta — mas 1 palavra distintiva no corpo + nada no título
     * ainda perde; por isso o mínimo é 2 no total.
     * Puro/testável.
     */
    fun matchSection(raw: String, sections: List<SectionRef>): String? {
        val t = normalizeText(raw)
        // 1) título integral contido na pergunta (sinal mais forte)
        sections.firstOrNull { s ->
            val ns = normalizeText(s.title)
            ns.isNotEmpty() && t.contains(ns)
        }?.let { return it.title }
        // 2) melhor score entre todas (título pesa 2, corpo pesa 1)
        val words = contentWords(t)
        if (words.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (s in sections) {
            val titleWords = contentWords(normalizeText(s.title))
            val bodyWords = contentWords(normalizeText(s.body))
            val score = 2 * words.count { it in titleWords } + words.count { it in bodyWords }
            if (score > bestScore) {
                bestScore = score
                best = s.title
            }
        }
        return if (bestScore >= 2) best else null
    }

    /** Palavras com conteúdo: len>=3 sem stopwords. Puro/testável. */
    fun contentWords(normalized: String): Set<String> =
        normalized.split(" ").filter { it.length >= 3 && it !in STOPWORDS_PT }.toSet()

    fun classify(raw: String, sections: List<SectionRef> = emptyList()): Intent {
        val t = normalizeText(raw)
        if (t.isBlank()) return Intent.Help
        fun has(vararg ws: String) = ws.any { w -> t.contains(w) }

        if (has("resum")) return Intent.Summarize
        if (has("ref", "obra", "pub") && has("esboc")) return Intent.OutlineRefs
        if (has("referencia") || t.contains("o que falta") || t.contains("falta baixar") ||
            (has("ref") && has("falt", "baix", "verific", "conf", "quais", "download", "pub", "obra"))
        ) {
            return Intent.CheckRefs
        }
        if (has("inser", "coloca", "adiciona", "inclu") && !has("esqueleto")) return Intent.Insert(parseNumber(t))
        // "me guie", "passo a passo", "desenvolver tudo" -> modo guiado
        if (has("me guie", "guia-me", "guiame", "passo a passo", "desenvolver tudo",
                "desenvolve tudo", "guia completo", "guia do esboco")
        ) {
            return Intent.Guided
        }
        // "compor/redigir com IA" -> modelo local (RAG); "ia" exige fronteira
        val wantsAi = Regex("""\bia\b""").containsMatchIn(t) &&
            has("compor", "compõe", "redig", "redija", "usar", "use", "gerar")
        if (wantsAi) {
            return Intent.Compose(matchSectionAny(raw, sections))
        }
        // "desenvolva a introdução", "me ajude com X" (X = seção) -> rascunho
        if (has("desenvolv", "escrev", "redig", "redija", "monte o texto", "montar o texto") ||
            ((has("me ajude", "me ajuda", "ajude-me", "ajuda-me")) && matchSectionAny(raw, sections) != null)
        ) {
            return Intent.Develop(matchSectionAny(raw, sections))
        }
        exampleKindHint(raw)?.let { k ->
            return Intent.Example(k.takeIf { it != "any" }, matchSectionAny(raw, sections))
        }
        if (has("ideia", "sugest", "me ajude", "me ajuda", "tema", "topico", "assunto", "desenvol")) {
            return Intent.Ideas(matchSectionAny(raw, sections))
        }
        if (has("desvincul") || ((has("remov") || has("exclu") || has("apag") || has("tir")) && has("esboc"))) {
            return Intent.Unlink
        }
        if (has("esqueleto")) return Intent.Skeleton
        if (has("secao") || has("secoes") || has("sumario") || has("estrutura")) return Intent.Sections
        if ((has("sincroniz", "sincroniza", "atualiz") ) && has("esboc")) return Intent.Sync
        if (has("base") && has("baix", "download", "falt", "quais", "verific")) return Intent.Bases
        if (has("obrigad", "valeu", "brigad")) return Intent.Thanks
        if (t.length < 24 && has("oi", "ola", "opa", "bom dia", "boa tarde", "boa noite", "ajuda", "help", "como funciona", "o que voce", "o que você")) {
            return Intent.Help
        }
        if (isAck(raw)) return Intent.Help
        return Intent.Ask(raw.trim())
    }
}
