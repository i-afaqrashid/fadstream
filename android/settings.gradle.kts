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
        // WebRTC prebuilt (libwebrtc) for WHIP ingest
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "FadStream"
include(":app")
