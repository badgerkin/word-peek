plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wordpeek"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wordpeek"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Release key comes from the environment (GitHub Actions secrets, or a local shell).
        // It is never committed. Debug builds use Android's default per-machine debug key.
        create("release") {
            System.getenv("WORDPEEK_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("WORDPEEK_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("WORDPEEK_KEY_ALIAS")
            keyPassword = System.getenv("WORDPEEK_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("WORDPEEK_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No library dependencies: the app uses only framework APIs (Activity, HttpURLConnection, org.json).
