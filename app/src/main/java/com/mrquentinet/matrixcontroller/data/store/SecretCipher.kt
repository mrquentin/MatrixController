package com.mrquentinet.matrixcontroller.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun seal(plaintext: String): String

    /** null when the sealed blob cannot be opened — e.g. the Keystore key is gone after a restore. */
    fun open(sealed: String): String?
}

/**
 * AES-256/GCM through a non-exportable Android Keystore key. The key is created lazily on the
 * first [seal]; a device-to-device restore copies the sealed blob but not the key, so [open]
 * answers null and the UI simply offers pairing again.
 */
class AndroidKeystoreSecretCipher : SecretCipher {

    override fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val blob = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(blob)
        ciphertext.copyInto(blob, iv.size)
        return Base64.getEncoder().encodeToString(blob)
    }

    override fun open(sealed: String): String? {
        return try {
            val blob = Base64.getDecoder().decode(sealed)
            if (blob.size <= IV_BYTES) return null
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException (tampered blob) and KeyPermanentlyInvalidatedException land here.
            null
        } catch (_: IllegalArgumentException) {
            // Not valid Base64.
            null
        }
    }

    private fun existingKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        keyStore.getKey(ALIAS, null) as? SecretKey
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "matrix_controller_board_secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
