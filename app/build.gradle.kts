plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "ai.alagent.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.alagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val releaseStoreFile = System.getenv("AL_AGENT_STORE_FILE")
    val releaseStorePassword = System.getenv("AL_AGENT_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("AL_AGENT_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("AL_AGENT_KEY_PASSWORD")
    val releaseSigning = if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseStoreFile))
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else null
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            releaseSigning?.let { signingConfig = it }
        }
    }
    lint { abortOnError = true; checkReleaseBuilds = true; warningsAsErrors = false }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES") }
}
dependencies {
    implementation(project(":features:home"))
    implementation(project(":features:chat"))
    implementation(project(":features:agents"))
    implementation(project(":features:models"))
    implementation(project(":features:providers"))
    implementation(project(":features:skills"))
    implementation(project(":features:memory"))
    implementation(project(":features:automations"))
    implementation(project(":features:settings"))
    implementation(project(":features:debug"))
    implementation(project(":service:agent"))
    implementation(project(":agent:runtime"))
    implementation(project(":agent:subagents"))
    implementation(project(":ai:provider-api"))
    implementation(project(":ai:provider-openai"))
    implementation(project(":ai:provider-anthropic"))
    implementation(project(":ai:provider-google"))
    implementation(project(":ai:provider-openrouter"))
    implementation(project(":ai:provider-local"))
    implementation(project(":ai:inference"))
    implementation(project(":skills:runtime"))
    implementation(project(":tools:api"))
    implementation(project(":tools:android"))
    implementation(project(":tools:intents"))
    implementation(project(":tools:files"))
    implementation(project(":tools:notifications"))
    implementation(project(":tools:clipboard"))
    implementation(project(":tools:web"))
    implementation(project(":tools:mcp"))
    implementation(project(":tools:termux"))
    implementation(project(":tools:ssh"))
    implementation(project(":tools:shizuku"))
    implementation(project(":service:local-api"))
    implementation(project(":tools:accessibility"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":agent:memory"))
    implementation(project(":agent:policy"))
    implementation(project(":agent:cognition"))
    implementation(project(":agent:planning"))
    implementation(project(":core:logging"))
    implementation(project(":core:network"))
    implementation(project(":core:files"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
}
