pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenWeights"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:engine")
include(":core:generation")
include(":core:hub")
include(":core:tools")
include(":core:device")
include(":core:data")
include(":core:sandbox")

// Not shipped. It drives the app on a device to record the profile the app ships with.
include(":baselineprofile")
