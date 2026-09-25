package com.bettertalker.app.data.db

/**
 * SQL das migrations em objeto puro (sem Android) para que testes de JVM
 * executem exatamente as mesmas instruções aplicadas em produção.
 */
object MigrationSql {

    /** Fase 8: v9 -> v10, proveniência + trilho. Aditiva, sem perda. */
    val MIGRATION_9_10: List<String> = listOf(
        "ALTER TABLE attachments ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'content'",
        "ALTER TABLE attachments ADD COLUMN symbol TEXT",
        "ALTER TABLE passages ADD COLUMN ref TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE passages ADD COLUMN page INTEGER",
        "ALTER TABLE passages ADD COLUMN paragraph INTEGER",
        "ALTER TABLE passages ADD COLUMN ord INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE passages ADD COLUMN trainingCategory TEXT",
        "CREATE INDEX IF NOT EXISTS index_attachments_baseSlot ON attachments(baseSlot)",
        "CREATE INDEX IF NOT EXISTS index_passages_attachmentId ON passages(attachmentId)",
        "UPDATE attachments SET sourceType = 'training' WHERE baseSlot IN ('be', 'th')",
        "UPDATE attachments SET sourceType = 'bible' WHERE baseSlot = 'nwt'"
    )
}
