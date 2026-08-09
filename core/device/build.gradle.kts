plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.device"
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
}
