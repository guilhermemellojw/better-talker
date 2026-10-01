package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.repo.RoomRetrievalRepository
import com.bettertalker.app.data.repo.RoomTrainingRepository
import com.bettertalker.app.domain.planning.OutlineProposer

/**
 * Fia os 3 adapters de retrieval + o gerador real em um [OutlineProposer]
 * pronto para uso. Sem DI framework — construção manual, como no resto do app.
 *
 * Um único [RoomRetrievalRepository] é reusado por bible + publication;
 * um único [RoomTrainingRepository] serve ao method index.
 */
class OutlineProposerFactory(
    private val passageDao: PassageDao,
    private val attachmentDao: AttachmentDao,
    private val llmProvider: LlmProvider,
) {
    /**
     * Cria o propositor fiado: adapters → gerador → propositor.
     */
    fun create(): OutlineProposer {
        val retrieval = RoomRetrievalRepository(passageDao, attachmentDao)
        val training = RoomTrainingRepository(passageDao, attachmentDao)
        return OutlineProposer(
            bibleIndex = RoomBibleIndex(retrieval, attachmentDao),
            publicationIndex = RoomPublicationIndex(retrieval, attachmentDao),
            methodIndex = RoomMethodIndex(training, attachmentDao),
            generator = OutlineGeneratorImpl(llmProvider),
        )
    }
}
