package com.naveenapps.expensemanager.core.data.repository

import com.google.gson.Gson

data class DriveBackupMetadata(
    val backupVersion: Int = 1,
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val appId: String,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): DriveBackupMetadata =
            Gson().fromJson(json, DriveBackupMetadata::class.java)
    }
}
