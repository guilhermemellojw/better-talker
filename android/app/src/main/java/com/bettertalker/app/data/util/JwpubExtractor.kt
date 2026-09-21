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
    const val MAX_DOCS = 5000
    const val MAX_TEXT = 400_000
    const val MAX_DB_BYTES = 120L * 1024 * 1024

    private const val XOR_CONST_HEX =
        "11cbb5587e32846d4c26790c633da289f66fe5842a3a585ce1bc3a294af5ada7"

    class JwpubException(msg: String) : Exception(msg)

    fun extract(ctx: Context, file: File): String {
        val outer: Map<String, ByteArray>
        try {
            outer = readOuter(file)
        } catch (_: Exception) {
            throw JwpubException("Arquivo .jwpub inválido.")
        }
        val manifestRaw = outer["manifest.json"]
            ?: throw JwpubException("Arquivo .jwpub inválido (sem manifest).")
        val dbName = manifestDbName(String(manifestRaw, Charsets.UTF_8))
        val innerZip = outer["contents"]
            ?: throw JwpubException("Arquivo .jwpub inválido (sem conteúdo).")
        val dbFile = File(ctx.cacheDir, "jwpub-${System.currentTimeMillis()}.db")
        try {
            unzipDb(innerZip, dbName, dbFile)
            return readDb(ctx, dbFile)
        } finally {
            runCatching { dbFile.delete() }
        }
    }

    private fun readOuter(file: File): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            var total = 0L
            while (entry != null && out.size < 8) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && (name == "manifest.json" || name == "contents")) {
                    val bytes = zin.readBytes()
                    total += bytes.size
                    if (total > MAX_DB_BYTES) throw JwpubException("Arquivo .jwpub muito grande.")
                    out[name] = bytes
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (out.isEmpty()) throw JwpubException("Arquivo .jwpub inválido.")
        return out
    }

    /** Nome do .db interno via manifest (fallback: primeiro *.db). */
    fun manifestDbName(manifestJson: String): String? {
        // "\"fileName\"\\s*:\\s*\"([^\"]+\\.db)\"" sem regex pesada
        val m = Regex("\"fileName\"\\s*:\\s*\"([^\"]+\\.db)\"").find(manifestJson)
        return m?.groupValues?.get(1)?.substringAfterLast('/')
    }

    private fun unzipDb(innerZip: ByteArray, dbName: String?, dst: File) {
        var wrote = false
        ZipInputStream(innerZip.inputStream().buffered()).use { zin ->
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
                            if (total > MAX_DB_BYTES) throw JwpubException("Banco interno muito grande.")
                            out.write(buf, 0, n)
                        }
                    }
                    wrote = true
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (!wrote) throw JwpubException("Banco de dados não encontrado no .jwpub.")
    }

    private fun readDb(ctx: Context, dbFile: File): String {
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
                "SELECT Title, TocTitle, Content FROM Document ORDER BY DocumentId", null
            ).use { c ->
                val iTitle = c.getColumnIndex("Title")
                val iToc = c.getColumnIndex("TocTitle")
                val iContent = c.getColumnIndex("Content")
                while (c.moveToNext() && count < MAX_DOCS && sb.length < MAX_TEXT) {
                    val title = (c.getString(iToc)?.takeIf { it.isNotBlank() }
                        ?: c.getString(iTitle).orEmpty()).trim()
                    if (title.isNotEmpty()) sb.append("\n\n# ").append(title).append('\n')
                    if (!c.isNull(iContent)) {
                        val html = decryptInflate(c.getBlob(iContent), key, iv)
                            ?: throw JwpubException(
                                "Esquema de criptografia não suportado — baixe EPUB ou PDF no site."
                            )
                        sb.append(stripXml(html)).append('\n')
                    }
                    count++
                }
            }
            val text = sb.toString()
            if (text.isBlank()) throw JwpubException("Nenhum texto legível no .jwpub.")
            return text
        } catch (e: JwpubException) {
            throw e
        } catch (_: Exception) {
            throw JwpubException("Não foi possível ler o .jwpub (arquivo inválido ou corrompido).")
        } finally {
            runCatching { db?.close() }
        }
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
