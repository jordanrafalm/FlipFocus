pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "FlipFocus"
include(":shared:domain", ":shared:data", ":shared:presentation", ":composeApp")
