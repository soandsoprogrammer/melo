import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Firebase setup is optional for local builds until the app owner adds app/google-services.json.
// A separate test installation (-PmeloTestApplicationId) has no Firebase client and sends no analytics.
if (file("google-services.json").isFile && !project.hasProperty("meloTestApplicationId")) {
    apply(plugin = "com.google.gms.google-services")
}

val updateRepository = providers.gradleProperty("meloUpdateRepository").getOrElse("")
require(updateRepository.isEmpty() || updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "meloUpdateRepository must be owner/repository"
}
val statsEndpoint = providers.gradleProperty("meloStatsEndpoint")
    .getOrElse("")
require(statsEndpoint.isEmpty() || statsEndpoint.matches(Regex("https://[A-Za-z0-9.-]+/api/presence"))) {
    "meloStatsEndpoint must be an HTTPS /api/presence URL"
}

val forkBuild = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get().toInt()

val releaseSigningFile = rootProject.file(".signing/melo-release.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "in.melo"
    compileSdk = 37

    defaultConfig {
        // A separate installation keeps the regular account and downloads intact during device tests.
        applicationId = providers.gradleProperty("meloTestApplicationId").getOrElse("app.shenye.melo")
        minSdk = 26
        targetSdk = 36
        versionCode = 100000 + forkBuild
        versionName = "1.3.1-shenye.$forkBuild"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A fork must never offer the upstream APK as an update.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "MELO_STATS_ENDPOINT", "\"$statsEndpoint\"")
        buildConfigField("boolean", "FORCE_OFFLINE", project.hasProperty("meloNoInternet").toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (releaseSigningFile.isFile) {
        signingConfigs.create("meloRelease") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("meloRelease")
    }
    // Release downloads: one APK per phone architecture next to the universal one the updater fetches.
    if (project.hasProperty("meloAbiSplits")) splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a")
        isUniversalApk = true
    }
    // Offline verification: no socket can open, with no phone setting touched.
    if (project.hasProperty("meloNoInternet")) sourceSets.getByName("debug").manifest.srcFile("src/noInternet/AndroidManifest.xml")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // SF Symbols as Compose vectors, for the app's icons. Without its own
    // dependencies: the plain ImageVectors it holds need nothing the app's Compose lacks.
    implementation("com.github.cosmictaserdev-creator.Jetpack_SF_Symbols:sfsymbols-android:1.1.1") {
        isTransitive = false
    }
    implementation("com.google.protobuf:protobuf-javalite:4.32.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // Add-on streams only: Melo's own sources stay progressive.
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    // The lyrics share video: the card over the song, encoded on the device.
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // UI building blocks.
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.10.6")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("ir.mahozad.multiplatform:wavy-slider:2.2.0")
    implementation("com.github.racra:smooth-corner-rect-android-compose:v1.0.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-websockets:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
