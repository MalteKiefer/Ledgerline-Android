package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.SodiumCrypto
import de.ledgerline.app.core.integrity.AndroidIntegritySignal
import de.ledgerline.app.core.integrity.IntegritySignal

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds
    abstract fun bindCrypto(impl: SodiumCrypto): Crypto

    // §3.6 client integrity — AOSP baseline (foss); a play flavor may override with a
    // Play-Integrity-augmented binding via a flavor-specific module (§0.1).
    @Binds
    abstract fun bindIntegritySignal(impl: AndroidIntegritySignal): IntegritySignal
}
