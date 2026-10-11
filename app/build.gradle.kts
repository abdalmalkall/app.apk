import java.security.KeyStore
import java.security.cert.X509Certificate
import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.io.FileInputStream
import java.util.Locale
import java.util.Properties

plugins {
    id("naveenapps.plugin.android.app")
    id("com.google.android.gms.oss-licenses-plugin")
    id("naveenapps.plugin.kotlin.basic")
    id("naveenapps.plugin.compose")
    id("naveenapps.plugin.di")
    id("com.github.triplet.play")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.appdistribution")
    alias(libs.plugins.compose.compiler)
}


val keysFolderPath: String =
    if (File("${rootDir.absolutePath}/keys/credentials.properties").exists()) {
        "${rootDir.absolutePath}/keys"
    } else {
        rootDir.absolutePath
    }

fun getCredentialsFile(): File {
    val credentialFilePath = "$keysFolderPath/credentials.properties"
    return File(credentialFilePath)
}

fun getKeystoreFile(): File {
    val keystoreFilePath = "$keysFolderPath/android_keystore.jks"
    return File(keystoreFilePath)
}

fun getPlayStorePublisherFile(): File {
    val playStorePublisherFile = "$keysFolderPath/play_publish.json"
    return File(playStorePublisherFile)
}

fun getFirebasePublisherFile(): File {
    val firebasePublishJson = "$keysFolderPath/firebase_distribution_service_account.json"
    return File(firebasePublishJson)
}

val credentials = getCredentialsFile()
val keystore = getKeystoreFile()
if (credentials.exists() && keystore.exists()) {
    println("----- Both Keystore & Credentials available -----")
    println("----- ${credentials.absolutePath} -----")
    val properties = Properties().apply {
        load(FileInputStream(credentials))
    }

    android {
        signingConfigs {
            create("release") {
                keyAlias = properties.getProperty("KEY_ALIAS")
                storePassword = properties.getProperty("KEY_STORE_PASSWORD")
                keyPassword = properties.getProperty("KEY_PASSWORD")
                storeFile = keystore
            }
        }
    }
} else {
    println("----- Credentials not available -----")
}

val externalReleaseStore = providers.environmentVariable("EXPENSE_RELEASE_STORE_FILE").orNull
if (android.signingConfigs.findByName("release") == null && externalReleaseStore != null) {
    android.signingConfigs.create("release") {
        storeFile = file(externalReleaseStore)
        storePassword = providers.environmentVariable("EXPENSE_RELEASE_STORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("EXPENSE_RELEASE_KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("EXPENSE_RELEASE_KEY_PASSWORD").orNull
    }
}

val playStorePublisher = getPlayStorePublisherFile()
if (playStorePublisher.exists()) {
    println("----- Play Store Publisher available -----")
    println("----- ${playStorePublisher.absolutePath} -----")
    val track = System.getenv()["PLAYSTORE_TRACK"]
    val status = System.getenv()["PLAYSTORE_RELEASE_STATUS"]?.uppercase()
    println("----- ENV: $track & $status -----")
    val playStoreTrack = track ?: "beta"
    val playStoreReleaseStatus =
        runCatching { ReleaseStatus.valueOf(status!!) }.getOrNull() ?: ReleaseStatus.DRAFT

    println("----- $playStoreTrack & $playStoreReleaseStatus-----")

    android {
        play {
            this.serviceAccountCredentials.set(playStorePublisher)
            this.track.set(playStoreTrack)
            this.releaseStatus.set(playStoreReleaseStatus)
            println(this.serviceAccountCredentials.get().asFile.absolutePath)
        }
    }
} else {
    println("----- Publisher not available -----")
}

val firebasePublisher = getFirebasePublisherFile()
if (firebasePublisher.exists()) {
    println("----- Firebase Distribution Publisher available -----")
    println("----- ${firebasePublisher.absolutePath} -----")
    var firebaseDistGroups = System.getenv()["FIREBASE_DISTRIBUTION_GROUPS"]
    if (firebaseDistGroups.isNullOrBlank()) {
        firebaseDistGroups = "testers"
    }

    android {
        buildTypes {
            debug {
                firebaseAppDistribution {
                    serviceCredentialsFile = firebasePublisher.absolutePath
                    releaseNotes = "Nothing for now"
                    groups = firebaseDistGroups
                }
            }
            release {
                firebaseAppDistribution {
                    serviceCredentialsFile = firebasePublisher.absolutePath
                    releaseNotes = "Nothing for now"
                    groups = firebaseDistGroups
                }
            }
        }

        /**
         * Our APK path leads to our universal APK file - do guarantee that it's present we need to
         * make the distribution task depend on the universal APK assembly task.
         */
        applicationVariants.all { variant ->
            variant.outputs.forEach { output ->
                tasks.filter {
                    return@filter it.name.startsWith(
                        "appDistributionUpload${variant.name.toCapital()}"
                    )
                }.forEach {
                    it?.dependsOn("assemble${variant.name.toCapital()}")
                }
            }
            return@all true
        }
    }
} else {
    println("----- Firebase Distribution Publisher not available -----")
}

android {

    namespace = "com.naveenapps.expensemanager"

    defaultConfig {
        applicationId = "com.naveenapps.expensemanager"
        versionCode = 1_005_001
        versionName = "1.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = true
        }
        create("macrobenchmark") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        release {
            configure<CrashlyticsExtension> { mappingFileUploadEnabled = false }
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions.unitTests.isIncludeAndroidResources = true


    testOptions {
        managedDevices {
            devices {
                maybeCreate<com.android.build.api.dsl.ManagedVirtualDevice>("pixel2api30").apply {
                    // Use device profiles you typically see in Android Studio.
                    device = "Pixel 2"
                    // Use only API levels 27 and higher.
                    apiLevel = 30
                    // To include Google services, use "google".
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

dependencies {
    constraints {
        implementation(libs.androidx.concurrent.futures)
        implementation(libs.androidx.concurrent.futures.ktx)
    }

    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:navigation"))
    implementation(project(":core:notification"))
    implementation(project(":core:repository"))
    implementation(project(":core:settings"))

    implementation(project(":feature:account"))
    implementation(project(":feature:analysis"))
    implementation(project(":feature:budget"))
    implementation(project(":feature:category"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:transaction"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:filter"))
    implementation(project(":feature:country"))
    implementation(project(":feature:currency"))

    implementation(project(":feature:settings"))
    implementation(project(":feature:theme"))
    implementation(project(":feature:language"))
    implementation(project(":feature:export"))
    implementation(project(":feature:reminder"))
    implementation(project(":feature:currency"))
    implementation(project(":feature:about"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    implementation(libs.androidx.splash.screen)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.google.oss.licenses)

    implementation(libs.app.update.ktx)
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences.proto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.jxl)
    testImplementation(libs.robolectric)
    testImplementation(project(":core:testing"))
    androidTestImplementation(project(":core:testing"))
}

fun String.toCapital(): String {
    return this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(
            Locale.getDefault()
        ) else it.toString()
    }
}

// Never produce a publishing artifact with the debug-key fallback.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val config = android.signingConfigs.findByName("release")
            ?: throw GradleException("Release requires the original publishing keystore and secure credentials; no debug fallback is permitted.")
        val keyFile = config.storeFile
        require(keyFile?.isFile == true && !config.storePassword.isNullOrBlank() && !config.keyAlias.isNullOrBlank() && !config.keyPassword.isNullOrBlank()) {
            "Original Release signing configuration is incomplete."
        }
        val keyStore = KeyStore.getInstance(config.storeType ?: "JKS")
        keyFile!!.inputStream().use { keyStore.load(it, config.storePassword!!.toCharArray()) }
        val certificate = keyStore.getCertificate(config.keyAlias) as? X509Certificate
            ?: throw GradleException("Release certificate is missing.")
        require(!certificate.subjectX500Principal.name.contains("CN=Android Debug", true)) { "Android Debug certificates cannot sign Release." }
    }
}
tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "assembleRelease")) dependsOn(verifyReleaseSigning)
}
