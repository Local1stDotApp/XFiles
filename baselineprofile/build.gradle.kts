import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Drives the app through its hot paths and records the classes and methods they run into
// app/src/release/generated/baselineProfiles. Run: ./gradlew :app:generateBaselineProfile
android {
    namespace = "app.local1st.files.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Profile capture needs API 33+, or a rooted device on API 28-32.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    // A Gradle-managed emulator, so generation doesn't depend on (or disturb) whatever phone
    // is plugged in. The Google APIs image is the one Android Studio usually has already.
    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "google"
        }
    }
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
