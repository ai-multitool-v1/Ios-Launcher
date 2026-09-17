import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Signing: CI provides credentials through environment variables (GitHub
// secrets). Local builds fall back to the debug key so `assembleRelease`
// always succeeds on a developer machine.
// ---------------------------------------------------------------------------
val keystoreFilePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystoreStorePass: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keystoreKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val keystoreKeyPass: String? = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "org.setbd.cloner"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.setbd.cloner"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // GitHub repository used by the in-app updater (releases are hosted there).
        buildConfigField("String", "GITHUB_REPO", "\"ai-multitool-v1/Ios-Launcher\"")
    }

    signingConfigs {
        if (keystoreFilePath != null && keystoreStorePass != null) {
            create("release") {
                storeFile = rootProject.file(keystoreFilePath)
                storePassword = keystoreStorePass
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreFilePath != null && keystoreStorePass != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Local fallback: sign the release artifact with the debug key.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    testOptions {
        unitTests {
            // ClonerLog uses android.util.Log; unit tests run on the JVM.
            isReturnDefaultValues = true
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
