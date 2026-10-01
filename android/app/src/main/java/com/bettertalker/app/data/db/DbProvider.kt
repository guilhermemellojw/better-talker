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

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notes ADD COLUMN richHtml TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE attachments ADD COLUMN downloadId INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE attachments ADD COLUMN sourceUrl TEXT")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS chat_messages " +
                    "(id TEXT NOT NULL, noteId TEXT NOT NULL, fromMe INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))"
            )
        }
    }

    /**
     * Fase 8: proveniência dos passages + trilho/símbolo dos attachments.
     * Aditiva e anulável (exceto defaults): nenhum dado apagado.
     * Backfill do trilho a partir do slot legado (be|th -> training, nwt -> bible).
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_9_10) db.execSQL(sql)
        }
    }

    /**
     * Fase 19-B.3: tabelas do OutlineDocument (s34_*). Aditiva: nenhum dado
     * apagado; tabelas novas começam vazias.
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_10_11) db.execSQL(sql)
        }
    }

    /**
     * Fase 19-B.4: reancora as chaves das tabelas s34_* (prefixo do outline).
     * `s34_*` é cache derivado — recriar é seguro.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_11_12) db.execSQL(sql)
        }
    }

    /** Fase 3.1: tabela speech_sections (aditiva). */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_12_13) db.execSQL(sql)
        }
    }

    /** Fase 3.2.2: tabela sub_points (aditiva). */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_13_14) db.execSQL(sql)
        }
    }

    /** Fase 3.2.4a: coluna discourseType em notes (aditiva). */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in MigrationSql.MIGRATION_14_15) db.execSQL(sql)
        }
    }

    fun get(ctx: Context): AppDatabase =
        inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "better-talker.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
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
