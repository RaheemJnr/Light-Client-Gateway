package com.example.ckbwallet.di

import android.content.Context
import com.example.ckbwallet.data.crypto.Blake2b
import com.example.ckbwallet.data.gateway.GatewayApi
import com.example.ckbwallet.data.gateway.GatewayRepository
import com.example.ckbwallet.data.wallet.KeyManager
import com.example.ckbwallet.data.wallet.WalletPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideGatewayApi(): GatewayApi = GatewayApi()

    @Provides
    @Singleton
    fun provideGatewayRepository(
        api: GatewayApi,
        keyManager: KeyManager,
        walletPreferences: WalletPreferences
    ): GatewayRepository = GatewayRepository(api, keyManager,walletPreferences)
}
