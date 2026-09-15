// Top-level build file
// No kotlin-android plugin: AGP 9 provides built-in Kotlin support.
// No kotlin-kapt plugin: Hilt now runs on KSP instead.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
