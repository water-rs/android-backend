import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    plugins {
        id("com.android.library") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
    repositories {
        google()
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

rootProject.name = "waterui-android"
include(":runtime")
