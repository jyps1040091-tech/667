plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gesturemouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gesturemouse"
        minSdk = 26            // dispatchGesture 的 continued gesture（拖曳）需要 API 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 固定 debug 簽名：每次 GitHub 建出來的 APK 簽名都一樣，才能直接 adb install -r 覆蓋安裝
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // 不要壓縮 .task 模型檔
    androidResources {
        noCompress += "task"
    }
}

// 第一次建置時自動下載 MediaPipe 手部模型（約 8MB）到 assets；已存在就略過
val handModel = file("src/main/assets/hand_landmarker.task")
val downloadHandModel by tasks.registering {
    onlyIf { !handModel.exists() }
    doLast {
        handModel.parentFile.mkdirs()
        val url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        java.net.URL(url).openStream().use { input ->
            handModel.outputStream().use { input.copyTo(it) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 相機
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    // 讓 Service 也能當 LifecycleOwner（CameraX 綁定需要）
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // 手部關鍵點（和電腦版同一套 21 個關節點）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
