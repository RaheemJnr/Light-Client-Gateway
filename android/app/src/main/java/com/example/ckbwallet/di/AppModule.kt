package com.example.ckbwallet.di

import android.content.Context
import com.example.ckbwallet.data.crypto.Blake2b
import com.example.ckbwallet.data.gateway.GatewayRepository
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.WalletPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
        @ApplicationContext context: Context
    ): KeyManager = KeyManager(context)

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideGatewayRepository(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
        walletPreferences: WalletPreferences,
        json: Json
    ): GatewayRepository = GatewayRepository(context, keyManager, walletPreferences, json)
}
