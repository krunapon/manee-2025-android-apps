// app/build.gradle.kts - MINIMAL WORKING VERSION

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "th.ac.kkw.tslgovapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "th.ac.kkw.tslgovapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // for mediapipe
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // for mediapipe
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                pickFirsts += listOf("**/libc++_shared.so", "**/libjsc.so")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Camera dependencies - ONLY THESE ARE NEEDED
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.camera:camera-core:1.3.1")

    // AppCompat for your existing Activity-based UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // MediaPipe dependencies (if installed)
    implementation("com.google.mediapipe:solution-core:0.10.14")
    implementation("com.google.mediapipe:hands:0.10.14")

    // Required for MediaPipe
    implementation("com.google.protobuf:protobuf-java:3.21.12")
    implementation("com.google.guava:guava:31.1-android")

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.camera:camera-video:1.2.3")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
}

// Add this outside android block
configurations.all {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    resolutionStrategy {
        force("com.google.protobuf:protobuf-java:3.21.12")
    }
}