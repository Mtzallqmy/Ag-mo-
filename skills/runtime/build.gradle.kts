plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "ai.alagent.skills.runtime"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":skills:api"))
    implementation(project(":tools:api"))
    implementation(project(":agent:policy"))
    implementation(project(":core:files"))
    implementation(libs.kotlinx.serialization.json)

}
