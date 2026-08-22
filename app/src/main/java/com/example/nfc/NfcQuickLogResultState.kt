package com.example.nfc

/** UI-independent result states; the Activity maps these to lock-safe Compose copy. */
sealed interface NfcQuickLogResultState {
    data object Parsing : NfcQuickLogResultState

    data object DuplicatePresentation : NfcQuickLogResultState

    data object Staging : NfcQuickLogResultState

    data class AwaitingUndo(
        val commitId: String,
        val uses: Int,
        val cartName: String?,
        val submittedAtEpochMillis: Long,
    ) : NfcQuickLogResultState

    data class Saving(
        val uses: Int,
        val cartName: String?,
    ) : NfcQuickLogResultState

    data class Saved(
        val uses: Int,
        val cartName: String?,
    ) : NfcQuickLogResultState

    data class Undone(val uses: Int) : NfcQuickLogResultState

    data object UnregisteredTag : NfcQuickLogResultState

    data object RegistryMismatch : NfcQuickLogResultState

    data object RegistryCorrupt : NfcQuickLogResultState

    data object NoCart : NfcQuickLogResultState

    data object PreviousTapBusy : NfcQuickLogResultState

    data object StorageFailure : NfcQuickLogResultState

    data object InvalidTag : NfcQuickLogResultState

    data object NfcLaunchDisabled : NfcQuickLogResultState

    data object NfcUnavailable : NfcQuickLogResultState

    data class UnexpectedFailure(val message: String? = null) : NfcQuickLogResultState
}
