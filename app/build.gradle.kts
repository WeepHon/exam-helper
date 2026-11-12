plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.examhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.examhelper"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "1.8.12"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/*.version"
            )
        }
    }
}

dependencies {
    // LSPosed / Xposed API：仅在编译期提供，运行时由 LSPosed 框架注入
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // 本地 OCR（中文识别，模型离线下载后可用）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // PDF 文本提取
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 单元测试（匹配/置信度判定逻辑回归）
    testImplementation("junit:junit:4.13.2")
}
