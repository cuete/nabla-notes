import java.util.Properties
import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Load local.properties (never committed to git)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

// Auto-increment version: YYYY.MM.DD.NN
val versionPropsFile = file("version.properties")
val today = LocalDate.now().toString() // "YYYY-MM-DD"
val todayCompact = today.replace("-", "") // "YYYYMMDD"

val versionProps = Properties()
var buildNumber = 1
if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
    val lastDate = versionProps.getProperty("lastDate", "")
    buildNumber = if (lastDate == today) {
        versionProps.getProperty("buildNumber", "0").toInt() + 1
    } else {
        1
    }
}
versionProps.setProperty("lastDate", today)
versionProps.setProperty("buildNumber", buildNumber.toString())
versionPropsFile.writer().use { versionProps.store(it, null) }

val buildNN = buildNumber.toString().padStart(2, '0')
val computedVersionCode = "$todayCompact$buildNN".toInt()
val computedVersionName = "1.0"

android {
    namespace = "com.nabla.notes"
    // Compose 1.12 / core-ktx 1.19 require compiling against API 37+.
    // targetSdk stays a step behind on purpose, matching nabla-chato-voice's
    // toolchain bump: compiling against new APIs is independent of opting
    // in to new runtime behavior, and nothing here needs the latter yet.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.nabla.notes"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MSAL client ID from local.properties (no hardcoded secrets)
        buildConfigField(
            "String",
            "MSAL_CLIENT_ID",
            "\"${localProperties.getProperty("msal.clientId", "YOUR_CLIENT_ID_HERE")}\""
        )
    }

    // APKs keep Gradle's default names (app-debug.apk / app-release.apk). The old
    // applicationVariants rename is gone with AGP 9's new DSL, and renaming here is
    // not worth an internal-API cast: the OneDrive copy hook already prefixes each
    // file with its repo folder name, so builds from different projects don't collide.

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Replaces the old android.kotlinOptions block, which AGP 9's built-in Kotlin
// support no longer provides. jvmTarget rather than jvmToolchain so the build
// uses the JDK already running Gradle instead of provisioning a second one.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // Microsoft MSAL
    implementation(libs.msal)

    // HTTP client
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Markdown rendering
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.image)
    implementation(libs.markwon.image.glide)
    implementation(libs.markwon.html)

    // DataStore
    implementation(libs.datastore.preferences)

    // JSON — see version-catalog comment on the gson alias
    implementation(libs.gson)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.robolectric:robolectric:4.12.1")
}
