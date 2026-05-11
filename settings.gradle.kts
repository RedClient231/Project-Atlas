pluginManagement {
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
        maven("https://jitpack.io")
        // Pine hooking framework - author's Maven repo
        maven("https://raw.githubusercontent.com/canyie/maven-repo/master")
    }
}

rootProject.name = "ProjectAtlas"
include(":app")
