package com.example.nfc

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfcQuickLogAndroidContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun frameworkMessageUsesExactRecordOrderAndRuntimeAar() {
        val message = NfcQuickLogContract.messageFor(
            tagId = "00112233-4455-4677-8899-aabbccddeeff",
            uses = 3,
            packageName = context.packageName,
        )

        assertEquals(2, message.records.size)
        assertEquals(
            NfcQuickLogTagData("00112233-4455-4677-8899-aabbccddeeff", 3),
            NfcQuickLogContract.parse(message, context.packageName),
        )
        assertTrue(message.toByteArray().size > NfcQuickLogContract.PAYLOAD_SIZE_BYTES)
        assertTrue("v1 tag must fit the documented 144-byte minimum", message.toByteArray().size <= 144)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }

    @Test
    fun manifestResolvesOnlyTheDedicatedExternalTypeHandlerAndWriterIsPrivate() {
        val intent = Intent(
            "android.nfc.action.NDEF_DISCOVERED",
            Uri.parse(NfcQuickLogContract.EXTERNAL_URI),
        ).addCategory(Intent.CATEGORY_DEFAULT)
        val handlers = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(handlers.any { it.activityInfo.name == NfcQuickLogActivity::class.java.name })

        val nearMiss = Intent(
            "android.nfc.action.NDEF_DISCOVERED",
            Uri.parse("${NfcQuickLogContract.EXTERNAL_URI}-near-miss"),
        ).addCategory(Intent.CATEGORY_DEFAULT)
        assertTrue(
            context.packageManager.queryIntentActivities(
                nearMiss,
                PackageManager.MATCH_DEFAULT_ONLY,
            ).none { it.activityInfo.name == NfcQuickLogActivity::class.java.name },
        )

        val writerInfo = context.packageManager.getActivityInfo(
            ComponentName(context, NfcTagWriterActivity::class.java),
            0,
        )
        assertEquals(false, writerInfo.exported)
    }

    @Test
    fun adoptStartsAnInspectOnlySessionAndNeverPreparesAWritableTarget() {
        val adopt = NfcTagWriterActivityContract.adopt(context, label = "  bedside  ".trim())

        assertEquals(
            NfcTagWriterActivity.WriterMode.ADOPT.name,
            adopt.getStringExtra(NfcTagWriterActivity.EXTRA_MODE),
        )
        // Adoption must not carry a pre-minted UUID or quantity: a blank or foreign tag has to
        // resolve to NoCompatibleTagToAdopt rather than silently being written.
        assertEquals(null, adopt.getStringExtra(NfcTagWriterActivity.EXTRA_TAG_ID))
        assertEquals(false, adopt.hasExtra(NfcTagWriterActivity.EXTRA_USES))
        assertEquals("bedside", adopt.getStringExtra(NfcTagWriterActivity.EXTRA_LABEL))
        assertEquals(
            NfcTagWriterActivity::class.java.name,
            adopt.component?.className,
        )
    }

    @Test
    fun writeNewTagStillCarriesItsConfiguredQuantity() {
        val write = NfcTagWriterActivityContract.newTag(context, uses = 5, label = null)

        assertEquals(5, write.getIntExtra(NfcTagWriterActivity.EXTRA_USES, -1))
        assertEquals(null, write.getStringExtra(NfcTagWriterActivity.EXTRA_MODE))
        assertEquals(
            NfcTagWriterActivity.WriterMode.WRITE,
            NfcTagWriterActivity.WriterMode.from(write.getStringExtra(NfcTagWriterActivity.EXTRA_MODE)),
        )
    }
}
