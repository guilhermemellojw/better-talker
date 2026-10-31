package com.bettertalker.app.matrix

import android.content.Context
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.repo.ImportException
import com.bettertalker.app.data.repo.LibraryRepository
import com.bettertalker.app.data.util.ImportLimits
import com.bettertalker.app.data.util.RefDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Baixa/indexa as edições citadas na matriz C2.6.
 *
 * Idempotente: edição já pronta é pulada; placeholder baixando/indexando é
 * aguardado. Downloads usam a API oficial e a ordem JWPUB → EPUB → PDF → RTF.
 * O índice é o mesmo `LibraryRepository.importFile` do app (WorkManager +
 * IndexPublicationWorker), então o que a matriz mede é o pipeline real.
 */
class AcervoBuilder(
    private val ctx: Context,
    private val db: AppDatabase,
    private val repo: LibraryRepository,
    private val log: (String) -> Unit,
) {
    companion object {
        const val MAX_BYTES = ImportLimits.LIBRARY_BYTES
        const val INDEX_TIMEOUT_MS = 20 * 60 * 1000L
        const val PENDING_TIMEOUT_MS = 20 * 60 * 1000L
    }

    suspend fun ensure(
        rows: List<MatrixRow>,
        onProgress: (String) -> Unit = {},
    ): AcervoReport {
        val started = System.currentTimeMillis()
        val editions = rows.map { it.edition }.distinct()
        val results = mutableListOf<EditionResult>()
        var totalBytes = 0L
        val progress = File(ctx.filesDir, "matriz-c26-acervo-progress.csv")
        progress.writeText("edition,status,format,fileName,attachmentId,bytes,error\n")

        editions.forEachIndexed { index, edition ->
            val label = "[${index + 1}/${editions.size}] $edition"
            onProgress("$label — verificando")
            log("ACERVO $label")
            val result = try {
                ensureOne(edition) { msg -> onProgress("$label — $msg") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                EditionResult(edition, "FAILED", error = e.message?.take(200))
            }
            results += result
            totalBytes += result.bytes
            progress.appendText(
                listOf(
                    result.edition, result.status, result.format.orEmpty(),
                    result.fileName.orEmpty(), result.attachmentId.orEmpty(),
                    result.bytes.toString(), result.error.orEmpty(),
                ).joinToString(",") { csv(it) } + "\n"
            )
            log("ACERVO ${result.edition} → ${result.status} ${result.format.orEmpty()} ${result.error.orEmpty()}")
            onProgress("$label — ${result.status}")
        }

        val formatValidation = mutableListOf<EditionResult>()
        formatValidation += validateFormatOnly("EPUB", editions, onProgress)
        formatValidation += validateFormatOnly("PDF", editions, onProgress)

        return AcervoReport(
            results = results,
            totalBytes = totalBytes,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            formatValidation = formatValidation,
        )
    }

    private suspend fun ensureOne(
        edition: String,
        onProgress: (String) -> Unit,
    ): EditionResult {
        val plan = withContext(Dispatchers.IO) { JwFormatProbe.probe(edition) }
        onProgress("API ${plan.status} ${plan.formats.keys.joinToString("/")}")
        if (plan.formats.isEmpty() || plan.status == "OK_MONTH_FALLBACK") {
            val detected = detectedFor(edition)
            val (pageUrl, hint, exact) = RefDetector.downloadUrl(detected)
            val status = if (exact && pageUrl.isNotBlank()) "PAGE_ONLY" else "UNAVAILABLE"
            return EditionResult(
                edition = edition,
                status = status,
                error = (plan.error ?: if (plan.status == "OK_MONTH_FALLBACK") {
                    "sem arquivo do dia; só a edição mensal"
                } else {
                    hint
                }).take(200),
            )
        }
        val expected = EditionPlan.FORMAT_PREFERENCE
            .firstOrNull { plan.formats.containsKey(it) }
            ?.let { plan.fileNames[it] }
        existingFor(edition, expected)?.let { return it }
        return downloadAndImport(edition, plan, null, onProgress)
    }

    private suspend fun downloadAndImport(
        edition: String,
        plan: JwFormatProbe.PlanData,
        forceFormat: String?,
        onProgress: (String) -> Unit,
    ): EditionResult {
        val format = forceFormat
            ?: EditionPlan.FORMAT_PREFERENCE.firstOrNull { plan.formats.containsKey(it) }
            ?: return EditionResult(edition, "UNAVAILABLE", error = "sem formato preferido")
        val url = plan.formats[format]
            ?: return EditionResult(edition, "UNAVAILABLE", error = "sem URL para $format")
        val fileName = plan.fileNames[format]
            ?: "$edition.${format.lowercase()}"
        val expected = plan.sizes[format] ?: 0L
        if (expected > MAX_BYTES) {
            return EditionResult(edition, "TOO_BIG", format, fileName, bytes = expected, error = "arquivo > 400 MB")
        }
        val tmp = File(ctx.cacheDir, "matrix-${sha1(edition + "|" + format)}.${format.lowercase()}")
        onProgress("baixando $format ${if (expected > 0) "(${expected / 1024 / 1024} MB)" else ""}")
        val bytes = try {
            download(url, tmp, expected)
        } catch (e: Exception) {
            tmp.delete()
            return EditionResult(edition, "FAILED_DOWNLOAD", format, fileName, error = e.message?.take(200))
        }
        if (bytes <= 0) {
            tmp.delete()
            return EditionResult(edition, "FAILED_DOWNLOAD", format, fileName, error = "download vazio")
        }
        if (bytes > MAX_BYTES) {
            tmp.delete()
            return EditionResult(edition, "TOO_BIG", format, fileName, bytes = bytes, error = "arquivo > 400 MB")
        }
        onProgress("indexando ${bytes / 1024 / 1024} MB")
        val attachmentId = try {
            repo.importFile(tmp, fileName, null)
        } catch (e: ImportException) {
            tmp.delete()
            return EditionResult(edition, "FAILED_IMPORT", format, fileName, bytes = bytes, error = e.message?.take(200))
        } catch (e: Exception) {
            tmp.delete()
            return EditionResult(edition, "FAILED_IMPORT", format, fileName, bytes = bytes, error = e.message?.take(200))
        } finally {
            tmp.delete()
        }
        val indexed = awaitIndex(attachmentId, onProgress)
        return indexed.copy(
            edition = edition,
            format = format,
            fileName = fileName,
            expectedFileName = fileName,
            bytes = bytes,
        )
    }

    private suspend fun awaitIndex(
        attachmentId: String,
        onProgress: (String) -> Unit,
    ): EditionResult {
        val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
        var lastStatus = ""
        while (System.currentTimeMillis() < deadline) {
            val att = db.attachmentDao().get(attachmentId)
                ?: return EditionResult("", "INDEX_FAILED", attachmentId = attachmentId, error = "anexo sumiu")
            if (att.indexed) {
                return EditionResult(
                    edition = "",
                    status = "READY",
                    format = att.kind.uppercase(),
                    fileName = att.fileName,
                    attachmentId = att.id,
                )
            }
            if (att.status == "failed") {
                return EditionResult(
                    edition = "",
                    status = "INDEX_FAILED",
                    format = att.kind.uppercase(),
                    fileName = att.fileName,
                    attachmentId = att.id,
                    error = att.error?.take(200),
                )
            }
            if (att.status != lastStatus) {
                lastStatus = att.status
                onProgress("index ${att.status}")
            }
            delay(2_000)
        }
        return EditionResult(
            edition = "",
            status = "INDEX_FAILED",
            attachmentId = attachmentId,
            error = "timeout de indexação",
        )
    }

    /**
     * Só aceita o arquivo EXATO que a API indica para a edição. O matcher do
     * app é deliberadamente frouxo para citações antigas; usá-lo aqui fazia
     * `w|2020|3` "encontrar" `w_T_20030715.jwpub` e pular o download correto.
     * A matriz mede o matcher depois; o acervo precisa estar certo.
     */
    private suspend fun existingFor(
        edition: String,
        expectedFileName: String?,
    ): EditionResult? {
        if (expectedFileName.isNullOrBlank()) return null
        val hit = db.attachmentDao().all().firstOrNull { it.fileName == expectedFileName }
            ?: return null
        if (hit.indexed && hit.status == "ready") {
            return EditionResult(
                edition = edition,
                status = "READY",
                format = hit.kind.uppercase(),
                fileName = hit.fileName,
                expectedFileName = expectedFileName,
                attachmentId = hit.id,
                bytes = hit.sizeBytes,
            )
        }
        if (hit.status == "failed") {
            return EditionResult(
                edition = edition,
                status = "INDEX_FAILED",
                format = hit.kind.uppercase(),
                fileName = hit.fileName,
                expectedFileName = expectedFileName,
                attachmentId = hit.id,
                bytes = hit.sizeBytes,
                error = hit.error?.take(200),
            )
        }
        // Placeholder baixando/indexando: espera o pipeline terminar.
        val indexed = awaitIndex(hit.id) { }
        return indexed.copy(edition = edition, expectedFileName = expectedFileName)
    }

    private suspend fun download(url: String, dst: File, expected: Long): Long =
        withContext(Dispatchers.IO) {
            val part = File(dst.parentFile, dst.name + ".part")
            part.delete()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BetterTalker-Matrix/1.0")
                setRequestProperty("Accept", "*/*")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
                if (total > MAX_BYTES) throw IllegalStateException("arquivo > 400 MB")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > MAX_BYTES) throw IllegalStateException("arquivo > 400 MB")
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                dst.length()
            } finally {
                conn.disconnect()
                part.delete()
            }
        }

    private suspend fun validateFormatOnly(
        target: String,
        matrixEditions: List<String>,
        onProgress: (String) -> Unit,
    ): EditionResult {
        val other = if (target == "EPUB") "PDF" else "EPUB"
        val extras = listOf(
            "w|1990|1|1", "w|1995|1|1", "g|1990|1|8", "g|1995|1|8",
            "w|2000|1|1", "g|2000|1|8", "w|2005|1|1", "g|2005|1|8",
            "w|2010|1|1", "g|2010|1|8", "w|2013|1|1", "g|2013|1|8",
        )
        val candidates = (matrixEditions + extras).distinct()
        onProgress("validando formato só-$target")
        for (edition in candidates) {
            val plan = withContext(Dispatchers.IO) { JwFormatProbe.probe(edition) }
            val formats = plan.formats.keys
            if (target in formats && "JWPUB" !in formats && other !in formats) {
                log("FORMATO só-$target: $edition (${plan.formats.keys})")
                val result = downloadAndImport(edition, plan, target, onProgress)
                return result.copy(edition = edition, format = target)
            }
        }
        // Não há edição exclusiva no acervo/região: valida o PIPELINE real
        // com uma edição que oferece o formato (mesmo tendo JWPUB/PDF).
        val fallback = listOf("w|2025|7", "w|2015|4|15", "w|2012|4|15").distinct()
        for (edition in fallback) {
            val plan = withContext(Dispatchers.IO) { JwFormatProbe.probe(edition) }
            if (target in plan.formats.keys) {
                log("FORMATO pipeline-$target: $edition (${plan.formats.keys})")
                val result = downloadAndImport(edition, plan, target, onProgress)
                return result.copy(
                    edition = edition,
                    format = target,
                    error = "pipeline; edição também oferece outros formatos",
                )
            }
        }
        log("FORMATO só-$target: nenhuma edição encontrada")
        return EditionResult(
            edition = "NONE",
            status = "NO_${target}_ONLY",
            format = target,
            error = "nenhuma edição só-$target entre ${candidates.size} candidatas",
        )
    }

    private fun detectedFor(edition: String): RefDetector.DetectedRef {
        val p = edition.split("|")
        val book = p.firstOrNull() == "book"
        val pubKey = if (book) (p.getOrNull(1) ?: edition) else (p.firstOrNull() ?: edition)
        return RefDetector.DetectedRef(
            raw = edition,
            kind = if (book) RefDetector.Kind.BOOK else RefDetector.Kind.MAGAZINE,
            pubKey = pubKey,
            editionKey = edition,
            label = edition,
            ambiguous = false,
        )
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun csv(value: String): String {
        val v = value.replace("\n", " ").replace("\r", " ")
        return if (v.contains(',') || v.contains('"')) "\"${v.replace("\"", "\"\"")}\"" else v
    }
}
