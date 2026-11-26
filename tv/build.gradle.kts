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

android {
    namespace = "de.dgstudios.iptvstream.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.dgstudios.iptvstream.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 95
        versionName = "1.0.78-puffer4"
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
    // Screenshot-Tests (Paparazzi 2.x, braucht Java 21 zum Bauen) laufen wieder mit.
    testOptions.unitTests.isIncludeAndroidResources = true
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
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    // Fokus-Tests (Fernbedienung) ohne Gerät.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Paparazzi 1.3.5 nimmt android.os.Build aus der android.jar des compileSdk; die von API 36 passt nicht
// zur mitgelieferten Layoutlib. Für die Unit-Tests darum an derselben Stelle die android.jar von API 35
// (wie bis 1.0.76). Fehlt die Plattform, laufen die Screenshot-Tests nicht mit.
tasks.withType<Test>().configureEach {
    val legacy = File(android.sdkDirectory, "platforms/android-35/android.jar")
    // Screenshots nur auf Wunsch (-PwithScreenshots): Layoutlib 1.3.5 ist mit API 36 nicht ganz stabil.
    if (!legacy.isFile || !project.hasProperty("withScreenshots")) {
        exclude("**/*Screenshots*")
    } else {
        doFirst {
            classpath = files(classpath.files.map { if (it.name == "android.jar") legacy else it })
        }
    }
}
