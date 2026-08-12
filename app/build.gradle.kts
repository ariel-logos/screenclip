import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Guarded: an unconditional signingConfigs.getByName("release") throws at configuration
// time, which would break assembleDebug too on a machine without the keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "dev.screenclip"
    compileSdk = 35

    defaultConfig {
        // Never add an applicationIdSuffix: res/xml/shortcuts.xml hardcodes
        // targetPackage="dev.screenclip", and a suffix would break the launcher
        // long-press shortcut with no error and no log line.
        applicationId = "dev.screenclip"
        // 30 keeps the code free of compat branches: WindowMetrics, clipOutRect
        // and the display-cutout layout mode are all API 30 APIs.
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false // pointless above API 24
                enableV2Signing = true
                enableV3Signing = true // what API 30+ actually verifies with
                enableV4Signing = false // would emit a separate .apk.idsig to carry around
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Not optional. Omitting this line does NOT mean "no default rules": AGP
            // silently applies proguard-android.txt, which contains -dontoptimize, and
            // the dex comes out twice the size with no warning anywhere.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
