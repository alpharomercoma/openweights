/*
 * The generation interfaces, and nothing that can generate.
 *
 * Multiplatform from the start rather than converted later, because the whole reason this
 * module exists is that a diffusion or speech runtime is not the chat engine, and the
 * runtimes worth trying are not all Android ones. Its first version was written as an
 * Android library and had `java.io.File` in four signatures, which is exactly the mistake an
 * interface meant to outlive one runtime should not make. See [Artifact].
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "io.github.alpharomercoma.openweights.core.generation"
        compileSdk = 37
        minSdk = 29

        withHostTestBuilder {}
    }

    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies { implementation(kotlin("test")) }
        }
    }
}
