package com.example.nfc

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
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

    private fun awaitFinishing(controller: org.robolectric.android.controller.ActivityController<NfcQuickLogActivity>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!controller.get().isFinishing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun recreationAfterPresentingFinishesWithoutReposting() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        val saved = Bundle().apply {
            putString("nfc_state_id", "nfc-unavailable")
            putBoolean("nfc_notification_presented", true)
        }
        val controller = Robolectric.buildActivity(NfcQuickLogActivity::class.java).setup(saved)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        assertNull(shadowOf(manager).getNotification(1002))
        controller.pause().stop().destroy()
    }

    @Test
    fun recreationMidScanReportsInterruptedTapInsteadOfVanishing() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        // Saved while the scan was still parsing; the outbox holds no payload.
        val saved = Bundle().apply { putString("nfc_state_id", "parsing") }
        val controller = Robolectric.buildActivity(NfcQuickLogActivity::class.java).setup(saved)
        awaitFinishing(controller)
        assertTrue(controller.get().isFinishing)
        assertEquals("NFC quick-log could not be completed.", shadowOf(manager).getNotification(1002).text())
        controller.pause().stop().destroy()
    }
}
