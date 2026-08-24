plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.tools"
}

dependencies {
    api(project(":core:common"))
    implementation(project(":core:sandbox"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
