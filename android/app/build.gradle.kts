plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.gnss.tracking"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "org.gnss.tracking"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        val revision = runCatching {
            providers.exec {
                workingDir(rootDir.parentFile)
                commandLine("git", "rev-parse", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "unknown"
        buildConfigField("String", "SOURCE_REVISION", "\"$revision\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["test"].resources.apply {
        srcDir("../../protocol/fixtures")
        exclude("**/*.md")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric 4.14's API 35 native loader shares a fonts ZIP filesystem across
// sandboxes. Isolate classes to prevent native initialization in one SDK/shadow
// configuration from contaminating another; retain every API-boundary assertion.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach { forkEvery = 1 }
