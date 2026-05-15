import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.android.kitsune"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.android.kitsune"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "5.1.5.26"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ─── GeminiAI API Key Setup ───
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }
        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // ─── FIX: Packaging Options (Menyelesaikan error META-INF) ───
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // --- Core Android ---
    implementation(libs.androidx.core.ktx.v1131)
    implementation(libs.appcompat)
    implementation(libs.material.v1120)
    implementation(libs.constraintlayout)

    // UI Components
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.swiperefreshlayout)

    // --- Lifecycle & Navigation ---
    implementation(libs.androidx.lifecycle.viewmodel.ktx.v270)
    implementation(libs.androidx.lifecycle.livedata.ktx.v270)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // --- Local Data (ROOM & DataStore) ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    annotationProcessor(libs.androidx.room.compiler.v261)
    implementation(libs.androidx.datastore.preferences)

    // --- Google Services & Firebase ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.play.services.location.v2101)
    implementation(libs.play.services.maps.v1820)
    implementation(libs.play.services.auth) // Untuk Google Sign-In

    // --- Google Drive API (Backup) ---
    implementation(libs.google.api.services.drive)
    implementation(libs.google.api.client.android)
    implementation(libs.google.oauth.client.jetty)

    // --- Third-party Libraries ---
    implementation(libs.lottie.v660)
    implementation(libs.glide.v4151)
    annotationProcessor(libs.compiler.v4151)
    implementation(libs.okhttp)
    implementation(libs.gson.v2101)
    implementation(libs.shimmer)
    implementation(libs.yalantis.ucrop)

    // --- Maps (OSM) & Biometrics ---
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.wms.v6110)
    implementation(libs.androidx.biometric)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.v121)
    androidTestImplementation(libs.androidx.espresso.core.v361)
}