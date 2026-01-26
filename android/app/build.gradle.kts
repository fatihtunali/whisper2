plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.whisper2.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whisper2.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Default BuildConfig values (production)
        buildConfigField("String", "WS_URL", "\"wss://whisper2.aiakademiturkiye.com:3051/ws\"")
        buildConfigField("String", "API_BASE_URL", "\"https://whisper2.aiakademiturkiye.com:3051\"")
        buildConfigField("String", "TEST_MNEMONIC_1", "\"\"")
        buildConfigField("String", "TEST_MNEMONIC_2", "\"\"")
        buildConfigField("String", "TEST_DEVICE_ID_A", "\"\"")
        buildConfigField("String", "TEST_DEVICE_ID_B", "\"\"")
        buildConfigField("boolean", "IS_CONFORMANCE_BUILD", "false")
    }

    // Product flavors for different environments
    flavorDimensions += "environment"
    productFlavors {
        create("production") {
            dimension = "environment"
            // Uses default values
        }
        create("stagingConformance") {
            dimension = "environment"
            applicationIdSuffix = ".conformance"
            versionNameSuffix = "-conformance"

            // Conformance test configuration
            buildConfigField("String", "WS_URL", "\"wss://whisper2.aiakademiturkiye.com:3051/ws\"")
            buildConfigField("String", "API_BASE_URL", "\"https://whisper2.aiakademiturkiye.com:3051\"")
            buildConfigField("String", "TEST_MNEMONIC_1", "\"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about\"")
            buildConfigField("String", "TEST_MNEMONIC_2", "\"zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong\"")
            buildConfigField("String", "TEST_DEVICE_ID_A", "\"conformance-device-a-00000001\"")
            buildConfigField("String", "TEST_DEVICE_ID_B", "\"conformance-device-b-00000002\"")
            buildConfigField("boolean", "IS_CONFORMANCE_BUILD", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose (2026 BOM)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room (Local Database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Crypto - LazySodium (libsodium for Android - server compatible)
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Crypto for JVM tests
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.14.0")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Firebase (Push Notifications)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.3.0")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Telephony/Calls
    implementation("androidx.core:core-telecom:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
