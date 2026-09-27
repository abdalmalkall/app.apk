package com.naveenapps.expensemanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriveBackupMetadataTest {

    @Test
    fun `metadata round trips with app and schema details`() {
        val metadata = DriveBackupMetadata(
            backupVersion = 1,
            schemaVersion = 10,
            createdAtEpochMs = 1_700_000_000_000L,
            appId = "com.naveenapps.expensemanager",
        )

        val json = metadata.toJson()
        val parsed = DriveBackupMetadata.fromJson(json)

        assertThat(parsed).isEqualTo(metadata)
    }
}
