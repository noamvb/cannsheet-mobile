package com.example.nfc

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit foreground-only writer. It has no exported NFC filter and never writes read-only. */
class NfcTagWriterActivity : ComponentActivity() {
    private val writer = NfcTagWriter()
    private val registry by lazy { NfcQuickLogRegistryRepository(this) }
    private var adapter: NfcAdapter? = null
    private var state by mutableStateOf<NfcTagWriterState>(NfcTagWriterState.Editing)
    private var mode by mutableStateOf(WriterMode.WRITE)
    private var target: NfcQuickLogTagData? = null
    private var label: String? = null
    private var inspected: NfcInspectedTag? = null
    private var observedPhysical: NfcQuickLogTagData? = null
    private var overwriteFingerprint: NfcPhysicalTagFingerprint? = null
    private var overwriteArmed = false
    private var overwriteIdentityWarning by mutableStateOf(false)
    private var verificationRequired = false
    private var launchAllowed by mutableStateOf(true)
    private val tagIoMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = WriterMode.from(intent.getStringExtra(EXTRA_MODE))
        target = runCatching {
            val tagId = intent.getStringExtra(EXTRA_TAG_ID)
            val uses = intent.getIntExtra(EXTRA_USES, 1)
            when {
                // ADOPT has no pre-known target; it is discovered from the physical tag. Minting
                // a placeholder here would render a misleading quantity before the first tap.
                mode == WriterMode.ADOPT -> null
                tagId == null -> NfcQuickLogContract.newTagData(uses)
                NfcQuickLogContract.isCanonicalRfc4122Uuid(tagId) && NfcQuickLogContract.isValidUses(uses) ->
                    NfcQuickLogTagData(tagId, uses)
                else -> null
            }
        }.getOrNull()
        label = intent.getStringExtra(EXTRA_LABEL)
        if (target == null && mode != WriterMode.ADOPT) state = NfcTagWriterState.InvalidConfiguration
        setContent {
            MyApplicationTheme {
                NfcTagWriterContent(
                    state = state,
                    target = target,
                    launchAllowed = launchAllowed,
                    overwriteIdentityWarning = overwriteIdentityWarning,
                    onEnableNfc = ::openNfcSettings,
                    onOpenLaunchPreference = ::openLaunchPreference,
                    onArmOverwrite = ::armOverwrite,
                    onAdopt = ::adopt,
                    onRepair = ::repair,
                    onRetry = ::retry,
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter = NfcAdapter.getDefaultAdapter(this)
        val current = adapter
        launchAllowed = current == null || Build.VERSION.SDK_INT < 36 ||
            !current.isTagIntentAppPreferenceSupported() || current.isTagIntentAllowed()
        when {
            current == null -> state = NfcTagWriterState.UnavailableNoAdapter
            !current.isEnabled -> state = NfcTagWriterState.NfcDisabled
            Build.VERSION.SDK_INT >= 36 && current.isTagIntentAppPreferenceSupported() &&
                !current.isTagIntentAllowed() && mode != WriterMode.VERIFY ->
                state = NfcTagWriterState.LaunchPermissionBlocked
            else -> enableReader()
        }
    }

    override fun onPause() {
        disableReader()
        super.onPause()
    }

    private fun enableReader() {
        val current = adapter ?: return
        if (state is NfcTagWriterState.Writing ||
            state is NfcTagWriterState.Verified ||
            state is NfcTagWriterState.InvalidConfiguration
        ) return
        state = when {
            verificationRequired -> NfcTagWriterState.WaitingToVerify
            overwriteArmed -> NfcTagWriterState.WaitingToWrite
            else -> NfcTagWriterState.WaitingToInspect
        }
        runCatching {
            current.enableReaderMode(
                this,
                { tag ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        tagIoMutex.withLock { handleTag(tag) }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V,
                null,
            )
        }.onFailure { state = NfcTagWriterState.Failed("Reader mode could not start") }
    }

    private fun disableReader() {
        runCatching { adapter?.disableReaderMode(this) }
    }

    private suspend fun handleTag(tag: Tag) {
        val configuredTarget = target
        // Every mode but ADOPT requires a pre-known target; ADOPT discovers its target from the
        // physical tag, so it proceeds into inspection even when none is known yet.
        if (configuredTarget == null && mode != WriterMode.ADOPT) return
        val shouldProcess = withContext(Dispatchers.Main) {
            val waiting = state == NfcTagWriterState.WaitingToInspect ||
                state == NfcTagWriterState.WaitingToWrite ||
                state == NfcTagWriterState.WaitingToVerify
            if (waiting) {
                state = if (verificationRequired) {
                    NfcTagWriterState.Verifying
                } else {
                    NfcTagWriterState.Inspecting
                }
                disableReader()
            }
            waiting
        }
        if (!shouldProcess) return
        if (verificationRequired && configuredTarget != null) {
            val expected = NfcQuickLogContract.messageFor(
                configuredTarget.tagId,
                configuredTarget.uses,
                packageName,
            )
            when (val result = writer.verifyExact(tag, expected)) {
                is NfcTagVerificationResult.Exact -> {
                    val mutation = runCatching {
                        if (mode == WriterMode.REWRITE) {
                            registry.updateAfterVerifiedRewrite(configuredTarget, label)
                        } else {
                            registry.registerAfterVerifiedWrite(configuredTarget, label)
                        }
                    }.getOrElse {
                        withContext(Dispatchers.Main) {
                            state = NfcTagWriterState.RegistrySaveFailed
                            verificationRequired = false
                            disableReader()
                        }
                        return
                    }
                    withContext(Dispatchers.Main) {
                        state = when (mutation) {
                            is NfcQuickLogRegistryMutationResult.Applied -> NfcTagWriterState.Verified
                            NfcQuickLogRegistryMutationResult.RegistryCorrupt -> NfcTagWriterState.RegistryCorrupt
                            NfcQuickLogRegistryMutationResult.StorageFailure -> NfcTagWriterState.RegistrySaveFailed
                            else -> NfcTagWriterState.RegistrySaveFailed
                        }
                        verificationRequired = false
                        disableReader()
                    }
                }
                is NfcTagVerificationResult.Mismatch -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.VerificationMismatch
                }
                NfcTagVerificationResult.Unsupported -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.Unsupported
                }
                NfcTagVerificationResult.TagLost -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.TagLost
                }
                is NfcTagVerificationResult.Failed -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.Failed("Verification failed; retap to try again")
                }
            }
            return
        }

        val inspection = withContext(Dispatchers.IO) { writer.inspect(tag) }
        when (inspection) {
            is NfcTagInspectionResult.Inspected -> inspectAndMaybeWrite(tag, inspection.tag, configuredTarget)
            NfcTagInspectionResult.Unsupported -> withContext(Dispatchers.Main) {
                state = NfcTagWriterState.Unsupported
            }
            NfcTagInspectionResult.TagLost -> withContext(Dispatchers.Main) {
                state = NfcTagWriterState.TagLost
            }
            is NfcTagInspectionResult.Failed -> withContext(Dispatchers.Main) {
                state = NfcTagWriterState.Failed("Could not read this tag")
            }
        }
    }

    private suspend fun inspectAndMaybeWrite(
        tag: Tag,
        current: NfcInspectedTag,
        configuredTarget: NfcQuickLogTagData?,
    ) {
        val previousInspection = inspected
        inspected = current
        overwriteFingerprint?.let { expected ->
            if (overwriteArmed && !samePhysicalTag(expected, current.fingerprint)) {
                withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.Failed("A different tag was presented; inspect again")
                    overwriteArmed = false
                    overwriteFingerprint = null
                    disableReader()
                }
                return
            }
        }
        val existing = current.message?.let { NfcQuickLogContract.parse(it, packageName) }
        if (existing != null) {
            observedPhysical = existing
            when (val verification = registry.verify(existing)) {
                is NfcQuickLogRegistryVerification.Verified -> if (mode == WriterMode.REWRITE && configuredTarget != null) {
                    if (existing.tagId != configuredTarget.tagId) {
                        withContext(Dispatchers.Main) {
                            state = NfcTagWriterState.Failed("A different registered tag was presented")
                            disableReader()
                        }
                    } else {
                        writeVerifiedTag(tag, current, configuredTarget)
                    }
                } else withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.AlreadyVerified
                    disableReader()
                }
                is NfcQuickLogRegistryVerification.UsesMismatch -> if (mode == WriterMode.REWRITE && configuredTarget != null) {
                    if (existing.tagId != configuredTarget.tagId) {
                        withContext(Dispatchers.Main) {
                            state = NfcTagWriterState.Failed("A different registered tag was presented")
                            disableReader()
                        }
                    } else if (!overwriteArmed) {
                        withContext(Dispatchers.Main) {
                            state = NfcTagWriterState.WaitingToWrite
                            overwriteArmed = true
                            overwriteFingerprint = current.fingerprint
                            disableReader()
                        }
                    } else {
                        writeVerifiedTag(tag, current, configuredTarget)
                    }
                } else withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.RegistryMismatch
                    disableReader()
                }
                NfcQuickLogRegistryVerification.Unregistered -> withContext(Dispatchers.Main) {
                    target = existing
                    state = NfcTagWriterState.CompatibleUnregistered
                    disableReader()
                }
                NfcQuickLogRegistryVerification.RegistryCorrupt -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.RegistryCorrupt
                    disableReader()
                }
                NfcQuickLogRegistryVerification.InvalidTag -> withContext(Dispatchers.Main) {
                    state = NfcTagWriterState.InvalidConfiguration
                    disableReader()
                }
            }
            return
        }
        // ADOPT never writes. Once the tag is known not to carry valid Cannsheet content (blank,
        // formatable, or foreign), there is nothing to adopt, so stop here rather than falling
        // into the overwrite/write paths below.
        if (mode == WriterMode.ADOPT) {
            withContext(Dispatchers.Main) {
                state = NfcTagWriterState.NoCompatibleTagToAdopt
                disableReader()
            }
            return
        }
        val configuredTarget = configuredTarget ?: return
        if (overwriteArmed && previousInspection != null &&
            !samePhysicalTag(previousInspection.fingerprint, current.fingerprint)
        ) {
            withContext(Dispatchers.Main) {
                state = NfcTagWriterState.Failed("A different tag was presented; inspect again")
                overwriteArmed = false
                disableReader()
            }
            return
        }
        if (current.message != null && !overwriteArmed) {
            withContext(Dispatchers.Main) {
                state = NfcTagWriterState.ForeignContentFound
                overwriteFingerprint = current.fingerprint
                overwriteIdentityWarning = current.fingerprint.uidHex.isEmpty()
                disableReader()
            }
            return
        }
        // An empty, initialized NDEF tag contains no owner content to overwrite, so it may be
        // written on this first presentation. Foreign/nonempty content remains a two-presentation
        // operation through the explicit overwrite path above. A formatable blank tag is still
        // written here but returns NeedsVerificationRetap from NfcTagWriter.
        if (!current.writable && !current.formatable) {
            withContext(Dispatchers.Main) { state = NfcTagWriterState.ReadOnlyIncompatible }
            return
        }
        val message = NfcQuickLogContract.messageFor(
            configuredTarget.tagId,
            configuredTarget.uses,
            packageName,
        )
        withContext(Dispatchers.Main) {
            state = NfcTagWriterState.Writing
        }
        val result = withContext(Dispatchers.IO) { writer.write(tag, message) }
        withContext(Dispatchers.Main) {
            when (result) {
                NfcTagWriteResult.WrittenAndVerified -> activateRegistryAfterWrite(configuredTarget)
                NfcTagWriteResult.WrittenAwaitingRetap -> {
                    verificationRequired = true
                    overwriteArmed = false
                    state = NfcTagWriterState.NeedsVerificationRetap
                    disableReader()
                }
                NfcTagWriteResult.ReadOnly -> state = NfcTagWriterState.ReadOnlyIncompatible
                is NfcTagWriteResult.TooSmall -> state = NfcTagWriterState.TooSmall(
                    result.requiredBytes,
                    result.availableBytes,
                )
                NfcTagWriteResult.Unsupported -> state = NfcTagWriterState.Unsupported
                NfcTagWriteResult.TagLost -> state = NfcTagWriterState.TagLost
                is NfcTagWriteResult.Failed -> state = NfcTagWriterState.WriteUncertain
            }
        }
    }

    private suspend fun writeVerifiedTag(
        tag: Tag,
        current: NfcInspectedTag,
        configuredTarget: NfcQuickLogTagData,
    ) {
        if (!overwriteArmed) {
            withContext(Dispatchers.Main) {
                state = NfcTagWriterState.WaitingToWrite
                overwriteArmed = true
                overwriteFingerprint = current.fingerprint
                disableReader()
            }
            return
        }
        val expectedFingerprint = overwriteFingerprint
        if (expectedFingerprint != null && !samePhysicalTag(expectedFingerprint, current.fingerprint)) {
            withContext(Dispatchers.Main) {
                state = NfcTagWriterState.Failed("A different tag was presented; inspect again")
                overwriteArmed = false
                overwriteFingerprint = null
                disableReader()
            }
            return
        }
        withContext(Dispatchers.Main) {
            state = NfcTagWriterState.Writing
        }
        val result = withContext(Dispatchers.IO) {
            writer.write(
                tag,
                NfcQuickLogContract.messageFor(configuredTarget.tagId, configuredTarget.uses, packageName),
            )
        }
        withContext(Dispatchers.Main) {
            when (result) {
                NfcTagWriteResult.WrittenAndVerified -> activateRegistryAfterWrite(configuredTarget)
                NfcTagWriteResult.WrittenAwaitingRetap -> {
                    verificationRequired = true
                    state = NfcTagWriterState.NeedsVerificationRetap
                    disableReader()
                }
                NfcTagWriteResult.ReadOnly -> state = NfcTagWriterState.ReadOnlyIncompatible
                is NfcTagWriteResult.TooSmall -> state = NfcTagWriterState.TooSmall(
                    result.requiredBytes,
                    result.availableBytes,
                )
                NfcTagWriteResult.Unsupported -> state = NfcTagWriterState.Unsupported
                NfcTagWriteResult.TagLost -> state = NfcTagWriterState.TagLost
                is NfcTagWriteResult.Failed -> state = NfcTagWriterState.WriteUncertain
            }
        }
    }

    private fun activateRegistryAfterWrite(configuredTarget: NfcQuickLogTagData) {
        lifecycleScope.launch {
            val mutation = runCatching {
                if (mode == WriterMode.REWRITE) {
                    registry.updateAfterVerifiedRewrite(configuredTarget, label)
                } else {
                    registry.registerAfterVerifiedWrite(configuredTarget, label)
                }
            }.getOrElse {
                state = NfcTagWriterState.RegistrySaveFailed
                disableReader()
                return@launch
            }
            state = when (mutation) {
                is NfcQuickLogRegistryMutationResult.Applied -> NfcTagWriterState.Verified
                NfcQuickLogRegistryMutationResult.RegistryCorrupt -> NfcTagWriterState.RegistryCorrupt
                NfcQuickLogRegistryMutationResult.StorageFailure -> NfcTagWriterState.RegistrySaveFailed
                else -> NfcTagWriterState.RegistrySaveFailed
            }
            disableReader()
        }
    }

    private fun armOverwrite() {
        overwriteArmed = true
        inspected = null
        enableReader()
    }

    private fun adopt() {
        val existing = target ?: return
        lifecycleScope.launch {
            val mutation = runCatching { registry.adoptVerifiedTag(existing, label) }
                .getOrElse {
                    state = NfcTagWriterState.RegistrySaveFailed
                    disableReader()
                    return@launch
                }
            state = when (mutation) {
                is NfcQuickLogRegistryMutationResult.Applied -> NfcTagWriterState.Verified
                NfcQuickLogRegistryMutationResult.RegistryCorrupt -> NfcTagWriterState.RegistryCorrupt
                NfcQuickLogRegistryMutationResult.StorageFailure -> NfcTagWriterState.RegistrySaveFailed
                else -> NfcTagWriterState.RegistrySaveFailed
            }
        }
    }

    private fun repair() {
        val physical = observedPhysical ?: return
        lifecycleScope.launch {
            val mutation = runCatching { registry.alignRegistryToVerifiedPhysicalTag(physical) }
                .getOrElse {
                    state = NfcTagWriterState.RegistrySaveFailed
                    disableReader()
                    return@launch
                }
            state = when (mutation) {
                is NfcQuickLogRegistryMutationResult.Applied -> NfcTagWriterState.Verified
                NfcQuickLogRegistryMutationResult.RegistryCorrupt -> NfcTagWriterState.RegistryCorrupt
                NfcQuickLogRegistryMutationResult.StorageFailure -> NfcTagWriterState.RegistrySaveFailed
                else -> NfcTagWriterState.RegistrySaveFailed
            }
            disableReader()
        }
    }

    private fun retry() {
        verificationRequired = state is NfcTagWriterState.NeedsVerificationRetap
        enableReader()
    }

    private fun openNfcSettings() {
        val nfcIntent = Intent(Settings.ACTION_NFC_SETTINGS)
        val fallback = Intent(Settings.ACTION_SETTINGS)
        val target = nfcIntent.takeIf { it.resolveActivity(packageManager) != null } ?: fallback
        runCatching { startActivity(target) }
    }

    private fun openLaunchPreference() {
        if (Build.VERSION.SDK_INT >= 36) {
            val preferenceIntent = Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)
            if (preferenceIntent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(preferenceIntent) }
            } else {
                openNfcSettings()
            }
        } else {
            openNfcSettings()
        }
    }

    enum class WriterMode { WRITE, REWRITE, VERIFY, ADOPT;
        companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: WRITE }
    }

    companion object {
        const val EXTRA_USES = "com.noamv.cannsheet.mobile.nfc.USES"
        const val EXTRA_LABEL = "com.noamv.cannsheet.mobile.nfc.LABEL"
        const val EXTRA_TAG_ID = "com.noamv.cannsheet.mobile.nfc.TAG_ID"
        const val EXTRA_MODE = "com.noamv.cannsheet.mobile.nfc.MODE"
    }
}

/** Explicit intent builders used by Settings; keeping extras in one place prevents broadening
 * the exported NFC handler's input surface. */
object NfcTagWriterActivityContract {
    fun writeNew(context: android.content.Context): android.content.Intent =
        newTag(context, uses = 1, label = null)

    /**
     * With no [tag], starts an inspect-only ADOPT session: the writer never mints or writes a new
     * tag, it only adopts whatever compatible, unregistered content the physical tag already
     * carries. With a [tag], delegates to [verify] as before.
     */
    fun adopt(
        context: android.content.Context,
        tag: RegisteredNfcQuickLogTag? = null,
        label: String? = null,
    ): android.content.Intent = tag?.let { verify(context, it) } ?: adoptIntent(context, label)

    fun verify(
        context: android.content.Context,
        tag: RegisteredNfcQuickLogTag,
    ): android.content.Intent = verify(context, tag.tagId, tag.uses, tag.label)

    fun newTag(context: android.content.Context, uses: Int, label: String?): android.content.Intent =
        android.content.Intent(context, NfcTagWriterActivity::class.java)
            .putExtra(NfcTagWriterActivity.EXTRA_USES, uses)
            .putExtra(NfcTagWriterActivity.EXTRA_LABEL, label)

    fun rewrite(
        context: android.content.Context,
        tagId: String,
        uses: Int,
        label: String?,
    ): android.content.Intent = newTag(context, uses, label)
        .putExtra(NfcTagWriterActivity.EXTRA_TAG_ID, tagId)
        .putExtra(NfcTagWriterActivity.EXTRA_MODE, NfcTagWriterActivity.WriterMode.REWRITE.name)

    private fun verify(
        context: android.content.Context,
        tagId: String,
        uses: Int,
        label: String?,
    ): android.content.Intent =
        android.content.Intent(context, NfcTagWriterActivity::class.java)
            .putExtra(NfcTagWriterActivity.EXTRA_TAG_ID, tagId)
            .putExtra(NfcTagWriterActivity.EXTRA_USES, uses)
            .putExtra(NfcTagWriterActivity.EXTRA_LABEL, label)
            .putExtra(NfcTagWriterActivity.EXTRA_MODE, NfcTagWriterActivity.WriterMode.VERIFY.name)

    private fun adoptIntent(context: android.content.Context, label: String?): android.content.Intent =
        android.content.Intent(context, NfcTagWriterActivity::class.java)
            .putExtra(NfcTagWriterActivity.EXTRA_MODE, NfcTagWriterActivity.WriterMode.ADOPT.name)
            .putExtra(NfcTagWriterActivity.EXTRA_LABEL, label)
}

sealed interface NfcTagWriterState {
    data object Editing : NfcTagWriterState
    data object UnavailableNoAdapter : NfcTagWriterState
    data object NfcDisabled : NfcTagWriterState
    data object LaunchPermissionBlocked : NfcTagWriterState
    data object WaitingToInspect : NfcTagWriterState
    data object Inspecting : NfcTagWriterState
    data object CompatibleUnregistered : NfcTagWriterState
    data object NoCompatibleTagToAdopt : NfcTagWriterState
    data object AlreadyVerified : NfcTagWriterState
    data object RegistryMismatch : NfcTagWriterState
    data object ForeignContentFound : NfcTagWriterState
    data object WaitingToWrite : NfcTagWriterState
    data object Writing : NfcTagWriterState
    data object NeedsVerificationRetap : NfcTagWriterState
    data object WaitingToVerify : NfcTagWriterState
    data object Verifying : NfcTagWriterState
    data object VerificationMismatch : NfcTagWriterState
    data object Verified : NfcTagWriterState
    data object ReadOnlyIncompatible : NfcTagWriterState
    data class TooSmall(val required: Int, val available: Int) : NfcTagWriterState
    data object Unsupported : NfcTagWriterState
    data object TagLost : NfcTagWriterState
    data object WriteUncertain : NfcTagWriterState
    data object RegistrySaveFailed : NfcTagWriterState
    data object RegistryCorrupt : NfcTagWriterState
    data object InvalidConfiguration : NfcTagWriterState
    data class Failed(val message: String) : NfcTagWriterState
}

@androidx.compose.runtime.Composable
private fun NfcTagWriterContent(
    state: NfcTagWriterState,
    target: NfcQuickLogTagData?,
    launchAllowed: Boolean,
    overwriteIdentityWarning: Boolean,
    onEnableNfc: () -> Unit,
    onOpenLaunchPreference: () -> Unit,
    onArmOverwrite: () -> Unit,
    onAdopt: () -> Unit,
    onRepair: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .testTag("nfc-tag-writer"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NFC quick-log tag", style = MaterialTheme.typography.headlineSmall)
            target?.let { Text("${it.uses} uses per tap") }
            Text(
                when (state) {
                    NfcTagWriterState.Editing -> "Choose a tag to write."
                    NfcTagWriterState.UnavailableNoAdapter -> "This phone has no NFC hardware."
                    NfcTagWriterState.NfcDisabled -> "NFC is off."
                    NfcTagWriterState.LaunchPermissionBlocked -> "Launch via NFC is disabled for Cannsheet."
                    NfcTagWriterState.WaitingToInspect -> "Hold a tag near the back of the phone."
                    NfcTagWriterState.Inspecting -> "Reading tag…"
                    NfcTagWriterState.CompatibleUnregistered -> "Compatible tag found. Adopt it to activate."
                    NfcTagWriterState.NoCompatibleTagToAdopt ->
                        "This tag has no Cannsheet quick-log content to adopt. Use Write new tag instead."
                    NfcTagWriterState.AlreadyVerified -> "This registered tag is verified."
                    NfcTagWriterState.RegistryMismatch -> "The tag quantity does not match registration."
                    NfcTagWriterState.ForeignContentFound -> if (overwriteIdentityWarning) {
                        "Other content found. This tag has no stable ID; readback cannot prove the same physical tag."
                    } else {
                        "This tag has other content. Overwrite it?"
                    }
                    NfcTagWriterState.WaitingToWrite -> "Remove the tag, then retap to confirm the write."
                    NfcTagWriterState.Writing -> "Writing…"
                    NfcTagWriterState.NeedsVerificationRetap ->
                        "Written. Remove the tag and retap to verify and activate."
                    NfcTagWriterState.WaitingToVerify -> "Remove the tag and retap to verify."
                    NfcTagWriterState.Verifying -> "Verifying…"
                    NfcTagWriterState.VerificationMismatch ->
                        "Readback does not match; keep the tag near the phone and retry."
                    NfcTagWriterState.Verified -> "Tag verified and active."
                    NfcTagWriterState.ReadOnlyIncompatible -> "This tag is read-only and cannot be changed."
                    is NfcTagWriterState.TooSmall ->
                        "Tag is too small (${state.available} bytes; need ${state.required})."
                    NfcTagWriterState.Unsupported -> "Unsupported NFC tag."
                    NfcTagWriterState.TagLost -> "Tag left the field; retry."
                    NfcTagWriterState.WriteUncertain -> "Write result is uncertain. Retap to verify."
                    NfcTagWriterState.RegistrySaveFailed ->
                        "Physical write succeeded, but activation was not saved. Repair or retry."
                    NfcTagWriterState.RegistryCorrupt -> "Registry is corrupt; reset it in Settings before continuing."
                    NfcTagWriterState.InvalidConfiguration -> "Writer configuration is invalid."
                    is NfcTagWriterState.Failed -> state.message
                },
            )
            when (state) {
                NfcTagWriterState.NfcDisabled, NfcTagWriterState.UnavailableNoAdapter ->
                    Button(onClick = onEnableNfc, modifier = Modifier.fillMaxWidth()) { Text("Open NFC settings") }
                NfcTagWriterState.LaunchPermissionBlocked ->
                    Button(onClick = onOpenLaunchPreference, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Launch via NFC")
                    }
                NfcTagWriterState.ForeignContentFound ->
                    Button(
                        onClick = onArmOverwrite,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Overwrite after retap") }
                NfcTagWriterState.CompatibleUnregistered ->
                    Button(
                        onClick = onAdopt,
                        enabled = launchAllowed,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Adopt tag") }
                NfcTagWriterState.RegistryMismatch ->
                    Button(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
                        Text("Use verified physical quantity")
                    }
                NfcTagWriterState.VerificationMismatch,
                NfcTagWriterState.TagLost,
                NfcTagWriterState.WriteUncertain,
                NfcTagWriterState.NeedsVerificationRetap,
                -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                else -> Unit
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
