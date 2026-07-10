package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val status: Int,
    val cost: Double = 0.0,
    val thc: Double = 0.0,
    val grams: Double = 0.0
)

@Entity(tableName = "purchase_actions")
data class PurchaseAction(
    @PrimaryKey val tempId: String,
    val date: String,
    val type: String,
    val name: String,
    val cost: Double,
    val thc: Double,
    val grams: Double,
    val borrowed: Int,
    val postTax: Boolean
)

@Entity(tableName = "consumption_actions")
data class ConsumptionAction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,
    val time: String,
    val productId: String,
    val uses: Double,
    val isFinished: Boolean
)

@Dao
interface CannsheetDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)

    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(action: PurchaseAction)

    @Query("SELECT * FROM purchase_actions")
    suspend fun getPendingPurchases(): List<PurchaseAction>

    @Query("DELETE FROM purchase_actions")
    suspend fun clearPendingPurchases()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsumption(action: ConsumptionAction)

    @Query("SELECT * FROM consumption_actions")
    suspend fun getPendingConsumptions(): List<ConsumptionAction>

    @Query("DELETE FROM consumption_actions")
    suspend fun clearPendingConsumptions()

    @Query("SELECT COUNT(*) FROM purchase_actions")
    fun getPendingPurchasesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM consumption_actions")
    fun getPendingConsumptionsCount(): Flow<Int>
}

@Database(entities = [Product::class, PurchaseAction::class, ConsumptionAction::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cannsheetDao(): CannsheetDao
}
