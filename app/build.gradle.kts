plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名。
// CI 每次都在全新的容器里编译，AGP 默认的 debug 签名是当场随机生成的，
// 于是每次构建出来的包装在手机上签名都不一样 —— 系统把它当成另一个 App，
// 只能卸载重装。这里改用一把固定的 key，签名就永远一致，
// 新包能直接覆盖旧包升级，数据也不会丢。
//
// 注意：这把 key 不进公开仓库（keystore/ 已被 .gitignore 排除），
// 口令也不写死在这里。本地要出签名包时先设环境变量：
//   DAKA_STORE_PASS / DAKA_KEY_PASS / DAKA_KEY_ALIAS（默认 daka）
val dakaKeystore = rootProject.file("keystore/daka-release.p12")
val hasDakaKeystore = dakaKeystore.exists()

android {
    namespace = "com.daka.reminder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.daka.reminder"
        minSdk = 26
        targetSdk = 34
        // versionCode 必须单调递增，系统靠它判断这是「升级」而不是「降级」。
        // CI 传 GitHub 的构建序号，本机直接构建默认 1。
        versionCode = (System.getenv("DAKA_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("DAKA_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (hasDakaKeystore) {
            create("daka") {
                storeFile = dakaKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("DAKA_STORE_PASS")
                    ?: error("检测到 keystore/daka-release.p12，但没有设置 DAKA_STORE_PASS")
                keyAlias = System.getenv("DAKA_KEY_ALIAS") ?: "daka"
                keyPassword = System.getenv("DAKA_KEY_PASS")
                    ?: System.getenv("DAKA_STORE_PASS")
                    ?: error("检测到 keystore/daka-release.p12，但没有设置 DAKA_KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug 也用同一把 key：本机出的包和 CI 出的包可以互相覆盖
            if (hasDakaKeystore) {
                signingConfig = signingConfigs.getByName("daka")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasDakaKeystore) {
                signingConfig = signingConfigs.getByName("daka")
            }
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

    buildFeatures {
        viewBinding = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
