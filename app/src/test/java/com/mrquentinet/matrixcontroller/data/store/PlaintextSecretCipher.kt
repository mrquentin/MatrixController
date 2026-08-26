package com.mrquentinet.matrixcontroller.data.store

/**
 * Test double for [SecretCipher]: keeps sealing observable without an Android Keystore, which is
 * only available on device (see `SecretCipherInstrumentedTest`).
 */
internal class PlaintextSecretCipher : SecretCipher {
    override fun seal(plaintext: String): String = "sealed:$plaintext"
    override fun open(sealed: String): String? = sealed.removePrefix("sealed:")
}
