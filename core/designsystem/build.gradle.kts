plugins {
    id("openweights.android.library")
    id("openweights.android.compose")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.extended)
}
