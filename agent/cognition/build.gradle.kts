plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":agent:planning"))
    implementation(project(":tools:api"))
    implementation(project(":ai:provider-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }
