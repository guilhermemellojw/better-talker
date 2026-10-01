package com.bettertalker.app.data.s34

/**
 * Livro da TNM 2015 indexável por docId do EPUB.
 *
 * @param order ordem canônica (1-66).
 * @param name nome por extenso ("Gênesis").
 * @param abbrev abreviação oficial do índice do EPUB ("Gên").
 * @param docIdBase docId base do livro no EPUB ("1001061105").
 */
data class NwtBook(
    val order: Int,
    val name: String,
    val abbrev: String,
    val docIdBase: String,
)

/**
 * Catálogo dos 66 livros da TNM 2015 (docId base → livro).
 * Abreviações conforme o índice oficial do EPUB.
 */
val NWT_BOOKS: Map<String, NwtBook> = mapOf(
    "1001061105" to NwtBook(1, "Gênesis", "Gên", "1001061105"),
    "1001061106" to NwtBook(2, "Êxodo", "Êx", "1001061106"),
    "1001061107" to NwtBook(3, "Levítico", "Le", "1001061107"),
    "1001061108" to NwtBook(4, "Números", "Núm", "1001061108"),
    "1001061109" to NwtBook(5, "Deuteronômio", "De", "1001061109"),
    "1001061110" to NwtBook(6, "Josué", "Jos", "1001061110"),
    "1001061111" to NwtBook(7, "Juízes", "Jz", "1001061111"),
    "1001061112" to NwtBook(8, "Rute", "Ru", "1001061112"),
    "1001061113" to NwtBook(9, "1 Samuel", "1Sa", "1001061113"),
    "1001061114" to NwtBook(10, "2 Samuel", "2Sa", "1001061114"),
    "1001061115" to NwtBook(11, "1 Reis", "1Rs", "1001061115"),
    "1001061116" to NwtBook(12, "2 Reis", "2Rs", "1001061116"),
    "1001061117" to NwtBook(13, "1 Crônicas", "1Cr", "1001061117"),
    "1001061118" to NwtBook(14, "2 Crônicas", "2Cr", "1001061118"),
    "1001061119" to NwtBook(15, "Esdras", "Esd", "1001061119"),
    "1001061120" to NwtBook(16, "Neemias", "Ne", "1001061120"),
    "1001061121" to NwtBook(17, "Ester", "Est", "1001061121"),
    "1001061122" to NwtBook(18, "Jó", "Jó", "1001061122"),
    "1001061123" to NwtBook(19, "Salmos", "Sal", "1001061123"),
    "1001061124" to NwtBook(20, "Provérbios", "Pr", "1001061124"),
    "1001061125" to NwtBook(21, "Eclesiastes", "Ec", "1001061125"),
    "1001061126" to NwtBook(22, "Cântico de Salomão", "Cân", "1001061126"),
    "1001061127" to NwtBook(23, "Isaías", "Is", "1001061127"),
    "1001061128" to NwtBook(24, "Jeremias", "Je", "1001061128"),
    "1001061129" to NwtBook(25, "Lamentações", "La", "1001061129"),
    "1001061130" to NwtBook(26, "Ezequiel", "Ez", "1001061130"),
    "1001061131" to NwtBook(27, "Daniel", "Da", "1001061131"),
    "1001061132" to NwtBook(28, "Oseias", "Os", "1001061132"),
    "1001061133" to NwtBook(29, "Joel", "Jl", "1001061133"),
    "1001061134" to NwtBook(30, "Amós", "Am", "1001061134"),
    "1001061135" to NwtBook(31, "Obadias", "Ob", "1001061135"),
    "1001061136" to NwtBook(32, "Jonas", "Jon", "1001061136"),
    "1001061137" to NwtBook(33, "Miqueias", "Miq", "1001061137"),
    "1001061138" to NwtBook(34, "Naum", "Na", "1001061138"),
    "1001061139" to NwtBook(35, "Habacuque", "Hab", "1001061139"),
    "1001061140" to NwtBook(36, "Sofonias", "Sof", "1001061140"),
    "1001061141" to NwtBook(37, "Ageu", "Ag", "1001061141"),
    "1001061142" to NwtBook(38, "Zacarias", "Za", "1001061142"),
    "1001061143" to NwtBook(39, "Malaquias", "Mal", "1001061143"),
    "1001061144" to NwtBook(40, "Mateus", "Mt", "1001061144"),
    "1001061145" to NwtBook(41, "Marcos", "Mr", "1001061145"),
    "1001061146" to NwtBook(42, "Lucas", "Lu", "1001061146"),
    "1001061147" to NwtBook(43, "João", "Jo", "1001061147"),
    "1001061148" to NwtBook(44, "Atos", "At", "1001061148"),
    "1001061149" to NwtBook(45, "Romanos", "Ro", "1001061149"),
    "1001061150" to NwtBook(46, "1 Coríntios", "1Co", "1001061150"),
    "1001061151" to NwtBook(47, "2 Coríntios", "2Co", "1001061151"),
    "1001061152" to NwtBook(48, "Gálatas", "Gál", "1001061152"),
    "1001061153" to NwtBook(49, "Efésios", "Ef", "1001061153"),
    "1001061154" to NwtBook(50, "Filipenses", "Fil", "1001061154"),
    "1001061155" to NwtBook(51, "Colossenses", "Col", "1001061155"),
    "1001061156" to NwtBook(52, "1 Tessalonicenses", "1Te", "1001061156"),
    "1001061157" to NwtBook(53, "2 Tessalonicenses", "2Te", "1001061157"),
    "1001061158" to NwtBook(54, "1 Timóteo", "1Ti", "1001061158"),
    "1001061159" to NwtBook(55, "2 Timóteo", "2Ti", "1001061159"),
    "1001061160" to NwtBook(56, "Tito", "Tit", "1001061160"),
    "1001061161" to NwtBook(57, "Filêmon", "Flm", "1001061161"),
    "1001061162" to NwtBook(58, "Hebreus", "He", "1001061162"),
    "1001061163" to NwtBook(59, "Tiago", "Tg", "1001061163"),
    "1001061164" to NwtBook(60, "1 Pedro", "1Pe", "1001061164"),
    "1001061165" to NwtBook(61, "2 Pedro", "2Pe", "1001061165"),
    "1001061166" to NwtBook(62, "1 João", "1Jo", "1001061166"),
    "1001061167" to NwtBook(63, "2 João", "2Jo", "1001061167"),
    "1001061168" to NwtBook(64, "3 João", "3Jo", "1001061168"),
    "1001061169" to NwtBook(65, "Judas", "Ju", "1001061169"),
    "1001061170" to NwtBook(66, "Apocalipse", "Ap", "1001061170"),
)

/**
 * Mapa de nome completo ("Gênesis") para abreviação TNM 2015 ("Gên").
 * Inclui aliases para divergências entre os rótulos do RefDetector e o
 * catálogo: "Cânticos"/"Cantares" (catálogo: "Cântico de Salomão") e
 * "Filemom" (catálogo: "Filêmon").
 *
 * DÉBITO TÉCNICO (3.2.3a-fix): RefDetector não distingue "Jó" de "João"
 * porque ambos normalizam para a chave "jo". Ref "Jó 14:1" é interpretada
 * como "João 14:1". Requer mudança no matcher do detectBible (sensível ao
 * texto cru) — fora do escopo desta tarefa.
 */
val NWT_NAME_TO_ABBREV: Map<String, String> = buildMap {
    NWT_BOOKS.values.forEach { put(it.name.lowercase(), it.abbrev) }
    put("cânticos", "Cân")
    put("cantares", "Cân")
    put("filemom", "Flm")
}

/**
 * Normaliza nome de livro para a abreviação TNM 2015. Aceita variações
 * comuns (case-insensitive, espaços múltiplos). Retorna null se não reconhecer.
 */
fun normalizeBibleBookName(name: String): String? {
    val key = name.trim().lowercase().replace(Regex("\\s+"), " ")
    return NWT_NAME_TO_ABBREV[key]
}
