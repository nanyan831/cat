plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.catlifepet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.catlifepet"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            val apiBaseUrl = providers.gradleProperty("CATLIFEPET_API_BASE_URL")
                .orElse("http://127.0.0.1:8080/")
                .get()
                .let { if (it.endsWith('/')) it else "$it/" }
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        getByName("release") {
            val apiBaseUrl = providers.gradleProperty("CATLIFEPET_RELEASE_API_BASE_URL")
                .orElse("https://api.catlifepet.invalid/")
                .get()
                .let { if (it.endsWith('/')) it else "$it/" }
            require(apiBaseUrl.startsWith("https://")) {
                "CATLIFEPET_RELEASE_API_BASE_URL must use HTTPS."
            }
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}
