package com.naveenapps.expensemanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupPackageTest {

    @Test
    fun `encrypted package validates manifest and restores file tree`() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "backup-package-${System.nanoTime()}")
        val sourceDatabase = File(root, "source.db")
        val sourceAttachment = File(root, "receipt.jpg")
        val output = File(root, "backup.embk")
        val restoreStage = File(root, "restore-stage")
        try {
            root.mkdirs()
            sourceDatabase.writeBytes("database-snapshot".toByteArray())
            sourceAttachment.writeBytes(ByteArray(4096) { (it % 127).toByte() })

            val manifest = BackupPackage.createEncrypted(
                sources = listOf(
                    BackupPackageSource("database/expense_manager_database.db", sourceDatabase),
                    BackupPackageSource("attachments/transaction_attachments/receipt.jpg", sourceAttachment),
                ),
                outputFile = output,
                passphrase = PASSPHRASE,
                packageName = PACKAGE_NAME,
                databaseVersion = 10,
                appVersion = "0.4",
            )
            val verifiedManifest = BackupPackage.decryptAndValidate(
                encryptedFile = output,
                passphrase = PASSPHRASE,
                stagingDirectory = restoreStage,
                expectedPackageName = PACKAGE_NAME,
                supportedDatabaseVersion = 2..10,
            )

            assertThat(verifiedManifest).isEqualTo(manifest)
            assertThat(File(restoreStage, "payload/database/expense_manager_database.db").readBytes())
                .isEqualTo(sourceDatabase.readBytes())
            assertThat(File(restoreStage, "payload/attachments/transaction_attachments/receipt.jpg").readBytes())
                .isEqualTo(sourceAttachment.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `decrypt rejects an incorrect passphrase and removes partial staging`() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "backup-package-${System.nanoTime()}")
        val source = File(root, "settings.pb")
        val output = File(root, "backup.embk")
        val restoreStage = File(root, "restore-stage")
        try {
            root.mkdirs()
            source.writeBytes("settings".toByteArray())
            BackupPackage.createEncrypted(
                sources = listOf(BackupPackageSource("settings/app.preferences_pb", source)),
                outputFile = output,
                passphrase = PASSPHRASE,
                packageName = PACKAGE_NAME,
                databaseVersion = 10,
                appVersion = "0.4",
            )

            val failure = runCatching {
                BackupPackage.decryptAndValidate(
                    encryptedFile = output,
                    passphrase = "wrong-passphrase",
                    stagingDirectory = restoreStage,
                    expectedPackageName = PACKAGE_NAME,
                    supportedDatabaseVersion = 2..10,
                )
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(restoreStage.exists()).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects incompatible database schema`() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "backup-package-${System.nanoTime()}")
        val source = File(root, "snapshot.db")
        val output = File(root, "backup.embk")
        try {
            root.mkdirs()
            source.writeBytes("database-snapshot".toByteArray())
            BackupPackage.createEncrypted(
                sources = listOf(BackupPackageSource("database/snapshot.db", source)),
                outputFile = output,
                passphrase = PASSPHRASE,
                packageName = PACKAGE_NAME,
                databaseVersion = 11,
                appVersion = "0.5",
            )

            val failure = runCatching {
                BackupPackage.decryptAndValidate(
                    encryptedFile = output,
                    passphrase = PASSPHRASE,
                    stagingDirectory = File(root, "restore-stage"),
                    expectedPackageName = PACKAGE_NAME,
                    supportedDatabaseVersion = 2..10,
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `package creation rejects traversal paths`() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "backup-package-${System.nanoTime()}")
        val source = File(root, "payload.bin")
        val output = File(root, "backup.embk")
        try {
            root.mkdirs()
            source.writeBytes(byteArrayOf(1, 2, 3))

            val failure = runCatching {
                BackupPackage.createEncrypted(
                    sources = listOf(BackupPackageSource("attachments/../../outside.bin", source)),
                    outputFile = output,
                    passphrase = PASSPHRASE,
                    packageName = PACKAGE_NAME,
                    databaseVersion = 10,
                    appVersion = "0.4",
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(output.exists()).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects a modified encrypted package`() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "backup-package-${System.nanoTime()}")
        val source = File(root, "snapshot.db")
        val output = File(root, "backup.embk")
        val restoreStage = File(root, "restore-stage")
        try {
            root.mkdirs()
            source.writeBytes("database-snapshot".toByteArray())
            BackupPackage.createEncrypted(
                sources = listOf(BackupPackageSource("database/snapshot.db", source)),
                outputFile = output,
                passphrase = PASSPHRASE,
                packageName = PACKAGE_NAME,
                databaseVersion = 10,
                appVersion = "0.4",
            )
            val corrupted = output.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            output.writeBytes(corrupted)

            val failure = runCatching {
                BackupPackage.decryptAndValidate(
                    encryptedFile = output,
                    passphrase = PASSPHRASE,
                    stagingDirectory = restoreStage,
                    expectedPackageName = PACKAGE_NAME,
                    supportedDatabaseVersion = 2..10,
                )
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(restoreStage.exists()).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PASSPHRASE = "correct-horse-battery-staple"
        const val PACKAGE_NAME = "com.naveenapps.expensemanager"
    }
}