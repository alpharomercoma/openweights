/*
 * The first multiplatform module, and the one chosen because it was already portable.
 *
 * Nothing in `core/common` imports anything from `android` or `androidx`: it is data classes,
 * enums and arithmetic. That made it the honest place to start, because the conversion can be
 * judged on whether the existing tests still pass rather than on how much had to be rewritten
 * to make them.
 *
 * ### The targets, and what each one is for
 *
 * `android` is the app that exists. `iosArm64` and `iosSimulatorArm64` are the phone and the
 * simulator this is heading for. `jvm` is not a shipping target at all and earns its place
 * anyway: it is the cheapest possible check that `commonMain` has no Android in it, it runs
 * on any machine including one with no Xcode, and it takes a second. Without it a
 * "multiplatform" module is an Android module with extra ceremony, and the first attempt at
 * this file was exactly that: it reported success while compiling zero classes, because the
 * sources were still under `src/main` where the Android plugin claims them.
 *
 * `x64` simulator targets are absent because this is an Apple silicon project throughout.
 */
plugins {
    // By id and not by alias: build-logic already puts the Kotlin and Android plugins on the
    // classpath, and asking for a version of something already there is an error rather than
    // a check.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "io.github.alpharomercoma.openweights.core.common"
        compileSdk = 37
        minSdk = 29

        withHostTestBuilder {}
    }

    // Targets 17 like the rest of the project, without demanding a JDK 17 toolchain: the
    // machine has 21, and asking Gradle to provision another JDK to compile a handful of
    // data classes would be a download for nothing.
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    iosArm64()
    iosSimulatorArm64()

    // Android and the JVM share a source set of their own, and it exists for one file.
    // CpuTopology reads /sys/devices/system/cpu to count the big cores, which is a Linux
    // fact and a java.io.File one: it is not portable to iOS and pretending otherwise would
    // mean an expect/actual whose iOS half returned a guess. A phone's core layout is the
    // wrong thing to guess about, so the code stays where it is true and iOS will need its
    // own answer when there is an iOS engine to need it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        androidMain { dependsOn(jvmAndAndroidMain) }
        jvmMain { dependsOn(jvmAndAndroidMain) }

        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        // A small set of tests that run everywhere, including on a simulator.
        //
        // `kotlin("test")` rather than a catalog entry: it resolves to a different artifact
        // per target, the JUnit-backed one on the JVM and the built-in one on Native, and
        // naming the plain artifact leaves the JVM target unable to find the annotations.
        commonTest {
            dependencies { implementation(kotlin("test")) }
        }

        // Most tests stay with the JVM, and that is a statement about JUnit and Truth rather
        // than about the code they cover. Both are JVM only, and rewriting a hundred working
        // assertions against kotlin.test would gain a second execution of arithmetic that
        // has no platform in it.
        //
        // The split is the honest version of "it works on iOS": everything in commonMain is
        // proved to *compile* for iOS on every build, and the handful of things where a
        // platform difference could plausibly hide are proved to *behave* there as well.
        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation(libs.truth)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmTest { dependsOn(jvmAndAndroidTest) }
        getByName("androidHostTest") { dependsOn(jvmAndAndroidTest) }
    }
}
