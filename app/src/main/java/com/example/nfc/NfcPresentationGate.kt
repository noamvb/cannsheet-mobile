package com.example.nfc

import java.util.UUID

/**
 * Process-local guard for one continuous physical presentation. It is deliberately only a
 * duplicate-dispatch guard: the durable event ID and DataStore outbox remain the correctness
 * boundary when the process is restarted or a callback is replayed.
 */
class NfcPresentationGate {
    @Volatile
    private var activeTagId: String? = null
    private var activeSinceEpochMillis: Long = 0L

    @Synchronized
    fun begin(tagId: String, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        if (!NfcQuickLogContract.isCanonicalRfc4122Uuid(tagId)) return false
        if (activeTagId == tagId &&
            nowEpochMillis - activeSinceEpochMillis < FALLBACK_EXPIRY_MILLIS
        ) return false
        activeTagId = tagId
        activeSinceEpochMillis = nowEpochMillis
        return true
    }

    @Synchronized
    fun removed(tagId: String) {
        if (activeTagId == tagId) {
            activeTagId = null
            activeSinceEpochMillis = 0L
        }
    }

    @Synchronized
    fun clear() {
        activeTagId = null
        activeSinceEpochMillis = 0L
    }

    @Synchronized
    fun isActive(tagId: String): Boolean = activeTagId == tagId

    companion object {
        private const val FALLBACK_EXPIRY_MILLIS = 8_000L
        private val PROCESS_GATE = NfcPresentationGate()

        /** One gate per app process, so a no-history Activity cannot reset duplicate protection. */
        fun processLocal(): NfcPresentationGate = PROCESS_GATE
    }
}
