plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
}

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.mahiai.vzrtkp"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "3.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ksPath = System.getenv("MAHI_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && rootProject.file(ksPath).exists()) {
                storeFile = rootProject.file(ksPath)
                storePassword = System.getenv("MAHI_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("MAHI_KEY_ALIAS") ?: "mahi"
                keyPassword = System.getenv("MAHI_KEY_PASSWORD") ?: "android"
            } else {
                storeFile = rootProject.file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.appcheck.recaptcha)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
