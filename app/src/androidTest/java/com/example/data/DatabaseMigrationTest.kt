package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun migrationFrom5To6PreservesExistingDataAndAddsAnalyticsCache() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version5 = factory.create(configuration(5, object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE purchase_actions (tempId TEXT NOT NULL PRIMARY KEY, actionId TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE consumption_actions " +
                        "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE product_interactions " +
                        "(productId TEXT NOT NULL PRIMARY KEY, lastLoggedAtEpochMillis INTEGER NOT NULL, " +
                        "lastQuantity REAL NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE sync_request_state " +
                        "(id INTEGER NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, " +
                        "createdAtEpochMillis INTEGER NOT NULL)",
                )
                db.execSQL("INSERT INTO products VALUES ('p1', 'Keep me')")
                db.execSQL("INSERT INTO purchase_actions VALUES ('temp-1', 'action-1')")
                db.execSQL("INSERT INTO consumption_actions (eventId) VALUES ('event-1')")
                db.execSQL("INSERT INTO product_interactions VALUES ('p1', 123456, 1)")
                db.execSQL("INSERT INTO sync_request_state VALUES (1, 'request-1', 123456)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version5.writableDatabase
        version5.close()

        val version6 = factory.create(configuration(6, object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_5_6.migrate(db)
            }
        }))
        val migrated = version6.writableDatabase

        listOf(
            "products",
            "purchase_actions",
            "consumption_actions",
            "product_interactions",
            "sync_request_state",
        ).forEach { table ->
            migrated.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'analytics_cache'",
        ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        version6.close()
    }

    @Test
    fun migrationFrom6To7PreservesExistingDataAndAddsFinishQueue() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version6 = factory.create(configuration(6, object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL("INSERT INTO products VALUES ('p1', 'Keep me')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version6.writableDatabase
        version6.close()

        val version7 = factory.create(configuration(7, object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_6_7.migrate(db)
            }
        }))
        val migrated = version7.writableDatabase

        migrated.query("SELECT name FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(0))
        }
        migrated.execSQL(
            "INSERT INTO finish_actions " +
                "(actionId, date, time, productId, productUuid) VALUES " +
                "('finish-1', '2026-07-22', '12:34', 'p1', NULL)",
        )
        migrated.query("SELECT productId FROM finish_actions WHERE actionId = 'finish-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
        }
        version7.close()
    }

    @Test
    fun migrationFrom7To8PreservesPurchasesAndAllowsUnknownBorrowedNumbers() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version7 = factory.create(configuration(7, object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE purchase_actions (
                        tempId TEXT NOT NULL PRIMARY KEY,
                        actionId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        cost REAL NOT NULL,
                        thc REAL NOT NULL,
                        grams REAL NOT NULL,
                        borrowed INTEGER NOT NULL,
                        postTax INTEGER NOT NULL,
                        productUuid TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_purchase_actions_actionId " +
                        "ON purchase_actions (actionId)",
                )
                db.execSQL(
                    """
                    INSERT INTO purchase_actions (
                        tempId,
                        actionId,
                        date,
                        type,
                        name,
                        cost,
                        thc,
                        grams,
                        borrowed,
                        postTax,
                        productUuid
                    ) VALUES (
                        'temp-existing',
                        'action-existing',
                        '2026-07-22',
                        'F',
                        'Existing product',
                        12.5,
                        0.21,
                        3.5,
                        0,
                        1,
                        'existing-uuid'
                    )
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version7.writableDatabase
        version7.close()

        val version8 = factory.create(configuration(8, object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_7_8.migrate(db)
            }
        }))
        val migrated = version8.writableDatabase

        migrated.query(
            "SELECT actionId, cost, thc, grams, productUuid " +
                "FROM purchase_actions WHERE tempId = 'temp-existing'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("action-existing", cursor.getString(0))
            assertEquals(12.5, cursor.getDouble(1), 0.0)
            assertEquals(0.21, cursor.getDouble(2), 0.0)
            assertEquals(3.5, cursor.getDouble(3), 0.0)
            assertEquals("existing-uuid", cursor.getString(4))
        }

        migrated.execSQL(
            """
            INSERT INTO purchase_actions (
                tempId,
                actionId,
                date,
                type,
                name,
                cost,
                thc,
                grams,
                borrowed,
                postTax,
                productUuid
            ) VALUES (
                'temp-borrowed',
                'action-borrowed',
                '2026-07-22',
                'F',
                'Borrowed product',
                NULL,
                NULL,
                NULL,
                1,
                0,
                NULL
            )
            """.trimIndent(),
        )
        migrated.query(
            "SELECT cost, thc, grams FROM purchase_actions WHERE tempId = 'temp-borrowed'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.query("PRAGMA index_list('purchase_actions')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundUniqueActionIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_purchase_actions_actionId") {
                    foundUniqueActionIndex = cursor.getInt(uniqueIndex) == 1
                }
            }
            assertTrue(foundUniqueActionIndex)
        }
        version8.close()
    }

    @Test
    fun migrationFrom8To9PreservesDataAndAddsDurableCorrectionQueue() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version8 = factory.create(configuration(8, object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL(
                    """
                    CREATE TABLE sync_request_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        requestId TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO products VALUES ('p1', 'Keep me')")
                db.execSQL(
                    "INSERT INTO sync_request_state VALUES " +
                        "(1, '50000000-0000-4000-8000-000000000001', 123456)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version8.writableDatabase
        version8.close()

        val version9 = factory.create(configuration(9, object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_8_9.migrate(db)
            }
        }))
        val migrated = version9.writableDatabase

        migrated.query("SELECT name FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(0))
        }
        migrated.query(
            "SELECT requestId, payloadFingerprint FROM sync_request_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("50000000-0000-4000-8000-000000000001", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        migrated.execSQL(
            """
            INSERT INTO pending_consumption_corrections (
                targetEventId,
                actionId,
                expectedCorrectionHeadId,
                operation,
                reopenProduct,
                reason,
                replacementDate,
                replacementTime,
                replacementProductUuid,
                replacementProductId,
                replacementUses,
                replacementWeightCode,
                replacementFinished
            ) VALUES (
                '10000000-0000-4000-8000-000000000001',
                '20000000-0000-4000-8000-000000000001',
                '',
                'REPLACE',
                0,
                'Correct quantity',
                '2026-07-28',
                '20:15:00',
                '40000000-0000-4000-8000-000000000001',
                '*P1',
                2.0,
                '',
                0
            )
            """.trimIndent(),
        )
        migrated.query(
            "SELECT actionId, operation, replacementWeightCode " +
                "FROM pending_consumption_corrections " +
                "WHERE targetEventId = '10000000-0000-4000-8000-000000000001'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20000000-0000-4000-8000-000000000001", cursor.getString(0))
            assertEquals("REPLACE", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        migrated.query("PRAGMA index_list('pending_consumption_corrections')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundUniqueActionIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_pending_consumption_corrections_actionId") {
                    foundUniqueActionIndex = cursor.getInt(uniqueIndex) == 1
                }
            }
            assertTrue(foundUniqueActionIndex)
        }
        version9.close()
    }

    @Test
    fun migrationFrom9To10PreservesProductsAndQueuedConsumptions() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version9 = factory.create(configuration(9, object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "type TEXT NOT NULL, status INTEGER NOT NULL, cost REAL NOT NULL, " +
                        "thc REAL NOT NULL, grams REAL NOT NULL, productUuid TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE consumption_actions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "eventId TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, " +
                        "productId TEXT NOT NULL, uses REAL NOT NULL, isFinished INTEGER NOT NULL, " +
                        "productUuid TEXT)",
                )
                db.execSQL(
                    "INSERT INTO products VALUES ('p1', 'Keep me', 'F', 0, 10.0, 0.2, 3.5, 'uuid-p1')",
                )
                db.execSQL(
                    "INSERT INTO consumption_actions " +
                        "(eventId, date, time, productId, uses, isFinished, productUuid) VALUES " +
                        "('event-1', '2026-08-09', '12:00', 'p1', 0.5, 0, 'uuid-p1')",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }))
        version9.writableDatabase
        version9.close()

        val version10 = factory.create(configuration(10, object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_9_10.migrate(db)
            }
        }))
        val migrated = version10.writableDatabase

        migrated.query("SELECT name, totalUses FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(0))
            assertNull(cursor.getString(1))
        }
        migrated.query("PRAGMA table_info('products')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var foundTotalUses = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "totalUses") foundTotalUses = true
            }
            assertTrue(foundTotalUses)
        }
        migrated.query("SELECT uses FROM consumption_actions WHERE eventId = 'event-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0.5, cursor.getDouble(0), 0.0)
        }
        version10.close()
    }

    @Test
    fun migrationFrom10To11ProducesASchemaRoomAccepts() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version10 = factory.create(
            configuration(10, object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    this@DatabaseMigrationTest.createVersion10Schema(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }),
        )
        version10.writableDatabase
        version10.close()

        val sampleEntry = ConsumptionHistoryEntry(
            eventId = "history-event-1",
            date = "2026-08-20",
            time = "10:00",
            productId = "p1",
            productUuid = "uuid-p1",
            uses = 0.5,
            isFinished = false,
            loggedAtEpochMillis = 123456L,
        )
        val database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName,
        ).addMigrations(AppDatabase.MIGRATION_10_11).build()

        try {
            runBlocking {
                database.cannsheetDao().insertConsumptionHistory(sampleEntry)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom10To11PreservesProductsAndQueuedActions() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val version10 = factory.create(
            configuration(10, object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    this@DatabaseMigrationTest.createVersion10Schema(db)
                    db.execSQL(
                        "INSERT INTO products VALUES " +
                            "('p1', 'Keep me', 'F', 0, 10.0, 0.2, 3.5, 'uuid-p1', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO purchase_actions VALUES " +
                            "('temp-1', 'purchase-1', '2026-08-20', 'F', 'Keep me', " +
                            "12.5, 0.2, 3.5, 0, 0, 'uuid-p1')",
                    )
                    db.execSQL(
                        "INSERT INTO consumption_actions " +
                            "(eventId, date, time, productId, uses, isFinished, productUuid) VALUES " +
                            "('event-1', '2026-08-20', '12:00', 'p1', 0.5, 0, 'uuid-p1')",
                    )
                    db.execSQL(
                        "INSERT INTO finish_actions VALUES " +
                            "('finish-1', '2026-08-20', '13:00', 'p1', 'uuid-p1')",
                    )
                    db.execSQL(
                        """
                        INSERT INTO pending_consumption_corrections (
                            targetEventId,
                            actionId,
                            expectedCorrectionHeadId,
                            operation,
                            reopenProduct,
                            reason,
                            replacementDate,
                            replacementTime,
                            replacementProductUuid,
                            replacementProductId,
                            replacementUses,
                            replacementWeightCode,
                            replacementFinished
                        ) VALUES (
                            'target-1',
                            'correction-1',
                            '',
                            'REPLACE',
                            0,
                            'Migration test',
                            '2026-08-20',
                            '12:30',
                            'uuid-p1',
                            'p1',
                            0.5,
                            '',
                            0
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }),
        )
        version10.writableDatabase
        version10.close()

        val version11 = factory.create(
            configuration(11, object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    AppDatabase.MIGRATION_10_11.migrate(db)
                }
            }),
        )
        val migrated = version11.writableDatabase

        migrated.query("SELECT name, totalUses FROM products WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(0))
            assertNull(cursor.getString(1))
        }
        migrated.query(
            "SELECT actionId, name FROM purchase_actions WHERE tempId = 'temp-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("purchase-1", cursor.getString(0))
            assertEquals("Keep me", cursor.getString(1))
        }
        migrated.query(
            "SELECT eventId, uses FROM consumption_actions WHERE eventId = 'event-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("event-1", cursor.getString(0))
            assertEquals(0.5, cursor.getDouble(1), 0.0)
        }
        migrated.query(
            "SELECT actionId, productId FROM finish_actions WHERE actionId = 'finish-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("finish-1", cursor.getString(0))
            assertEquals("p1", cursor.getString(1))
        }
        migrated.query(
            "SELECT actionId, operation " +
                "FROM pending_consumption_corrections " +
                "WHERE targetEventId = 'target-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("correction-1", cursor.getString(0))
            assertEquals("REPLACE", cursor.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM consumption_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        version11.close()
    }

    private fun createVersion10Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE products (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                status INTEGER NOT NULL,
                cost REAL NOT NULL,
                thc REAL NOT NULL,
                grams REAL NOT NULL,
                productUuid TEXT,
                totalUses REAL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE purchase_actions (
                tempId TEXT NOT NULL,
                actionId TEXT NOT NULL,
                date TEXT NOT NULL,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                cost REAL,
                thc REAL,
                grams REAL,
                borrowed INTEGER NOT NULL,
                postTax INTEGER NOT NULL,
                productUuid TEXT,
                PRIMARY KEY(tempId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE consumption_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                eventId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                productId TEXT NOT NULL,
                uses REAL NOT NULL,
                isFinished INTEGER NOT NULL,
                productUuid TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE finish_actions (
                actionId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                productId TEXT NOT NULL,
                productUuid TEXT,
                PRIMARY KEY(actionId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending_consumption_corrections (
                targetEventId TEXT NOT NULL,
                actionId TEXT NOT NULL,
                expectedCorrectionHeadId TEXT NOT NULL,
                operation TEXT NOT NULL,
                reopenProduct INTEGER NOT NULL,
                reason TEXT,
                replacementDate TEXT,
                replacementTime TEXT,
                replacementProductUuid TEXT,
                replacementProductId TEXT,
                replacementUses REAL,
                replacementWeightCode TEXT,
                replacementFinished INTEGER,
                PRIMARY KEY(targetEventId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE product_interactions (
                productId TEXT NOT NULL,
                lastLoggedAtEpochMillis INTEGER NOT NULL,
                lastQuantity REAL NOT NULL,
                PRIMARY KEY(productId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_request_state (
                id INTEGER NOT NULL,
                requestId TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                payloadFingerprint TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE analytics_cache (
                environment TEXT NOT NULL,
                resource TEXT NOT NULL,
                analyticsVersion INTEGER NOT NULL,
                requestJson TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                sourceDataVersion TEXT NOT NULL,
                generatedAtEpochMillis INTEGER NOT NULL,
                cachedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(environment, resource)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_purchase_actions_actionId " +
                "ON purchase_actions (actionId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_consumption_actions_eventId " +
                "ON consumption_actions (eventId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_pending_consumption_corrections_actionId " +
                "ON pending_consumption_corrections (actionId)",
        )
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
