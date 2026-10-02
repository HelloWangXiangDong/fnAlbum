plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fnalbum.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fnalbum.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("fnalbum.keystore")
            storePassword = "fnalbum"
            keyAlias = "fnalbum"
            keyPassword = "fnalbum"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.annotation:annotation:1.8.2")
    // 仅核心编码库，用于把扫码登录地址画成二维码（约 500KB，无 UI 依赖）
    implementation("com.google.zxing:core:3.5.3")
}
