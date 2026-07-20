plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.qdreaderexporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.qdreaderexporter"
        // Broader install base; module still intended for Android 12+ usage
        minSdk = 27
        targetSdk = 34
        versionCode = 9
        versionName = "0.3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // Removed custom package-id 0x64: some LSPosed managers fail to parse module meta
    // when resources are not under the default 0x7f package id.

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// YukiHookAPI 1.3.2 AAR metadata asks for compileSdk 37; local/CI may only have 35.
tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) {
        enabled = false
    }
}

// Fail fast if Xposed entry asset is missing from the built APK (clean CI regression guard).
afterEvaluate {
    tasks.register("verifyXposedAssets") {
        dependsOn("packageDebug")
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
            val apk = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
                ?: error("Debug APK not found under ${apkDir.absolutePath}")
            val listing = providers.exec {
                commandLine("unzip", "-l", apk.absolutePath)
            }.standardOutput.asText.get()
            check(listing.contains("assets/xposed_init")) {
                "APK missing assets/xposed_init — LSPosed will not load this module: ${apk.name}"
            }
            check(listing.contains("META-INF/yukihookapi_init")) {
                "APK missing META-INF/yukihookapi_init: ${apk.name}"
            }
            logger.lifecycle("Verified Xposed assets in ${apk.name}")
        }
    }
    tasks.named("assembleDebug") {
        finalizedBy("verifyXposedAssets")
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
