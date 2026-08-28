plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":ai:provider-api"))
    implementation(project(":ai:provider-openai"))
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }
