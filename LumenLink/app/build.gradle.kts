plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lumenlink"
    compileSdk = 35

    // ── 版本号：唯一来源是 versionName，versionCode 由它推导 ────────────────
    // 历史上 0.20.0 / 0.20.1 都用过 versionCode 25，导致"装了新包却看不出变化"；
    // 另外 1.0.0→1.1.1 跳过了 60，1.3.0→1.3.1 跳过了 65、66。
    // 根因是 versionCode 和 versionName 两套编号在人工并行维护，总有一边漏改。
    // 现在只改下面这一行 `appVersionName`，versionCode 自动算出，物理上不可能再对不齐。
    //
    //   1.5.2  →  1*10_000 + 5*100 + 2  =  10502
    //
    // major 上限 99、minor/patch 上限 99（足够用了）。规则：单调递增，
    // 任何一次版本提升必然产生更大的 versionCode，系统必定接受覆盖安装。
    val appVersionName = "1.5.2"
    val appVersionCode = run {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").find(appVersionName)
            ?: error("versionName 必须形如 x.y.z，当前是 $appVersionName")
        val (major, minor, patch) = m.destructured
        require(major.toInt() <= 99 && minor.toInt() <= 99 && patch.toInt() <= 99) {
            "版本号每段不能超过 99，当前是 $appVersionName"
        }
        major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }

    defaultConfig {
        applicationId = "com.lumenlink"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // pure-algorithm unit tests (NoiseSuppressor / TransientDetector); no Android runtime
    testImplementation("junit:junit:4.13.2")
}























