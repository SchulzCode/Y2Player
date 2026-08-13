import java.io.File
import java.security.MessageDigest
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
        versionCode = 9
        versionName = "2.2.3"

        buildConfigField("String", "BUILD_ID", "\"$resolvedBuildId\"")

        ndk {
            abiFilters += "armeabi-v7a"
        }
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

    packaging {
        jniLibs {
            // Already stripped by the pinned NDK. AGP's newer strip tool must not rewrite it.
            keepDebugSymbols += "**/liby2audio.so"
        }
    }
}

val verifyNativeAudioStamp by tasks.registering {
    val nativeSource = rootProject.file("app/src/main/c/y2audio.c")
    val versionScript = rootProject.file("app/src/main/c/y2audio.map")
    val nativeBuildScript = rootProject.file("tools/native/build-ffmpeg.sh")
    val patchDirectory = rootProject.file("tools/native/patches")
    // Byte order, to match the LC_ALL=C sort in build-ffmpeg.sh.
    val patches = patchDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".patch") }
        ?.sortedBy { it.name }
        ?: emptyList()
    val stampFile = rootProject.file("app/src/main/jniLibs/armeabi-v7a/liby2audio.stamp")
    val library = rootProject.file("app/src/main/jniLibs/armeabi-v7a/liby2audio.so")

    val nativeNeon = when (val value = project.findProperty("nativeNeon")?.toString()?.lowercase()) {
        null, "false", "0" -> false
        "true", "1" -> true
        else -> throw GradleException("nativeNeon must be true/false or 1/0, not '$value'")
    }
    inputs.property("nativeNeon", nativeNeon)

    val allowStale = project.findProperty("allowStaleNative")?.toString() == "true"

    inputs.files(nativeSource, versionScript, nativeBuildScript)
    inputs.files(patches)
    outputs.upToDateWhen { false }

    doLast {
        if (allowStale) {
            logger.warn(
                "liby2audio.so stamp check skipped (-PallowStaleNative=true). " +
                    "Native sources may not match the packaged binary."
            )
            return@doLast
        }

        val rebuild = "powershell -File tools/build-native-audio.ps1"
        if (!library.isFile) {
            throw GradleException("liby2audio.so is missing. Run: $rebuild")
        }

        val sources: List<File> = listOf(nativeSource, versionScript, nativeBuildScript) + patches
        sources.forEach { source ->
            if (!source.isFile) {
                throw GradleException("native build input is missing: $source")
            }
        }

        // Same construction as the tail of build-ffmpeg.sh: hash each input, join
        // the hex digests with newlines, hash that.
        val perFileDigests = sources.joinToString("") { source ->
            MessageDigest.getInstance("SHA-256")
                .digest(source.readBytes())
                .joinToString("") { byte -> String.format("%02x", byte) } + "\n"
        } + MessageDigest.getInstance("SHA-256")
            .digest("neon=${if (nativeNeon) 1 else 0}".toByteArray())
            .joinToString("") { byte -> String.format("%02x", byte) } + "\n"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(perFileDigests.toByteArray())
            .joinToString("") { byte -> String.format("%02x", byte) }

        if (!stampFile.isFile) {
            throw GradleException(
                "liby2audio.so carries no source stamp, so it cannot be shown to " +
                    "match app/src/main/c. Run: $rebuild"
            )
        }
        val recorded = stampFile.readText().trim()
        if (recorded != expected) {
            throw GradleException(
                "liby2audio.so is stale: it was built from different native " +
                    "sources or a different FFmpeg configuration.\n" +
                    "  recorded: $recorded\n" +
                    "  current:  $expected\n" +
                    "Run: $rebuild"
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyNativeAudioStamp) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Host tests only; never packaged. Lets the screen catalogue derive from the
    // sealed hierarchy so a new screen cannot be added without being covered.
    testImplementation(kotlin("reflect"))
}
