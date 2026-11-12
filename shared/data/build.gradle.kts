import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.3.3")
            api(project(":shared:domain"))
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.datetime)
            implementation(libs.firebase.app)
            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.3.3") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.3.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
    }
}

android {
    namespace = "pl.flipfocus.data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
