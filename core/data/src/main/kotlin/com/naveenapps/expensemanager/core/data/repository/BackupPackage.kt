package com.naveenapps.expensemanager.core.data.repository

import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class BackupPackageSource(
    val archivePath: String,
    val file: File,
)

internal data class BackupPackageManifest(
    val format: String = FORMAT_NAME,
    val formatVersion: Int = FORMAT_VERSION,
    val packageName: String,
    val databaseVersion: Int,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val files: List<BackupPackageFile>,
    val backupId: String,
) {
    companion object {
        const val FORMAT_NAME = "ExpenseManagerBackupV2"
        const val FORMAT_VERSION = 2
    }
}

internal data class BackupPackageFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal object BackupPackage {
    private const val MANIFEST_PATH = "manifest.json"
    private const val MAX_FILE_COUNT = 10_000
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    fun createEncrypted(
        sources: List<BackupPackageSource>,
        outputFile: File,
        passphrase: String,
        packageName: String,
        databaseVersion: Int,
        appVersion: String,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        backupId: String = UUID.randomUUID().toString(),
    ): BackupPackageManifest {
        require(sources.isNotEmpty()) { "Backup package has no files." }
        require(sources.size <= MAX_FILE_COUNT) { "Backup package contains too many files." }
        require(sources.map { it.archivePath }.toSet().size == sources.size) { "Backup package contains duplicate paths." }

        val fileRecords = sources.map { source ->
            validateArchivePath(source.archivePath)
            require(source.file.isFile) { "Backup source file is missing: ${source.archivePath}" }
            val size = source.file.length()
            require(size in 1..MAX_FILE_BYTES) { "Backup source file has an unsupported size: ${source.archivePath}" }
            BackupPackageFile(source.archivePath, size, sha256(source.file))
        }
        val totalSize = fileRecords.sumOf { it.sizeBytes }
        require(totalSize <= MAX_TOTAL_BYTES) { "Backup package exceeds the supported size limit." }

        val manifest = BackupPackageManifest(
            packageName = packageName,
            databaseVersion = databaseVersion,
            createdAtEpochMs = createdAtEpochMs,
            appVersion = appVersion,
            fileCount = fileRecords.size,
            totalSizeBytes = totalSize,
            files = fileRecords,
            backupId = backupId,
        )
        val parent = outputFile.absoluteFile.parentFile ?: throw IllegalArgumentException("Backup output directory is invalid.")
        check(parent.exists() || parent.mkdirs()) { "Could not create the backup output directory." }
        val archiveFile = File(parent, "backup-package-${UUID.randomUUID()}.zip")
        val encryptedTemp = File(parent, "backup-encrypted-${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zip ->
                sources.zip(fileRecords).forEach { (source, record) ->
                    zip.putNextEntry(ZipEntry(record.path))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    FileInputStream(source.file).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= record.sizeBytes) { "Backup source changed while being read: ${record.path}" }
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                        buffer.fill(0)
                    }
                    require(copied == record.sizeBytes && digest.digest().toHex() == record.sha256) {
                        "Backup source changed while being read: ${record.path}"
                    }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(MANIFEST_PATH))
                zip.write(Gson().toJson(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            require(archiveFile.length() <= MAX_TOTAL_BYTES) { "Backup archive exceeds the supported size limit." }
            BackupCrypto.encryptFile(archiveFile, encryptedTemp, passphrase)
            check(encryptedTemp.renameTo(outputFile)) { "Could not publish the encrypted backup package." }
            return manifest
        } finally {
            archiveFile.delete()
            encryptedTemp.delete()
        }
    }

    fun decryptAndValidate(
        encryptedFile: File,
        passphrase: String,
        stagingDirectory: File,
        expectedPackageName: String,
        supportedDatabaseVersion: IntRange,
    ): BackupPackageManifest {
        require(encryptedFile.isFile && encryptedFile.length() <= MAX_TOTAL_BYTES) {
            "Backup file is missing or exceeds the supported size limit."
        }
        check(!stagingDirectory.exists() || stagingDirectory.deleteRecursively()) {
            "Could not prepare the restore staging directory."
        }
        check(stagingDirectory.mkdirs()) { "Could not create the restore staging directory." }

        val archiveFile = File(stagingDirectory.parentFile, "restore-${UUID.randomUUID()}.zip")
        val payloadDirectory = File(stagingDirectory, "payload").apply { mkdirs() }
        try {
            BackupCrypto.decryptFile(encryptedFile, archiveFile, passphrase, MAX_TOTAL_BYTES)
            val observedFiles = mutableMapOf<String, BackupPackageFile>()
            var manifest: BackupPackageManifest? = null
            var totalBytes = 0L
            ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Backup archive contains an unexpected directory entry." }
                    validateArchivePath(entry.name, allowManifest = true)
                    require(entry.name !in observedFiles && (entry.name != MANIFEST_PATH || manifest == null)) {
                        "Backup archive contains duplicate entries."
                    }
                    if (entry.name == MANIFEST_PATH) {
                        val manifestBytes = zip.readLimited(MAX_FILE_BYTES)
                        manifest = Gson().fromJson(String(manifestBytes, Charsets.UTF_8), BackupPackageManifest::class.java)
                        manifestBytes.fill(0)
                    } else {
                        val target = File(payloadDirectory, entry.name).canonicalFile
                        require(target.toPath().startsWith(payloadDirectory.canonicalFile.toPath())) {
                            "Backup archive contains an unsafe file path."
                        }
                        check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true) {
                            "Could not create a restore staging directory."
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var fileBytes = 0L
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                totalBytes += count
                                require(fileBytes <= MAX_FILE_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                                    "Backup archive expands beyond the supported size limit."
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            buffer.fill(0)
                        }
                        observedFiles[entry.name] = BackupPackageFile(entry.name, fileBytes, digest.digest().toHex())
                    }
                    zip.closeEntry()
                }
            }

            val parsedManifest = manifest ?: throw IllegalArgumentException("Backup manifest is missing.")
            require(parsedManifest.format == BackupPackageManifest.FORMAT_NAME &&
                parsedManifest.formatVersion == BackupPackageManifest.FORMAT_VERSION
            ) { "Backup package format is not supported." }
            require(parsedManifest.packageName == expectedPackageName) { "Backup belongs to a different application." }
            require(parsedManifest.databaseVersion in supportedDatabaseVersion) {
                "Backup database version ${parsedManifest.databaseVersion} is not supported."
            }
            require(parsedManifest.fileCount == parsedManifest.files.size && parsedManifest.files.size <= MAX_FILE_COUNT) {
                "Backup manifest file count is invalid."
            }
            require(parsedManifest.totalSizeBytes == totalBytes && parsedManifest.totalSizeBytes <= MAX_TOTAL_BYTES) {
                "Backup manifest size is invalid."
            }
            require(parsedManifest.files.toSet() == observedFiles.values.toSet()) {
                "Backup contents do not match the integrity manifest."
            }
            return parsedManifest
        } catch (exception: Exception) {
            stagingDirectory.deleteRecursively()
            throw exception
        } finally {
            archiveFile.delete()
        }
    }

    private fun validateArchivePath(path: String, allowManifest: Boolean = false) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path) {
            "Backup archive contains an unsafe file path."
        }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Backup archive contains an unsafe file path."
        }
        val allowed = path.startsWith("database/") || path.startsWith("settings/") || path.startsWith("attachments/")
        require(allowed || allowManifest && path == MANIFEST_PATH) { "Backup archive contains an unknown file path." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return digest.digest().toHex()
    }

    private fun java.util.zip.ZipInputStream.readLimited(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup manifest exceeds the supported size limit." }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
