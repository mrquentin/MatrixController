package com.mrquentinet.matrixcontroller.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a real Android Keystore, so it runs on device. */
@RunWith(AndroidJUnit4::class)
class SecretCipherInstrumentedTest {

    private val cipher = AndroidKeystoreSecretCipher()
    private val secretHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    @Test
    fun sealAndOpenRoundTripsTheSecret() {
        assertEquals(secretHex, cipher.open(cipher.seal(secretHex)))
    }

    @Test
    fun sealingTwiceProducesDifferentCiphertexts() {
        assertNotEquals(cipher.seal(secretHex), cipher.seal(secretHex))
    }

    @Test
    fun openReturnsNullOnCorruptedInput() {
        val sealed = cipher.seal(secretHex)
        // Flip a character in the middle of the Base64 blob.
        val index = sealed.length / 2
        val flipped = if (sealed[index] == 'A') 'B' else 'A'
        val corrupted = sealed.substring(0, index) + flipped + sealed.substring(index + 1)

        assertNull(cipher.open(corrupted))
    }

    @Test
    fun openReturnsNullOnGarbage() {
        assertNull(cipher.open("not base64 at all!!"))
        assertNull(cipher.open(""))
    }
}
