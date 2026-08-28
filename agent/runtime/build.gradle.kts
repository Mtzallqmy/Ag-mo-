plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":ai:provider-api"))
    implementation(project(":agent:planning"))
    implementation(project(":agent:cognition"))
    implementation(project(":agent:memory"))
    implementation(project(":agent:policy"))
    implementation(project(":tools:api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }
