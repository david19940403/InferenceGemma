plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "hn.fredi.inferencelocal"
    compileSdk = 36

    defaultConfig {
        applicationId = "hn.fredi.inferencelocal"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Forzar extracción de librerías nativas para que LiteRT pueda encontrarlas (Requerido para NPU/QNN)
        manifestPlaceholders["extractNativeLibs"] = "true"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        // ...
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libLiteRt.so"
            pickFirsts += "**/libLiteRtClGlAccelerator.so"
            pickFirsts += "**/libLiteRtGpuAccelerator.so"
            pickFirsts += "**/liblitert_jni.so"
            pickFirsts += "**/liblitert_dispatch.so"
            pickFirsts += "**/liblitert_gpu_delegate.so"
            keepDebugSymbols += "**/libLiteRt*.so"
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "**/attach_hotspot_windows.dll",
                "META-INF/licenses/**",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
            pickFirsts += setOf("META-INF/native-image/**")
        }
    }
}

dependencies {

    implementation(libs.kotlinx.coroutines.android)
    // LiteRT-LM
    implementation(libs.litertlm.android)

    // LiteRT Core and Accelerators
    implementation(libs.litert.core)
    implementation(libs.litert.gpu) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation(libs.litert.qnn)

    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite")
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }

    //implementation(libs.tasks.genai)
    // ── Ktor Server (Netty engine) ────────────────────────────

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.slf4j.api)
    // ── Kotlinx Serialization ─────────────────────────────────
    implementation(libs.kotlinx.serialization.json)

    // ── Logback (requerido por Ktor para logging) ─────────────
    implementation(libs.logback.android)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

