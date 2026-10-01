package com.bettertalker.app.data.db

import androidx.room.withTransaction

/**
 * Abstração sobre transação de banco. Permite testar atomicidade sem
 * Robolectric, injetando um runner fake que simula rollback.
 */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

/**
 * Implementação real sobre Room. Delega para [AppDatabase.withTransaction].
 */
class RoomTransactionRunner(private val db: AppDatabase) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction(block)
}
