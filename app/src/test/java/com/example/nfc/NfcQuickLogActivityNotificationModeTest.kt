package com.example.nfc

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NfcQuickLogActivityNotificationModeTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)

    @Test
    fun grantedNotificationsPostResultAndFinishActivity() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        val controller = Robolectric.buildActivity(
            NfcQuickLogActivity::class.java, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        assertEquals("NFC is unavailable or turned off.", shadowOf(manager).getNotification(1002).text())
        controller.pause().stop().destroy()
    }

    @Test
    fun deniedNotificationsKeepPopupWithoutPosting() {
        // Positive control with the same manager and launch path before denial.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        val positive = Robolectric.buildActivity(
            NfcQuickLogActivity::class.java, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(manager).getNotification(1002))
        positive.pause().stop().destroy()
        manager.cancel(1002)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(
            NfcQuickLogActivity::class.java, Intent(NfcAdapter.ACTION_NDEF_DISCOVERED),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertNull(shadowOf(manager).getNotification(1002))
        controller.pause().stop().destroy()
    }
}
