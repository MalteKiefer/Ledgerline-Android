package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.SodiumCrypto

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds
    abstract fun bindCrypto(impl: SodiumCrypto): Crypto
}
