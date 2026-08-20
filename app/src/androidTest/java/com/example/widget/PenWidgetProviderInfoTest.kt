package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the `android:configure` attribute format. The framework treats the
 * attribute as a bare class name and builds ComponentName(providerPackage, it),
 * so a flattened "package/class" value yields a class name containing a slash
 * and a component that cannot be started.
 */
@RunWith(AndroidJUnit4::class)
class PenWidgetProviderInfoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyConfigurableProviderResolvesItsConfigurationActivity() {
        val packageManager = context.packageManager
        val providers = AppWidgetManager.getInstance(context).installedProviders
            .filter { it.provider.packageName == context.packageName }

        assertTrue("No Cannsheet widget providers found", providers.isNotEmpty())

        providers.mapNotNull { it.configure }.forEach { configure ->
            assertEquals(
                "Configuration component must live in this package",
                context.packageName,
                configure.packageName,
            )
            assertTrue(
                "Class name must not contain '/': $configure",
                !configure.className.contains('/'),
            )
            assertNotNull(
                "Configuration activity is not resolvable: $configure",
                packageManager.resolveActivity(Intent().setComponent(configure), 0),
            )
        }
    }

    @Test
    fun penWidgetDeclaresItsConfigurationActivity() {
        val info = AppWidgetManager.getInstance(context).installedProviders
            .single { it.provider.className == PenConsumptionWidgetProvider::class.java.name }

        assertEquals(
            "com.example.widget.PenWidgetConfigureActivity",
            info.configure?.className,
        )
    }
}
