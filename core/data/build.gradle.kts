plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.data"
}

// MigrationTestHelper reads the exported schemas out of the assets folder, and it is the test
// that needs them rather than the app: shipping four JSON files describing the database to
// every install would be paying for a test at runtime. Configured through the new DSL type by
// name, because the generated `android { }` accessor still resolves to the old source set
// interface and casting it fails at configuration time.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
}

room {
    // Schemas are checked in so migrations can be written against a known starting point.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:common"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
}
