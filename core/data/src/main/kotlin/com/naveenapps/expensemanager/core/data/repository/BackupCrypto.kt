package com.naveenapps.expensemanager.core.data.repository

import java.nio.ByteBuffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object BackupCrypto {
    private val magic = byteArrayOf(0x45, 0x4d, 0x42, 0x4b)
    private const val FORMAT_VERSION: Byte = 1
    private const val KDF_SHA256: Byte = 1
    private const val KDF_SHA1: Byte = 2
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE_BITS = 256
    private const val ITERATIONS = 600_000
    private const val TAG_SIZE_BITS = 128
    private const val HEADER_SIZE = 4 + 1 + 1 + SALT_SIZE + NONCE_SIZE
    private const val MIN_PASSPHRASE_LENGTH = 12

    fun encrypt(plainText: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters."
        }
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val (kdfId, algorithm) = keyDerivationAlgorithm()
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(magic)
            .put(FORMAT_VERSION)
            .put(kdfId)
            .put(salt)
            .put(nonce)
            .array()
        val key = deriveKey(passphrase, salt, algorithm)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plainText)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(encrypted: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters."
        }
        require(encrypted.size >= HEADER_SIZE + TAG_SIZE_BITS / 8) { "Backup file is incomplete." }
        val input = ByteBuffer.wrap(encrypted)
        val fileMagic = ByteArray(magic.size).also(input::get)
        require(fileMagic.contentEquals(magic)) { "Backup file format is not recognized." }
        require(input.get() == FORMAT_VERSION) { "Backup file format is not supported." }
        val kdfId = input.get()
        val algorithm = when (kdfId) {
            KDF_SHA256 -> "PBKDF2WithHmacSHA256"
            KDF_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw IllegalArgumentException("Backup key derivation format is not supported.")
        }
        val salt = ByteArray(SALT_SIZE).also(input::get)
        val nonce = ByteArray(NONCE_SIZE).also(input::get)
        val header = encrypted.copyOfRange(0, HEADER_SIZE)
        val ciphertext = encrypted.copyOfRange(HEADER_SIZE, encrypted.size)
        val key = deriveKey(passphrase, salt, algorithm)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (exception: AEADBadTagException) {
            throw IllegalArgumentException("Backup passphrase is incorrect or the file was altered.", exception)
        } finally {
            key.fill(0)
        }
    }

    fun encryptFile(inputFile: File, outputFile: File, passphrase: String) {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters."
        }
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val (kdfId, algorithm) = keyDerivationAlgorithm()
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(magic)
            .put(FORMAT_VERSION)
            .put(kdfId)
            .put(salt)
            .put(nonce)
            .array()
        val key = deriveKey(passphrase, salt, algorithm)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(header)
            FileOutputStream(outputFile).use { fileOutput ->
                fileOutput.write(header)
                javax.crypto.CipherOutputStream(fileOutput, cipher).use { encryptedOutput ->
                    FileInputStream(inputFile).use { it.copyTo(encryptedOutput) }
                }
            }
        } finally {
            key.fill(0)
        }
    }

    fun decryptFile(
        inputFile: File,
        outputFile: File,
        passphrase: String,
        maxOutputBytes: Long = Long.MAX_VALUE,
    ) {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters."
        }
        require(inputFile.length() >= HEADER_SIZE + TAG_SIZE_BITS / 8) { "Backup file is incomplete." }
        val header = ByteArray(HEADER_SIZE)
        FileInputStream(inputFile).use { input ->
            require(input.read(header) == HEADER_SIZE) { "Backup file is incomplete." }
        }
        val headerBuffer = ByteBuffer.wrap(header)
        val fileMagic = ByteArray(magic.size).also(headerBuffer::get)
        require(fileMagic.contentEquals(magic)) { "Backup file format is not recognized." }
        require(headerBuffer.get() == FORMAT_VERSION) { "Backup file format is not supported." }
        val algorithm = when (headerBuffer.get()) {
            KDF_SHA256 -> "PBKDF2WithHmacSHA256"
            KDF_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw IllegalArgumentException("Backup key derivation format is not supported.")
        }
        val salt = ByteArray(SALT_SIZE).also(headerBuffer::get)
        val nonce = ByteArray(NONCE_SIZE).also(headerBuffer::get)
        val key = deriveKey(passphrase, salt, algorithm)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            cipher.updateAAD(header)
            FileInputStream(inputFile).use { input ->
                input.skip(HEADER_SIZE.toLong())
                javax.crypto.CipherInputStream(input, cipher).use { decryptedInput ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val count = decryptedInput.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= maxOutputBytes) { "Backup archive exceeds the supported size limit." }
                            output.write(buffer, 0, count)
                        }
                        buffer.fill(0)
                    }
                }
            }
        } catch (exception: Exception) {
            outputFile.delete()
            throw if (exception is IllegalArgumentException) exception else {
                IllegalArgumentException("Backup passphrase is incorrect or the file was altered.", exception)
            }
        } finally {
            key.fill(0)
        }
    }

    private fun keyDerivationAlgorithm(): Pair<Byte, String> = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        KDF_SHA256 to "PBKDF2WithHmacSHA256"
    } catch (_: Exception) {
        KDF_SHA1 to "PBKDF2WithHmacSHA1"
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, algorithm: String): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}