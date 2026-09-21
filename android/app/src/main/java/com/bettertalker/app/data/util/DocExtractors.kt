package com.bettertalker.app.data.util

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extração de texto por formato (offline, sem dependências além do pdfbox).
 * Reutilizado pelo indexador e pela importação de esboços.
 */
object DocExtractors {
    const val MAX_TEXT = 400_000
    const val MAX_PDF_PAGES = 60
    const val MAX_ZIP_FILES = 40
    const val MAX_EPUB_FILES = 40

    fun extract(ctx: Context, file: File, kind: DocKind): String = when (kind) {
        DocKind.EPUB -> readEpub(file)
        DocKind.DOCX -> readDocx(file)
        DocKind.RTF -> stripRtf(readTxt(file))
        DocKind.ZIP -> readZipRtfs(file)
        DocKind.TXT -> readTxt(file)
        DocKind.PDF -> readPdf(ctx, file)
        DocKind.JWPUB -> JwpubExtractor.extract(ctx, file)
        DocKind.UNSUPPORTED -> ""
    }

    fun readPdf(ctx: Context, f: File): String {
        return try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            PDDocument.load(f).use { doc ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(MAX_PDF_PAGES, doc.numberOfPages)
                stripper.getText(doc).take(MAX_TEXT)
            }
        } catch (_: Exception) { "" }
    }

    fun readEpub(f: File): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                var count = 0
                while (entry != null && count < MAX_EPUB_FILES && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".xhtml") || name.endsWith(".html") ||
                        name.endsWith(".xml") || name.endsWith(".opf")
                    ) {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..300_000) {
                            sb.append(String(bytes, Charsets.UTF_8)).append(' ')
                            count++
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            stripXml(sb.toString())
        } catch (_: Exception) { "" }
    }

    /** DOCX é zip: extrai word/document.xml (+ footnotes/endnotes). Sem dependência. */
    fun readDocx(f: File): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (name == "word/document.xml" || name == "word/footnotes.xml" || name == "word/endnotes.xml") {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..500_000) {
                            // w:t contém o texto; parágrafos, quebras, células e linhas
                            // de tabela (abertura E fechamento) viram quebra de linha
                            var xml = String(bytes, Charsets.UTF_8)
                            // runs adjacentes colam sem espaço (podem partir palavra no meio,
                            // inclusive com <w:r> entre eles: </w:t></w:r><w:r><w:t>)
                            xml = xml.replace(Regex("</w:t>\\s*(</w:r>\\s*<w:r[^>]*>)?\\s*<w:t[^>]*>"), "")
                            xml = xml.replace(Regex("</w:(p|tc|tr|tbl)[^>]*>"), "\n")
                            xml = xml.replace(Regex("<w:(p|br|tab|tc|tr)[^>]*/?>"), "\n")
                            sb.append(stripXml(xml)).append('\n')
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            // colapsa 3+ quebras (tabelas geram muitas) preservando separação
            sb.toString().replace(Regex("\n{3,}"), "\n\n")
        } catch (_: Exception) { "" }
    }

    /** ZIP de RTFs: indexa TODOS os .rtf encontrados, separados por marcador. */
    fun readZipRtfs(f: File): String {
        return try {
            val sb = StringBuilder()
            var count = 0
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null && count < MAX_ZIP_FILES && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && name.endsWith(".rtf")) {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..300_000) {
                            val raw = decodeBytes(bytes)
                            sb.append("\n\n=== ${entry.name} ===\n")
                            sb.append(stripRtf(raw))
                            count++
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }

    fun readTxt(f: File): String {
        return try {
            // leitura limitada por stream (nunca o arquivo inteiro na RAM)
            val buf = ByteArrayOutputStream()
            f.inputStream().buffered().use { ins ->
                val tmp = ByteArray(64 * 1024)
                var total = 0
                while (total < MAX_TEXT * 2) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    total += n
                }
            }
            decodeBytes(buf.toByteArray())
        } catch (_: Exception) { "" }
    }
}
