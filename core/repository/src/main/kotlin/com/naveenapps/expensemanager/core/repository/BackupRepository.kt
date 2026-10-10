package com.naveenapps.expensemanager.core.repository

import com.naveenapps.expensemanager.core.model.Resource

class DriveApiException(
    val statusCode: Int,
    message: String,
) : Exception(message)

data class DriveBackupItem(
    val id: String,
    val name: String,
    val modifiedAtEpochMs: Long,
    val sizeBytes: Long,
    val legacy: Boolean = false,
)

interface BackupRepository {

    suspend fun backupData(uri: String?, passphrase: String, accessToken: String): Resource<Boolean>

    suspend fun restoreData(
        uri: String?,
        passphrase: String,
        accessToken: String,
        backupFileId: String? = null,
    ): Resource<Boolean>

    suspend fun listDriveBackups(accessToken: String): Resource<List<DriveBackupItem>>
}