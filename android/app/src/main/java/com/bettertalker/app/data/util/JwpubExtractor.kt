package com.bettertalker.app.data.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Lê texto de .jwpub (offline, somente leitura, sem dependências):
 * ZIP externo (manifest.json + contents) -> ZIP interno (*.db SQLite) ->
 * tabela Document (Content = AES-128-CBC + zlib), chave derivada dos
 * metadados da Publication. Uso pessoal BYOD; nunca altera o original.
 *
 * Falha com mensagem específica quando o esquema não é suportado.
 */
object JwpubExtractor {
    const val MAX_DOCS = 20_000

    /**
     * Teto de texto extraído. Medido no arquivo real: o Estudo Perspicaz
     * unificado tem 12,43M chars em 5.023 docs — 16M dá folga para edições
     * futuras (~32MB de buffer). O teto antigo (8M) cortava o it na letra M.
     */
    const val MAX_TEXT = 16_000_000

    /**
     * Teto do conteúdo interno (zip externo + banco descomprimido). O antigo
     * 120MB bloqueava o `rsg` (>120MB de banco) e o `it` (>120MB de contents).
     */
    const val MAX_DB_BYTES = 400L * 1024 * 1024

    private const val XOR_CONST_HEX =
        "11cbb5587e32846d4c26790c633da289f66fe5842a3a585ce1bc3a294af5ada7"

    class JwpubException(msg: String) : Exception(msg)

    /** ZIP externo lido em streaming: manifest em memória, contents em arquivo. */
    internal data class Outer(val manifestJson: String, val contentsFile: File)

    fun extract(ctx: Context, file: File): String = extractTo(file, ctx.cacheDir)

    /**
     * Extrai o texto com o `contents` **em arquivo** (nunca em RAM): o zip
     * interno descomprimido passa de 300MB no it — carregá-lo causaria OOM.
     */
    internal fun extractTo(file: File, cacheDir: File): String {
        val outer = try {
            readOuterTo(file, cacheDir)
        } catch (e: JwpubException) {
            throw e
        } catch (_: Exception) {
            throw JwpubException(
                "JWPUB inválido ou corrompido (ZIP/manifest). " +
                    "Tente reindexar; se persistir, baixe o EPUB."
            )
        }
        // UUID: o reindexAll enfileira vários JWPUBs; millis colidia e dois
        // workers compartilhavam o MESMO .db/.zip de cache (CRC/ENOENT).
        val dbFile = File(cacheDir, "jwpub-${java.util.UUID.randomUUID()}.db")
        try {
            val dbName = manifestDbName(outer.manifestJson)
            unzipDb(outer.contentsFile, dbName, dbFile)
            return readDb(dbFile)
        } finally {
            runCatching { dbFile.delete() }
            runCatching { outer.contentsFile.delete() }
        }
    }

    /**
     * Lê o ZIP externo em streaming: `manifest.json` (pequeno) para memória e
     * `contents` para arquivo no cache, com teto [maxBytes] no total.
     */
    internal fun readOuterTo(
        file: File,
        cacheDir: File,
        maxBytes: Long = MAX_DB_BYTES,
    ): Outer {
        var manifest: String? = null
        var contents: File? = null
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            var total = 0L
            var entries = 0
            while (entry != null && entries < 8) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && (name == "manifest.json" || name == "contents")) {
                    if (name == "manifest.json") {
                        val bytes = zin.readBytes()
                        total += bytes.size
                        if (total > maxBytes) throw JwpubException("Arquivo .jwpub muito grande.")
                        manifest = String(bytes, Charsets.UTF_8)
                    } else {
                        val dst = File(
                            cacheDir,
                            "jwpub-contents-${java.util.UUID.randomUUID()}.zip",
                        )
                        dst.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) {
                                    dst.delete()
                                    throw JwpubException("Arquivo .jwpub muito grande.")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                        contents = dst
                    }
                    entries++
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        val c = contents ?: throw JwpubException(
            "JWPUB sem conteúdo interno. Tente reindexar; se persistir, baixe o EPUB."
        )
        val m = manifest ?: throw JwpubException(
            "JWPUB sem manifest. Tente reindexar; se persistir, baixe o EPUB."
        )
        return Outer(m, c)
    }

    /** Nome do .db interno via manifest (fallback: primeiro *.db). */
    fun manifestDbName(manifestJson: String): String? {
        // "\"fileName\"\\s*:\\s*\"([^\"]+\\.db)\"" sem regex pesada
        val m = Regex("\"fileName\"\\s*:\\s*\"([^\"]+\\.db)\"").find(manifestJson)
        return m?.groupValues?.get(1)?.substringAfterLast('/')
    }

    internal fun unzipDb(
        innerFile: File,
        dbName: String?,
        dst: File,
        maxBytes: Long = MAX_DB_BYTES,
    ) {
        var wrote = false
        ZipInputStream(innerFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            var total = 0L
            while (entry != null && !wrote) {
                val name = entry.name
                if (!entry.isDirectory && (dbName == null || name.endsWith(dbName) || name.endsWith(".db")) &&
                    !name.contains("..")
                ) {
                    // pega o primeiro .db (ou o do manifest); ignora imagens
                    if (dbName == null && !name.endsWith(".db")) {
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    dst.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw JwpubException("Banco interno muito grande.")
                            out.write(buf, 0, n)
                        }
                    }
                    wrote = true
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (!wrote) throw JwpubException(
            "Banco interno não encontrado no JWPUB. Tente reindexar; se persistir, baixe o EPUB."
        )
    }

    private fun readDb(dbFile: File): String {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val card = readCard(db)
            val (key, iv) = computeKeyIv(card)
            val sb = StringBuilder()
            var count = 0
            db.rawQuery(
                "SELECT DocumentId, Title, TocTitle, Content, FirstPageNumber, LastPageNumber, " +
                    // lmd (schema 9) não tem a coluna ContentLength; `length(Content)`
                    // devolve o tamanho do blob e mantém a estimativa de página.
                    "length(Content) AS ContentLength FROM Document ORDER BY DocumentId",
                null
            ).use { c ->
                val iDoc = c.getColumnIndex("DocumentId")
                val iTitle = c.getColumnIndex("Title")
                val iToc = c.getColumnIndex("TocTitle")
                val iContent = c.getColumnIndex("Content")
                val iFirst = c.getColumnIndex("FirstPageNumber")
                val iLast = c.getColumnIndex("LastPageNumber")
                val iLen = c.getColumnIndex("ContentLength")
                while (c.moveToNext() && count < MAX_DOCS && sb.length < MAX_TEXT) {
                    val title = (c.getString(iToc)?.takeIf { it.isNotBlank() }
                        ?: c.getString(iTitle).orEmpty()).trim()
                    if (title.isNotEmpty()) sb.append("\n\n# ").append(title).append('\n')
                    if (!c.isNull(iContent)) {
                        val html = decryptInflate(c.getBlob(iContent), key, iv)
                            ?: throw JwpubException(
                                "Esquema do JWPUB não suportado. Tente reindexar; " +
                                    "se persistir, baixe o EPUB."
                            )
                        // RC1-pages: §§ do artigo ganham a página estimada
                        // (Document.First/LastPageNumber + posição do §).
                        val pages = paragraphPages(
                            labeledPositions = labeledParagraphPositions(db, c.getLong(iDoc)),
                            firstPage = c.getInt(iFirst),
                            lastPage = c.getInt(iLast),
                            contentLength = c.getInt(iLen),
                        )
                        val marked = markParagraphs(html)
                        sb.append(
                            if (marked.contains("=== PAGE:")) marked
                            else withPageMarkers(marked, pages)
                        ).append('\n')
                    }
                    count++
                }
            }
            val text = sb.toString()
            if (text.isBlank()) throw JwpubException("Nenhum texto legível no .jwpub.")
            return text
        } catch (e: JwpubException) {
            throw e
        } catch (e: android.database.sqlite.SQLiteException) {
            throw JwpubException(
                "Banco interno do JWPUB inválido (${e.message?.take(80) ?: "SQLite"}). " +
                    "Tente reindexar; se persistir, baixe o EPUB."
            )
        } catch (_: Exception) {
            throw JwpubException(
                "Não foi possível ler o JWPUB (arquivo inválido ou corrompido). " +
                    "Tente reindexar; se persistir, baixe o EPUB."
            )
        } finally {
            runCatching { db?.close() }
        }
    }

    /**
     * T4b — preserva o NÚMERO do parágrafo numerado do JWPUB como marcador
     * `§N` no texto extraído. O HTML marca o número como primeiro filho do
     * parágrafo (`<p ...><strong>4.</strong> …`); índices (`class="si"`) e
     * subtítulos (`class="sn"`) ficam de fora (números de lista não são
     * parágrafos do artigo). O index então guarda `paragraph` e a citação
     * "it “Gedalias” n.° 4" resolve no parágrafo exato. Puro/testável.
     *
     * exp/chat-quality (2026-10-21): a regra acima acerta a PERGUNTA do
     * esboço (`<p class="qu"><strong>8.</strong> Em que armadilha Baruque
     * caiu?</p>`), NÃO o texto do parágrafo — e o texto do § ficava sem
     * numeração (o `<span class="parNum" data-pnum="8">` do corpo não era
     * reconhecido). Resultado: "w25.07 22 §§ 8-9" resolvia para o TÍTULO do
     * parágrafo, e o corpo saía com `paragraph = null`.
     *
     * A regra nova (PAR_NUM_RE) marca o CORPO pelo atributo `data-pnum`, que
     * é o número REAL do parágrafo (`ParagraphNumberLabel` no banco interno).
     * As duas regras convivem: a antiga cobre publicações sem `data-pnum`.
     */
    internal fun markParagraphs(html: String): String =
        stripXml(
            PAR_NUM_RE.replace(
                PAGE_NUM_RE.replace(html) { m ->
                    "\n=== PAGE: ${m.groupValues[1]} ===\n"
                }.let { withPages ->
                    NUMBERED_P_RE.replace(withPages) { m ->
                        m.groupValues[1] + "\n§" + m.groupValues[2] + " "
                    }
                }
            ) { m ->
                "§" + m.groupValues[1] + " "
            }
        )

    /**
     * JWPUB antigo (g 2013/wp 2019) traz a página impressa no próprio HTML:
     * `<span id="page6" class="pageNum" data-no="6">`. Usar esse marcador é
     * mais fiel que estimar a página pela posição do §.
     */
    private val PAGE_NUM_RE = Regex(
        """<span[^>]*class="[^"]*pageNum[^"]*"[^>]*data-no="(\d{1,4})"[^>]*>.*?</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val NUMBERED_P_RE = Regex(
        """(<p\b(?![^>]*class="(?:si|sn)")[^>]*>)\s*<strong>(\d{1,3})\.</strong>"""
    )

    /**
     * exp/chat-quality (2026-10-21): o número do parágrafo no JWPUB vem em
     * `<span class="parNum" data-pnum="N">…</span>` (o `N` é o
     * ParagraphNumberLabel — o mesmo número impresso na revista).
     */
    private val PAR_NUM_RE = Regex("""<span[^>]*data-pnum="(\d{1,3})"[^>]*>.*?</span>""")

    /** RC1-pages: posições dos §§ numerados do artigo (label → BeginPosition). */
    private fun labeledParagraphPositions(db: SQLiteDatabase, docId: Long): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        // Esquemas antigos (g 2013, user_version 7) não têm
        // ParagraphNumberLabel. Sem a coluna, ParagraphIndex é a melhor
        // aproximação posicional; se nem ela existir, não inventa página.
        val columns = mutableSetOf<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info(DocumentParagraph)", null).use { c ->
                val iName = c.getColumnIndex("name")
                while (c.moveToNext()) columns += c.getString(iName).orEmpty()
            }
        }
        val labelColumn = when {
            "ParagraphNumberLabel" in columns -> "ParagraphNumberLabel"
            "ParagraphIndex" in columns -> "ParagraphIndex"
            else -> return emptyList()
        }
        if ("BeginPosition" !in columns) return emptyList()
        db.rawQuery(
            "SELECT $labelColumn, BeginPosition FROM DocumentParagraph " +
                "WHERE DocumentId = ? AND $labelColumn IS NOT NULL",
            arrayOf(docId.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.getInt(0) to c.getInt(1)
        }
        return out
    }

    /**
     * RC1-pages: injeta "=== PAGE: N ===" antes de cada "§N" com página no
     * mapa (o TextNorm lê o marcador e o PassageEntity ganha `page`). Puro.
     */
    internal fun withPageMarkers(markedText: String, pageOfLabel: Map<Int, Int>): String =
        Regex("(?m)^§(\\d{1,3}) ").replace(markedText) { m ->
            val page = pageOfLabel[m.groupValues[1].toInt()]
            if (page != null) "=== PAGE: $page ===\n${m.value}" else m.value
        }

    /**
     * RC1-pages: página estimada de cada § do artigo — o JWPUB tem a página
     * POR ARTIGO (First/Last) e a posição de cada §; a fração da posição no
     * tamanho do artigo mapeia no intervalo. Vazio sem dados. Puro/testável.
     */
    internal fun paragraphPages(
        labeledPositions: List<Pair<Int, Int>>,
        firstPage: Int,
        lastPage: Int,
        contentLength: Int,
    ): Map<Int, Int> {
        if (firstPage <= 0 || contentLength <= 0 || lastPage < firstPage) return emptyMap()
        val span = lastPage - firstPage + 1
        val out = mutableMapOf<Int, Int>()
        for ((label, begin) in labeledPositions) {
            if (label > 0) {
                out[label] = firstPage +
                    ((begin.toDouble() / contentLength.toDouble()) * span).toInt()
            }
        }
        return out
    }

    private fun readCard(db: SQLiteDatabase): String {
        db.rawQuery(
            "SELECT MepsLanguageIndex, Symbol, Year, IssueTagNumber FROM Publication LIMIT 1", null
        ).use { c ->
            if (!c.moveToFirst()) throw JwpubException("Publicação não identificada no .jwpub.")
            val lang = c.getInt(0)
            val symbol = c.getString(1).orEmpty()
            val year = c.getInt(2)
            val issue = c.getString(3).orEmpty()
            if (symbol.isBlank() || year <= 0) throw JwpubException("Publicação não identificada no .jwpub.")
            return if (issue.isNotBlank() && issue != "0") "${lang}_${symbol}_${year}_$issue"
            else "${lang}_${symbol}_${year}"
        }
    }

    /** SHA256(cartão) XOR constante -> 16 bytes chave + 16 IV. Puro/testável. */
    fun computeKeyIv(card: String): Pair<ByteArray, ByteArray> {
        val h = MessageDigest.getInstance("SHA-256").digest(card.toByteArray(Charsets.UTF_8))
        val k = XOR_CONST_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val x = ByteArray(32) { i -> (h[i].toInt() xor k[i].toInt()).toByte() }
        return x.copyOfRange(0, 16) to x.copyOfRange(16, 32)
    }

    /** AES-128-CBC + zlib inflate. Retorna null se não abrir (esquema diferente). Puro/testável. */
    fun decryptInflate(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val raw = cipher.doFinal(blob)
            val inflater = java.util.zip.Inflater()
            inflater.setInput(raw)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_TEXT * 2) break
            }
            inflater.end()
            String(out.toByteArray(), Charsets.UTF_8).ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    // assinatura estendida (reservada p/ variantes futuras)
    @Suppress("UNUSED_PARAMETER")
    private fun decryptInflate(
        blob: ByteArray, key: ByteArray, iv: ByteArray, issueTag: String, card: String
    ): String? = decryptInflate(blob, key, iv)
}
