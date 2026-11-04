package com.bettertalker.app.data.work

/** Build público só entende o formato atual (Long). */
internal fun cloudTimestampMillis(value: Any?): Long? = (value as? Number)?.toLong()
