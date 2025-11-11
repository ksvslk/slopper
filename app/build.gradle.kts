import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.indrek.slopper"
    compileSdk {
        version = release(36)
    }

    assetPacks += listOf(":gemma3_1b_it", ":gemma3_270m", ":qwen2_5")

    defaultConfig {
        applicationId = "com.indrek.slopper"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    implementation(libs.integrity)
    implementation(libs.play.services.time)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
