package com.bettertalker.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.ui.theme.FolderBlue
import com.bettertalker.app.ui.theme.FolderGreen
import com.bettertalker.app.ui.theme.FolderYellow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DbProvider {
    @Volatile private var inst: AppDatabase? = null

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE attachments ADD COLUMN baseSlot TEXT")
            db.execSQL("ALTER TABLE attachments ADD COLUMN status TEXT NOT NULL DEFAULT 'indexing'")
            db.execSQL("ALTER TABLE attachments ADD COLUMN error TEXT")
            // heura o estado de quem já estava indexado
            db.execSQL("UPDATE attachments SET status = 'ready' WHERE indexed = 1")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tombstones " +
                    "(id TEXT NOT NULL, type TEXT NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE passages ADD COLUMN section TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS outlines " +
                    "(id TEXT NOT NULL, noteId TEXT NOT NULL, fileName TEXT NOT NULL, " +
                    "title TEXT NOT NULL, totalMinutes INTEGER, sectionsJson TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outlines ADD COLUMN refsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    fun get(ctx: Context): AppDatabase =
        inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "better-talker.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Pastas + nota demo (uso pessoal, sem conteúdo de terceiros)
                        CoroutineScope(Dispatchers.IO).launch {
                            get(ctx).let { database ->
                                val now = System.currentTimeMillis()
                                database.folderDao().upsert(
                                    FolderEntity(newId("fld"), "Discursos", FolderYellow.value.toLong(), now)
                                )
                                database.folderDao().upsert(
                                    FolderEntity(newId("fld"), "Ideias", FolderBlue.value.toLong(), now)
                                )
                                database.folderDao().upsert(
                                    FolderEntity(newId("fld"), "Rascunhos", FolderGreen.value.toLong(), now)
                                )
                                if (database.noteDao().count() == 0) {
                                    val folders = database.folderDao().all()
                                    database.noteDao().upsert(
                                        NoteEntity(
                                            id = newId("note"), title = "Meu primeiro discurso",
                                            mdText = "# Abertura\n\nEscreva aqui manualmente.\n\n## Desenvolvimento\n\nAnexe publicações na Biblioteca e peça ideias ao Copilot.",
                                            plainText = "Abertura Escreva aqui manualmente. Desenvolvimento Anexe publicações na Biblioteca e peça ideias ao Copilot.",
                                            folderId = folders.firstOrNull()?.id, colorArgb = FolderYellow.value.toLong(),
                                            pinned = false, trashed = false, createdAt = now, updatedAt = now
                                        )
                                    )
                                }
                            }
                        }
                    }
                })
                .build().also { inst = it }
        }
}
