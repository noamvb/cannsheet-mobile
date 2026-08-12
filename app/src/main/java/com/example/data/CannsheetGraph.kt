package com.example.data

import android.content.Context
import androidx.room.Room
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

data class BackgroundSyncEvent(
    val outcome: SyncOutcome.Applied,
)

class CannsheetGraph private constructor(context: Context) {
    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "cannsheet_db",
    ).addMigrations(
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8,
        AppDatabase.MIGRATION_8_9,
        AppDatabase.MIGRATION_9_10,
    ).build()

    val repository = CannsheetRepository(database)
    val consumptionPreferences = ConsumptionPreferencesRepository(context.applicationContext)
    val consumptionLogger = ConsumptionLogger(repository, consumptionPreferences)
    val purchaseDefaultsRepository = PurchaseDefaultsRepository(context.applicationContext)
    val syncPreferences = SyncPreferencesRepository(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    val moshi: Moshi = Moshi.Builder().build()

    val apiService: GasApiService = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GasApiService::class.java)

    val analyticsRepository = AnalyticsRepository(
        api = apiService,
        dao = database.cannsheetDao(),
        moshi = moshi,
        endpoint = BuildConfig.GAS_URL,
        environment = BuildConfig.APP_ENVIRONMENT,
    )

    val syncMutex = Mutex()
    val syncEngine = SyncEngine(
        api = apiService,
        moshi = moshi,
        gateway = repository,
        expectedEnvironment = BuildConfig.APP_ENVIRONMENT,
        mutex = syncMutex,
    )
    val catalogRefresher = ProductCatalogRefresher(
        api = apiService,
        moshi = moshi,
        gateway = repository,
        expectedEnvironment = BuildConfig.APP_ENVIRONMENT,
    )

    private val mutableBackgroundSyncEvents = MutableSharedFlow<BackgroundSyncEvent>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val backgroundSyncEvents: SharedFlow<BackgroundSyncEvent> =
        mutableBackgroundSyncEvents.asSharedFlow()

    suspend fun emitBackgroundSyncEvent(event: BackgroundSyncEvent) {
        mutableBackgroundSyncEvents.emit(event)
    }

    companion object {
        @Volatile
        private var instance: CannsheetGraph? = null

        fun get(context: Context): CannsheetGraph = instance ?: synchronized(this) {
            instance ?: CannsheetGraph(context.applicationContext).also { instance = it }
        }
    }
}
