plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "ai.alagent.tools.mcp"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":tools:api"))
    implementation(project(":core:network"))
    implementation(project(":agent:policy"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

}
