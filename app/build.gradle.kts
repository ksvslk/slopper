import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt") // <-- THE FIX: Apply by ID
    alias(libs.plugins.hilt)        // <-- Apply Hilt
}

android {
    namespace = "com.indrek.slopper"
    compileSdk {
        version = release(36)
    }

    androidResources {
        noCompress.add("tflite")
        noCompress.add("task")
    }

    assetPacks += listOf(":gemma3_1b_it", ":gemma3_270m", ":qwen2_5")

    defaultConfig {
        applicationId = "com.indrek.slopper"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true  // ← Add this line
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}


// In app/build.gradle.kts

dependencies {
    // Keep core and UI libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // Use the 'androidx' prefixed alii

    // as for consistency
    implementation(libs.material)
    implementation(libs.androidx.cardview) // Use the 'androidx' prefixed alias

// --- ADD HILT ---
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler) // This line now works because the plugin is applied

    // --- ADD VIEWMODEL & LIFECYCLE ---
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx) // For 'by viewModels'

    // Keep your other libraries
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.text.recognition)
    implementation(libs.tasks.genai)
    implementation(libs.shimmer)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.asset.delivery.ktx)
    implementation(libs.play.services.tasks)
    implementation(libs.purchases)
    implementation(libs.purchases.ui)
    implementation(libs.play.services.time)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}



kapt {
    correctErrorTypes = true
}