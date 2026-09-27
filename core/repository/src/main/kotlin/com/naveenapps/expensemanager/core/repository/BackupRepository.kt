package com.naveenapps.expensemanager.core.repository

import android.content.Intent
import com.naveenapps.expensemanager.core.model.Resource

interface BackupRepository {

    fun backupData(uri: String?): Resource<Boolean>

    fun restoreData(uri: String?): Resource<Boolean>

    fun isGoogleDriveSignedIn(): Boolean

    fun requestGoogleDriveSignIn(): Intent?

    fun handleGoogleDriveSignInResult(data: Intent?): Resource<Boolean>
}