package com.rjnr.pocketnode.di

import android.content.Context
import androidx.room.Room
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.DaoSyncManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBlake2b(): Blake2b = Blake2b()

    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context,
        mnemonicManager: MnemonicManager
    ): KeyManager = KeyManager(context, mnemonicManager)

    @Provides
    @Singleton
    fun provideMnemonicManager(): MnemonicManager = MnemonicManager()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 10_000  // 10 seconds
            socketTimeout = 10_000   // 10 seconds
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Provides
    @Singleton
    fun provideAuthManager(
        @ApplicationContext context: Context
    ): AuthManager = AuthManager(context)

    @Provides
    @Singleton
    fun providePinManager(
        @ApplicationContext context: Context,
        blake2b: Blake2b
    ): PinManager = PinManager(context, blake2b)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pocket_node.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideBalanceCacheDao(db: AppDatabase): BalanceCacheDao = db.balanceCacheDao()

    @Provides
    fun provideHeaderCacheDao(db: AppDatabase): HeaderCacheDao = db.headerCacheDao()

    @Provides
    fun provideDaoCellDao(db: AppDatabase): DaoCellDao = db.daoCellDao()

    @Provides
    @Singleton
    fun provideCacheManager(
        transactionDao: TransactionDao,
        balanceCacheDao: BalanceCacheDao
    ): CacheManager = CacheManager(transactionDao, balanceCacheDao)

    @Provides
    @Singleton
    fun provideDaoSyncManager(
        headerCacheDao: HeaderCacheDao,
        daoCellDao: DaoCellDao
    ): DaoSyncManager = DaoSyncManager(headerCacheDao, daoCellDao)

    @Provides
    @Singleton
    fun provideGatewayRepository(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
        walletPreferences: WalletPreferences,
        json: Json,
        cacheManager: CacheManager,
        daoSyncManager: DaoSyncManager
    ): GatewayRepository = GatewayRepository(context, keyManager, walletPreferences, json, cacheManager, daoSyncManager)
}
