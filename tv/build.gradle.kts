import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

fun signingProp(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

val signingStorePassword = signingProp("SIGNING_STORE_PASSWORD")
val signingKeyPassword = signingProp("SIGNING_KEY_PASSWORD")
val signingKeyAlias = signingProp("SIGNING_KEY_ALIAS")
val signingKeystoreBase64 = signingProp("SIGNING_KEYSTORE_BASE64")

val releaseKeystoreFile: java.io.File? = signingKeystoreBase64?.let { encoded ->
    val decoded = Base64.getDecoder().decode(encoded.replace(Regex("\\s"), ""))
    // Outside build/: AGP cleans module build dirs and would delete the keystore before signing.
    val out = File(System.getProperty("java.io.tmpdir"), "iptvstream-${project.name}-release.keystore")
    out.writeBytes(decoded)
    out
}

val hasReleaseSigning = releaseKeystoreFile != null &&
    signingStorePassword != null &&
    signingKeyPassword != null &&
    signingKeyAlias != null

// Upload-Schlüssel für Google Play: Pfad, Alias und Passwort nur per Umgebung/Gradle-Property.
val playUploadStore = signingProp("PLAY_UPLOAD_KEYSTORE")
val playUploadPassword = signingProp("PLAY_UPLOAD_PASSWORD")
val playUploadAlias = signingProp("PLAY_UPLOAD_ALIAS")
val hasPlayUploadSigning = playUploadStore != null && playUploadPassword != null && playUploadAlias != null

android {
    namespace = "de.dgstudios.iptvstream.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dgstudios.iptvstream.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 76
        versionName = "1.0.74"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Vertriebsweg: github = eigene APKs mit Update-Feed (wie bisher), play = Google Play.
    // play: kein Selbst-Update, kein REQUEST_INSTALL_PACKAGES, eigener Upload-Schlüssel (Play App Signing).
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
            // Google Play verlangt seit 31.08.2026 für neue Apps API 36 (TV-Apps: 34+).
            targetSdk = 35
        }
    }

    signingConfigs {
        if (hasPlayUploadSigning) {
            create("playUpload") {
                storeFile = file(playUploadStore!!)
                storeType = "PKCS12"
                storePassword = playUploadPassword
                keyAlias = playUploadAlias
                keyPassword = playUploadPassword
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storeType = "PKCS12"
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Veröffentlichte APKs signiert nur der Tag-Job. Der setzt REQUIRE_RELEASE_SIGNING.
            val requireReleaseSigning = System.getenv("REQUIRE_RELEASE_SIGNING")?.equals("true", ignoreCase = true) == true
            if (!hasReleaseSigning && requireReleaseSigning) {
                throw GradleException("Release-Signierung fehlt: CI benötigt SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD und SIGNING_KEY_ALIAS.")
            }
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs.excludes += setOf("**/x86/**", "**/x86_64/**")
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// Play-Release mit dem eigenen Upload-Schlüssel signieren (Play App Signing ersetzt ihn durch den App-Schlüssel).
androidComponents {
    onVariants(selector().withFlavor("distribution" to "play").withBuildType("release")) { variant ->
        if (hasPlayUploadSigning) variant.signingConfig?.setConfig(android.signingConfigs.getByName("playUpload"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
