import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// GOOGLE_WEB_CLIENT_ID (Firebase Authentication > Sign-in method > Google > Web SDK configuration
// > Web client ID) is read from gitignored local.properties or the environment, never committed —
// see docs/adr/0004-anonymous-account-linking.md follow-ups. Left unset, googleSignIn() no-ops.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val googleWebClientId: String =
    (localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: System.getenv("GOOGLE_WEB_CLIENT_ID") ?: "")

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "ComposeApp"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:data"))
            implementation(project(":shared:presentation"))
            implementation("org.jetbrains.compose.runtime:runtime:1.10.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.10.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("org.jetbrains.compose.ui:ui:1.10.1")
            implementation("org.jetbrains.compose.components:components-resources:1.10.1")
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
        }
        androidMain.dependencies {
            implementation(libs.android.activity.compose)
            implementation(libs.android.core)
            implementation(libs.android.credentials)
            implementation(libs.android.credentials.play.services)
            implementation(libs.android.googleid)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        val desktopTest by getting {
            dependencies { implementation(compose.desktop.currentOs); implementation("org.jetbrains.compose.ui:ui-test-junit4:1.10.1") }
        }
    }
}

android {
    namespace = "pl.flipfocus.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.flipfocus.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

compose.resources { packageOfResClass = "pl.flipfocus.resources" }
compose.desktop { application { mainClass = "pl.flipfocus.app.MainKt" } }
