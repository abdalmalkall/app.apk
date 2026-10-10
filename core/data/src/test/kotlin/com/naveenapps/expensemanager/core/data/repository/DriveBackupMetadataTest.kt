package com.naveenapps.expensemanager.core.data.repository

import android.app.Activity
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriveBackupMetadataTest {

    private class TestActivity : Activity()

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

    @Test
    fun `findActivity unwraps compose context wrappers`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val wrapped = ContextWrapper(ContextWrapper(activity))

        assertThat(findActivity(wrapped)).isSameInstanceAs(activity)
    }
}
