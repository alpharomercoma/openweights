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

    api(libs.markdown.renderer)
    api(libs.markdown.renderer.m3)
    api(libs.markdown.renderer.code)
}
