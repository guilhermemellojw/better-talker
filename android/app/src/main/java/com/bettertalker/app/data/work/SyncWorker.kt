package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.cloud.AuthRepository
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.prefs.SettingsStore
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Sync Firestore <-> Room. Room é a verdade local; Firestore espelha
 * notas/pastas/metadados de anexos do usuário (nunca binários nem trechos).
 * Conflito: last-write-wins por updatedAt. Exclusões via tombstones.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val auth = AuthRepository(applicationContext)
        if (!auth.isConfigured()) return Result.success()
        val user = auth.currentUser() ?: return Result.success()
        val db: AppDatabase = DbProvider.get(applicationContext)
        return try {
            val fs = FirebaseFirestore.getInstance()
            val root = fs.collection("users").document(user.uid)
            syncNotes(db, root)
            syncFolders(db, root)
            syncAttachments(db, root)
            SettingsStore(applicationContext).setLastSync(System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    // ---------- notes ----------

    private suspend fun syncNotes(db: AppDatabase, root: com.google.firebase.firestore.DocumentReference) {
        val col = root.collection("notes")
        val cloud = col.get().await().documents.associate { it.id to it.data.orEmpty() }
        val tombs = db.tombstoneDao().all().filter { it.type == "note" }.map { it.id }.toSet()
        val local = db.noteDao().allIncludingTrashed()

        // push tombstones -> apaga na nuvem e limpa local
        for (tid in tombs) {
            col.document(tid).delete().await()
            if (!cloud.containsKey(tid)) db.tombstoneDao().remove(tid)
        }
        // pull
        for ((id, data) in cloud) {
            if (tombs.contains(id)) continue
            val cur = local.firstOrNull { it.id == id }
            val cup = (data["updatedAt"] as? Long) ?: 0L
            if (cur == null) {
                db.noteDao().upsert(cloudNote(id, data))
            } else if (cup > cur.updatedAt) {
                db.noteDao().upsert(cloudNote(id, data))
            }
        }
        // push
        for (n in local) {
            val data = cloud[n.id]
            val cup = (data?.get("updatedAt") as? Long) ?: -1L
            if (data == null || n.updatedAt > cup) {
                col.document(n.id).set(noteMap(n), SetOptions.merge()).await()
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
        "folderId" to n.folderId, "colorArgb" to n.colorArgb,
        "pinned" to n.pinned, "trashed" to n.trashed,
        "createdAt" to n.createdAt, "updatedAt" to n.updatedAt
    ).filterValues { it != null }

    // ---------- folders (união por id; tombstones propagam exclusão) ----------

    private suspend fun syncFolders(db: AppDatabase, root: com.google.firebase.firestore.DocumentReference) {
        val col = root.collection("folders")
        val cloud = col.get().await().documents.associate { it.id to it.data.orEmpty() }
        val tombs = db.tombstoneDao().all().filter { it.type == "folder" }.map { it.id }.toSet()
        val local = db.folderDao().all()
        for (tid in tombs) {
            col.document(tid).delete().await()
            if (!cloud.containsKey(tid)) db.tombstoneDao().remove(tid)
        }
        for ((id, data) in cloud) {
            if (tombs.contains(id)) continue
            if (local.none { it.id == id }) {
                db.folderDao().upsert(
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
                col.document(f.id).set(
                    mapOf(
                        "name" to f.name, "colorArgb" to f.colorArgb,
                        "createdAt" to f.createdAt
                    ).filterValues { it != null },
                    SetOptions.merge()
                ).await()
            }
        }
    }

    // ---------- attachments: só METADADOS (nunca binário/trechos) ----------

    private suspend fun syncAttachments(db: AppDatabase, root: com.google.firebase.firestore.DocumentReference) {
        val col = root.collection("attachments")
        val cloudIds = col.get().await().documents.map { it.id }.toSet()
        val tombs = db.tombstoneDao().all().filter { it.type == "attachment" }.map { it.id }.toSet()
        val local = db.attachmentDao().all()
        for (tid in tombs) {
            col.document(tid).delete().await()
            if (!cloudIds.contains(tid)) db.tombstoneDao().remove(tid)
        }
        // pull: metadados de anexos de outro aparelho (arquivo continua local-only)
        for (id in cloudIds) {
            if (tombs.contains(id)) continue
            if (local.none { it.id == id }) {
                val data = col.document(id).get().await().data.orEmpty()
                db.attachmentDao().upsert(
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
                col.document(a.id).set(
                    mapOf(
                        "fileName" to a.fileName, "kind" to a.kind,
                        "sizeBytes" to a.sizeBytes, "baseSlot" to a.baseSlot,
                        "noteId" to a.noteId, "indexed" to a.indexed,
                        "addedAt" to a.addedAt
                    ).filterValues { it != null },
                    SetOptions.merge()
                ).await()
            }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
        AuthRepository.awaitTask { this.addOnCompleteListener(it) }
}
