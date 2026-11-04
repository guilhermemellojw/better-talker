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
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneDao
import com.bettertalker.app.data.db.decodePublicationRefList
import com.bettertalker.app.data.db.encodePublicationRefList
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.repo.decodeStringList
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.repo.encodeStringList
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.DiscourseType
import androidx.room.withTransaction
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Sync Firestore <-> Room. Room é a verdade local; Firestore espelha
 * notas/pastas/metadados de anexos do usuário (nunca binários nem trechos).
 * Conflito: last-write-wins por updatedAt. Exclusões via tombstones.
 *
 * Decisão de produto: anexos nunca vão para o Firebase Storage. O que sobe
 * é apenas o metadado + `sourceUrl`, para o 2º aparelho reimportar da fonte.
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

        // Guard mínimo de multi-conta: o banco local é único. Se o uid atual
        // não é o vinculado ao aparelho, não sincroniza nada (defesa extra
        // além do bloqueio no AuthRepository.signIn).
        val settings = SettingsStore(applicationContext)
        val lastUid = settings.lastUidValue()
        if (lastUid == null) {
            // Primeira conta deste aparelho (inclusive upgrade): vincula as
            // notas locais a ela. Depois disso, trocar exige apagar o local.
            settings.setLastUid(user.uid)
        } else if (com.bettertalker.app.data.cloud.accountSwitchBlocked(lastUid, user.uid)) {
            val msg = "Conta diferente da vinculada a este aparelho. " +
                "Apague os dados locais para trocar de conta."
            Log.w("SyncWorker", msg)
            SyncStatus.set(SyncStatus.State.Error(msg, System.currentTimeMillis()))
            return Result.failure()
        }

        // M2: primeiro sync não decide sozinho (Enviar/Baixar/Mesclar).
        val firstPending = settings.syncFirstPendingValue()
        val firstMode = inputData.getString(KEY_FIRST_SYNC_MODE)
        if (firstPending) {
            if (firstMode == null) {
                SyncStatus.set(
                    SyncStatus.State.Error(
                        "Escolha como sincronizar este aparelho: Enviar, Baixar ou Mesclar.",
                        System.currentTimeMillis(),
                    )
                )
                return Result.success()
            }
            settings.setSyncFirstPending(false)
        }
        val pushEnabled = firstMode != MODE_DOWNLOAD

        val db: AppDatabase = DbProvider.get(applicationContext)
        if (firstMode == MODE_UPLOAD) {
            // "Enviar": o estado local vence o LWW — carimba tudo com now.
            bumpLocalForUpload(db, System.currentTimeMillis())
        }
        return try {
            // F5g: o estado permanece Running durante TODAS as etapas abaixo.
            SyncStatus.set(SyncStatus.State.Running)
            val fs = FirebaseFirestore.getInstance()
            val root = fs.collection("users").document(user.uid)
            // F7a: operações remotas atrás da porta SyncRemote (testável em JVM).
            // Mesmo contrato de antes: getAll/set(merge)/delete por coleção flat.
            val remote: SyncRemote = FirestoreSyncRemote(root)

            // Guard de skew: LWW compara horários; relógio local errado
            // inverteria a ordem. Falha antes de qualquer pull/push.
            val serverNow = remote.serverNow()
            if (serverNow == null) {
                SyncStatus.set(
                    SyncStatus.State.Error(
                        "Não foi possível validar o relógio com o servidor.",
                        System.currentTimeMillis(),
                    )
                )
                return Result.retry()
            }
            val localNow = System.currentTimeMillis()
            if (clockSkewExceeded(serverNow, localNow)) {
                val minutes = kotlin.math.abs(serverNow - localNow) / 60_000
                val msg = "Relógio fora de sincronia (~${minutes}min). " +
                    "Ajuste a data/hora do aparelho e tente de novo."
                Log.e("SyncWorker", msg)
                SyncStatus.set(SyncStatus.State.Error(msg, localNow))
                // WorkManager.Result.failure() não carrega mensagem; o texto
                // acionável fica no SyncStatus (UI) e no log.
                return Result.failure()
            }

            // Tombstones remotos (delete forever/estrutural de outro device)
            // são lidos uma vez e aplicados em cada coleção antes do pull.
            val remoteTombs = remote.getAll("tombstones")
            syncNotes(
                remote, db.noteDao(), db.tombstoneDao(), remoteTombs,
                sectionDao = db.speechSectionDao(),
                subPointDao = db.subPointDao(),
                pushEnabled = pushEnabled,
            )
            // F7b: seções e sub-pontos (flat). Ordem: notes -> sections ->
            // subPoints (a cascata de nota já limpou os docs remotos antes).
            syncSections(
                remote, db.speechSectionDao(), db.noteDao(),
                db.tombstoneDao(), remoteTombs, pushEnabled = pushEnabled,
                applyTransaction = { block -> db.withTransaction { block() } },
            )
            syncSubPoints(
                remote, db.subPointDao(), db.speechSectionDao(), db.noteDao(),
                db.tombstoneDao(), remoteTombs, pushEnabled = pushEnabled,
            )
            syncFolders(
                remote, db.folderDao(), db.tombstoneDao(),
                db.noteDao(), remoteTombs, pushEnabled = pushEnabled,
            )
            syncAttachments(
                remote, db.attachmentDao(), db.tombstoneDao(),
                remoteTombs, db.passageDao(), db.s34Dao(), pushEnabled = pushEnabled,
            )
            syncOutlines(
                remote, db.outlineDao(), db.tombstoneDao(), remoteTombs,
                pushEnabled = pushEnabled,
            )
            val now = System.currentTimeMillis()
            settings.setLastSync(now)
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

    companion object {
        const val KEY_FIRST_SYNC_MODE = "firstSyncMode"
        const val MODE_UPLOAD = "upload"
        const val MODE_DOWNLOAD = "download"
        const val MODE_MERGE = "merge"
    }
}

/**
 * M2 "Enviar": carimba o estado local com `now` para ele vencer o LWW
 * (notas, seções, subpontos e outlines; pastas/anexos não têm updatedAt).
 */
private suspend fun bumpLocalForUpload(db: AppDatabase, now: Long) {
    for (n in db.noteDao().allIncludingTrashed()) {
        db.noteDao().upsert(n.copy(updatedAt = now))
        for (sec in db.speechSectionDao().forNote(n.id)) {
            db.speechSectionDao().upsert(sec.copy(updatedAt = now))
            for (sp in db.subPointDao().forSection(sec.id)) {
                db.subPointDao().upsert(sp.copy(updatedAt = now))
            }
        }
    }
    for (o in db.outlineDao().all()) {
        db.outlineDao().upsert(o.copy(updatedAt = now))
    }
}

// ---------- notes ----------

/** Tolerância do guard de relógio: 5 minutos (alinhado ao NTP comum). */
internal const val MAX_CLOCK_SKEW_MS: Long = 5 * 60 * 1000L

/** Puro/testável: true quando o relógio local está fora da tolerância. */
internal fun clockSkewExceeded(serverMs: Long, localMs: Long): Boolean =
    kotlin.math.abs(serverMs - localMs) > MAX_CLOCK_SKEW_MS

/** Documento de tombstone remoto (id = id da entidade apagada). */
private fun tombstoneMap(type: String, deletedAt: Long): Map<String, Any?> =
    mapOf("type" to type, "deletedAt" to deletedAt)

/**
 * O doc remoto traz `Timestamp` (docs antigos escritos com serverTimestamp)
 * ou `Long` (push atual, com o horário de edição já validado pelo guard).
 * Leitura tolerante para o LWW. Puro/testável.
 */
internal fun cloudUpdatedAt(data: Map<String, Any?>): Long =
    when (val v = data["updatedAt"]) {
        is Long -> v
        is com.google.firebase.Timestamp -> v.toDate().time
        else -> 0L
    }

/** updatedAt do doc para o PUSH (-1 quando ausente/ilegível = envia). */
internal fun cloudUpdatedAtOrMinus1(data: Map<String, Any?>?): Long {
    if (data == null) return -1L
    return cloudUpdatedAt(data).takeIf { it > 0 } ?: -1L
}

/**
 * Nome de DiscourseType válido vindo do doc remoto; null = ausente/inválido.
 * Inválido é tratado como ausente para permitir o backfill corretivo.
 */
internal fun validDiscourseType(raw: Any?): String? =
    (raw as? String)?.takeIf { name -> DiscourseType.values().any { it.name == name } }

internal suspend fun syncNotes(
    remote: SyncRemote,
    noteDao: NoteDao,
    tombstoneDao: TombstoneDao,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    sectionDao: SpeechSectionDao? = null,
    subPointDao: SubPointDao? = null,
    pushEnabled: Boolean = true,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    // 1. Tombstones remotos: exclusão definitiva de outro device vence.
    //    Apaga local (CASCADE em seções/sub-pontos) e garante que o doc
    //    remoto não seja ressuscitado.
    val remoteNoteTombs = remoteTombs
        .filterValues { it["type"] == "note" }
        .keys
    for (id in remoteNoteTombs) {
        noteDao.delete(id)
        deleteNoteDeep(remote, id)
    }

    val cloud = remote.getAll("notes")
    val localTombs = tombstoneDao.all().filter { it.type == "note" }
    val tombIds = (localTombs.map { it.id } + remoteNoteTombs).toSet()
    val local = noteDao.allIncludingTrashed()

    // 2. Tombstones locais: publica o tombstone e executa a cascata remota
    //    FLAT (sub-pontos -> seções -> nota).
    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("note", t.deletedAt))
            deleteNoteDeep(remote, t.id)
            if (!cloud.containsKey(t.id)) tombstoneDao.remove(t.id)
        }
    }
    // pull
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null) {
            // Nova nota na nuvem: aceita como veio (inclusive trashed,
            // pois não há local vivo para proteger).
            noteDao.upsert(cloudNote(id, data).copy(lastSyncedAt = cup))
        } else if (cup > cur.updatedAt) {
            // Remoto mais novo: nunca lixa local vivo (filtro abaixo).
            val remoteTrashed = (data["trashed"] as? Boolean) ?: false
            if (remoteCanTrashLocal(cur.trashed, remoteTrashed)) {
                // M2: conflito real = local E remoto mudaram desde o último sync.
                // Preserva o estado local numa nota "(conflito)" antes de aplicar
                // o remoto (o texto local nunca some).
                if (cur.lastSyncedAt > 0 &&
                    cur.updatedAt > cur.lastSyncedAt &&
                    cup > cur.lastSyncedAt
                ) {
                    copyNoteAsConflict(
                        src = cur,
                        noteDao = noteDao,
                        sectionDao = sectionDao,
                        subPointDao = subPointDao,
                        now = clock(),
                    )
                }
                noteDao.upsert(cloudNote(id, data).copy(lastSyncedAt = cup))
            }
        } else if (cup == cur.updatedAt) {
            // Mesma revisão: completa metadados que o outro lado ainda não
            // tinha (M3). Local com valor próprio vence; nada bumpa updatedAt.
            var merged = cur
            val remoteType = validDiscourseType(data["discourseType"])
            if (cur.discourseType == DiscourseType.S34_DISCOURSE.name &&
                remoteType != null &&
                remoteType != DiscourseType.S34_DISCOURSE.name
            ) {
                merged = merged.copy(discourseType = remoteType)
            }
            // Já iguais (ex.: push anterior): marca como sincronizada.
            if (merged.lastSyncedAt != cup) merged = merged.copy(lastSyncedAt = cup)
            if (merged != cur) noteDao.upsert(merged)
        }
    }
    // push
    val pushedNoteIds = mutableSetOf<String>()
    for (n in local) {
        if (tombIds.contains(n.id)) continue
        val data = cloud[n.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || n.updatedAt > cup) {
            if (!pushEnabled) continue
            // M2: se o remoto também mudou desde o último sync e o local vai
            // vencer (LWW), preserva o estado remoto numa nota "(conflito)"
            // antes de sobrescrever — nenhum dos dois lados some.
            if (data != null &&
                n.lastSyncedAt > 0 &&
                n.updatedAt > n.lastSyncedAt &&
                cup > n.lastSyncedAt
            ) {
                val remoteState = cloudNote(n.id, data)
                noteDao.upsert(
                    remoteState.copy(
                        id = newId("note"),
                        title = "${remoteState.title} (conflito)",
                        pinned = false,
                        updatedAt = clock(),
                        lastSyncedAt = 0L,
                    )
                )
            }
            // `updatedAt` = horário da edição (guard de skew já validou o
            // relógio). Usar serverTimestamp aqui faria o LWW virar
            // "último sync vence" e deixaria edição velha vencer.
            remote.set("notes", n.id, noteMap(n))
            // M2: marca o remoto como sincronizado (base do próximo conflito).
            noteDao.upsert(n.copy(lastSyncedAt = n.updatedAt))
            pushedNoteIds += n.id
        }
    }
    // M3: docs já sincronizados antes do campo existir não têm updatedAt
    // maior, então o LWW não os reenviaria. Backfill só em empate de
    // revisão (nunca sobrescreve um remoto mais novo).
    if (pushEnabled) {
        val localAfterPull = noteDao.allIncludingTrashed()
        for (n in localAfterPull) {
            if (tombIds.contains(n.id) || pushedNoteIds.contains(n.id)) continue
            // Default não precisa ser escrito: ausência já significa S34.
            // Escrever S34 aqui bloquearia o valor real de outro aparelho
            // (o campo passaria a existir e o pull não o trocaria).
            if (n.discourseType == DiscourseType.S34_DISCOURSE.name) continue
            val data = cloud[n.id] ?: continue
            if (validDiscourseType(data["discourseType"]) != null) continue
            if (cloudUpdatedAt(data) != n.updatedAt) continue
            remote.set("notes", n.id, noteMap(n))
            noteDao.upsert(n.copy(lastSyncedAt = n.updatedAt))
        }
    }
}

/**
 * M2 — cópia de conflito: duplica a nota local (com seções/subpontos, quando
 * os DAOs são fornecidos) numa nota nova "<título> (conflito)". O original
 * recebe o estado remoto; o texto local fica preservado na cópia.
 */
private suspend fun copyNoteAsConflict(
    src: NoteEntity,
    noteDao: NoteDao,
    sectionDao: SpeechSectionDao?,
    subPointDao: SubPointDao?,
    now: Long,
) {
    val copyId = newId("note")
    noteDao.upsert(
        src.copy(
            id = copyId,
            title = "${src.title} (conflito)",
            pinned = false,
            updatedAt = now,
            lastSyncedAt = 0L,
        )
    )
    if (sectionDao == null) return
    val sections = sectionDao.forNote(src.id)
    val subs = subPointDao?.forSections(sections.map { it.id }).orEmpty()
    val remap = mutableMapOf<String, String>()
    for (sec in sections) {
        val newSecId = newId("sec")
        remap[sec.id] = newSecId
        sectionDao.upsert(
            sec.copy(id = newSecId, noteId = copyId, updatedAt = now)
        )
    }
    val subDao = subPointDao ?: return
    for (sp in subs) {
        val newSecId = remap[sp.sectionId] ?: continue
        subDao.upsert(
            sp.copy(id = newId("sp"), sectionId = newSecId, updatedAt = now)
        )
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
        updatedAt = cloudUpdatedAt(d).takeIf { it > 0 } ?: System.currentTimeMillis(),
        // M3: campo obrigatório no round-trip; valor desconhecido cai no default.
        discourseType = DiscourseType.fromNameOrDefault(d["discourseType"] as? String).name,
    )
}

private fun noteMap(n: NoteEntity) = mapOf(
    "title" to n.title, "mdText" to n.mdText,
    "richHtml" to n.richHtml,
    "folderId" to n.folderId, "colorArgb" to n.colorArgb,
    "pinned" to n.pinned, "trashed" to n.trashed,
    "createdAt" to n.createdAt, "updatedAt" to n.updatedAt,
    // M3: sem este campo o 2º aparelho rebaixava toda nota para S34_DISCOURSE.
    "discourseType" to n.discourseType,
).filterValues { it != null }

// ---------- folders (união por id; tombstones propagam exclusão) ----------

internal suspend fun syncFolders(
    remote: SyncRemote,
    folderDao: FolderDao,
    tombstoneDao: TombstoneDao,
    noteDao: NoteDao? = null,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    pushEnabled: Boolean = true,
) {
    // Tombstones remotos: apaga a pasta local e limpa referências.
    val remoteFolderTombs = remoteTombs
        .filterValues { it["type"] == "folder" }
        .keys
    for (id in remoteFolderTombs) {
        noteDao?.clearFolder(id)
        folderDao.delete(id)
        remote.delete("folders", id)
    }

    val cloud = remote.getAll("folders")
    val localTombs = tombstoneDao.all().filter { it.type == "folder" }
    val tombIds = (localTombs.map { it.id } + remoteFolderTombs).toSet()
    val local = folderDao.all()
    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("folder", t.deletedAt))
            noteDao?.clearFolder(t.id)
            remote.delete("folders", t.id)
            if (!cloud.containsKey(t.id)) tombstoneDao.remove(t.id)
        }
    }
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
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
        if (!pushEnabled) continue
        if (tombIds.contains(f.id)) continue
        if (!cloudIds.contains(f.id)) {
            remote.set(
                "folders", f.id,
                mapOf(
                    "name" to f.name, "colorArgb" to f.colorArgb,
                    "createdAt" to f.createdAt
                )
            )
        }
    }
}

// ---------- attachments: só METADADOS (nunca binário/trechos) ----------

internal suspend fun syncAttachments(
    remote: SyncRemote,
    attachmentDao: AttachmentDao,
    tombstoneDao: TombstoneDao,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    passageDao: PassageDao? = null,
    s34Dao: S34Dao? = null,
    pushEnabled: Boolean = true,
) {
    // Tombstones remotos: remove metadados + derivados locais. O binário
    // nunca esteve na nuvem.
    val remoteAttachmentTombs = remoteTombs
        .filterValues { it["type"] == "attachment" }
        .keys
    for (id in remoteAttachmentTombs) {
        val cur = attachmentDao.get(id)
        if (cur != null) {
            java.io.File(cur.appPath).delete()
            passageDao?.deleteForAttachment(id)
            s34Dao?.let { com.bettertalker.app.data.repo.S34OutlineRepository(it).deleteBySource(id) }
            attachmentDao.delete(id)
        }
        remote.delete("attachments", id)
    }

    val cloud = remote.getAll("attachments")
    val cloudIds = cloud.keys
    val localTombs = tombstoneDao.all().filter { it.type == "attachment" }
    val tombIds = (localTombs.map { it.id } + remoteAttachmentTombs).toSet()
    val local = attachmentDao.all()
    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("attachment", t.deletedAt))
            remote.delete("attachments", t.id)
            if (!cloudIds.contains(t.id)) tombstoneDao.remove(t.id)
        }
    }
    // pull: metadados de anexos de outro aparelho (arquivo continua local-only)
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
        val cur = local.firstOrNull { it.id == id }
        if (cur == null) {
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
                    // A URL de origem permite reimportar da fonte no 2º
                    // aparelho; o binário nunca sobe para o Storage.
                    sourceUrl = data["sourceUrl"] as? String,
                    // M4: metadados que organizam o acervo no 2º aparelho.
                    sourceType = data["sourceType"] as? String ?: "content",
                    symbol = data["symbol"] as? String,
                    error = "Arquivo neste aparelho: use Importar da fonte."
                )
            )
        } else {
            // Metadados antigos podem ter sido puxados antes do M4/M3.
            // Completa só o que falta; o arquivo local nunca é tocado.
            var merged = cur
            val remoteSource = data["sourceUrl"] as? String
            if (merged.sourceUrl == null && remoteSource != null) {
                merged = merged.copy(sourceUrl = remoteSource)
            }
            val remoteSourceType = data["sourceType"] as? String
            if (merged.sourceType == "content" &&
                remoteSourceType != null &&
                remoteSourceType != "content"
            ) {
                merged = merged.copy(sourceType = remoteSourceType)
            }
            val remoteSymbol = data["symbol"] as? String
            if (merged.symbol == null && remoteSymbol != null) {
                merged = merged.copy(symbol = remoteSymbol)
            }
            if (merged != cur) attachmentDao.update(merged)
        }
    }
    for (a in local) {
        if (!pushEnabled) continue
        if (tombIds.contains(a.id)) continue
        val remoteData = cloud[a.id]
        if (remoteData == null) {
            // Cria o doc na 1ª vez (o binário nunca sobe).
            if (a.status != "downloading") {
                remote.set(
                    "attachments", a.id,
                    mapOf(
                        "fileName" to a.fileName, "kind" to a.kind,
                        "sizeBytes" to a.sizeBytes, "baseSlot" to a.baseSlot,
                        "noteId" to a.noteId, "indexed" to a.indexed,
                        "addedAt" to a.addedAt, "sourceUrl" to a.sourceUrl,
                        // M4: trilho/símbolo para o 2º aparelho organizar
                        // o acervo mesmo sem o binário.
                        "sourceType" to a.sourceType, "symbol" to a.symbol,
                    ).filterValues { it != null }
                )
            }
        } else {
            // Não sobrescreve indexed/sizeBytes de um aparelho que tem o
            // arquivo; corrige só metadados default/ausentes no doc.
            val patch = mutableMapOf<String, Any?>()
            val remoteSource = remoteData["sourceUrl"] as? String
            if (a.sourceUrl != null && a.sourceUrl != remoteSource) {
                patch["sourceUrl"] = a.sourceUrl
            }
            // Metadados classificados localmente vencem o default/ausência
            // remota; anexos não têm updatedAt, então a regra é explícita.
            if (a.sourceType != "content" && remoteData["sourceType"] != a.sourceType) {
                patch["sourceType"] = a.sourceType
            }
            if (a.symbol != null && remoteData["symbol"] != a.symbol) {
                patch["symbol"] = a.symbol
            }
            if (patch.isNotEmpty()) remote.set("attachments", a.id, patch)
        }
    }
}

// ---------- outlines (união por id determinístico out-<noteId>) ----------

internal suspend fun syncOutlines(
    remote: SyncRemote,
    outlineDao: OutlineDao,
    tombstoneDao: TombstoneDao,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    pushEnabled: Boolean = true,
) {
    val remoteOutlineTombs = remoteTombs
        .filterValues { it["type"] == "outline" }
        .keys
    for (id in remoteOutlineTombs) {
        outlineDao.delete(id)
        remote.delete("outlines", id)
    }

    val cloud = remote.getAll("outlines")
    val localTombs = tombstoneDao.all().filter { it.type == "outline" }
    val tombIds = (localTombs.map { it.id } + remoteOutlineTombs).toSet()
    val local = outlineDao.all()
    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("outline", t.deletedAt))
            remote.delete("outlines", t.id)
            if (!cloud.containsKey(t.id)) tombstoneDao.remove(t.id)
        }
    }
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
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
        if (!pushEnabled) continue
        if (tombIds.contains(o.id)) continue
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
                ).filterValues { it != null }
            )
        }
    }
}

// ---------- F7b: sections (flat) ----------

/**
 * F7b — sincroniza `speech_sections` na coleção FLAT `sections` (campo
 * `noteId`), no padrão LWW + tombstone (inclusive de seção individual).
 *
 * - Tombstone local de seção publica o doc em `tombstones` e apaga a cascata
 *   remota (sub-pontos -> seção).
 * - Tombstone remoto de seção apaga a seção local e a cascata remota.
 * - A iteração local usa `noteDao.allIncludingTrashed()` + `forNote`
 *   porque `SpeechSectionDao`/`SubPointDao` não têm `all()` global.
 */
internal suspend fun syncSections(
    remote: SyncRemote,
    sectionDao: SpeechSectionDao,
    noteDao: NoteDao,
    tombstoneDao: TombstoneDao? = null,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    pushEnabled: Boolean = true,
    /** Seam de transação; o worker passa `db.withTransaction`. */
    applyTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {
    val remoteSectionTombs = remoteTombs
        .filterValues { it["type"] == "section" }
        .keys
    for (id in remoteSectionTombs) {
        sectionDao.get(id)?.let { sectionDao.delete(it) }
        deleteSectionDeep(remote, id)
    }

    val cloud = remote.getAll("sections")
    val localTombs = tombstoneDao?.all()?.filter { it.type == "section" }.orEmpty()
    val tombIds = (localTombs.map { it.id } + remoteSectionTombs).toSet()
    val noteIds = noteDao.allIncludingTrashed().map { it.id }.toSet()
    // Repara ordens temporárias/negativas de uma execução interrompida.
    applyTransaction { normalizeSectionOrders(sectionDao, noteIds) }
    val local = noteIds.flatMap { sectionDao.forNote(it) }

    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("section", t.deletedAt))
            deleteSectionDeep(remote, t.id)
            if (!cloud.containsKey(t.id)) tombstoneDao?.remove(t.id)
        }
    }
    // pull (LWW) — só de notas que ainda existem localmente. Coleta antes de
    // aplicar: reorder remoto precisa de duas fases para não violar o índice
    // único (noteId, order).
    val toApply = LinkedHashMap<String, SpeechSectionEntity>()
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
        val noteId = data["noteId"] as? String ?: continue
        if (!noteIds.contains(noteId)) continue
        val cur = local.firstOrNull { it.id == id }
        val cup = cloudUpdatedAt(data)
        if (cur == null || cup > cur.updatedAt) {
            toApply[id] = cloudSection(id, data)
        } else if (cup == cur.updatedAt && cur.tribunaJson == null) {
            // M3: mesma revisão, mas o remoto já tem a cápsula. Local null
            // adota; se ambos tiverem valores diferentes, o local vence.
            val remoteTribuna = data["tribunaJson"] as? String
            if (remoteTribuna != null) {
                toApply[id] = cur.copy(tribunaJson = remoteTribuna)
            }
        }
    }
    if (toApply.isNotEmpty()) {
        applyTransaction { applySectionPulls(sectionDao, toApply.values) }
    }
    // push
    val pushedSectionIds = mutableSetOf<String>()
    for (sec in local) {
        if (!pushEnabled) continue
        if (tombIds.contains(sec.id)) continue
        val data = cloud[sec.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || sec.updatedAt > cup) {
            remote.set("sections", sec.id, sectionMap(sec))
            pushedSectionIds += sec.id
        }
    }
    // M3: backfill de cápsula da Tribuna em docs antigos, só quando a
    // revisão empatou (o remoto mais novo nunca é sobrescrito).
    if (pushEnabled) {
        val localAfterPull = noteIds.flatMap { sectionDao.forNote(it) }
        for (sec in localAfterPull) {
            if (tombIds.contains(sec.id) || pushedSectionIds.contains(sec.id)) continue
            if (sec.tribunaJson == null) continue
            val data = cloud[sec.id] ?: continue
            if (data["tribunaJson"] is String) continue
            if (cloudUpdatedAt(data) != sec.updatedAt) continue
            remote.set("sections", sec.id, sectionMap(sec))
        }
    }
}

/** Repara ordens negativas deixadas por um pull interrompido. */
private suspend fun normalizeSectionOrders(
    sectionDao: SpeechSectionDao,
    noteIds: Set<String>,
) {
    for (noteId in noteIds) {
        val rows = sectionDao.forNote(noteId)
        if (rows.none { it.order < 0 }) continue
        val ordered = rows.sortedWith(compareBy({ it.order }, { it.id }))
        ordered.forEachIndexed { index, sec ->
            sectionDao.upsert(sec.copy(order = -2_000_000 - index))
        }
        ordered.forEachIndexed { index, sec ->
            sectionDao.upsert(sec.copy(order = index))
        }
    }
}

/**
 * Aplica pulls de seção por nota. Se a ordem remota colide com a local
 * (reorder entre aparelhos), move as seções locais para uma faixa temporária,
 * grava as remotas e devolve as locais não presentes no remoto para o fim.
 * Sem isso, o índice único `(noteId, order)` derruba o sync no meio.
 */
private suspend fun applySectionPulls(
    sectionDao: SpeechSectionDao,
    pulls: Collection<SpeechSectionEntity>,
) {
    for ((noteId, rawRemote) in pulls.groupBy { it.noteId }) {
        // Firestore não tem a constraint (noteId, order): normaliza duplicatas
        // e ordens negativas com tie-break determinístico (order, id).
        val remoteOrdered = rawRemote.sortedWith(compareBy({ it.order }, { it.id }))
        val remote = if (rawRemote.size != rawRemote.map { it.order }.distinct().size ||
            rawRemote.any { it.order < 0 }
        ) {
            remoteOrdered.mapIndexed { index, sec -> sec.copy(order = index) }
        } else {
            rawRemote
        }
        val local = sectionDao.forNote(noteId)
        val localById = local.associateBy { it.id }
        val remoteIds = remote.map { it.id }.toSet()
        val collision = remote.any { r ->
            val l = localById[r.id]
            (l != null && l.order != r.order) ||
                (l == null && local.any { it.id !in remoteIds && it.order == r.order })
        }
        if (!collision) {
            for (sec in remote) sectionDao.upsert(sec)
            continue
        }
        // Fase 1: tira todas as locais da faixa de ordem remota.
        local.forEachIndexed { index, sec ->
            sectionDao.upsert(sec.copy(order = -1_000_000 - index))
        }
        // Fase 2: grava as remotas com a ordem vencedora.
        for (sec in remote) sectionDao.upsert(sec)
        // Locais que o remoto não tem (raro) vão para o fim, sem colidir.
        var next = (remote.map { it.order }.maxOrNull() ?: -1) + 1
        for (sec in local) {
            if (sec.id in remoteIds) continue
            sectionDao.upsert(sec.copy(order = next++))
        }
    }
}

// ---------- F7b: subPoints (flat) ----------

/**
 * F7b — sincroniza `sub_points` na coleção FLAT `subPoints` (campos
 * `noteId` e `sectionId`). Sub-ponto órfão (seção/nota que não existe mais
 * localmente) NÃO é puxado — evita "ressuscitar" por doc remoto.
 * Tombstones próprios cobrem delete individual de sub-ponto.
 */
internal suspend fun syncSubPoints(
    remote: SyncRemote,
    subPointDao: SubPointDao,
    sectionDao: SpeechSectionDao,
    noteDao: NoteDao,
    tombstoneDao: TombstoneDao? = null,
    remoteTombs: Map<String, Map<String, Any?>> = emptyMap(),
    pushEnabled: Boolean = true,
) {
    val remoteSubTombs = remoteTombs
        .filterValues { it["type"] == "subPoint" }
        .keys
    for (id in remoteSubTombs) {
        subPointDao.get(id)?.let { subPointDao.delete(it) }
        remote.delete("subPoints", id)
    }

    val cloud = remote.getAll("subPoints")
    val localTombs = tombstoneDao?.all()?.filter { it.type == "subPoint" }.orEmpty()
    val tombIds = (localTombs.map { it.id } + remoteSubTombs).toSet()
    val noteIds = noteDao.allIncludingTrashed().map { it.id }.toSet()
    val sections = noteIds.flatMap { sectionDao.forNote(it) }
    val sectionIds = sections.map { it.id }.toSet()
    val local = sections.flatMap { subPointDao.forSection(it.id) }

    if (pushEnabled) {
        for (t in localTombs) {
            remote.set("tombstones", t.id, tombstoneMap("subPoint", t.deletedAt))
            remote.delete("subPoints", t.id)
            if (!cloud.containsKey(t.id)) tombstoneDao?.remove(t.id)
        }
    }
    // pull (LWW) — só de seções que ainda existem localmente
    for ((id, data) in cloud) {
        if (tombIds.contains(id)) continue
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
        if (!pushEnabled) continue
        if (tombIds.contains(sp.id)) continue
        val data = cloud[sp.id]
        val cup = cloudUpdatedAtOrMinus1(data)
        if (data == null || sp.updatedAt > cup) {
            remote.set("subPoints", sp.id, subPointMap(sp))
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

/** Cascata remota de seção: sub-pontos -> seção. */
internal suspend fun deleteSectionDeep(remote: SyncRemote, sectionId: String) {
    val subs = remote.getAll("subPoints").filterValues { it["sectionId"] == sectionId }
    for (sid in subs.keys) remote.delete("subPoints", sid)
    remote.delete("sections", sectionId)
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
    // M3: cápsula da Tribuna gerada no aparelho; sem isto o 2º aparelho
    // recebia a seção sem a memória da Tribuna.
    "tribunaJson" to e.tribunaJson,
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
        tribunaJson = data["tribunaJson"] as? String,
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
