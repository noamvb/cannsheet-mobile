package com.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.widget.EXTRA_START_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {
    @Test
    fun widgetRouteIsConsumedExactlyOnce() {
        val intent = Intent().putExtra(EXTRA_START_ROUTE, "settings")

        assertEquals("settings", intent.consumeStartRoute())
        assertFalse(intent.hasExtra(EXTRA_START_ROUTE))
        assertNull(intent.consumeStartRoute())
    }
}
