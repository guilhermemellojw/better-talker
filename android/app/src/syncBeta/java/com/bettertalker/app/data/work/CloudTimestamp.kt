package com.bettertalker.app.data.work

import com.google.firebase.Timestamp

/** Converte `updatedAt` antigo (Timestamp) ou novo (Long) para millis. */
internal fun cloudTimestampMillis(value: Any?): Long? = when (value) {
    is Long -> value
    is Number -> value.toLong()
    is Timestamp -> value.toDate().time
    else -> null
}
