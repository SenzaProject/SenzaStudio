plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.senzaproject.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.senzaproject.studio"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p1"
    }

    buildTypes {
        release {
            // Signed, minified release build is Phase 7.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-design"))
    implementation(project(":audio-engine"))
    implementation(project(":feature-studio"))
    implementation(project(":feature-equalizer"))
    implementation(project(":feature-spatial"))
    implementation(project(":feature-presets"))
    implementation(project(":feature-settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
