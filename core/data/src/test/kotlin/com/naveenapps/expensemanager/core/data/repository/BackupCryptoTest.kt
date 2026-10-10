package com.naveenapps.expensemanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {

    @Test
    fun `encrypted backup round trips and uses a fresh nonce`() {
        val content = "expense manager database snapshot".toByteArray()
        val first = BackupCrypto.encrypt(content, PASSPHRASE)
        val second = BackupCrypto.encrypt(content, PASSPHRASE)

        assertThat(first).isNotEqualTo(second)
        assertThat(BackupCrypto.decrypt(first, PASSPHRASE)).isEqualTo(content)
    }

    @Test
    fun `decrypt rejects an incorrect passphrase`() {
        val encrypted = BackupCrypto.encrypt("private data".toByteArray(), PASSPHRASE)

        val exception = runCatching { BackupCrypto.decrypt(encrypted, "incorrect passphrase") }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `encrypt rejects a short passphrase`() {
        val exception = runCatching { BackupCrypto.encrypt(byteArrayOf(1), "short") }.exceptionOrNull()

        assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `streaming file encryption round trips`() {
        val directory = RuntimeEnvironment.getApplication().cacheDir
        val source = File(directory, "crypto-source-${System.nanoTime()}.bin").apply {
            writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
        }
        val encrypted = File(directory, "crypto-encrypted-${System.nanoTime()}.bin")
        val restored = File(directory, "crypto-restored-${System.nanoTime()}.bin")
        try {
            BackupCrypto.encryptFile(source, encrypted, PASSPHRASE)
            BackupCrypto.decryptFile(encrypted, restored, PASSPHRASE)

            assertThat(restored.readBytes()).isEqualTo(source.readBytes())
        } finally {
            source.delete()
            encrypted.delete()
            restored.delete()
        }
    }

    private companion object {
        const val PASSPHRASE = "correct-horse-battery-staple"
    }
}