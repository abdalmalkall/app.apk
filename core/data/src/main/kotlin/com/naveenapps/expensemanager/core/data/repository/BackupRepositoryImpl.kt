package com.naveenapps.expensemanager.core.data.repository

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.gson.Gson
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.BackupRepository
import com.naveenapps.expensemanager.core.repository.DriveBackupItem
import com.naveenapps.expensemanager.core.repository.DriveApiException
import de.raphaelebner.roomdatabasebackup.core.RoomBackup
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current != null) {
        when (current) {
            is Activity -> return current
            is ContextWrapper -> current = current.baseContext
            else -> return null
        }
    }
    return null
}

class BackupRepositoryImpl(
    private val context: Context,
    private val roomBackup: RoomBackup,
    private val database: ExpenseManagerDatabase,
) : BackupRepository {

    private val gson = Gson()
    private val operationMutex = Mutex()

    override suspend fun backupData(uri: String?, passphrase: String, accessToken: String): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return runLocalBackupFallback(passphrase)
        }

        return try {
            withContext(Dispatchers.IO) {
                operationMutex.withLock { uploadDatabaseToDrive(accessToken, passphrase) }
            }
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Drive backup failed", exception)
            Resource.Error(exception)
        }
    }

    override suspend fun restoreData(
        uri: String?,
        passphrase: String,
        accessToken: String,
        backupFileId: String?,
    ): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return runLocalRestoreFallback(passphrase)
        }

        return try {
            withContext(Dispatchers.IO) {
                operationMutex.withLock { restoreDatabaseFromDrive(accessToken, passphrase, backupFileId) }
            }
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Drive restore failed", exception)
            Resource.Error(exception)
        }
    }

    override suspend fun listDriveBackups(accessToken: String): Resource<List<DriveBackupItem>> =
        withContext(Dispatchers.IO) {
            try {
                val visibleFiles = listVisibleBackupFiles(accessToken)
                val appDataFiles = listDriveFiles(accessToken)
                val backups = visibleFiles
                    .filter { it.name.endsWith(BACKUP_PACKAGE_EXTENSION) }
                    .map { file ->
                        DriveBackupItem(
                            id = file.id,
                            name = file.name,
                            modifiedAtEpochMs = runCatching { Instant.parse(file.modifiedTime).toEpochMilli() }.getOrDefault(0L),
                            sizeBytes = file.size?.toLongOrNull() ?: 0L,
                        )
                    }
                    .plus(
                        (appDataFiles + visibleFiles)
                            .filter { isBackupMetadataFile(it.name) }
                            .mapNotNull { metadataFile ->
                                val metadata = runCatching {
                                    DriveBackupMetadata.fromJson(downloadDriveFileText(accessToken, metadataFile.id))
                                }.getOrNull() ?: return@mapNotNull null
                                if (metadata.appId != context.packageName || metadata.backupVersion != BACKUP_FORMAT_VERSION) {
                                    return@mapNotNull null
                                }
                                val databaseName = metadata.databaseFileName.ifBlank { DATABASE_BACKUP_FILE_NAME }
                                val databaseFile = (appDataFiles + visibleFiles).firstOrNull { it.name == databaseName }
                                    ?: return@mapNotNull null
                                DriveBackupItem(
                                    id = metadataFile.id,
                                    name = metadata.backupId.ifBlank { databaseName },
                                    modifiedAtEpochMs = metadata.createdAtEpochMs,
                                    sizeBytes = databaseFile.size?.toLongOrNull() ?: 0L,
                                    legacy = true,
                                )
                            },
                    )
                    .distinctBy { it.id }
                    .sortedByDescending { it.modifiedAtEpochMs }
                Resource.Success(backups)
            } catch (exception: Exception) {
                Log.e(TAG, "Drive backup listing failed", exception)
                Resource.Error(exception)
            }
        }

    private suspend fun runLocalBackupFallback(passphrase: String): Resource<Boolean> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                try {
                    roomBackup
                        .database(database)
                        .enableLogDebug(false)
                        .backupIsEncrypted(true)
                        .customEncryptPassword(passphrase)
                        .backupLocation(RoomBackup.BACKUP_FILE_LOCATION_CUSTOM_DIALOG)
                        .maxFileCount(5)
                        .onCompleteListener { success, message, exitCode ->
                            if (continuation.isActive) {
                                continuation.resume(
                                    if (success) Resource.Success(true) else {
                                        Resource.Error(IllegalStateException("$message ($exitCode)"))
                                    },
                                )
                            }
                        }
                        .backup()
                } catch (exception: Exception) {
                    if (continuation.isActive) continuation.resume(Resource.Error(exception))
                }
            }
        }

    private suspend fun runLocalRestoreFallback(passphrase: String): Resource<Boolean> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                try {
                    roomBackup
                        .database(database)
                        .enableLogDebug(false)
                        .backupIsEncrypted(true)
                        .customEncryptPassword(passphrase)
                        .backupLocation(RoomBackup.BACKUP_FILE_LOCATION_CUSTOM_DIALOG)
                        .onCompleteListener { success, message, exitCode ->
                            if (continuation.isActive) {
                                continuation.resume(
                                    if (success) Resource.Success(true) else {
                                        Resource.Error(IllegalStateException("$message ($exitCode)"))
                                    },
                                )
                            }
                        }
                        .restore()
                } catch (exception: Exception) {
                    if (continuation.isActive) continuation.resume(Resource.Error(exception))
                }
            }
        }

    private fun uploadDatabaseToDrive(accessToken: String, passphrase: String) {
        val workDirectory = File(context.cacheDir, "backup-build-${UUID.randomUUID()}").apply { mkdirs() }
        val databaseSnapshot = File(workDirectory, "expense_manager_database.db")
        val encryptedPackage = File(workDirectory, "expense_manager_backup.embk")
        try {
            val liveDatabase = databaseFile()
            val checkpoint = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
            checkpoint.use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    "The database is busy; wait for active changes to finish and try again."
                }
            }
            database.close()
            try {
                check(liveDatabase.isFile) { "The local database file was not found." }
                liveDatabase.copyTo(databaseSnapshot, overwrite = true)
            } finally {
                database.openHelper.writableDatabase
            }

            val sources = mutableListOf(
                BackupPackageSource("database/expense_manager_database.db", databaseSnapshot),
            )
            addDataStoreSource(sources, "expense_manager_app_data_store")
            addDataStoreSource(sources, "expense_manager_settings")
            listOf("category_images", "account_images", "transaction_attachments").forEach { folderName ->
                val directory = File(context.filesDir, folderName)
                addDirectorySources(sources, directory, "attachments/$folderName")
            }
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            val manifest = BackupPackage.createEncrypted(
                sources = sources,
                outputFile = encryptedPackage,
                passphrase = passphrase,
                packageName = context.packageName,
                databaseVersion = DATABASE_SCHEMA_VERSION,
                appVersion = versionName,
            )
            val folderId = getOrCreateBackupFolder(accessToken)
            val backupName = "ExpenseManager_${manifest.createdAtEpochMs}_${manifest.backupId}.embk"
            uploadFileToDrive(accessToken, folderId, backupName, encryptedPackage)
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun addDataStoreSource(sources: MutableList<BackupPackageSource>, dataStoreName: String) {
        val dataStoreFile = File(context.filesDir, "datastore/$dataStoreName.preferences_pb")
        if (dataStoreFile.isFile) {
            sources += BackupPackageSource("settings/${dataStoreFile.name}", dataStoreFile)
        }
    }

    private fun addDirectorySources(sources: MutableList<BackupPackageSource>, directory: File, archiveRoot: String) {
        if (!directory.exists()) return
        require(directory.isDirectory) { "A local attachment path is not a directory: ${directory.name}" }
        val canonicalRoot = directory.canonicalFile.toPath()
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.canonicalFile.toPath().startsWith(canonicalRoot)) {
                "A local attachment points outside its managed directory."
            }
            val relativePath = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            sources += BackupPackageSource("$archiveRoot/$relativePath", file)
        }
    }

    private fun restoreDatabaseFromDrive(accessToken: String, passphrase: String, backupFileId: String?) {
        val visibleFiles = listVisibleBackupFiles(accessToken)
        val packageBackups = visibleFiles
            .filter { it.name.endsWith(BACKUP_PACKAGE_EXTENSION) }
        val packageBackup = if (backupFileId == null) {
            packageBackups.maxByOrNull { runCatching { Instant.parse(it.modifiedTime).toEpochMilli() }.getOrDefault(0L) }
        } else {
            packageBackups.firstOrNull { it.id == backupFileId }
        }
        if (packageBackup != null) {
            restoreBackupPackage(accessToken, packageBackup, passphrase)
            return
        }

        val metadata = if (backupFileId == null) {
            readLatestBackupMetadata(accessToken)
        } else {
            val metadataFile = (listDriveFiles(accessToken) + visibleFiles)
                .firstOrNull { it.id == backupFileId && isBackupMetadataFile(it.name) }
                ?: throw IllegalArgumentException("The selected Drive backup is no longer available.")
            runCatching { DriveBackupMetadata.fromJson(downloadDriveFileText(accessToken, metadataFile.id)) }
                .getOrNull()
        }
            ?: throw IllegalStateException("Google Drive backup metadata was not found.")

        if (metadata.backupVersion != BACKUP_FORMAT_VERSION || metadata.appId != context.packageName) {
            throw IllegalStateException("This backup was created by an incompatible application version.")
        }
        if (metadata.schemaVersion != DATABASE_SCHEMA_VERSION) {
            throw IllegalStateException(
                "Backup schema mismatch. Expected $DATABASE_SCHEMA_VERSION but found ${metadata.schemaVersion}.",
            )
        }

        val databaseName = metadata.databaseFileName.ifBlank { DATABASE_BACKUP_FILE_NAME }
        val databaseFiles = listDriveFiles(accessToken) + listVisibleBackupFiles(accessToken)
        val databaseFile = databaseFiles.firstOrNull { it.name == databaseName }
            ?: throw IllegalStateException("Google Drive backup database was not found.")

        val restoreFile = File(context.cacheDir, "drive-restore-${UUID.randomUUID()}.db")
        val downloadedBytes = downloadDriveFileBytes(accessToken, databaseFile.id)
        val databaseBytes = try {
            if (metadata.encrypted) BackupCrypto.decrypt(downloadedBytes, passphrase) else downloadedBytes
        } finally {
            downloadedBytes.fill(0)
        }
        try {
            restoreFile.writeBytes(databaseBytes)
            validateDatabaseFile(restoreFile)
            replaceDatabaseWithRollback(restoreFile)
        } finally {
            databaseBytes.fill(0)
            restoreFile.delete()
        }
    }

    private fun restoreBackupPackage(accessToken: String, driveFile: DriveFileRecord, passphrase: String) {
        val workDirectory = File(context.cacheDir, "backup-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val encryptedFile = File(workDirectory, "download.embk")
        val stagingDirectory = File(workDirectory, "staging")
        try {
            downloadDriveFileTo(accessToken, driveFile.id, encryptedFile)
            val manifest = BackupPackage.decryptAndValidate(
                encryptedFile = encryptedFile,
                passphrase = passphrase,
                stagingDirectory = stagingDirectory,
                expectedPackageName = context.packageName,
                supportedDatabaseVersion = MINIMUM_SUPPORTED_SCHEMA_VERSION..DATABASE_SCHEMA_VERSION,
            )
            check(manifest.files.any { it.path == "database/$DATABASE_NAME" }) {
                "Backup package does not contain the Expense Manager database."
            }
            val payload = File(stagingDirectory, "payload")
            val stagedDatabase = File(payload, "database/$DATABASE_NAME")
            remapBackupAttachmentPaths(stagedDatabase, payload)
            validateDatabaseFile(stagedDatabase)
            applyBackupPackage(payload, manifest)
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun remapBackupAttachmentPaths(stagedDatabase: File, payload: File) {
        val managedFolders = setOf("category_images", "account_images", "transaction_attachments")
        val sqlite = SQLiteDatabase.openDatabase(stagedDatabase.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val pathColumns = listOf(
                "transaction_attachment" to "image_path",
                "account" to "custom_image_path",
                "category" to "custom_image_path",
            )
            pathColumns.forEach { (table, column) ->
                sqlite.rawQuery("SELECT $column FROM $table WHERE $column IS NOT NULL", null).use { cursor ->
                    val paths = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                    paths.forEach { originalPath ->
                        val originalFile = File(originalPath)
                        val folderName = originalFile.parentFile?.name
                        if (folderName !in managedFolders) return@forEach
                        val stagedFile = File(payload, "attachments/$folderName/${originalFile.name}")
                        check(stagedFile.isFile) { "A referenced backup attachment is missing." }
                        val targetPath = File(context.filesDir, "$folderName/${originalFile.name}").absolutePath
                        sqlite.execSQL(
                            "UPDATE $table SET $column = ? WHERE $column = ?",
                            arrayOf(targetPath, originalPath),
                        )
                    }
                }
            }
        } finally {
            sqlite.close()
        }
    }

    private fun applyBackupPackage(payload: File, manifest: BackupPackageManifest) {
        val rollbackDirectory = File(context.cacheDir, "backup-rollback-${UUID.randomUUID()}").apply { mkdirs() }
        val liveDatabase = databaseFile()
        val databaseRollback = File(rollbackDirectory, DATABASE_NAME)
        val managedDirectories = listOf("category_images", "account_images", "transaction_attachments")
        val dataStoreNames = listOf("expense_manager_app_data_store", "expense_manager_settings")
        val filesToRestore = dataStoreNames.map { name ->
            val dataStoreName = "$name.preferences_pb"
            File(context.filesDir, "datastore/$dataStoreName") to File(payload, "settings/$dataStoreName")
        }
        val directoryRollbacks = managedDirectories.associateWith { name ->
            File(rollbackDirectory, name).also { backup ->
                val live = File(context.filesDir, name)
                if (live.exists()) copyDirectory(live, backup)
            }
        }
        val dataStoreRollbacks = filesToRestore.associate { (live, _) ->
            live to File(rollbackDirectory, "datastore/${live.name}").also { backup ->
                if (live.isFile) live.copyTo(backup, overwrite = true)
            }
        }
        val databasePresent = liveDatabase.isFile
        val dataStorePresent = dataStoreRollbacks.mapValues { (live, _) -> live.isFile }
        var databaseClosed = false
        try {
            val checkpoint = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
            checkpoint.use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "The database is busy." } }
            database.close()
            databaseClosed = true
            check(databasePresent) { "The current database file was not found for the safety backup." }
            liveDatabase.copyTo(databaseRollback, overwrite = true)
            File(liveDatabase.path + "-wal").delete()
            File(liveDatabase.path + "-shm").delete()

            managedDirectories.forEach { folderName ->
                val live = File(context.filesDir, folderName)
                live.deleteRecursively()
                val staged = File(payload, "attachments/$folderName")
                if (staged.exists()) copyDirectory(staged, live)
            }
            filesToRestore.forEach { (live, staged) ->
                if (staged.isFile) {
                    check(live.parentFile?.exists() == true || live.parentFile?.mkdirs() == true) {
                        "Could not prepare the settings restore directory."
                    }
                    staged.copyTo(live, overwrite = true)
                } else {
                    live.delete()
                }
            }

            val stagedDatabase = File(payload, "database/$DATABASE_NAME")
            stagedDatabase.copyTo(liveDatabase, overwrite = true)
            database.openHelper.writableDatabase
            databaseClosed = false
            database.invalidationTracker.refreshVersionsAsync()
        } catch (exception: Exception) {
            managedDirectories.forEach { folderName ->
                val live = File(context.filesDir, folderName)
                live.deleteRecursively()
                val rollback = directoryRollbacks.getValue(folderName)
                if (rollback.exists()) copyDirectory(rollback, live)
            }
            dataStoreRollbacks.forEach { (live, rollback) ->
                if (dataStorePresent.getValue(live)) rollback.copyTo(live, overwrite = true) else live.delete()
            }
            if (databaseRollback.isFile) {
                File(liveDatabase.path + "-wal").delete()
                File(liveDatabase.path + "-shm").delete()
                databaseRollback.copyTo(liveDatabase, overwrite = true)
            }
            if (databaseClosed) database.openHelper.writableDatabase
            throw exception
        } finally {
            rollbackDirectory.deleteRecursively()
        }
    }

    private fun copyDirectory(source: File, destination: File) {
        check(source.isDirectory) { "Backup attachment path is not a directory." }
        source.walkTopDown().filter { it.isDirectory }.forEach { directory ->
            val relative = source.toPath().relativize(directory.toPath())
            File(destination, relative.toString()).mkdirs()
        }
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = source.toPath().relativize(file.toPath())
            val target = File(destination, relative.toString())
            check(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath())) {
                "Backup attachment path is unsafe."
            }
            check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true) {
                "Could not create an attachment restore directory."
            }
            file.copyTo(target, overwrite = true)
        }
    }

    private fun readLatestBackupMetadata(accessToken: String): DriveBackupMetadata? {
        var latest: DriveBackupMetadata? = null
        val files = listDriveFiles(accessToken) + listVisibleBackupFiles(accessToken)
        for (entry in files) {
            if (!isBackupMetadataFile(entry.name)) continue
            val json = downloadDriveFileText(accessToken, entry.id)
            val metadata = runCatching { DriveBackupMetadata.fromJson(json) }.getOrNull() ?: continue
            if (latest == null || metadata.createdAtEpochMs > latest.createdAtEpochMs) latest = metadata
        }
        return latest
    }

    private fun isBackupMetadataFile(fileName: String): Boolean =
        fileName == METADATA_FILE_NAME || fileName.startsWith(METADATA_FILE_PREFIX) && fileName.endsWith(".json")

    private fun validateDatabaseFile(file: File) {
        check(file.isFile && file.length() > 0L) { "The backup file is empty or incomplete." }
        val restoredDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            check(restoredDatabase.version in MINIMUM_SUPPORTED_SCHEMA_VERSION..DATABASE_SCHEMA_VERSION) {
                "Backup database version ${restoredDatabase.version} is not supported by this application."
            }
            restoredDatabase.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { cursor ->
                check(cursor.moveToFirst()) { "The selected file is not a valid Expense Manager database." }
            }
            restoredDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                check(!cursor.moveToFirst()) { "The backup database contains broken relationships." }
            }
        } finally {
            restoredDatabase.close()
        }
    }

    private fun replaceDatabaseWithRollback(restoredFile: File) {
        val liveFile = databaseFile()
        val rollbackFile = File(context.cacheDir, "pre-restore-${UUID.randomUUID()}.db")
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        database.close()
        try {
            check(liveFile.isFile) { "The current database file was not found for the safety backup." }
            liveFile.copyTo(rollbackFile, overwrite = true)
            val stagedFile = File(liveFile.parentFile, "${liveFile.name}.restore")
            restoredFile.copyTo(stagedFile, overwrite = true)
            File(liveFile.path + "-wal").delete()
            File(liveFile.path + "-shm").delete()
            check(stagedFile.renameTo(liveFile)) { "Could not install the restored database." }
            database.openHelper.writableDatabase
            database.invalidationTracker.refreshVersionsAsync()
        } catch (exception: Exception) {
            if (rollbackFile.isFile) {
                File(liveFile.path + "-wal").delete()
                File(liveFile.path + "-shm").delete()
                rollbackFile.copyTo(liveFile, overwrite = true)
                database.openHelper.writableDatabase
            }
            throw exception
        } finally {
            rollbackFile.delete()
        }
    }

    private fun uploadFileToDrive(accessToken: String, parentFolderId: String, fileName: String, bytes: ByteArray) {
        val boundary = "Boundary-${UUID.randomUUID()}"
        val metadataJson = gson.toJson(mapOf("name" to fileName, "parents" to listOf(parentFolderId)))
        val output = java.io.ByteArrayOutputStream()
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(metadataJson.toByteArray(Charsets.UTF_8))
        output.write("\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val connection = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(output.toByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "upload"))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadFileToDrive(accessToken: String, parentFolderId: String, fileName: String, file: File) {
        val metadata = gson.toJson(
            mapOf(
                "name" to fileName,
                "mimeType" to "application/octet-stream",
                "parents" to listOf(parentFolderId),
            ),
        )
        val initiate = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
            doOutput = true
        }
        val uploadUrl = try {
            initiate.outputStream.use { it.write(metadata.toByteArray(Charsets.UTF_8)) }
            val responseCode = initiate.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "start upload"))
            }
            initiate.getHeaderField("Location")
                ?: throw IllegalStateException("Google Drive did not return a resumable upload URL.")
        } finally {
            initiate.disconnect()
        }

        val upload = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(file.length())
            doOutput = true
        }
        try {
            upload.outputStream.use { output -> FileInputStream(file).use { it.copyTo(output) } }
            val responseCode = upload.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "upload"))
            }
            val response = upload.inputStream.bufferedReader().use { it.readText() }
            check(gson.fromJson(response, DriveFileRecord::class.java).id.isNotBlank()) {
                "Google Drive did not confirm the uploaded backup file."
            }
        } finally {
            upload.disconnect()
        }
    }

    private fun listDriveFiles(accessToken: String): List<DriveFileRecord> =
        listDriveFiles(accessToken, "spaces=appDataFolder")

    private fun listVisibleBackupFiles(accessToken: String): List<DriveFileRecord> {
        val folderId = findBackupFolder(accessToken) ?: return emptyList()
        val query = "spaces=drive&q=${encodeQuery("'$folderId' in parents and trashed=false")}"
        return listDriveFiles(accessToken, query)
    }

    private fun listDriveFiles(accessToken: String, query: String): List<DriveFileRecord> {
        val files = mutableListOf<DriveFileRecord>()
        var pageToken: String? = null
        do {
            val pageQuery = if (pageToken == null) query else "$query&pageToken=${encodeQuery(pageToken)}"
            val url = "https://www.googleapis.com/drive/v3/files?$pageQuery&fields=nextPageToken,files(id,name,mimeType,modifiedTime,size)&pageSize=1000"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            val response = try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw DriveApiException(responseCode, driveFailureMessage(responseCode, "list backups"))
                }
                val responseJson = connection.inputStream.bufferedReader().use { it.readText() }
                gson.fromJson(responseJson, DriveListResponse::class.java)
            } finally {
                connection.disconnect()
            }
            files += response.files.orEmpty()
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return files
    }

    private fun findBackupFolder(accessToken: String): String? {
        val query = "spaces=drive&q=${encodeQuery("name='$BACKUP_FOLDER_NAME' and mimeType='$DRIVE_FOLDER_MIME_TYPE' and trashed=false")}"
        return listDriveFiles(accessToken, query).firstOrNull()?.id
    }

    private fun getOrCreateBackupFolder(accessToken: String): String {
        findBackupFolder(accessToken)?.let { return it }
        val connection = (URL("https://www.googleapis.com/drive/v3/files?fields=id,name,mimeType").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            doOutput = true
        }
        val body = gson.toJson(mapOf("name" to BACKUP_FOLDER_NAME, "mimeType" to DRIVE_FOLDER_MIME_TYPE))
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "create backup folder"))
            }
            val responseJson = connection.inputStream.bufferedReader().use { it.readText() }
            return gson.fromJson(responseJson, DriveFileRecord::class.java).id
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDriveFileBytes(accessToken: String, fileId: String): ByteArray {
        val connection = (URL("https://www.googleapis.com/drive/v3/files/${encodeQuery(fileId)}?alt=media").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "download backup"))
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDriveFileTo(accessToken: String, fileId: String, destination: File) {
        val connection = (URL("https://www.googleapis.com/drive/v3/files/${encodeQuery(fileId)}?alt=media").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw DriveApiException(responseCode, driveFailureMessage(responseCode, "download backup"))
            }
            require(connection.contentLengthLong <= MAX_ENCRYPTED_BACKUP_BYTES || connection.contentLengthLong < 0L) {
                "Backup file exceeds the supported size limit."
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ENCRYPTED_BACKUP_BYTES) { "Backup file exceeds the supported size limit." }
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDriveFileText(accessToken: String, fileId: String): String =
        downloadDriveFileBytes(accessToken, fileId).toString(Charsets.UTF_8)

    private fun encodeQuery(query: String): String = URLEncoder.encode(query, Charsets.UTF_8.name())

    private fun driveFailureMessage(statusCode: Int, operation: String): String = when (statusCode) {
        401 -> "Google Drive authorization expired. Reconnect and try again."
        403 -> "Google Drive denied this request. Check the granted permissions."
        429 -> "Google Drive is receiving too many requests. Wait and try again."
        in 500..599 -> "Google Drive is temporarily unavailable. Try again later."
        else -> "Google Drive could not $operation (HTTP $statusCode)."
    }

    private data class DriveFileRecord(
        val id: String,
        val name: String,
        val mimeType: String? = null,
        val modifiedTime: String? = null,
        val size: String? = null,
    )

    private data class DriveListResponse(
        val files: List<DriveFileRecord>? = null,
        val nextPageToken: String? = null,
    )

    private fun databaseFile(): File = File(context.getDatabasePath(DATABASE_NAME).absolutePath)

    companion object {
        private const val TAG = "Backup"
        private const val BACKUP_FOLDER_NAME = "ExpenseManager Backups"
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val BACKUP_PACKAGE_EXTENSION = ".embk"
        private const val MAX_ENCRYPTED_BACKUP_BYTES = 512L * 1024 * 1024
        private const val DATABASE_NAME = "expense_manager_database.db"
        private const val DATABASE_SCHEMA_VERSION = 10
        private const val DATABASE_BACKUP_FILE_NAME = "expense_manager_database_backup.db"
        private const val MINIMUM_SUPPORTED_SCHEMA_VERSION = 2
        private const val METADATA_FILE_NAME = "expense_manager_backup_metadata.json"
        private const val METADATA_FILE_PREFIX = "expense_manager_backup_metadata_"
        private const val BACKUP_FORMAT_VERSION = 1
    }
}