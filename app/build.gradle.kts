// Prunoid App Module — Root SDK Component Auditor
// Kotlin DSL | libsu root shell | Material 3 Expressive | single :app module, no DI

import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    // AGP 9.2+ auto-applies kotlin-android — do NOT apply manually
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

val APP_VERSION_NAME = "0.15.0"

android {
    namespace = "io.github.deserthouse.prunoid"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.deserthouse.prunoid"
        minSdk = 31  // Android 12 minimum — full Material You generation, aligned with OptIcon
        targetSdk = 37
        versionCode = 22
        versionName = APP_VERSION_NAME
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // release 签名凭据只从 ~/.gradle/gradle.properties 读取（仓库不落任何默认口令）：
    // SDKPRUNER_STORE_FILE / SDKPRUNER_STORE_PASS / SDKPRUNER_KEY_ALIAS / SDKPRUNER_KEY_PASS
    // 凭据缺失时（如 CI）release 回退 debug 签名，保证可构建
    val hasReleaseSigning = providers.gradleProperty("SDKPRUNER_STORE_FILE").isPresent &&
        providers.gradleProperty("SDKPRUNER_STORE_PASS").isPresent

    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(providers.gradleProperty("SDKPRUNER_STORE_FILE").get())
            storePassword = providers.gradleProperty("SDKPRUNER_STORE_PASS").get()
            keyAlias = providers.gradleProperty("SDKPRUNER_KEY_ALIAS").getOrElse("sdkpruner")
            keyPassword = providers.gradleProperty("SDKPRUNER_KEY_PASS").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

// ━━━ APK Output Naming: Prunoid-vX.Y.Z-YYYYMMDD-HHMMSS.apk ━━━
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val buildTime = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            output.outputFileName.set("Prunoid-v$APP_VERSION_NAME-$buildTime.apk")
        }
    }
}

dependencies {
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // 批P：LSPosed 模块身份（仅编译，不入包）
    compileOnly("de.robv.android.xposed:api:82")
    // ━━━ Root shell (libsu, topjohnwu, Apache-2.0) ━━━
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    // ━━━ AndroidX Core ━━━
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    // ━━━ Material 3 (Compose) with Expressive APIs ━━━
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material-icons-extended")

    // ━━━ Navigation ━━━
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // ━━━ Image Loading (Coil 2.x for Compose) ━━━
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ━━━ Serialization (rule schema) + Preferences (DataStore) ━━━
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // ━━━ Coroutines ━━━
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // ━━━ Networking (rule subscription, M2) ━━━
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ━━━ Debug ━━━
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ━━━ Unit tests ━━━
    testImplementation("junit:junit:4.13.2")
}
