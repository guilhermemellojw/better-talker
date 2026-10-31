package com.bettertalker.app.data.backup

import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.domain.speech.DiscourseType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Codec do backup local. Puro (org.json), testável na JVM com o artefato
 * `org.json:json`. O ZIP tem só dois entries:
 *
 * - `manifest.json`: formato, versão, contagens e SHA-256 do data.json;
 * - `data.json`: arrays das entidades do usuário.
 *
 * O checksum é calculado sobre os BYTES exatos do data.json; não depende da
 * ordem das chaves.
 */
object BackupCodec {
    const val FORMAT = "better-talker-backup"
    const val SCHEMA_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"

    /** Teto defensivo contra ZIP bomba; o backup normal fica em KB. */
    const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
    const val MAX_DATA_BYTES = 32 * 1024 * 1024

    fun encodeZip(
        snapshot: BackupSnapshot,
        appVersion: String,
        versionCode: Int,
        dbVersion: Int,
        exportedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        val data = encodeData(snapshot)
        if (data.size > MAX_DATA_BYTES) {
            throw BackupValidationException(
                "dados do backup excedem o limite de ${MAX_DATA_BYTES / (1024 * 1024)} MB",
            )
        }
        val manifest = BackupManifest(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
            appVersion = appVersion,
            versionCode = versionCode,
            dbVersion = dbVersion,
            exportedAt = exportedAt,
            counts = snapshot.counts(),
            dataSha256 = sha256Hex(data),
        )
        val manifestBytes = manifestToJson(manifest).toString(2).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(data)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun readZip(input: InputStream, currentDbVersion: Int): BackupPreview {
        try {
            var manifestBytes: ByteArray? = null
            var dataBytes: ByteArray? = null
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifestBytes =
                                readLimited(zip, MAX_MANIFEST_BYTES)
                            DATA_ENTRY -> dataBytes =
                                readLimited(zip, MAX_DATA_BYTES)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifestRaw = manifestBytes
                ?: throw BackupValidationException("manifest.json ausente no ZIP")
            val dataRaw = dataBytes
                ?: throw BackupValidationException("data.json ausente no ZIP")
            val manifest = manifestFromJson(
                JSONObject(String(manifestRaw, Charsets.UTF_8)),
            )
            validateManifest(manifest, currentDbVersion)
            val actualHash = sha256Hex(dataRaw)
            if (!actualHash.equals(manifest.dataSha256, ignoreCase = true)) {
                throw BackupValidationException(
                    "checksum do data.json não confere; o backup pode estar corrompido",
                )
            }
            val snapshot = decodeData(dataRaw)
            val actualCounts = snapshot.counts()
            if (actualCounts != manifest.counts) {
                throw BackupValidationException(
                    "contagens do manifesto não conferem com o data.json",
                )
            }
            validateSnapshot(snapshot)
            return BackupPreview(manifest, snapshot)
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: Exception) {
            throw BackupValidationException(
                "backup inválido: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    fun encodeData(snapshot: BackupSnapshot): ByteArray {
        val root = JSONObject()
        root.put("folders", JSONArray(snapshot.folders.map { it.toJson() }))
        root.put("notes", JSONArray(snapshot.notes.map { it.toJson() }))
        root.put("attachments", JSONArray(snapshot.attachments.map { it.toJson() }))
        root.put("outlines", JSONArray(snapshot.outlines.map { it.toJson() }))
        root.put("speechSections", JSONArray(snapshot.speechSections.map { it.toJson() }))
        root.put("subPoints", JSONArray(snapshot.subPoints.map { it.toJson() }))
        root.put("s34Outlines", JSONArray(snapshot.s34Outlines.map { it.toJson() }))
        root.put("s34Sections", JSONArray(snapshot.s34Sections.map { it.toJson() }))
        root.put("s34Subsections", JSONArray(snapshot.s34Subsections.map { it.toJson() }))
        root.put("s34References", JSONArray(snapshot.s34References.map { it.toJson() }))
        root.put("tombstones", JSONArray(snapshot.tombstones.map { it.toJson() }))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeData(bytes: ByteArray): BackupSnapshot {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        fun <T> array(name: String, parse: (JSONObject) -> T): List<T> {
            val arr = root.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        }
        return BackupSnapshot(
            folders = array("folders") { it.toFolder() },
            notes = array("notes") { it.toNote() },
            attachments = array("attachments") { it.toBackupAttachment() },
            outlines = array("outlines") { it.toOutline() },
            speechSections = array("speechSections") { it.toSpeechSection() },
            subPoints = array("subPoints") { it.toSubPoint() },
            s34Outlines = array("s34Outlines") { it.toS34Outline() },
            s34Sections = array("s34Sections") { it.toS34Section() },
            s34Subsections = array("s34Subsections") { it.toS34Subsection() },
            s34References = array("s34References") { it.toS34Reference() },
            tombstones = array("tombstones") { it.toTombstone() },
        )
    }

    fun validateManifest(manifest: BackupManifest, currentDbVersion: Int) {
        if (manifest.format != FORMAT) {
            throw BackupValidationException("formato desconhecido: ${manifest.format}")
        }
        if (manifest.schemaVersion !in 1..SCHEMA_VERSION) {
            throw BackupValidationException(
                "schema do backup (${manifest.schemaVersion}) não é suportado",
            )
        }
        if (manifest.dbVersion !in 1..currentDbVersion) {
            throw BackupValidationException(
                "banco do backup (v${manifest.dbVersion}) é mais novo que o app (v$currentDbVersion)",
            )
        }
        if (manifest.counts.toMap().values.any { it < 0 }) {
            throw BackupValidationException("contagens negativas no manifesto")
        }
        if (!manifest.dataSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            throw BackupValidationException("SHA-256 do manifesto inválido")
        }
    }

    fun validateSnapshot(snapshot: BackupSnapshot) {
        fun <T> unique(name: String, items: List<T>, id: (T) -> String) {
            val seen = HashSet<String>()
            items.forEach {
                val value = id(it)
                if (value.isBlank()) {
                    throw BackupValidationException("$name com id vazio")
                }
                if (!seen.add(value)) {
                    throw BackupValidationException("$name com id duplicado: $value")
                }
            }
        }
        unique("folders", snapshot.folders) { it.id }
        unique("notes", snapshot.notes) { it.id }
        unique("attachments", snapshot.attachments) { it.id }
        unique("outlines", snapshot.outlines) { it.id }
        unique("speechSections", snapshot.speechSections) { it.id }
        unique("subPoints", snapshot.subPoints) { it.id }
        unique("s34Outlines", snapshot.s34Outlines) { it.id }
        unique("s34Sections", snapshot.s34Sections) { it.id }
        unique("s34Subsections", snapshot.s34Subsections) { it.id }
        unique("s34References", snapshot.s34References) { it.id }
        unique("tombstones", snapshot.tombstones) { it.id }

        val folderIds = snapshot.folders.mapTo(HashSet()) { it.id }
        val noteIds = snapshot.notes.mapTo(HashSet()) { it.id }
        val sectionIds = snapshot.speechSections.mapTo(HashSet()) { it.id }
        val s34OutlineIds = snapshot.s34Outlines.mapTo(HashSet()) { it.id }
        val s34SectionIds = snapshot.s34Sections.mapTo(HashSet()) { it.id }
        val s34SubsectionIds = snapshot.s34Subsections.mapTo(HashSet()) { it.id }

        snapshot.notes.forEach {
            if (it.folderId != null && it.folderId !in folderIds) {
                throw BackupValidationException(
                    "nota ${it.id} aponta para pasta ausente ${it.folderId}",
                )
            }
        }
        snapshot.attachments.forEach {
            if (it.noteId != null && it.noteId !in noteIds) {
                throw BackupValidationException(
                    "anexo ${it.id} aponta para nota ausente ${it.noteId}",
                )
            }
        }
        snapshot.outlines.forEach {
            if (it.noteId !in noteIds) {
                throw BackupValidationException(
                    "esboço ${it.id} aponta para nota ausente ${it.noteId}",
                )
            }
        }
        snapshot.speechSections.forEach {
            if (it.noteId !in noteIds) {
                throw BackupValidationException(
                    "seção ${it.id} aponta para nota ausente ${it.noteId}",
                )
            }
        }
        snapshot.subPoints.forEach {
            if (it.sectionId !in sectionIds) {
                throw BackupValidationException(
                    "subponto ${it.id} aponta para seção ausente ${it.sectionId}",
                )
            }
        }
        snapshot.s34Sections.forEach {
            if (it.outlineId !in s34OutlineIds) {
                throw BackupValidationException(
                    "s34 seção ${it.id} aponta para outline ausente ${it.outlineId}",
                )
            }
        }
        snapshot.s34Subsections.forEach {
            if (it.sectionId !in s34SectionIds) {
                throw BackupValidationException(
                    "s34 subseção ${it.id} aponta para seção ausente ${it.sectionId}",
                )
            }
        }
        snapshot.s34References.forEach {
            if (it.outlineId !in s34OutlineIds) {
                throw BackupValidationException(
                    "s34 ref ${it.id} aponta para outline ausente ${it.outlineId}",
                )
            }
            if (it.sectionId != null && it.sectionId !in s34SectionIds) {
                throw BackupValidationException(
                    "s34 ref ${it.id} aponta para seção ausente ${it.sectionId}",
                )
            }
            if (it.subsectionId != null && it.subsectionId !in s34SubsectionIds) {
                throw BackupValidationException(
                    "s34 ref ${it.id} aponta para subseção ausente ${it.subsectionId}",
                )
            }
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---------- manifest ----------

    private fun manifestToJson(m: BackupManifest): JSONObject = JSONObject().apply {
        put("format", m.format)
        put("schemaVersion", m.schemaVersion)
        put("appVersion", m.appVersion)
        put("versionCode", m.versionCode)
        put("dbVersion", m.dbVersion)
        put("exportedAt", m.exportedAt)
        put("dataSha256", m.dataSha256)
        put("counts", JSONObject().apply {
            m.counts.toMap().forEach { (k, v) -> put(k, v) }
        })
    }

    private fun manifestFromJson(o: JSONObject): BackupManifest {
        val countsObj = o.optJSONObject("counts") ?: JSONObject()
        val counts = BackupCounts.fromMap(
            countsObj.keys().asSequence().associateWith { countsObj.optInt(it, 0) },
        )
        return BackupManifest(
            format = o.optString("format", ""),
            schemaVersion = o.optInt("schemaVersion", 0),
            appVersion = o.optString("appVersion", ""),
            versionCode = o.optInt("versionCode", 0),
            dbVersion = o.optInt("dbVersion", 0),
            exportedAt = o.optLong("exportedAt", 0L),
            counts = counts,
            dataSha256 = o.optString("dataSha256", ""),
        )
    }

    // ---------- entity JSON ----------

    private fun FolderEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("colorArgb", colorArgb)
        put("createdAt", createdAt)
    }

    private fun JSONObject.toFolder() = FolderEntity(
        id = getString("id"), name = getString("name"),
        colorArgb = getLong("colorArgb"), createdAt = getLong("createdAt"),
    )

    private fun NoteEntity.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("mdText", mdText)
        put("plainText", plainText); putNullable("folderId", folderId)
        put("colorArgb", colorArgb); put("pinned", pinned); put("trashed", trashed)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("richHtml", richHtml); put("discourseType", discourseType)
    }

    private fun JSONObject.toNote() = NoteEntity(
        id = getString("id"), title = getString("title"), mdText = getString("mdText"),
        plainText = getString("plainText"), folderId = stringOrNull("folderId"),
        colorArgb = getLong("colorArgb"), pinned = optBoolean("pinned", false),
        trashed = optBoolean("trashed", false), createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"), richHtml = optString("richHtml", ""),
        discourseType = optString("discourseType", DiscourseType.S34_DISCOURSE.name),
    )

    private fun BackupAttachment.toJson() = JSONObject().apply {
        put("id", id); putNullable("noteId", noteId); put("fileName", fileName)
        put("kind", kind); put("sizeBytes", sizeBytes); putNullable("baseSlot", baseSlot)
        putNullable("sourceUrl", sourceUrl); put("sourceType", sourceType)
        putNullable("symbol", symbol); put("addedAt", addedAt)
    }

    private fun JSONObject.toBackupAttachment() = BackupAttachment(
        id = getString("id"), noteId = stringOrNull("noteId"),
        fileName = getString("fileName"), kind = getString("kind"),
        sizeBytes = getLong("sizeBytes"), baseSlot = stringOrNull("baseSlot"),
        sourceUrl = stringOrNull("sourceUrl"), sourceType = optString("sourceType", "content"),
        symbol = stringOrNull("symbol"), addedAt = getLong("addedAt"),
    )

    private fun OutlineEntity.toJson() = JSONObject().apply {
        put("id", id); put("noteId", noteId); put("fileName", fileName)
        put("title", title); putNullable("totalMinutes", totalMinutes)
        put("sectionsJson", sectionsJson); put("refsJson", refsJson)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun JSONObject.toOutline() = OutlineEntity(
        id = getString("id"), noteId = getString("noteId"),
        fileName = getString("fileName"), title = getString("title"),
        totalMinutes = intOrNull("totalMinutes"), sectionsJson = getString("sectionsJson"),
        refsJson = optString("refsJson", "[]"), createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
    )

    private fun SpeechSectionEntity.toJson() = JSONObject().apply {
        put("id", id); put("noteId", noteId); put("order", order); put("role", role)
        put("title", title); put("minutes", minutes); put("contentHtml", contentHtml)
        put("bibleRefsJson", bibleRefsJson); put("publicationRefsJson", publicationRefsJson)
        putNullable("methodPrinciple", methodPrinciple); putNullable("objective", objective)
        putNullable("agreedApproach", agreedApproach); putNullable("approvedAt", approvedAt)
        putNullable("tribunaJson", tribunaJson); put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun JSONObject.toSpeechSection() = SpeechSectionEntity(
        id = getString("id"), noteId = getString("noteId"), order = getInt("order"),
        role = getString("role"), title = getString("title"), minutes = getInt("minutes"),
        contentHtml = getString("contentHtml"), bibleRefsJson = getString("bibleRefsJson"),
        publicationRefsJson = getString("publicationRefsJson"),
        methodPrinciple = stringOrNull("methodPrinciple"),
        objective = stringOrNull("objective"), agreedApproach = stringOrNull("agreedApproach"),
        approvedAt = longOrNull("approvedAt"), tribunaJson = stringOrNull("tribunaJson"),
        createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"),
    )

    private fun SubPointEntity.toJson() = JSONObject().apply {
        put("id", id); put("sectionId", sectionId); put("order", order)
        put("outlineText", outlineText); put("bibleRefsJson", bibleRefsJson)
        put("publicationRefsJson", publicationRefsJson); putNullable("instruction", instruction)
        put("developedHtml", developedHtml); put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun JSONObject.toSubPoint() = SubPointEntity(
        id = getString("id"), sectionId = getString("sectionId"), order = getInt("order"),
        outlineText = getString("outlineText"), bibleRefsJson = getString("bibleRefsJson"),
        publicationRefsJson = getString("publicationRefsJson"),
        instruction = stringOrNull("instruction"), developedHtml = getString("developedHtml"),
        createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"),
    )

    private fun S34OutlineEntity.toJson() = JSONObject().apply {
        put("id", id); put("sourceAttachmentId", sourceAttachmentId)
        put("symbol", symbol); put("title", title); putNullable("objective", objective)
        put("headerLinesJson", headerLinesJson); put("createdAt", createdAt)
        put("updatedAt", updatedAt); put("parserVersion", parserVersion)
    }

    private fun JSONObject.toS34Outline() = S34OutlineEntity(
        id = getString("id"), sourceAttachmentId = getString("sourceAttachmentId"),
        symbol = getString("symbol"), title = getString("title"),
        objective = stringOrNull("objective"), headerLinesJson = optString("headerLinesJson", "[]"),
        createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"),
        parserVersion = optInt("parserVersion", 1),
    )

    private fun S34SectionEntity.toJson() = JSONObject().apply {
        put("id", id); put("outlineId", outlineId); put("order", order)
        put("title", title); put("content", content); putNullable("minutes", minutes)
        put("sourceLine", sourceLine)
    }

    private fun JSONObject.toS34Section() = S34SectionEntity(
        id = getString("id"), outlineId = getString("outlineId"), order = getInt("order"),
        title = getString("title"), content = getString("content"),
        minutes = intOrNull("minutes"), sourceLine = getInt("sourceLine"),
    )

    private fun S34SubsectionEntity.toJson() = JSONObject().apply {
        put("id", id); put("sectionId", sectionId); put("order", order)
        put("content", content); put("sourceLine", sourceLine)
    }

    private fun JSONObject.toS34Subsection() = S34SubsectionEntity(
        id = getString("id"), sectionId = getString("sectionId"), order = getInt("order"),
        content = getString("content"), sourceLine = getInt("sourceLine"),
    )

    private fun S34ReferenceEntity.toJson() = JSONObject().apply {
        put("id", id); put("outlineId", outlineId); putNullable("sectionId", sectionId)
        putNullable("subsectionId", subsectionId); put("order", order); put("type", type)
        put("rawText", rawText); put("normalizedReference", normalizedReference)
        put("sourceLine", sourceLine); putNullable("book", book); putNullable("bookNorm", bookNorm)
        putNullable("chapter", chapter); putNullable("verse", verse)
        putNullable("pubKind", pubKind); putNullable("pubKey", pubKey)
        putNullable("pubLabel", pubLabel); putNullable("editionKey", editionKey)
    }

    private fun JSONObject.toS34Reference() = S34ReferenceEntity(
        id = getString("id"), outlineId = getString("outlineId"),
        sectionId = stringOrNull("sectionId"), subsectionId = stringOrNull("subsectionId"),
        order = getInt("order"), type = getString("type"), rawText = getString("rawText"),
        normalizedReference = getString("normalizedReference"),
        sourceLine = getInt("sourceLine"), book = stringOrNull("book"),
        bookNorm = stringOrNull("bookNorm"), chapter = intOrNull("chapter"),
        verse = intOrNull("verse"), pubKind = stringOrNull("pubKind"),
        pubKey = stringOrNull("pubKey"), pubLabel = stringOrNull("pubLabel"),
        editionKey = stringOrNull("editionKey"),
    )

    private fun TombstoneEntity.toJson() = JSONObject().apply {
        put("id", id); put("type", type); put("deletedAt", deletedAt)
    }

    private fun JSONObject.toTombstone() = TombstoneEntity(
        id = getString("id"), type = getString("type"), deletedAt = getLong("deletedAt"),
    )

    // ---------- helpers ----------

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) {
                throw BackupValidationException("entry do ZIP maior que o limite de $max bytes")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
