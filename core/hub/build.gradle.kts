plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.hub"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:common"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.okhttp.mockwebserver)
}
