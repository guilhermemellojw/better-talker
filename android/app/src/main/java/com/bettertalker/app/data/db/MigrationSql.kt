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

    /**
     * Fase 19-B.4: v11 -> v12. As chaves de seção/subseção passaram a ser
     * prefixadas com o id do outline (`<outlineId>:sec-N`) porque `sec-N` é
     * determinística POR DOCUMENTO e dois S-34 colidiriam na chave primária.
     * As tabelas s34_* são cache derivado (reconstruível do arquivo-fonte),
     * então recriá-las é seguro e não perde dado do usuário. Dados antigos
     * das demais tabelas ficam intactos.
     */
    val MIGRATION_11_12: List<String> = listOf(
        "DROP TABLE IF EXISTS s34_references",
        "DROP TABLE IF EXISTS s34_subsections",
        "DROP TABLE IF EXISTS s34_sections",
        "DROP TABLE IF EXISTS s34_outlines"
    ) + MIGRATION_10_11

    /** Fase 8: v9 -> v10, proveniência + trilho. Aditiva, sem perda. */    val MIGRATION_9_10: List<String> = listOf(
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

    /** Fase 3.1 (v12 -> v13): tabela de seções de discurso. Aditiva, sem perda. */
    val MIGRATION_12_13: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `speech_sections` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `noteId` TEXT NOT NULL,
            `order` INTEGER NOT NULL,
            `role` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `minutes` INTEGER NOT NULL,
            `contentHtml` TEXT NOT NULL,
            `bibleRefsJson` TEXT NOT NULL,
            `publicationRefsJson` TEXT NOT NULL,
            `methodPrinciple` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_speech_sections_noteId` ON `speech_sections`(`noteId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_speech_sections_noteId_order` ON `speech_sections`(`noteId`, `order`)",
    )

    /** Fase 3.2.2 (v13 -> v14): tabela de sub-pontos de seções BODY. Aditiva, sem perda. */
    val MIGRATION_13_14: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sub_points` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `sectionId` TEXT NOT NULL,
            `order` INTEGER NOT NULL,
            `outlineText` TEXT NOT NULL,
            `bibleRefsJson` TEXT NOT NULL,
            `publicationRefsJson` TEXT NOT NULL,
            `instruction` TEXT,
            `developedHtml` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            FOREIGN KEY(`sectionId`) REFERENCES `speech_sections`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sub_points_sectionId` ON `sub_points`(`sectionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sub_points_sectionId_order` ON `sub_points`(`sectionId`, `order`)",
    )

    /** Fase 3.2.4a (v14 -> v15): tipo de discurso na nota. Aditiva, sem perda. */
    val MIGRATION_14_15: List<String> = listOf(
        "ALTER TABLE `notes` ADD COLUMN `discourseType` TEXT NOT NULL DEFAULT 'S34_DISCOURSE'",
    )

    /**
     * F2.3 (v15 -> v16): objetivo e abordagem acordada do tópico, e escopo
     * persistente da conversa do Copilot. Tudo aditivo e anulável — nenhum
     * dado existente é alterado; mensagens antigas ficam com `sectionId` NULL
     * (conversa global).
     */
    val MIGRATION_15_16: List<String> = listOf(
        "ALTER TABLE `speech_sections` ADD COLUMN `objective` TEXT",
        "ALTER TABLE `speech_sections` ADD COLUMN `agreedApproach` TEXT",
        "ALTER TABLE `chat_messages` ADD COLUMN `sectionId` TEXT",
    )

    /**
     * F3a (v16 -> v17): flag de aprovação da seção (timestamp anulável).
     * Aditiva: registros antigos ficam com `approvedAt` NULL (não aprovada).
     */
    val MIGRATION_16_17: List<String> = listOf(
        "ALTER TABLE `speech_sections` ADD COLUMN `approvedAt` INTEGER",
    )

    /** #20-fix-a: v17 -> v18, cápsula da Tribuna por seção (aditiva, nullable). */
    val MIGRATION_17_18: List<String> = listOf(
        "ALTER TABLE `speech_sections` ADD COLUMN `tribunaJson` TEXT",
    )

    /**
     * #26-fix-c (v18 -> v19): soft delete das mensagens do chat
     * ("Regenerar resposta" / "Editar pergunta"). Aditiva: mensagens antigas
     * ficam ativas (default 1); as leituras filtram `isActive = 1`.
     */
    val MIGRATION_18_19: List<String> = listOf(
        "ALTER TABLE `chat_messages` ADD COLUMN `isActive` INTEGER NOT NULL DEFAULT 1",
    )

    /**
     * Fase A (v19 -> v20): parte 6 "Discurso" deixa de ser MINISTRY_PART.
     *
     * Não há mudança de schema (`discourseType` já é TEXT); a migração é
     * apenas de DADOS. O sinal seguro disponível é o título gerado pelo
     * "Colar e Pronto" para a parte 6: "Discurso..." (a demo tem título de
     * cenário/tipo, nunca começa com "Discurso"). Notas criadas pelo Editor
     * em uma nota já existente não são tocadas (não há como inferir a parte).
     */
    val MIGRATION_19_20: List<String> = listOf(
        "UPDATE notes SET discourseType = 'MINISTRY_TALK' " +
            "WHERE discourseType = 'MINISTRY_PART' " +
            "AND LOWER(TRIM(title)) LIKE 'discurso%'",
    )

    /**
     * M2 (v20 -> v21): `lastSyncedAt` por nota (updatedAt remoto do último
     * sync). 0 = nunca sincronizada; notas existentes começam em 0.
     */
    val MIGRATION_20_21: List<String> = listOf(
        "ALTER TABLE `notes` ADD COLUMN `lastSyncedAt` INTEGER NOT NULL DEFAULT 0",
    )

    /**
     * P1 (v21 -> v22): hash SHA-256 para deduplicar importações/downloads.
     * Nulo em anexos antigos; o próximo import calcula e preenche.
     */
    val MIGRATION_21_22: List<String> = listOf(
        "ALTER TABLE `attachments` ADD COLUMN `sha256` TEXT",
    )

    /**
     * v22 -> v23: imagens da nota (anexo por seção ou da nota inteira).
     * Aditiva, sem perda; bytes ficam em filesDir/images.
     */
    val MIGRATION_22_23: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `note_images` (
            `id` TEXT NOT NULL,
            `noteId` TEXT NOT NULL,
            `sectionId` TEXT,
            `path` TEXT NOT NULL,
            `mime` TEXT NOT NULL,
            `caption` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `order` INTEGER NOT NULL,
            `addedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sectionId`) REFERENCES `speech_sections`(`id`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_note_images_noteId` ON `note_images`(`noteId`)",
        "CREATE INDEX IF NOT EXISTS `index_note_images_sectionId` ON `note_images`(`sectionId`)",
    )

    /**
     * v23 -> v24: estado do backup de imagens no Storage (5.4).
     * Aditiva; o binário local e a tabela note_images não mudam.
     */
    val MIGRATION_23_24: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `image_backups` (
            `imageId` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `cloudPath` TEXT,
            `sizeBytes` INTEGER NOT NULL,
            `sha256` TEXT,
            `uploadedAt` INTEGER,
            `error` TEXT,
            `attempts` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`imageId`),
            FOREIGN KEY(`imageId`) REFERENCES `note_images`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_image_backups_state` ON `image_backups`(`state`)",
        "CREATE INDEX IF NOT EXISTS `index_image_backups_updatedAt` ON `image_backups`(`updatedAt`)",
    )

    /**
     * v24 -> v25 (5.4.1): uid por backup e remoção da FK com note_images.
     * Assim a linha `deleted` sobrevive à exclusão local até o objeto remoto
     * ser apagado; linhas antigas ficam com uid NULL e são reenviadas com
     * metadata no próximo backfill.
     */
    val MIGRATION_24_25: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `image_backups_new` (
            `imageId` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `cloudPath` TEXT,
            `sizeBytes` INTEGER NOT NULL,
            `sha256` TEXT,
            `uploadedAt` INTEGER,
            `error` TEXT,
            `attempts` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `uid` TEXT,
            PRIMARY KEY(`imageId`)
        )
        """.trimIndent(),
        """
        INSERT INTO `image_backups_new`
            (`imageId`,`state`,`cloudPath`,`sizeBytes`,`sha256`,`uploadedAt`,
             `error`,`attempts`,`updatedAt`,`uid`)
        SELECT `imageId`,`state`,`cloudPath`,`sizeBytes`,`sha256`,`uploadedAt`,
               `error`,`attempts`,`updatedAt`,NULL
        FROM `image_backups`
        """.trimIndent(),
        "DROP TABLE `image_backups`",
        "ALTER TABLE `image_backups_new` RENAME TO `image_backups`",
        "CREATE INDEX IF NOT EXISTS `index_image_backups_state` ON `image_backups`(`state`)",
        "CREATE INDEX IF NOT EXISTS `index_image_backups_updatedAt` ON `image_backups`(`updatedAt`)",
    )

    /**
     * v25 -> v26 (5.6a): índice estrutural derivado das passages.
     * Aditiva; nenhum passage/anexo existente é alterado. `startOrd`/`endOrd`
     * apontam para os `ord` cobertos pela unidade.
     */
    val MIGRATION_25_26: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `publication_units` (
            `id` TEXT NOT NULL,
            `attachmentId` TEXT NOT NULL,
            `parentId` TEXT,
            `kind` TEXT NOT NULL,
            `number` TEXT,
            `title` TEXT NOT NULL,
            `canonicalKey` TEXT NOT NULL,
            `ord` INTEGER NOT NULL,
            `startOrd` INTEGER NOT NULL,
            `endOrd` INTEGER NOT NULL,
            `startPage` INTEGER,
            `endPage` INTEGER,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`attachmentId`) REFERENCES `attachments`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_publication_units_attachmentId` ON `publication_units`(`attachmentId`)",
        "CREATE INDEX IF NOT EXISTS `index_publication_units_parentId` ON `publication_units`(`parentId`)",
        "CREATE INDEX IF NOT EXISTS `index_publication_units_attachmentId_ord` ON `publication_units`(`attachmentId`, `ord`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_publication_units_attachmentId_canonicalKey` ON `publication_units`(`attachmentId`, `canonicalKey`)",
    )
}
