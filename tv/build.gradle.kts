import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

/**
 * Vertriebsvariante: Standard ist die GitHub-APK. Mit `-Pdistribution=play` entsteht die
 * Google-Play-Variante (targetSdk 36, ohne APK-Installer-Berechtigung, Updates über Play).
 */
val playBuild: Boolean = providers.gradleProperty("distribution").orNull == "play"

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
    compileSdk = if (playBuild) 36 else 35

    defaultConfig {
        applicationId = "de.dgstudios.iptvstream.tv"
        minSdk = 24
        // Play verlangt für Handy-Apps seit 31.08.2026 API 36, für Android TV reicht 34.
        targetSdk = if (playBuild) 35 else 35
        versionCode = 76
        versionName = "1.0.74"
        // Play-Variante: nur für Fernseher anbieten (Leanback Pflicht). GitHub-APK bleibt für jedes Gerät installierbar.
        manifestPlaceholders["leanbackRequired"] = if (playBuild) "true" else "false"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    if (playBuild) {
        // Manifest-Zusatz der Play-Variante: ohne REQUEST_INSTALL_PACKAGES und ohne Update-FileProvider.
        sourceSets["debug"].manifest.srcFile("src/play/AndroidManifest.xml")
        sourceSets["release"].manifest.srcFile("src/play/AndroidManifest.xml")
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
    if (playBuild) {
        // Paparazzi (Layoutlib) kennt compileSdk 36 noch nicht. Screenshots entstehen in der Standard-Variante.
        testOptions.unitTests.all { test -> test.exclude("**/*Screenshots*") }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
