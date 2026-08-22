package com.example.nfc

/**
 * Process-wide marker that the foreground tag writer owns the NFC field.
 *
 * The writer holds reader mode while resumed, which suppresses platform tag dispatch. This is the
 * second line of defence for the moments reader mode cannot cover — the gap while the Activity is
 * pausing or resuming — because a tag written by this app carries its own Application Record, so
 * a dispatch during that gap launches [NfcQuickLogActivity] over the writer. Mid-registration the
 * tag is not in the registry yet, so the owner sees "This NFC tag is not registered" while trying
 * to register it, and the writer is paused before it can finish.
 *
 * [isActiveWithin] therefore stays true for a short grace period after the writer pauses. The cost
 * is that a deliberate quick-log tap immediately after closing the writer is ignored; that is a
 * retap, against a flow that could otherwise not complete at all.
 */
internal object NfcWriterSession {
    const val GRACE_MILLIS = 2_500L

    @Volatile
    private var resumed = false

    @Volatile
    private var lastChangedAtEpochMillis = 0L

    fun onWriterResumed(nowEpochMillis: Long = System.currentTimeMillis()) {
        resumed = true
        lastChangedAtEpochMillis = nowEpochMillis
    }

    fun onWriterPaused(nowEpochMillis: Long = System.currentTimeMillis()) {
        resumed = false
        lastChangedAtEpochMillis = nowEpochMillis
    }

    fun isActiveWithin(
        graceMillis: Long = GRACE_MILLIS,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = resumed || nowEpochMillis - lastChangedAtEpochMillis in 0 until graceMillis

    /** Test seam; production code never needs to forget a session. */
    internal fun reset() {
        resumed = false
        lastChangedAtEpochMillis = 0L
    }
}
