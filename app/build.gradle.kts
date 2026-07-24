import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { input ->
        keystoreProperties.load(input)
    }
}

/**
 * Build identity shared by BuildConfig, structured logs and the release manifest.
 * CI can pass `-PbuildId`; local builds derive a UTC timestamp, commit and dirty flag.
 */
val resolvedBuildId: String = (project.findProperty("buildId") as String?)
    ?: run {
        fun git(vararg args: String): String? = runCatching {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && text.isNotEmpty()) text else null
        }.getOrNull()

        val commit = git("rev-parse", "--short=12", "HEAD") ?: "nogit"
        val dirty = if (git("status", "--porcelain").isNullOrEmpty()) "" else "-dirty"
        // UTC keeps locally generated IDs stable across build hosts.
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val stamp = formatter.format(Date())
        "$stamp-$commit$dirty"
    }

android {
    namespace = "com.schulzcode.y2player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.schulzcode.y2player"
        minSdk = 19
        targetSdk = 19
        // 1.0 is the first released version. Documents describing a 1.3-1.9
        // lineage referred to pre-history development builds that were never
        // released under those numbers and are not part of this repository.
        //
        // Increment on every release that could reach a device as a package
        // upgrade: Android refuses to install a lower versionCode over a higher
        // one, and the firmware image path (system/priv-app) does not remove
        // that constraint for anyone who sideloads.
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BUILD_ID", "\"$resolvedBuildId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(
                    keystoreProperties["storeFile"] as String
                )
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String

                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
