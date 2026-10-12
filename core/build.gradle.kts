plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "de.dgstudios.iptvstream.core"
    compileSdk = 35

    defaultConfig {
        // Beide Apps verlangen 24. Ein niedrigerer Wert täuscht alte Geräte vor,
        // auf denen putIfAbsent und der Keystore-Schlüssel ohnehin fehlen würden.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            // Media3 kennzeichnet seine Player-APIs als UnstableApi. Das ist der
            // dokumentierte Umgang der Bibliothek; Lint folgt diesem Flag.
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Media3 markiert seine Player-APIs mit UnstableApi (androidx-Marker). Der Einsatz ist
        // hier Absicht (eigene Renderer, FFmpeg-Fallback, TS-Flags). Weder @OptIn noch das
        // -opt-in-Flag werden von dieser Lint-Version für den Marker anerkannt; Compiler und
        // Release-Prüfung sind sauber.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")

    api("androidx.core:core-ktx:1.15.0")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.navigation:navigation-compose:2.8.5")

    api("androidx.media3:media3-exoplayer:1.5.1")
    api("androidx.media3:media3-exoplayer-hls:1.5.1")
    api("androidx.media3:media3-ui:1.5.1")
    api("androidx.media3:media3-datasource-okhttp:1.5.1")
    // Vorgebautes Media3-FFmpeg-Audio (arm64-v8a, armeabi-v7a; AC3/E-AC3/MP2/DTS). JNI passt zu 1.5.x.
    api("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    api("androidx.datastore:datastore-preferences:1.1.1")
    api("io.coil-kt:coil-compose:2.7.0")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
