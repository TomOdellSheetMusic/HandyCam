import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Load release signing configuration from keystore.properties (created by CI from
// GitHub secrets) or from environment variables. When absent, release builds fall
// back to the debug keystore so local builds keep working out of the box.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun signingStoreFile(): File? {
    keystoreProperties["storeFile"]?.let { return file(it as String) }
    System.getenv("ANDROID_KEYSTORE_PATH")?.let { return file(it) }
    return null
}

android {
    namespace = "com.example.handycam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.handycam"
        minSdk = 29
        targetSdk = 36
        // Version is stamped by CI from the release tag / nightly build. Falls back
        // to sensible defaults for local builds.
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = signingStoreFile()
            storePassword = keystoreProperties["storePassword"] as String?
                ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = keystoreProperties["keyAlias"] as String?
                ?: System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = keystoreProperties["keyPassword"] as String?
                ?: System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only sign with the release keystore when one is configured; otherwise
            // fall back to the debug keystore so local builds still succeed.
            signingConfig = if (signingStoreFile() != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.appcompat)
    
    // CameraX for camera capture
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // Lifecycle components
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    
    // Hilt for dependency injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    
    // DataStore for preferences
    implementation(libs.androidx.datastore.preferences)
    
    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    
    // Compose Foundation (HorizontalPager, TabRow) — included via BOM
    implementation(libs.androidx.compose.material.icons.extended)
    
    // HTTP control server
    // Align Netty modules to a release containing the TLS and IP filter fixes.
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
