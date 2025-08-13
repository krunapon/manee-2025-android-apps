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
        // Add MediaPipe repositories
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            name = "Sonatype Snapshots"
        }
        maven {
            url = uri("https://maven.google.com")
            name = "Google Maven"
        }
    }
}

rootProject.name = "TSLGovApp"
include(":app")
