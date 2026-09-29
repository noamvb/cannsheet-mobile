package com.example.widget

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetColorsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun widgetSurfaceColorMatchesDesignInLight() {
        val expected = 0xFFFFFFFF.toInt()
        val actual = ContextCompat.getColor(context, R.color.widget_surface)
        assertEquals(expected, actual)
    }

    @Test
    @Config(qualifiers = "night")
    fun widgetSurfaceColorMatchesDesignInNight() {
        val expected = 0xFF18221F.toInt()
        val actual = ContextCompat.getColor(context, R.color.widget_surface)
        assertEquals(expected, actual)
    }
}
