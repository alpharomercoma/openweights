plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.data"
}

room {
    // Schemas are checked in so migrations can be written against a known starting point.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:common"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
}
