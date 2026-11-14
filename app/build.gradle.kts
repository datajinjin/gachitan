plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kr.gachitan"
    compileSdk = 35

    // AAOS 표준 Car API(기어, 주행 규제)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.gachitan"
        // Pleos 요건은 minSdk 28 이상(사이닝 키 로테이션). NaviHelper 공식 샘플이 33을 쓰므로 33으로 맞춥니다.
        // Pleos Connect는 Android 14(API 34) 기반이라 호환성 손해가 없습니다.
        minSdk = 33
        targetSdk = 35
        // 심사 요청마다 버전을 올립니다(Pleos 앱 버전 관리 정책).
        versionCode = 9
        versionName = "1.5.0"
    }

    buildTypes {
        release {
            // 공모전 제출용 release APK. 정식 배포 시에는 배포용 서명 키로 서명합니다(보안 가이드).
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper.sdk)     // 여행일지: 목적지·경유지 도착 장소
    implementation(libs.fused.location.sdk)  // 여행일지: 출발·정차 위치
    implementation(libs.kotlin.reflect)      // SdkBridge (공식 샘플과 같은 방식)
    implementation(libs.zxing.core)          // 정산 QR

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
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
