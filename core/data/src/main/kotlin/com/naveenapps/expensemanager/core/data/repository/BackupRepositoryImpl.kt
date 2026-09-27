package com.naveenapps.expensemanager.core.data.repository

import android.content.Context
import android.content.Intent
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

class BackupRepositoryImpl(
    private val context: Context,
    private val roomBackup: RoomBackup,
    private val database: ExpenseManagerDatabase,
) : BackupRepository {

    private val googleSignInClient: GoogleSignInClient? by lazy {
        val activity = context as? android.app.Activity ?: return@lazy null
        GoogleSignIn.getClient(
            activity,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(Drive.SCOPE_APPFOLDER)
                .build(),
        )
    }

    override fun backupData(uri: String?): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return runLocalBackupFallback()
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !isGoogleDriveSignedIn()) {
            return Resource.Error(IllegalStateException("Google Drive sign-in is required before backing up data."))
        }

        return try {
            uploadDatabaseToDrive(account)
            Resource.Success(true)
        } catch (exception: Exception) {
            Log.e(TAG, "Drive backup failed", exception)
            Resource.Error(exception)
        }
    }

    override fun restoreData(uri: String?): Resource<Boolean> {
        if (!uri.isNullOrBlank() && uri.startsWith("content://")) {
            return runLocalRestoreFallback()
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || !isGoogleDriveSignedIn()) {
            return Resource.Error(IllegalStateException("Google Drive sign-in is required before restoring data."))
        }

        return try {
            restoreDatabaseFromDrive(account)
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
        val metadata = DriveBackupMetadata(
            schemaVersion = 10,
            createdAtEpochMs = System.currentTimeMillis(),
            appId = context.packageName,
        )

        val metadataFileBytes = metadata.toJson().toByteArray(Charsets.UTF_8)
        uploadFileToDrive(resourceClient, appFolder, METADATA_FILE_NAME, metadataFileBytes)
        uploadFileToDrive(resourceClient, appFolder, DATABASE_BACKUP_FILE_NAME, dbFile.readBytes())
    }

    private fun restoreDatabaseFromDrive(account: GoogleSignInAccount) {
        val resourceClient = Drive.getDriveResourceClient(context, account)
        val appFolder = Tasks.await(resourceClient.appFolder)
        val metadataBuffer = Tasks.await(resourceClient.listChildren(appFolder))
        try {
            val metadata = findMetadataByTitle(metadataBuffer, METADATA_FILE_NAME)
                ?: throw IllegalStateException("Google Drive backup metadata was not found.")

            val metadataFile = metadata.driveId.asDriveFile()
            val metadataContents = Tasks.await(resourceClient.openFile(metadataFile, DriveFile.MODE_READ_ONLY))
            val metadataJson = metadataContents.inputStream.bufferedReader().use { it.readText() }
            val parsed = DriveBackupMetadata.fromJson(metadataJson)
            if (parsed.schemaVersion != DATABASE_SCHEMA_VERSION) {
                throw IllegalStateException(
                    "Backup schema mismatch. Expected $DATABASE_SCHEMA_VERSION but found ${parsed.schemaVersion}.",
                )
            }

            val databaseMetadata = findMetadataByTitle(metadataBuffer, DATABASE_BACKUP_FILE_NAME)
                ?: throw IllegalStateException("Google Drive backup database was not found.")

            val databaseFile = databaseMetadata.driveId.asDriveFile()
            val contents = Tasks.await(resourceClient.openFile(databaseFile, DriveFile.MODE_READ_ONLY))
            val bytes = contents.inputStream.readBytes()
            databaseFile().writeBytes(bytes)
        } finally {
            metadataBuffer.release()
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
    }
}