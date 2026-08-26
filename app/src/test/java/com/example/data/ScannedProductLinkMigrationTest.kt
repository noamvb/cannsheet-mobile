package com.example.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannedProductLinkMigrationTest {
    @Test
    fun `migration creates the scanned product link entity columns`() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            if (method.name == "execSQL" && arguments?.size == 1) {
                statements += arguments[0] as String
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_11_12.migrate(database)

        assertEquals(
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `scanned_product_links` (
                    `gtin` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `lastBatch` TEXT,
                    `lastSeenAtEpochMillis` INTEGER NOT NULL,
                    `timesSeen` INTEGER NOT NULL,
                    PRIMARY KEY(`gtin`)
                )
                """.trimIndent(),
            ),
            statements,
        )
    }

    @Test
    fun `repeated sightings accumulate and replace the latest identity values`() {
        val first = nextScannedProductLink(
            existing = null,
            gtin = "00012345678905",
            name = "First name",
            type = "P",
            batch = "batch-1",
            nowEpochMillis = 1_000L,
        )
        val second = nextScannedProductLink(
            existing = first,
            gtin = "00012345678905",
            name = "Updated name",
            type = "E",
            batch = "batch-2",
            nowEpochMillis = 2_000L,
        )

        assertEquals(1, first.timesSeen)
        assertEquals(2, second.timesSeen)
        assertEquals("Updated name", second.name)
        assertEquals("E", second.type)
        assertEquals("batch-2", second.lastBatch)
        assertEquals(2_000L, second.lastSeenAtEpochMillis)
    }
}
