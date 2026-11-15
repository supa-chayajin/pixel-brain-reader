package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.privatevault.KeystorePasswordKeys
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PasswordKeys
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The private vault (12.18, V3): the password's biometric key lives in the Android Keystore. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PrivateVaultModule {

    @Binds
    abstract fun passwordKeys(keys: KeystorePasswordKeys): PasswordKeys
}
