import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Nur für JVM-Screenshot-Tests (README-Bilder), nicht Teil der Release-APK.
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

android {
    namespace = "de.dgstudios.iptvstream"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.dgstudios.iptvstream"
        minSdk = 24
        targetSdk = 36
        versionCode = 87
        versionName = "1.0.77-ctx1"
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
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs.excludes += setOf("**/x86/**", "**/x86_64/**")
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // Paparazzi 1.3.5 (Layoutlib) kennt compileSdk 36 noch nicht; 2.x bräuchte Java 21 in CI.
    // Die README-Screenshots entstehen darum vorerst nicht im normalen Testlauf (wie im Play-Zweig).
    testOptions.unitTests.all { test -> test.exclude("**/*Screenshots*") }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    // Chromecast-Sender (Handy → TV): Standard-Empfänger, eigene Auswahl im Glas-Look.
    implementation("com.google.android.gms:play-services-cast-framework:22.2.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
}
