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
import com.bettertalker.app.data.db.TombstoneDao
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.prefs.SettingsStore
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

internal suspend fun syncNotes(
    remote: SyncRemote,
    noteDao: NoteDao,
    tombstoneDao: TombstoneDao,
) {
    val cloud = remote.getAll("notes")
    val tombs = tombstoneDao.all().filter { it.type == "note" }.map { it.id }.toSet()
    val local = noteDao.allIncludingTrashed()

    // push tombstones -> apaga na nuvem e limpa local
    for (tid in tombs) {
        remote.delete("notes", tid)
        if (!cloud.containsKey(tid)) tombstoneDao.remove(tid)
    }
    // pull
    for ((id, data) in cloud) {
        if (tombs.contains(id)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = (data["updatedAt"] as? Long) ?: 0L
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
        val cup = (data?.get("updatedAt") as? Long) ?: -1L
        if (data == null || n.updatedAt > cup) {
            remote.set("notes", n.id, noteMap(n))
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
        updatedAt = (d["updatedAt"] as? Long) ?: System.currentTimeMillis()
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
        val cup = (data["updatedAt"] as? Long) ?: 0L
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
        val cup = (data?.get("updatedAt") as? Long) ?: -1L
        if (data == null || o.updatedAt > cup) {
            remote.set(
                "outlines", o.id,
                mapOf(
                    "noteId" to o.noteId, "fileName" to o.fileName,
                    "title" to o.title, "totalMinutes" to o.totalMinutes,
                    "sectionsJson" to o.sectionsJson, "refsJson" to o.refsJson,
                    "createdAt" to o.createdAt, "updatedAt" to o.updatedAt
                ).filterValues { it != null }
            )
        }
    }
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
