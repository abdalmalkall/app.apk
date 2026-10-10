# Google Drive Backup OAuth Setup

The Android client uses Google Identity `AuthorizationClient` in the foreground. It requests only:

- `https://www.googleapis.com/auth/drive.file` for app-created files and the visible backup folder.
- `https://www.googleapis.com/auth/drive.appdata` to read existing hidden backups created by older app versions. Old app-data files are not deleted or moved.

No OAuth access or refresh token is stored by the app. A `401` clears the cached access token; the next foreground action requests authorization again. Authorization is never started by background work.

## Google Cloud configuration

1. Select or create a Google Cloud project and enable the Google Drive API.
2. Configure the OAuth consent screen with the app name, support email, privacy policy, and terms URL. Add both scopes above. Keep the project in Testing and add test accounts until the flow is verified.
3. Create an OAuth client of type **Android** for each signing identity. The package name for the release app is `com.naveenapps.expensemanager`; the debug build uses `com.naveenapps.expensemanager.debug` because of its application ID suffix.
4. Obtain local certificate fingerprints with `./gradlew signingReport`. Register the SHA-1 for each package/signing pair that will be used. For Play-distributed builds, also register the app-signing certificate SHA-1 from Play Console; it differs from the upload key.
5. Install a build signed by a registered key, authorize a test account, create a backup, and verify that the encrypted `.embk` file appears in Drive under `ExpenseManager Backups`.

OAuth consent verification may be required before production use. Do not put access tokens, refresh tokens, or user credentials in source files, issue reports, or backup archives.

## Live test checklist

- Authorize a test account from the foreground Settings screen.
- Create and inspect the encrypted backup in Drive.
- List backups, download one, and restore it against disposable test data.
- Repeat with consent denied, an expired/revoked grant, no network, and a rate-limited request.

Passing unit tests or building an APK does not count as a Google Drive live test.