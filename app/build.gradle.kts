plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.qdreaderexporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.qdreaderexporter"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        additionalParameters += listOf(
            "--allow-reserved-package-id",
            "--package-id",
            "0x64"
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// YukiHookAPI 1.3.2 AAR metadata asks for compileSdk 37; local SDK only has 35.
tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) {
        enabled = false
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.yukihookapi.api)
    ksp(libs.yukihookapi.ksp.xposed)
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
