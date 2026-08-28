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
// finalizeDsl runs after module build files have been evaluated but before variants/tasks are created,
// so individual module defaults cannot silently raise the supported Android baseline.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
            finalizeDsl { androidDsl ->
                androidDsl.defaultConfig.minSdk = 26
            }
        }
    }
}
