import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

android {
    namespace = "de.dgstudios.iptvstream.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dgstudios.iptvstream.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 19
        versionName = "1.0.17"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
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
            // Release-APKs dürfen niemals mit dem Debug-Schlüssel veröffentlicht werden.
            if (!hasReleaseSigning && System.getenv("CI")?.equals("true", ignoreCase = true) == true) {
                throw GradleException("Release-Signierung fehlt: CI benötigt SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD und SIGNING_KEY_ALIAS.")
            }
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Nur für bewusst lokale Test-Builds zulässig; CI bricht oben ab.
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

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
}
