package com.bettertalker.app.beta.physical

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.DeliveryProvenance
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.util.ChatCodec
import java.util.UUID

/**
 * BETA-PHYSICAL/P9 — fixture de Room REAL em arquivo.
 *
 * Não usa in-memory/mocks. O banco é criado em `getDatabasePath`, fechado e
 * reaberto para provar persistência real. Limpeza em [close].
 */
class PhysicalRoomFixture(
    val dbName: String = "p9-physical-${UUID.randomUUID()}.db",
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext,
) {
    companion object {
        const val NOTE_ID = "p9-note"
        const val JO17 = "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade."
    }

    lateinit var db: AppDatabase
        private set

    fun open() {
        ctx.deleteDatabase(dbName)
        db = build()
    }

    fun reopen() {
        if (::db.isInitialized) db.close()
        db = build()
    }

    fun close() {
        if (::db.isInitialized) db.close()
        ctx.deleteDatabase(dbName)
    }

    private fun build(): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

    suspend fun seedNote(noteId: String = NOTE_ID) {
        db.noteDao().upsert(
            NoteEntity(
                id = noteId,
                title = "P9 Room",
                mdText = "",
                plainText = "",
                folderId = null,
                colorArgb = 0L,
                pinned = false,
                trashed = false,
                createdAt = 1L,
                updatedAt = 1L,
                richHtml = "",
            ),
        )
    }

    fun validProvenance(
        messageId: String,
        noteId: String = NOTE_ID,
        text: String,
        corpus: List<String> = listOf(JO17),
        scopeId: String? = null,
        targetKey: String? = null,
        boundAt: Long = 1L,
    ): DeliveryProvenance = DeliveryProvenance.newForMessage(
        messageId = messageId,
        turnId = "turn-$messageId",
        noteId = noteId,
        scopeId = scopeId,
        targetKey = targetKey,
        corpus = corpus,
        questionRefs = emptyList(),
        content = text,
        boundAt = boundAt,
    )

    fun rejectedProvenance(
        messageId: String,
        noteId: String = NOTE_ID,
        text: String,
    ): DeliveryProvenance = validProvenance(messageId, noteId, text).copy(
        rejected = true,
    )

    fun payload(text: String, provenance: DeliveryProvenance?): String =
        ChatCodec.escMap(
            buildMap {
                provenance?.let { putAll(it.toPayload()) }
                put("text", text)
            },
        )

    suspend fun seedMessage(
        id: String,
        text: String,
        provenance: DeliveryProvenance?,
        noteId: String = NOTE_ID,
        createdAt: Long = System.currentTimeMillis(),
    ): ChatEntity {
        val entity = ChatEntity(
            id = id,
            noteId = noteId,
            fromMe = false,
            kind = "text",
            payload = payload(text, provenance),
            createdAt = createdAt,
            sectionId = null,
            isActive = true,
        )
        db.chatDao().put(entity)
        return entity
    }

    suspend fun message(id: String): ChatEntity? = db.chatDao().get(id)

    fun textOf(entity: ChatEntity?): String =
        entity?.let { ChatCodec.unescMap(it.payload)["text"].orEmpty() }.orEmpty()

    fun provenanceOf(entity: ChatEntity?): DeliveryProvenance? =
        entity?.let { DeliveryProvenance.fromPayload(ChatCodec.unescMap(it.payload)) }

    fun invalidatedPayload(entity: ChatEntity, invalidText: String = "editado durante a geração"): String =
        ChatCodec.escMap(
            ChatCodec.unescMap(entity.payload).toMutableMap().apply {
                remove(DeliveryProvenance.KEY)
                remove(DeliveryProvenance.KEY_SHA256)
                put("text", invalidText)
            },
        )
}
