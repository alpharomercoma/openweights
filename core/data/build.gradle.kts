plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.data"
}

dependencies {
    api(project(":core:common"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
