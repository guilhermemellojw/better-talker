package com.bettertalker.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.cloud.SyncStatus
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.FolderDao
import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteDao
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.OutlineDao
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneDao
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.db.decodePublicationRefList
import com.bettertalker.app.data.db.encodePublicationRefList
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.repo.decodeStringList
import com.bettertalker.app.data.repo.encodeStringList
import com.bettertalker.app.domain.planning.PublicationRef
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Sync Firestore <-> Room. Room é a verdade local; Firestore espelha
 * notas/pastas/metadados de anexos do usuário (nunca binários nem trechos).
 * Conflito: last-write-wins por updatedAt. Exclusões via tombstones.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val auth = AuthRepository(applicationContext)
        if (!auth.isConfigured()) {
            Log.d("SyncWorker", "Sync ignorado: Firebase não configurado neste build.")
            return Result.success()
        }
        val user = auth.currentUser()
        if (user == null) {
            Log.d("SyncWorker", "Sync ignorado: sem usuário logado.")
            return Result.success()
        }
        val db: AppDatabase = DbProvider.get(applicationContext)
        return try {
            // F5g: o estado permanece Running durante TODAS as etapas abaixo.
            SyncStatus.set(SyncStatus.State.Running)
            val fs = FirebaseFirestore.getInstance()
            val root = fs.collection("users").document(user.uid)
            // F7a: operações remotas atrás da porta SyncRemote (testável em JVM).
            // Mesmo contrato de antes: getAll/set(merge)/delete por coleção flat.
            val remote: SyncRemote = FirestoreSyncRemote(root)
            syncNotes(remote, db.noteDao(), db.tombstoneDao())
            // F7b: seções e sub-pontos (flat). Ordem: notes -> sections ->
            // subPoints (a cascata de nota já limpou os docs remotos antes).
            syncSections(remote, db.speechSectionDao(), db.noteDao())
            syncSubPoints(remote, db.subPointDao(), db.speechSectionDao(), db.noteDao())
            syncFolders(remote, db.folderDao(), db.tombstoneDao())
            syncAttachments(remote, db.attachmentDao(), db.tombstoneDao())
            syncOutlines(remote, db.outlineDao(), db.tombstoneDao())
            val now = System.currentTimeMillis()
            SettingsStore(applicationContext).setLastSync(now)
            SyncStatus.set(SyncStatus.State.Success(now))
            Result.success()
        } catch (e: Exception) {
            // F5g: visibilidade — antes a exceção era engolida em silêncio.
            Log.e("SyncWorker", "Sync falhou: ${e.message}", e)
            SyncStatus.set(
                SyncStatus.State.Error(e.message ?: "Erro desconhecido", System.currentTimeMillis())
            )
            Result.retry()
        }
    }

}

// ---------- notes ----------

/**
 * Gap C: no push, `updatedAt` vira `FieldValue.serverTimestamp()` — o relógio
 * do aparelho pode divergir entre devices e inverter o LWW. Fallback: o valor
 * local quando o SDK não está disponível (testes JVM sem Firestore).
 */
private fun Map<String, Any?>.withServerUpdatedAt(): Map<String, Any?> =
    this + ("updatedAt" to runCatching {
        com.google.firebase.firestore.FieldValue.serverTimestamp()
    }.getOrElse { this["updatedAt"] })

/**
 * Gap C (volta): o doc remoto traz `Timestamp` (server) ou `Long` (legado).
 * Leitura tolerante para o LWW. Puro/testável.
 */
internal fun cloudUpdatedAt(data: Map<String, Any?>): Long =
    when (val v = data["updatedAt"]) {
        is Long -> v
        is com.google.firebase.Timestamp -> v.toDate().time
        else -> 0L
    }

/** Gap C: updatedAt do doc para o PUSH (-1 quando ausente/ilegível = envia). */
internal fun cloudUpdatedAtOrMinus1(data: Map<String, Any?>?): Long {
    if (data == null) return -1L
    return cloudUpdatedAt(data).takeIf { it > 0 } ?: -1L
}

internal suspend fun syncNotes(
    remote: SyncRemote,
    noteDao: NoteDao,
    tombstoneDao: TombstoneDao,
) {
    val cloud = remote.getAll("notes")
    val tombs = tombstoneDao.all().filter { it.type == "note" }.map { it.id }.toSet()
    val local = noteDao.allIncludingTrashed()

    // push tombstones -> cascata remota FLAT (sub-pontos -> seções -> nota)
    for (tid in tombs) {
        deleteNoteDeep(remote, tid)
        if (!cloud.containsKey(tid)) tombstoneDao.remove(tid)
    }
    // pull
    for ((id, data) in cloud) {
        if (tombs.contains(id)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null) {
            // Nova nota na nuvem: aceita como veio (inclusive trashed,
            // pois não há local vivo para proteger).
            noteDao.upsert(cloudNote(id, data))
        } else if (cup > cur.updatedAt) {
            // Remoto mais novo: nunca lixa local vivo (filtro abaixo).
            val remoteTrashed = (data["trashed"] as? Boolean) ?: false
            if (remoteCanTrashLocal(cur.trashed, remoteTrashed)) {
                noteDao.upsert(cloudNote(id, data))
            }
        }
    }
    // push
    for (n in local) {
        val data = cloud[n.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || n.updatedAt > cup) {
            remote.set("notes", n.id, noteMap(n).withServerUpdatedAt())
        }
    }
}

private fun cloudNote(id: String, d: Map<String, Any?>): NoteEntity {
    val md = d["mdText"] as? String ?: ""
    return NoteEntity(
        id = id,
        title = d["title"] as? String ?: "",
        mdText = md,
        plainText = com.bettertalker.app.data.util.plainFromMarkdown(md),
        richHtml = d["richHtml"] as? String ?: "",
        folderId = d["folderId"] as? String,
        colorArgb = (d["colorArgb"] as? Long) ?: 0L,
        pinned = (d["pinned"] as? Boolean) ?: false,
        trashed = (d["trashed"] as? Boolean) ?: false,
        createdAt = (d["createdAt"] as? Long) ?: System.currentTimeMillis(),
        updatedAt = cloudUpdatedAt(d).takeIf { it > 0 } ?: System.currentTimeMillis()
    )
}

private fun noteMap(n: NoteEntity) = mapOf(
    "title" to n.title, "mdText" to n.mdText,
    "richHtml" to n.richHtml,
    "folderId" to n.folderId, "colorArgb" to n.colorArgb,
    "pinned" to n.pinned, "trashed" to n.trashed,
    "createdAt" to n.createdAt, "updatedAt" to n.updatedAt
).filterValues { it != null }

// ---------- folders (união por id; tombstones propagam exclusão) ----------

internal suspend fun syncFolders(
    remote: SyncRemote,
    folderDao: FolderDao,
    tombstoneDao: TombstoneDao,
) {
    val cloud = remote.getAll("folders")
    val tombs = tombstoneDao.all().filter { it.type == "folder" }.map { it.id }.toSet()
    val local = folderDao.all()
    for (tid in tombs) {
        remote.delete("folders", tid)
        if (!cloud.containsKey(tid)) tombstoneDao.remove(tid)
    }
    for ((id, data) in cloud) {
        if (tombs.contains(id)) continue
        if (local.none { it.id == id }) {
            folderDao.upsert(
                FolderEntity(
                    id, data["name"] as? String ?: "Pasta",
                    (data["colorArgb"] as? Long) ?: 0L,
                    (data["createdAt"] as? Long) ?: System.currentTimeMillis()
                )
            )
        }
    }
    val cloudIds = cloud.keys
    for (f in local) {
        if (!cloudIds.contains(f.id)) {
            remote.set(
                "folders", f.id,
                mapOf(
                    "name" to f.name, "colorArgb" to f.colorArgb,
                    "createdAt" to f.createdAt
                ).filterValues { it != null }
            )
        }
    }
}

// ---------- attachments: só METADADOS (nunca binário/trechos) ----------

internal suspend fun syncAttachments(
    remote: SyncRemote,
    attachmentDao: AttachmentDao,
    tombstoneDao: TombstoneDao,
) {
    val cloudIds = remote.getAll("attachments").keys
    val tombs = tombstoneDao.all().filter { it.type == "attachment" }.map { it.id }.toSet()
    val local = attachmentDao.all()
    for (tid in tombs) {
        remote.delete("attachments", tid)
        if (!cloudIds.contains(tid)) tombstoneDao.remove(tid)
    }
    // pull: metadados de anexos de outro aparelho (arquivo continua local-only)
    for (id in cloudIds) {
        if (tombs.contains(id)) continue
        if (local.none { it.id == id }) {
            val data = remote.get("attachments", id) ?: emptyMap()
            attachmentDao.upsert(
                AttachmentEntity(
                    id = id, noteId = data["noteId"] as? String,
                    fileName = data["fileName"] as? String ?: "arquivo",
                    kind = data["kind"] as? String ?: "pdf",
                    sizeBytes = (data["sizeBytes"] as? Long) ?: 0L,
                    appPath = "", // arquivo não sincronizado: baixar de novo no aparelho
                    indexed = false,
                    addedAt = (data["addedAt"] as? Long) ?: System.currentTimeMillis(),
                    baseSlot = data["baseSlot"] as? String,
                    status = "missing",
                    error = "Arquivo neste aparelho: baixe no site e importe."
                )
            )
        }
    }
    for (a in local) {
        if (!cloudIds.contains(a.id) && a.status != "downloading") {
            remote.set(
                "attachments", a.id,
                mapOf(
                    "fileName" to a.fileName, "kind" to a.kind,
                    "sizeBytes" to a.sizeBytes, "baseSlot" to a.baseSlot,
                    "noteId" to a.noteId, "indexed" to a.indexed,
                    "addedAt" to a.addedAt
                ).filterValues { it != null }
            )
        }
    }
}

// ---------- outlines (união por id determinístico out-<noteId>) ----------

internal suspend fun syncOutlines(
    remote: SyncRemote,
    outlineDao: OutlineDao,
    tombstoneDao: TombstoneDao,
) {
    val cloud = remote.getAll("outlines")
    val tombs = tombstoneDao.all().filter { it.type == "outline" }.map { it.id }.toSet()
    val local = outlineDao.all()
    for (tid in tombs) {
        remote.delete("outlines", tid)
        if (!cloud.containsKey(tid)) tombstoneDao.remove(tid)
    }
    for ((id, data) in cloud) {
        if (tombs.contains(id)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null || cup > cur.updatedAt) {
            outlineDao.upsert(
                com.bettertalker.app.data.db.OutlineEntity(
                    id = id,
                    noteId = data["noteId"] as? String ?: "",
                    fileName = data["fileName"] as? String ?: "",
                    title = data["title"] as? String ?: "Esboço",
                    totalMinutes = (data["totalMinutes"] as? Long)?.toInt(),
                    sectionsJson = data["sectionsJson"] as? String ?: "[]",
                    refsJson = data["refsJson"] as? String ?: "[]",
                    createdAt = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
                    updatedAt = cup
                )
            )
        }
    }
    for (o in local) {
        val data = cloud[o.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || o.updatedAt > cup) {
            remote.set(
                "outlines", o.id,
                mapOf(
                    "noteId" to o.noteId, "fileName" to o.fileName,
                    "title" to o.title, "totalMinutes" to o.totalMinutes,
                    "sectionsJson" to o.sectionsJson, "refsJson" to o.refsJson,
                    "createdAt" to o.createdAt, "updatedAt" to o.updatedAt
                ).filterValues { it != null }.withServerUpdatedAt()
            )
        }
    }
}

// ---------- F7b: sections (flat) ----------

/**
 * F7b — sincroniza `speech_sections` na coleção FLAT `sections` (campo
 * `noteId`), no mesmo padrão LWW/tombstone do [syncNotes].
 *
 * LIMITAÇÕES DOCUMENTADAS (escopo de um round futuro):
 * - Não há tombstone de SEÇÃO/SUB-PONTO (a tabela `tombstones` só tem
 *   note/folder/attachment/outline): um item apagado individualmente no
 *   aparelho pode "ressuscitar" no próximo pull (o doc remoto continua lá).
 * - A cascata REMOTA vem do tombstone de NOTA via [deleteNoteDeep]
 *   (consumido no [syncNotes]); docs órfãos de versões anteriores ficam.
 * - A iteração local usa `noteDao.allIncludingTrashed()` + `forNote`
 *   porque `SpeechSectionDao`/`SubPointDao` não têm `all()` global.
 */
internal suspend fun syncSections(
    remote: SyncRemote,
    sectionDao: SpeechSectionDao,
    noteDao: NoteDao,
) {
    val cloud = remote.getAll("sections")
    val noteIds = noteDao.allIncludingTrashed().map { it.id }.toSet()
    val local = noteIds.flatMap { sectionDao.forNote(it) }

    // pull (LWW) — só de notas que ainda existem localmente
    for ((id, data) in cloud) {
        val noteId = data["noteId"] as? String ?: continue
        if (!noteIds.contains(noteId)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null || cup > cur.updatedAt) {
            sectionDao.upsert(cloudSection(id, data))
        }
    }
    // push
    for (sec in local) {
        val data = cloud[sec.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || sec.updatedAt > cup) {
            remote.set("sections", sec.id, sectionMap(sec).withServerUpdatedAt())
        }
    }
}

// ---------- F7b: subPoints (flat) ----------

/**
 * F7b — sincroniza `sub_points` na coleção FLAT `subPoints` (campos
 * `noteId` e `sectionId`). Sub-ponto órfão (seção/nota que não existe mais
 * localmente) NÃO é puxado — evita "ressuscitar" por doc remoto.
 * Mesmas limitações documentadas em [syncSections].
 */
internal suspend fun syncSubPoints(
    remote: SyncRemote,
    subPointDao: SubPointDao,
    sectionDao: SpeechSectionDao,
    noteDao: NoteDao,
) {
    val cloud = remote.getAll("subPoints")
    val noteIds = noteDao.allIncludingTrashed().map { it.id }.toSet()
    val sections = noteIds.flatMap { sectionDao.forNote(it) }
    val sectionIds = sections.map { it.id }.toSet()
    val local = sections.flatMap { subPointDao.forSection(it.id) }

    // pull (LWW) — só de seções que ainda existem localmente
    for ((id, data) in cloud) {
        val sectionId = data["sectionId"] as? String ?: continue
        if (!sectionIds.contains(sectionId)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null || cup > cur.updatedAt) {
            subPointDao.upsert(cloudSubPoint(id, data))
        }
    }
    // push
    for (sp in local) {
        val data = cloud[sp.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || sp.updatedAt > cup) {
            remote.set("subPoints", sp.id, subPointMap(sp).withServerUpdatedAt())
        }
    }
}

/**
 * F7b — cascata remota FLAT de nota: apaga sub-pontos -> seções -> nota.
 * O client do Firestore não tem recursive delete; iterar é o único caminho.
 * Substitui o delete direto do doc de nota no tombstone do [syncNotes].
 */
internal suspend fun deleteNoteDeep(remote: SyncRemote, noteId: String) {
    val sections = remote.getAll("sections").filterValues { it["noteId"] == noteId }
    val sectionIds = sections.keys
    val subs = remote.getAll("subPoints").filterValues { it["sectionId"] in sectionIds }
    for (sid in subs.keys) remote.delete("subPoints", sid)
    for (sid in sectionIds) remote.delete("sections", sid)
    remote.delete("notes", noteId)
}

// ---------- F7b: mapeamentos Entity <-> documento (refs como arrays) ----------

/** Documento de seção. O `id` é a chave do doc (como no noteMap). */
internal fun sectionMap(e: SpeechSectionEntity): Map<String, Any?> = mapOf(
    "noteId" to e.noteId,
    "order" to e.order,
    "role" to e.role,
    "title" to e.title,
    "minutes" to e.minutes,
    "contentHtml" to e.contentHtml,
    "bibleRefs" to decodeStringList(e.bibleRefsJson),
    "publicationRefs" to publicationRefsToDoc(decodePublicationRefList(e.publicationRefsJson)),
    "methodPrinciple" to e.methodPrinciple,
    "objective" to e.objective,
    "agreedApproach" to e.agreedApproach,
    "approvedAt" to e.approvedAt,
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
).filterValues { it != null }

/** Parser inverso defensivo (null-safe em cada campo). */
internal fun cloudSection(id: String, data: Map<String, Any?>): SpeechSectionEntity =
    SpeechSectionEntity(
        id = id,
        noteId = data["noteId"] as? String ?: "",
        order = (data["order"] as? Long)?.toInt() ?: 0,
        role = data["role"] as? String ?: "BODY",
        title = data["title"] as? String ?: "",
        minutes = (data["minutes"] as? Long)?.toInt() ?: 0,
        contentHtml = data["contentHtml"] as? String ?: "",
        bibleRefsJson = encodeStringList(docToStringList(data["bibleRefs"])),
        publicationRefsJson = encodePublicationRefList(docToPublicationRefs(data["publicationRefs"])),
        methodPrinciple = data["methodPrinciple"] as? String,
        objective = data["objective"] as? String,
        agreedApproach = data["agreedApproach"] as? String,
        approvedAt = data["approvedAt"] as? Long,
        createdAt = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
        updatedAt = cloudUpdatedAt(data).takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

/** Documento de sub-ponto. O `id` é a chave do doc. */
internal fun subPointMap(e: SubPointEntity): Map<String, Any?> = mapOf(
    "sectionId" to e.sectionId,
    "order" to e.order,
    "outlineText" to e.outlineText,
    "developedHtml" to e.developedHtml,
    "instruction" to e.instruction,
    "bibleRefs" to decodeStringList(e.bibleRefsJson),
    "publicationRefs" to publicationRefsToDoc(decodePublicationRefList(e.publicationRefsJson)),
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
).filterValues { it != null }

/** Parser inverso defensivo (null-safe em cada campo). */
internal fun cloudSubPoint(id: String, data: Map<String, Any?>): SubPointEntity =
    SubPointEntity(
        id = id,
        sectionId = data["sectionId"] as? String ?: "",
        order = (data["order"] as? Long)?.toInt() ?: 0,
        outlineText = data["outlineText"] as? String ?: "",
        bibleRefsJson = encodeStringList(docToStringList(data["bibleRefs"])),
        publicationRefsJson = encodePublicationRefList(docToPublicationRefs(data["publicationRefs"])),
        instruction = data["instruction"] as? String,
        developedHtml = data["developedHtml"] as? String ?: "",
        createdAt = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
        updatedAt = cloudUpdatedAt(data).takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

private fun publicationRefsToDoc(refs: List<PublicationRef>): List<Map<String, Any?>> =
    refs.map { r ->
        buildMap {
            put("symbol", r.symbol)
            r.page?.let { put("page", it) }
            r.paragraph?.let { put("paragraph", it) }
        }
    }

private fun docToStringList(v: Any?): List<String> =
    (v as? List<*>)?.filterIsInstance<String>().orEmpty()

private fun docToPublicationRefs(v: Any?): List<PublicationRef> =
    (v as? List<*>).orEmpty().mapNotNull { item ->
        val m = item as? Map<*, *> ?: return@mapNotNull null
        val symbol = m["symbol"] as? String ?: return@mapNotNull null
        PublicationRef(
            symbol = symbol,
            page = (m["page"] as? Long)?.toInt(),
            paragraph = (m["paragraph"] as? Long)?.toInt(),
        )
    }

/**
 * Filtro de segurança no pull: uma nota local VIVA nunca é lixada por
 * um doc remoto com `trashed=true`, mesmo se `updatedAt` remoto for
 * maior.
 *
 * Motivo: o pull aplicava `trashed=true` da nuvem com last-write-wins
 * puro, e uma contaminação na nuvem (edição manual, bug de cliente,
 * sync de outro device) propagava em lote para o local. Observado em
 * 01/10 e 02/10/2026 sem ação do usuário.
 *
 * Trade-off: delete via sync não propaga entre devices. Trash continua
 * sendo ação local explícita. Se um dia precisar propagar, revisitar.
 *
 * Nota: restauração (remoteTrashed=false em doc mais novo) continua
 * aplicando — deslixar é seguro.
 */
internal fun remoteCanTrashLocal(
    localTrashed: Boolean,
    remoteTrashed: Boolean,
): Boolean {
    // Remoto não trasha → sempre aplica (update normal / restauração)
    if (!remoteTrashed) return true
    // Remoto trasha → só aplica se local JÁ estava trashed (idempotente)
    return localTrashed
}
