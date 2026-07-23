import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// versionName is human-set in version.properties; the build number (versionCode) is passed by
// CI via -PbuildNumber and increments on every push. Defaults keep local builds working.
val appVersionName = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("versionName", "1.0")
val appBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

// Release signing comes from env (CI injects it from GitHub secrets). Absent locally → unsigned.
val ciKeystore: String? = System.getenv("XFILES_KEYSTORE")

android {
    namespace = "app.local1st.files"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.local1st.files"
        minSdk = 26
        targetSdk = 37
        versionCode = appBuildNumber
        versionName = appVersionName
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("release") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("XFILES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XFILES_KEY_ALIAS")
                keyPassword = System.getenv("XFILES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (ciKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Generate Android 13+ "App language" settings from the locales that this app ships.
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(project(path = ":vendor:bundletool-shaded", configuration = "shadedRuntimeElements"))
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.arsclib)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("xfiles.repo", rootDir.absolutePath)
    val sdkDir = rootProject.file("local.properties").takeIf { it.exists() }?.let { propertiesFile ->
        Properties().apply { propertiesFile.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
    sdkDir?.let { File(it, "build-tools/37.0.0") }
        ?.let { buildTools -> listOf("aapt2", "aapt2.exe").map { File(buildTools, it) } }
        ?.firstOrNull { it.isFile }
        ?.let { systemProperty("xfiles.aapt2", it.absolutePath) }
    testLogging.showStandardStreams = true
}
