plugins {
    id("openweights.android.library")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
