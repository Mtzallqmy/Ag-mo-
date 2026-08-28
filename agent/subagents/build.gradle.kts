plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":agent:runtime"))
    implementation(project(":core:common"))
    implementation(project(":tools:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }
