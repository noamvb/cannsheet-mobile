package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val status: Int,
    val cost: Double = 0.0,
    val thc: Double = 0.0,
    val grams: Double = 0.0,
    val productUuid: String? = null,
)

@Entity(
    tableName = "purchase_actions",
    indices = [Index(value = ["actionId"], unique = true)],
)
data class PurchaseAction(
    @PrimaryKey val tempId: String,
    val actionId: String,
    val date: String,
    val type: String,
    val name: String,
    val cost: Double,
    val thc: Double,
    val grams: Double,
    val borrowed: Int,
    val postTax: Boolean,
    val productUuid: String? = null,
)

@Entity(
    tableName = "consumption_actions",
    indices = [Index(value = ["eventId"], unique = true)],
)
data class ConsumptionAction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val eventId: String,
    val date: String,
    val time: String,
    val productId: String,
    val uses: Double,
    val isFinished: Boolean,
    val productUuid: String? = null,
)

@Entity(tableName = "finish_actions")
data class FinishAction(
    @PrimaryKey val actionId: String,
    val date: String,
    val time: String,
    val productId: String,
    val productUuid: String? = null,
)

@Entity(tableName = "product_interactions")
data class ProductInteraction(
    @PrimaryKey val productId: String,
    val lastLoggedAtEpochMillis: Long,
    val lastQuantity: Double,
)

@Entity(tableName = "sync_request_state")
data class SyncRequestState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val requestId: String,
    val createdAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "analytics_cache",
    primaryKeys = ["environment", "resource"],
)
data class AnalyticsCacheEntity(
    val environment: String,
    val resource: String,
    val analyticsVersion: Int,
    val requestJson: String,
    val payloadJson: String,
    val sourceDataVersion: String,
    val generatedAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
)

@Dao
interface CannsheetDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)

    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProduct(productId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(action: PurchaseAction)

    @Query("SELECT * FROM purchase_actions")
    suspend fun getPendingPurchases(): List<PurchaseAction>

    @Query("DELETE FROM purchase_actions WHERE actionId IN (:actionIds)")
    suspend fun deletePurchasesByActionIds(actionIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsumption(action: ConsumptionAction)

    @Query("SELECT * FROM consumption_actions")
    suspend fun getPendingConsumptions(): List<ConsumptionAction>

    @Query("DELETE FROM consumption_actions WHERE eventId IN (:eventIds)")
    suspend fun deleteConsumptionsByEventIds(eventIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinishAction(action: FinishAction)

    @Query("SELECT * FROM finish_actions")
    suspend fun getPendingFinishActions(): List<FinishAction>

    @Query("DELETE FROM finish_actions WHERE actionId IN (:actionIds)")
    suspend fun deleteFinishActionsByActionIds(actionIds: List<String>)

    @Query(
        "UPDATE consumption_actions SET productId = :newProductId, productUuid = :productUuid " +
            "WHERE productId = :oldProductId",
    )
    suspend fun remapPendingConsumptions(
        oldProductId: String,
        newProductId: String,
        productUuid: String?,
    )

    @Query(
        "UPDATE finish_actions SET productId = :newProductId, productUuid = :productUuid " +
            "WHERE productId = :oldProductId",
    )
    suspend fun remapPendingFinishActions(
        oldProductId: String,
        newProductId: String,
        productUuid: String?,
    )

    @Query("SELECT COUNT(*) FROM purchase_actions")
    fun getPendingPurchasesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM consumption_actions")
    fun getPendingConsumptionsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM finish_actions")
    fun getPendingFinishActionsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM purchase_actions")
    suspend fun getPendingPurchasesCountNow(): Int

    @Query("SELECT COUNT(*) FROM consumption_actions")
    suspend fun getPendingConsumptionsCountNow(): Int

    @Query("SELECT COUNT(*) FROM finish_actions")
    suspend fun getPendingFinishActionsCountNow(): Int

    @Query("SELECT * FROM product_interactions ORDER BY lastLoggedAtEpochMillis DESC")
    fun getAllProductInteractions(): Flow<List<ProductInteraction>>

    @Query("SELECT * FROM product_interactions WHERE productId = :productId LIMIT 1")
    suspend fun getProductInteraction(productId: String): ProductInteraction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductInteraction(interaction: ProductInteraction)

    @Query("DELETE FROM product_interactions WHERE productId = :productId")
    suspend fun deleteProductInteraction(productId: String)

    @Query("UPDATE products SET status = 0 WHERE id = :productId AND status = 2")
    suspend fun activateProductIfUnopened(productId: String)

    @Query("UPDATE products SET status = 1 WHERE id = :productId")
    suspend fun markProductFinished(productId: String)

    @Query("SELECT * FROM sync_request_state WHERE id = 1 LIMIT 1")
    suspend fun getSyncRequestState(): SyncRequestState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncRequestState(state: SyncRequestState)

    @Query("DELETE FROM sync_request_state")
    suspend fun clearSyncRequestState()

    @Query(
        "SELECT * FROM analytics_cache " +
            "WHERE environment = :environment AND resource = :resource LIMIT 1",
    )
    suspend fun getAnalyticsCache(environment: String, resource: String): AnalyticsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalyticsCache(cache: AnalyticsCacheEntity)

    @Query(
        "DELETE FROM analytics_cache " +
            "WHERE environment = :environment AND resource = :resource",
    )
    suspend fun deleteAnalyticsCache(environment: String, resource: String)

    @Transaction
    suspend fun replaceProductsAndMergeInteractions(
        products: List<Product>,
        remoteInteractions: List<ProductInteraction>,
    ) {
        val pendingPurchases = getPendingPurchases()
        val pendingFinishActions = getPendingFinishActions()
        deleteAllProducts()
        insertProducts(products)
        if (pendingPurchases.isNotEmpty()) {
            insertProducts(
                pendingPurchases.map { action ->
                    Product(
                        id = action.tempId,
                        name = action.name,
                        type = action.type,
                        status = ProductStatus.UNOPENED.code,
                        productUuid = action.productUuid,
                    )
                },
            )
        }

        pendingFinishActions.forEach { action ->
            markProductFinished(action.productId)
        }

        remoteInteractions.forEach { remote ->
            val local = getProductInteraction(remote.productId)
            if (local == null || remote.lastLoggedAtEpochMillis > local.lastLoggedAtEpochMillis) {
                upsertProductInteraction(remote)
            }
        }
    }

    @Transaction
    suspend fun recordConsumption(
        action: ConsumptionAction,
        interaction: ProductInteraction,
    ) {
        insertConsumption(action)

        val existing = getProductInteraction(interaction.productId)
        if (existing == null || interaction.lastLoggedAtEpochMillis >= existing.lastLoggedAtEpochMillis) {
            upsertProductInteraction(interaction)
        }

        if (action.isFinished) {
            markProductFinished(action.productId)
        } else {
            activateProductIfUnopened(action.productId)
        }
    }

    @Transaction
    suspend fun recordFinishAction(action: FinishAction) {
        insertFinishAction(action)
        markProductFinished(action.productId)
    }

    @Transaction
    suspend fun remapProductInteraction(oldProductId: String, newProductId: String) {
        if (oldProductId == newProductId) return

        val source = getProductInteraction(oldProductId) ?: return
        val destination = getProductInteraction(newProductId)
        if (destination == null || source.lastLoggedAtEpochMillis > destination.lastLoggedAtEpochMillis) {
            upsertProductInteraction(source.copy(productId = newProductId))
        }
        deleteProductInteraction(oldProductId)
    }
}

@Database(
    entities = [
        Product::class,
        PurchaseAction::class,
        ConsumptionAction::class,
        FinishAction::class,
        ProductInteraction::class,
        SyncRequestState::class,
        AnalyticsCacheEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cannsheetDao(): CannsheetDao

    companion object {
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `product_interactions` (
                        `productId` TEXT NOT NULL,
                        `lastLoggedAtEpochMillis` INTEGER NOT NULL,
                        `lastQuantity` REAL NOT NULL,
                        PRIMARY KEY(`productId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `productUuid` TEXT")
                db.execSQL("ALTER TABLE `purchase_actions` ADD COLUMN `actionId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `purchase_actions` ADD COLUMN `productUuid` TEXT")
                db.execSQL("ALTER TABLE `consumption_actions` ADD COLUMN `eventId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `consumption_actions` ADD COLUMN `productUuid` TEXT")

                db.query("SELECT `tempId` FROM `purchase_actions`").use { cursor ->
                    val tempIdIndex = cursor.getColumnIndexOrThrow("tempId")
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE `purchase_actions` SET `actionId` = ? WHERE `tempId` = ?",
                            arrayOf(UUID.randomUUID().toString(), cursor.getString(tempIdIndex)),
                        )
                    }
                }
                db.query("SELECT `id` FROM `consumption_actions`").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE `consumption_actions` SET `eventId` = ? WHERE `id` = ?",
                            arrayOf(UUID.randomUUID().toString(), cursor.getInt(idIndex)),
                        )
                    }
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_request_state` (
                        `id` INTEGER NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_purchase_actions_actionId` " +
                        "ON `purchase_actions` (`actionId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_consumption_actions_eventId` " +
                        "ON `consumption_actions` (`eventId`)",
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 1.2.2 could time out after the server had already accepted an action,
                // leaving that accepted action in the local queue. Clear only the local sync
                // queue during this one-time upgrade so those actions cannot be submitted again.
                db.execSQL("DELETE FROM `purchase_actions`")
                db.execSQL("DELETE FROM `consumption_actions`")
                db.execSQL("DELETE FROM `sync_request_state`")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analytics_cache` (
                        `environment` TEXT NOT NULL,
                        `resource` TEXT NOT NULL,
                        `analyticsVersion` INTEGER NOT NULL,
                        `requestJson` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `sourceDataVersion` TEXT NOT NULL,
                        `generatedAtEpochMillis` INTEGER NOT NULL,
                        `cachedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`environment`, `resource`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `finish_actions` (
                        `actionId` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `time` TEXT NOT NULL,
                        `productId` TEXT NOT NULL,
                        `productUuid` TEXT,
                        PRIMARY KEY(`actionId`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
