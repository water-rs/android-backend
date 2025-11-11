import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    plugins {
        id("com.android.library") version "8.4.1"
        id("org.jetbrains.kotlin.android") version "1.9.25"
        id("org.jetbrains.kotlin.plugin.compose") version "1.9.25"
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
