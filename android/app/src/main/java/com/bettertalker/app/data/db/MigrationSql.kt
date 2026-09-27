package com.bettertalker.app.data.db

/**
 * SQL das migrations em objeto puro (sem Android) para que testes de JVM
 * executem exatamente as mesmas instruções aplicadas em produção.
 */
object MigrationSql {

    /** Fase 19-B.3: v10 -> v11, tabelas do OutlineDocument. Aditiva, sem perda. */
    val MIGRATION_10_11: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS s34_outlines (" +
            "id TEXT NOT NULL, sourceAttachmentId TEXT NOT NULL, " +
            "symbol TEXT NOT NULL, title TEXT NOT NULL, objective TEXT, " +
            "headerLinesJson TEXT NOT NULL DEFAULT '[]', " +
            "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
            "parserVersion INTEGER NOT NULL, PRIMARY KEY(id))",
        "CREATE INDEX IF NOT EXISTS index_s34_outlines_sourceAttachmentId " +
            "ON s34_outlines(sourceAttachmentId)",
        "CREATE TABLE IF NOT EXISTS s34_sections (" +
            "id TEXT NOT NULL, outlineId TEXT NOT NULL, position INTEGER NOT NULL, " +
            "title TEXT NOT NULL, content TEXT NOT NULL, minutes INTEGER, " +
            "sourceLine INTEGER NOT NULL, PRIMARY KEY(id))",
        "CREATE INDEX IF NOT EXISTS index_s34_sections_outlineId " +
            "ON s34_sections(outlineId)",
        "CREATE TABLE IF NOT EXISTS s34_subsections (" +
            "id TEXT NOT NULL, sectionId TEXT NOT NULL, position INTEGER NOT NULL, " +
            "content TEXT NOT NULL, sourceLine INTEGER NOT NULL, PRIMARY KEY(id))",
        "CREATE INDEX IF NOT EXISTS index_s34_subsections_sectionId " +
            "ON s34_subsections(sectionId)",
        "CREATE TABLE IF NOT EXISTS s34_references (" +
            "id TEXT NOT NULL, outlineId TEXT NOT NULL, sectionId TEXT, " +
            "subsectionId TEXT, position INTEGER NOT NULL, type TEXT NOT NULL, " +
            "rawText TEXT NOT NULL, normalizedReference TEXT NOT NULL, " +
            "sourceLine INTEGER NOT NULL, book TEXT, bookNorm TEXT, " +
            "chapter INTEGER, verse INTEGER, " +
            "pubKind TEXT, pubKey TEXT, pubLabel TEXT, editionKey TEXT, PRIMARY KEY(id))",
        "CREATE INDEX IF NOT EXISTS index_s34_references_outlineId " +
            "ON s34_references(outlineId)",
        "CREATE INDEX IF NOT EXISTS index_s34_references_sectionId " +
            "ON s34_references(sectionId)"
    )

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
