plugins {
    id("openweights.android.library")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.generation"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
