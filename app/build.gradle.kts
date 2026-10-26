import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appProps = Properties().apply {
    rootProject.file("app.properties").reader(Charsets.UTF_8).use { load(it) }
}

fun xmlSafe(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace("\"", "\\\"")

val appName: String = appProps.getProperty("APP_NAME", "My VPN").trim()
val adminName: String = appProps.getProperty("ADMIN_NAME", "$appName Admin").trim()
val appId: String = appProps.getProperty("APP_ID", "com.myvpn.app").trim()
val configBranch: String = appProps.getProperty("CONFIG_BRANCH", "main").trim()
val configRepo: String = (findProperty("configRepo") as String?)
    ?: System.getenv("GITHUB_REPOSITORY")
    ?: appProps.getProperty("CONFIG_REPO", "")
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val backendUrl: String = System.getenv("BACKEND_URL")?.trim()?.takeIf { it.startsWith("https://") }
    ?: appProps.getProperty("BACKEND_URL", "").trim()

// Ship a copy of remote/config.json inside the app, used until the live one can be downloaded.
val bundledConfigDir: File = layout.buildDirectory.dir("generated/bundledConfig").get().asFile
bundledConfigDir.mkdirs()
rootProject.file("remote/config.json").takeIf { it.exists() }
    ?.copyTo(File(bundledConfigDir, "bundled_config.json"), overwrite = true)

android {
    namespace = "app.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 37
        versionCode = appProps.getProperty("VERSION_CODE", "1").trim().toInt()
        versionName = appProps.getProperty("VERSION_NAME", "1.0.0").trim()

        buildConfigField("String", "CONFIG_REPO", "\"$configRepo\"")
        buildConfigField("String", "CONFIG_BRANCH", "\"$configBranch\"")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // A single "client" flavor: admin is now built into the main app (sign in as admin from the login screen).
    flavorDimensions.add("role")
    productFlavors {
        create("client") {
            dimension = "role"
            resValue("string", "app_name", xmlSafe(appName))
            buildConfigField("boolean", "IS_ADMIN", "false")
        }
    }

    sourceSets {
        getByName("client") {
            assets.srcDir(bundledConfigDir)
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Xray core (downloaded by the build workflow into app/libs). Only the public app needs it.
    "clientImplementation"(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    // WebSocket client for instant settings updates.
    "clientImplementation"(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // Google Sign-In via Credential Manager (ID-token flow; no client secret involved).
    // Only the public client app signs in with Google.
    "clientImplementation"("androidx.credentials:credentials:1.3.0")
    "clientImplementation"("androidx.credentials:credentials-play-services-auth:1.3.0")
    "clientImplementation"("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
