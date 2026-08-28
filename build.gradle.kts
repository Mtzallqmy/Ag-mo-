plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// AL Agent supports Android 8.0 (API 26) across every Android library module.
// Keep this centralized so a feature/tool module cannot silently raise the app baseline.
subprojects {
    plugins.withId("com.android.library") {
        afterEvaluate {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                defaultConfig {
                    minSdk = 26
                }
            }
        }
    }
}
