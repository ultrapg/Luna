/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

android {
    namespace = "com.ultrapg.luna"
    compileSdk = 35

    androidResources {
        noCompress += listOf("tflite", "f16", "f32", "onnx", "bin")
    }

    defaultConfig {
        applicationId = "com.ultrapg.luna"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            val includeX86 = project.findProperty("includeX86")?.toString()?.toBoolean() == true
            if (includeX86) {
                abiFilters += listOf("arm64-v8a", "x86_64")
            } else {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        create("release") {
            val candidateKeystores = listOfNotNull(
                System.getenv("RELEASE_KEYSTORE")?.let { file(it) },
                file("/home/coder/release.jks"),
                file("/home/coder/.android/release.jks"),
                file("../../release.jks"),
                file("../release.jks"),
                file("${System.getProperty("user.home")}/release.jks"),
                file("${System.getProperty("user.home")}/.android/release.jks")
            )
            val resolvedKeystore = candidateKeystores.firstOrNull { it.exists() }
            if (resolvedKeystore != null) {
                storeFile = resolvedKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "password"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/**/libc++_shared.so",
                "lib/**/libLiteRt.so",
                "lib/**/libonnxruntime.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Accompanist Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // Room Database (Chat History & Persistent Memory)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkHttp for OpenAI-compatible API, DuckDuckGo Search, Web Grounding & MCP SSE/JSON-RPC
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Apache Commons Compress for extracting .tar.bz2 neural TTS voice archives
    implementation("org.apache.commons:commons-compress:1.21")

    // Desert Ant Labs Audio AI Ecosystem (Ear Language ID + Voz STT)
    implementation("ai.desertant:voz:4.0.0")
    implementation("ai.desertant:ear:4.0.0")

    // Sherpa-ONNX Offline Neural TTS Engine (VITS-Piper & Kokoro female voices + custom ONNX replacement)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
