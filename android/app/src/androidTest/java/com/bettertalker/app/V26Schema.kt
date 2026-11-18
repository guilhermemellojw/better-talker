package com.bettertalker.app

/**
 * 1C.2.1A2 — DDL exato do AppDatabase v26 gerado pelo Room, sem
 * `room_master_table` (o Room recria/valida a identidade ao abrir v27).
 *
 * Usado apenas para preparar a fixture de upgrade real; não toca no banco
 * do usuário.
 */
internal object V26Schema {
    val DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `mdText` TEXT NOT NULL, `plainText` TEXT NOT NULL, `folderId` TEXT, `colorArgb` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `trashed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `richHtml` TEXT NOT NULL, `discourseType` TEXT NOT NULL, `lastSyncedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `noteId` TEXT, `fileName` TEXT NOT NULL, `kind` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `appPath` TEXT NOT NULL, `indexed` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `baseSlot` TEXT, `status` TEXT NOT NULL, `error` TEXT, `downloadId` INTEGER NOT NULL, `sourceUrl` TEXT, `sourceType` TEXT NOT NULL, `symbol` TEXT, `sha256` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_attachments_baseSlot` ON `attachments` (`baseSlot`)",
        "CREATE TABLE IF NOT EXISTS `passages` (`id` TEXT NOT NULL, `attachmentId` TEXT NOT NULL, `text` TEXT NOT NULL, `normalized` TEXT NOT NULL, `section` TEXT NOT NULL, `ref` TEXT NOT NULL, `page` INTEGER, `paragraph` INTEGER, `ord` INTEGER NOT NULL, `trainingCategory` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_passages_attachmentId` ON `passages` (`attachmentId`)",
        "CREATE TABLE IF NOT EXISTS `publication_units` (`id` TEXT NOT NULL, `attachmentId` TEXT NOT NULL, `parentId` TEXT, `kind` TEXT NOT NULL, `number` TEXT, `title` TEXT NOT NULL, `canonicalKey` TEXT NOT NULL, `ord` INTEGER NOT NULL, `startOrd` INTEGER NOT NULL, `endOrd` INTEGER NOT NULL, `startPage` INTEGER, `endPage` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`attachmentId`) REFERENCES `attachments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_publication_units_attachmentId` ON `publication_units` (`attachmentId`)",
        "CREATE INDEX IF NOT EXISTS `index_publication_units_parentId` ON `publication_units` (`parentId`)",
        "CREATE INDEX IF NOT EXISTS `index_publication_units_attachmentId_ord` ON `publication_units` (`attachmentId`, `ord`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_publication_units_attachmentId_canonicalKey` ON `publication_units` (`attachmentId`, `canonicalKey`)",
        "CREATE TABLE IF NOT EXISTS `tombstones` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `outlines` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, `totalMinutes` INTEGER, `sectionsJson` TEXT NOT NULL, `refsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `fromMe` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sectionId` TEXT, `isActive` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `s34_outlines` (`id` TEXT NOT NULL, `sourceAttachmentId` TEXT NOT NULL, `symbol` TEXT NOT NULL, `title` TEXT NOT NULL, `objective` TEXT, `headerLinesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `parserVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_s34_outlines_sourceAttachmentId` ON `s34_outlines` (`sourceAttachmentId`)",
        "CREATE TABLE IF NOT EXISTS `s34_sections` (`id` TEXT NOT NULL, `outlineId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `minutes` INTEGER, `sourceLine` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_s34_sections_outlineId` ON `s34_sections` (`outlineId`)",
        "CREATE TABLE IF NOT EXISTS `s34_subsections` (`id` TEXT NOT NULL, `sectionId` TEXT NOT NULL, `position` INTEGER NOT NULL, `content` TEXT NOT NULL, `sourceLine` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_s34_subsections_sectionId` ON `s34_subsections` (`sectionId`)",
        "CREATE TABLE IF NOT EXISTS `s34_references` (`id` TEXT NOT NULL, `outlineId` TEXT NOT NULL, `sectionId` TEXT, `subsectionId` TEXT, `position` INTEGER NOT NULL, `type` TEXT NOT NULL, `rawText` TEXT NOT NULL, `normalizedReference` TEXT NOT NULL, `sourceLine` INTEGER NOT NULL, `book` TEXT, `bookNorm` TEXT, `chapter` INTEGER, `verse` INTEGER, `pubKind` TEXT, `pubKey` TEXT, `pubLabel` TEXT, `editionKey` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_s34_references_outlineId` ON `s34_references` (`outlineId`)",
        "CREATE INDEX IF NOT EXISTS `index_s34_references_sectionId` ON `s34_references` (`sectionId`)",
        "CREATE TABLE IF NOT EXISTS `speech_sections` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `order` INTEGER NOT NULL, `role` TEXT NOT NULL, `title` TEXT NOT NULL, `minutes` INTEGER NOT NULL, `contentHtml` TEXT NOT NULL, `bibleRefsJson` TEXT NOT NULL, `publicationRefsJson` TEXT NOT NULL, `methodPrinciple` TEXT, `objective` TEXT, `agreedApproach` TEXT, `approvedAt` INTEGER, `tribunaJson` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_speech_sections_noteId` ON `speech_sections` (`noteId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_speech_sections_noteId_order` ON `speech_sections` (`noteId`, `order`)",
        "CREATE TABLE IF NOT EXISTS `sub_points` (`id` TEXT NOT NULL, `sectionId` TEXT NOT NULL, `order` INTEGER NOT NULL, `outlineText` TEXT NOT NULL, `bibleRefsJson` TEXT NOT NULL, `publicationRefsJson` TEXT NOT NULL, `instruction` TEXT, `developedHtml` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sectionId`) REFERENCES `speech_sections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_sub_points_sectionId` ON `sub_points` (`sectionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sub_points_sectionId_order` ON `sub_points` (`sectionId`, `order`)",
        "CREATE TABLE IF NOT EXISTS `note_images` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `sectionId` TEXT, `path` TEXT NOT NULL, `mime` TEXT NOT NULL, `caption` TEXT NOT NULL, `kind` TEXT NOT NULL, `order` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sectionId`) REFERENCES `speech_sections`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_note_images_noteId` ON `note_images` (`noteId`)",
        "CREATE INDEX IF NOT EXISTS `index_note_images_sectionId` ON `note_images` (`sectionId`)",
        "CREATE TABLE IF NOT EXISTS `image_backups` (`imageId` TEXT NOT NULL, `state` TEXT NOT NULL, `cloudPath` TEXT, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT, `uploadedAt` INTEGER, `error` TEXT, `attempts` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `uid` TEXT, PRIMARY KEY(`imageId`))",
        "CREATE INDEX IF NOT EXISTS `index_image_backups_state` ON `image_backups` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_image_backups_updatedAt` ON `image_backups` (`updatedAt`)",
    )
}
