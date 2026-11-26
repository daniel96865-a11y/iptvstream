import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Sentry-DSN nur aus Umgebung (CI-Secret SENTRY_DSN), ~/.gradle/gradle.properties oder der
 * nicht eingecheckten local.properties. Nie im Repo. Ohne DSN bleibt Sentry aus.
 */
val sentryDsn: String = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    (System.getenv("SENTRY_DSN") ?: providers.gradleProperty("SENTRY_DSN").orNull ?: local.getProperty("SENTRY_DSN"))
        .orEmpty().trim()
}

android {
    namespace = "de.dgstudios.iptvstream.core"
    compileSdk = 36

    defaultConfig {
        // Beide Apps verlangen 24. Ein niedrigerer Wert täuscht alte Geräte vor,
        // auf denen putIfAbsent und der Keystore-Schlüssel ohnehin fehlen würden.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.replace("\"", "")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(rootProject.file("release-notes"))
    buildFeatures {
        compose = true
        buildConfig = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            // Media3 kennzeichnet seine Player-APIs als UnstableApi. Das ist der
            // dokumentierte Umgang der Bibliothek; Lint folgt diesem Flag.
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")

    api("androidx.core:core-ktx:1.18.0")
    api("androidx.activity:activity-compose:1.13.0")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    api("androidx.navigation:navigation-compose:2.9.8")

    api("androidx.media3:media3-exoplayer:1.9.4")
    api("androidx.media3:media3-exoplayer-hls:1.9.4")
    // MPEG-DASH (.mpd): DefaultMediaSourceFactory erkennt das Modul automatisch.
    api("androidx.media3:media3-exoplayer-dash:1.9.4")
    api("androidx.media3:media3-ui:1.9.4")
    api("androidx.media3:media3-datasource-okhttp:1.9.4")
    // Media3 bleibt bei 1.9.x: Das Jellyfin-FFmpeg-Modul gibt es nur bis 1.9.0+1 (Stand 11/2026),
    // ein Media3-Sprung auf 1.11 würde die JNI-Schnittstelle des AC3/E-AC3-Decoders brechen.
    // Vorgebautes Media3-FFmpeg-Audio (arm64-v8a, armeabi-v7a; AC3/E-AC3/MP2/DTS). JNI passt zu 1.9.x.
    api("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    api("androidx.room:room-runtime:2.8.5")
    api("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    api("androidx.datastore:datastore-preferences:1.3.0")
    // Absturz-/Fehlermeldungen an Sentry (nur Core: kein NDK, kein Session Replay).
    implementation("io.sentry:sentry-android-core:8.65.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.webkit:webkit:1.18.0")
    api("io.coil-kt:coil-compose:2.7.0")
    api("com.squareup.retrofit2:retrofit:2.12.0")
    // OkHttp und DNS-über-HTTPS immer in derselben Version (BOM).
    api(platform("com.squareup.okhttp3:okhttp-bom:4.12.1"))
    api("com.squareup.okhttp3:okhttp")
    // Sicheres DNS (DNS über HTTPS) für den eingebauten Browser.
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.media3:media3-test-utils:1.9.4")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.9.4")
}
