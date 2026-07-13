package com.example.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "cannsheet-migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFrom2To3PreservesProductsAndAddsInteractions() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version2 = factory.create(configuration(2, object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "type TEXT NOT NULL, status INTEGER NOT NULL, cost REAL NOT NULL, " +
                        "thc REAL NOT NULL, grams REAL NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO products VALUES ('p1', 'Test product', 'F', 0, 10.0, 0.2, 3.5)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version2.writableDatabase
        version2.close()

        val version3 = factory.create(configuration(3, object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_2_3.migrate(db)
            }
        }))
        val migrated = version3.writableDatabase

        migrated.query("SELECT name FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Test product", cursor.getString(0))
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'product_interactions'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        version3.close()
    }

    @Test
    fun migrationFrom3To4PreservesEveryTableAndBackfillsStableIds() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version3 = factory.create(configuration(3, object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "type TEXT NOT NULL, status INTEGER NOT NULL, cost REAL NOT NULL, " +
                        "thc REAL NOT NULL, grams REAL NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE purchase_actions (tempId TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, " +
                        "type TEXT NOT NULL, name TEXT NOT NULL, cost REAL NOT NULL, thc REAL NOT NULL, " +
                        "grams REAL NOT NULL, borrowed INTEGER NOT NULL, postTax INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE consumption_actions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "date TEXT NOT NULL, time TEXT NOT NULL, productId TEXT NOT NULL, uses REAL NOT NULL, " +
                        "isFinished INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE product_interactions (productId TEXT NOT NULL PRIMARY KEY, " +
                        "lastLoggedAtEpochMillis INTEGER NOT NULL, lastQuantity REAL NOT NULL)",
                )
                db.execSQL("INSERT INTO products VALUES ('temp-old', 'Pending product', 'P', 2, 0, 0, 0)")
                db.execSQL(
                    "INSERT INTO purchase_actions VALUES " +
                        "('temp-old', '2026-07-10', 'P', 'Pending product', 10, 0.8, 1, 0, 0)",
                )
                db.execSQL(
                    "INSERT INTO consumption_actions " +
                        "(date, time, productId, uses, isFinished) VALUES " +
                        "('2026-07-10', '22:00', 'temp-old', 1, 0)",
                )
                db.execSQL("INSERT INTO product_interactions VALUES ('temp-old', 123456, 1)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version3.writableDatabase
        version3.close()

        val version4 = factory.create(configuration(4, object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_3_4.migrate(db)
            }
        }))
        val migrated = version4.writableDatabase

        migrated.query("SELECT id, productUuid FROM products WHERE id = 'temp-old'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("temp-old", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.query("SELECT actionId FROM purchase_actions WHERE tempId = 'temp-old'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
        }
        migrated.query("SELECT eventId, productId FROM consumption_actions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
            assertEquals("temp-old", cursor.getString(1))
        }
        migrated.query("SELECT productId FROM product_interactions WHERE productId = 'temp-old'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sync_request_state'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        version4.close()
    }

    @Test
    fun migrationFrom4To5ClearsOnlyTheLegacySyncQueue() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version4 = factory.create(configuration(4, object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE purchase_actions (tempId TEXT NOT NULL PRIMARY KEY)",
                )
                db.execSQL(
                    "CREATE TABLE consumption_actions " +
                        "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE sync_request_state " +
                        "(id INTEGER NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, " +
                        "createdAtEpochMillis INTEGER NOT NULL)",
                )
                db.execSQL("INSERT INTO products VALUES ('p1', 'Keep me')")
                db.execSQL("INSERT INTO purchase_actions VALUES ('pending-purchase')")
                db.execSQL("INSERT INTO consumption_actions DEFAULT VALUES")
                db.execSQL("INSERT INTO sync_request_state VALUES (1, 'request-1', 123456)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version4.writableDatabase
        version4.close()

        val version5 = factory.create(configuration(5, object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_4_5.migrate(db)
            }
        }))
        val migrated = version5.writableDatabase

        migrated.query("SELECT name FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(0))
        }
        listOf("purchase_actions", "consumption_actions", "sync_request_state").forEach { table ->
            migrated.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        version5.close()
    }

    private fun configuration(
        version: Int,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
}
