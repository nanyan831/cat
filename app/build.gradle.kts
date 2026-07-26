import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("release-signing.properties")
    if (file.isFile) file.inputStream().use { input -> load(input) }
}

fun releaseProperty(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
        ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "com.example.catlifepet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.catlifepet"
        minSdk = 23
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = releaseProperty("CATLIFEPET_RELEASE_STORE_FILE")
            val storePasswordValue = releaseProperty("CATLIFEPET_RELEASE_STORE_PASSWORD")
            val keyAliasValue = releaseProperty("CATLIFEPET_RELEASE_KEY_ALIAS")
            val keyPasswordValue = releaseProperty("CATLIFEPET_RELEASE_KEY_PASSWORD")
            if (!storePath.isNullOrBlank() &&
                !storePasswordValue.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank() &&
                !keyPasswordValue.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
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
    // Room 2.7.x targets Kotlin 2.0, matching this project's compiler toolchain.
    val roomVersion = "2.7.2"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
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
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
