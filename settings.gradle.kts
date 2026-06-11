pluginManagement {
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
        // WebRTC prebuilt (io.github.webrtc-sdk) and OkHttp both resolve from
        // Maven Central, so no extra repository is needed here (unlike the
        // reference project, which pulled the Linphone SDK from
        // download.linphone.org).
    }
}

rootProject.name = "EmakRTCPhone"
include(":app")
