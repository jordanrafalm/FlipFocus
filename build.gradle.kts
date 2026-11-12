plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.register("test") {
    group = "verification"
    description = "Runs deterministic shared JVM tests and Compose UI tests."
    dependsOn(":shared:domain:jvmTest", ":shared:data:jvmTest", ":shared:presentation:jvmTest", ":composeApp:desktopTest")
}
