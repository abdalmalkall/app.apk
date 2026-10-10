package com.naveenapps.expensemanager.core.data.repository

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.tasks.Tasks
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.BackupRepository
import de.raphaelebner.roomdatabasebackup.core.RoomBackup
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    private val googleSignInClient: GoogleSignInClient? by lazy {
        val activity = findActivity(context) ?: return@lazy null
        GoogleSignIn.getClient(
            activity,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(Drive.SCOPE_APPFOLDER)
                .build(),
        )
    }

    override suspend fun backupData(uri: String?): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return withContext(Dispatchers.Main.immediate) { runLocalBackupFallback() }
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !isGoogleDriveSignedIn()) {
            return Resource.Error(IllegalStateException("Google Drive sign-in is required before backing up data."))
        }

        return try {
            withContext(Dispatchers.IO) { uploadDatabaseToDrive(account) }
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Drive backup failed", exception)
            Resource.Error(exception)
        }
    }

    override suspend fun restoreData(uri: String?): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return withContext(Dispatchers.Main.immediate) { runLocalRestoreFallback() }
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !isGoogleDriveSignedIn()) {
            return Resource.Error(IllegalStateException("Google Drive sign-in is required before restoring data."))
        }

        return try {
            withContext(Dispatchers.IO) { restoreDatabaseFromDrive(account) }
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Drive restore failed", exception)
            Resource.Error(exception)
        }
    }

    override fun isGoogleDriveSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account?.grantedScopes?.any { it.scopeUri == Drive.SCOPE_APPFOLDER.scopeUri } == true
    }

    override fun requestGoogleDriveSignIn(): Intent? = googleSignInClient?.signInIntent

    override fun handleGoogleDriveSignInResult(data: Intent?): Resource<Boolean> {
        if (data == null) return Resource.Error(IllegalStateException("Google Drive sign-in result was empty."))
        return try {
            val account = Tasks.await(GoogleSignIn.getSignedInAccountFromIntent(data))
            val valid = account.grantedScopes.any { it.scopeUri == Drive.SCOPE_APPFOLDER.scopeUri }
            if (!valid) {
                throw IllegalStateException("Google Drive App Data Folder access was not granted.")
            }
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Google Drive sign-in completion failed", exception)
            Resource.Error(exception)
        }
    }

    private fun runLocalBackupFallback(): Resource<Boolean> {
        roomBackup
            .database(database)
            .enableLogDebug(true)
            .backupIsEncrypted(true)
            .customEncryptPassword("YOUR_SECRET_PASSWORD")
            .backupLocation(RoomBackup.BACKUP_FILE_LOCATION_CUSTOM_DIALOG)
            .maxFileCount(5)
            .apply {
                onCompleteListener { success, message, exitCode ->
                    Log.d(TAG, "$message $exitCode")
                }
            }
            .backup()
        return Resource.Success(true)
    }

    private fun runLocalRestoreFallback(): Resource<Boolean> {
        roomBackup
            .database(database)
            .enableLogDebug(true)
            .backupIsEncrypted(true)
            .customEncryptPassword("YOUR_SECRET_PASSWORD")
            .backupLocation(RoomBackup.BACKUP_FILE_LOCATION_CUSTOM_DIALOG)
            .apply {
                onCompleteListener { success, message, exitCode ->
                    Log.d(TAG, "$message $exitCode")
                }
            }
            .restore()
        return Resource.Success(true)
    }

    private fun uploadDatabaseToDrive(account: GoogleSignInAccount) {
        val resourceClient = Drive.getDriveResourceClient(context, account)
        val appFolder = Tasks.await(resourceClient.appFolder)
        val dbFile = databaseFile()
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        check(dbFile.isFile) { "The local database file was not found." }
        val backupId = UUID.randomUUID().toString()
        val databaseFileName = "expense_manager_database_$backupId.db"
        val metadata = DriveBackupMetadata(
            schemaVersion = 10,
            createdAtEpochMs = System.currentTimeMillis(),
            appId = context.packageName,
            backupId = backupId,
            databaseFileName = databaseFileName,
        )

        val metadataFileBytes = metadata.toJson().toByteArray(Charsets.UTF_8)
        // Publish metadata last; restore treats it as the commit record for this backup.
        uploadFileToDrive(resourceClient, appFolder, databaseFileName, dbFile.readBytes())
        uploadFileToDrive(resourceClient, appFolder, "$METADATA_FILE_PREFIX$backupId.json", metadataFileBytes)
    }

    private fun restoreDatabaseFromDrive(account: GoogleSignInAccount) {
        val resourceClient = Drive.getDriveResourceClient(context, account)
        val appFolder = Tasks.await(resourceClient.appFolder)
        val metadataBuffer = Tasks.await(resourceClient.listChildren(appFolder))
        try {
            val metadata = readLatestBackupMetadata(resourceClient, metadataBuffer)
                ?: throw IllegalStateException("Google Drive backup metadata was not found.")
            val parsed = metadata
            if (parsed.backupVersion != BACKUP_FORMAT_VERSION || parsed.appId != context.packageName) {
                throw IllegalStateException("This backup was created by an incompatible application version.")
            }
            if (parsed.schemaVersion != DATABASE_SCHEMA_VERSION) {
                throw IllegalStateException(
                    "Backup schema mismatch. Expected $DATABASE_SCHEMA_VERSION but found ${parsed.schemaVersion}.",
                )
            }

            val databaseName = parsed.databaseFileName.ifBlank { DATABASE_BACKUP_FILE_NAME }
            val databaseMetadata = findMetadataByTitle(metadataBuffer, databaseName)
                ?: throw IllegalStateException("Google Drive backup database was not found.")

            val databaseFile = databaseMetadata.driveId.asDriveFile()
            val contents = Tasks.await(resourceClient.openFile(databaseFile, DriveFile.MODE_READ_ONLY))
            val restoreFile = File(context.cacheDir, "drive-restore-${UUID.randomUUID()}.db")
            contents.inputStream.use { input -> restoreFile.outputStream().use(input::copyTo) }
            try {
                validateDatabaseFile(restoreFile)
                replaceDatabaseWithRollback(restoreFile)
            } finally {
                restoreFile.delete()
            }
        } finally {
            metadataBuffer.release()
        }
    }

    private fun readLatestBackupMetadata(
        resourceClient: DriveResourceClient,
        metadataBuffer: com.google.android.gms.drive.MetadataBuffer,
    ): DriveBackupMetadata? {
        var latest: DriveBackupMetadata? = null
        for (index in 0 until metadataBuffer.count) {
            val entry = metadataBuffer.get(index)
            if (entry.title != METADATA_FILE_NAME && !entry.title.startsWith(METADATA_FILE_PREFIX)) continue
            val contents = Tasks.await(resourceClient.openFile(entry.driveId.asDriveFile(), DriveFile.MODE_READ_ONLY))
            val json = contents.inputStream.bufferedReader().use { it.readText() }
            val metadata = runCatching { DriveBackupMetadata.fromJson(json) }.getOrNull() ?: continue
            if (latest == null || metadata.createdAtEpochMs > latest.createdAtEpochMs) latest = metadata
        }
        return latest
    }

    private fun validateDatabaseFile(file: File) {
        check(file.isFile && file.length() > 0L) { "The backup file is empty or incomplete." }
        val restoredDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            check(restoredDatabase.version == DATABASE_SCHEMA_VERSION) {
                "Backup database version ${restoredDatabase.version} is not supported."
            }
            restoredDatabase.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { cursor ->
                check(cursor.moveToFirst()) { "The selected file is not a valid Expense Manager database." }
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

    private fun uploadFileToDrive(
        resourceClient: DriveResourceClient,
        parent: com.google.android.gms.drive.DriveFolder,
        fileName: String,
        bytes: ByteArray,
    ) {
        val driveContents = Tasks.await(resourceClient.createContents())
        driveContents.outputStream.use { it.write(bytes) }
        val metadata = com.google.android.gms.drive.MetadataChangeSet.Builder()
            .setTitle(fileName)
            .setMimeType("application/octet-stream")
            .build()
        Tasks.await(resourceClient.createFile(parent, metadata, driveContents))
    }

    private fun findMetadataByTitle(
        metadataBuffer: com.google.android.gms.drive.MetadataBuffer,
        title: String,
    ): com.google.android.gms.drive.Metadata? {
        for (index in 0 until metadataBuffer.count) {
            val entry = metadataBuffer.get(index)
            if (entry.title == title) return entry
        }
        return null
    }

    private fun databaseFile(): File = File(context.getDatabasePath(DATABASE_NAME).absolutePath)

    companion object {
        private const val TAG = "Backup"
        private const val DATABASE_NAME = "expense_manager_database.db"
        private const val DATABASE_SCHEMA_VERSION = 10
        private const val DATABASE_BACKUP_FILE_NAME = "expense_manager_database_backup.db"
        private const val METADATA_FILE_NAME = "expense_manager_backup_metadata.json"
        private const val METADATA_FILE_PREFIX = "expense_manager_backup_metadata_"
        private const val BACKUP_FORMAT_VERSION = 1
    }
}