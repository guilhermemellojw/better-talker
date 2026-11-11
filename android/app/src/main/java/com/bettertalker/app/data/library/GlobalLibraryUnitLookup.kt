package com.bettertalker.app.data.library

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.publication.PublicationUnitBackfill

/**
 * Acesso às unidades estruturais usado pelo tool/reader. Pequeno de propósito
 * para permitir teste JVM do [GlobalLibraryTool] sem Room.
 */
interface GlobalLibraryUnitLookup {
    suspend fun ensure(attachmentId: String)
    suspend fun unitAt(attachmentId: String, ord: Int): PublicationUnitEntity?
    suspend fun units(attachmentId: String): List<PublicationUnitEntity>
    suspend fun passages(
        attachmentId: String,
        startOrd: Int,
        endOrd: Int,
        limit: Int,
    ): List<PassageEntity>
}

class RoomGlobalLibraryUnitLookup(
    private val db: AppDatabase,
) : GlobalLibraryUnitLookup {
    override suspend fun ensure(attachmentId: String) {
        PublicationUnitBackfill.ensureFor(attachmentId, db)
    }

    override suspend fun unitAt(attachmentId: String, ord: Int): PublicationUnitEntity? =
        db.publicationUnitDao().unitAt(attachmentId, ord)

    override suspend fun units(attachmentId: String): List<PublicationUnitEntity> =
        db.publicationUnitDao().forAttachment(attachmentId)

    override suspend fun passages(
        attachmentId: String,
        startOrd: Int,
        endOrd: Int,
        limit: Int,
    ): List<PassageEntity> =
        db.passageDao().betweenOrd(attachmentId, startOrd, endOrd, limit)
}
