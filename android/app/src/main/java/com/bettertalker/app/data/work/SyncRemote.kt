package com.bettertalker.app.data.work

/**
 * F7a — porta das operações remotas usadas pelo SyncWorker.
 *
 * Motivação: os syncs estavam soldados em `FirebaseFirestore.getInstance()` e
 * em tipos finais do SDK (`DocumentReference`/`QuerySnapshot`/`Task`), o que
 * impedia testá-los em JVM. Esta interface expõe exatamente o subconjunto
 * remoto usado hoje — coleções FLAT (chave = id do documento), leitura em
 * lote, leitura de doc único, set com merge e delete. A implementação de
 * produção é [FirestoreSyncRemote]; os testes usam um fake Map-based.
 *
 * Contrato:
 * - [getAll] devolve `id -> dados`; documento sem dados vira mapa vazio.
 * - [get] devolve `null` quando o documento não existe.
 * - [set] faz MERGE no documento (equivale a `SetOptions.merge()`).
 * - [delete] é idempotente (remover doc inexistente não falha).
 *
 * A verificação do #7a encontrou UMA operação além de getAll/set/delete no
 * worker — `document(id).get()` em `syncAttachments` — coberta por [get].
 * Não há queries com `where`/`orderBy`/`limit` no worker hoje.
 */
interface SyncRemote {
    suspend fun getAll(collection: String): Map<String, Map<String, Any?>>
    suspend fun get(collection: String, id: String): Map<String, Any?>?
    suspend fun set(collection: String, id: String, data: Map<String, Any?>)
    suspend fun delete(collection: String, id: String)

    /**
     * Relógio autoritativo do Firestore (serverTimestamp resolvido).
     *
     * Usado pelo guard de skew do SyncWorker: se o relógio local divergir
     * mais de 5 min, o sync aborta em vez de inverter o LWW. Null = não foi
     * possível resolver o timestamp do servidor.
     */
    suspend fun serverNow(): Long?
}
