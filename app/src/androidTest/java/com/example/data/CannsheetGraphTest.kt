package com.example.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CannsheetGraphTest {
    @Test
    fun processGraphReusesTheSameRoomDatabaseInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val first = CannsheetGraph.get(context)
        val second = CannsheetGraph.get(context)

        assertSame(first, second)
        assertSame(first.database, second.database)
    }
}
