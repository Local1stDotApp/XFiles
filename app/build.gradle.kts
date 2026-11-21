import java.util.Properties
import javax.inject.Inject
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations

/** The SDK's CMake package for libxfarchive; AGP installs it on first build. */
val CMAKE_VERSION = "3.31.6"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// versionName and versionCode are human-set in version.properties, so building a release tag
// reproduces that release's versionCode — which is how F-Droid builds it. CI passes -PbuildNumber
// for the rolling prerelease builds in between (see .github/workflows/release.yml).
val versionProperties = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val appVersionName = versionProperties.getProperty("versionName", "1.0")
val appBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
    ?: versionProperties.getProperty("versionCode")?.toIntOrNull()
    ?: 1

// Release signing comes from env (CI injects it from GitHub secrets). Absent locally → unsigned.
val ciKeystore: String? = System.getenv("XFILES_KEYSTORE")

android {
    namespace = "app.local1st.files"
    compileSdk = 37
    // libxfarchive, the native archive reader (src/main/cpp). F-Droid's recipe pins the same NDK.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.local1st.files"
        minSdk = 26
        targetSdk = 37
        versionCode = appBuildNumber
        versionName = appVersionName
    }

    // F-Droid ships only free software and counts junrar's UnRAR licence as non-free. Both
    // flavors read archives natively; the full flavor also keeps junrar for what libarchive
    // can't read, archives made by RAR 1.x. GitHub releases and Google Play ship the full
    // flavor. See docs/fdroid.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        create("fdroid") {
            dimension = "distribution"
        }
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
        aidl = true
        buildConfig = true
        compose = true
    }

    // Generate Android 13+ "App language" settings from the locales that this app ships.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en", "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko",
            "nl", "pl", "pt-rBR", "ru", "tr", "vi", "zh-rCN", "zh-rTW",
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = CMAKE_VERSION
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md}"
            // Payloads no code can read — resource shrinking only covers res/, so these ride
            // along dead: ARSCLib's bundled framework tables (we drive its chunk classes
            // directly, never its decoder), the API-level database belonging to the R8
            // internals the vendored bundletool drops, bundletool's AndroidX migration map,
            // and the .proto sources that protobuf and bundletool ship for reference — the
            // runtime reads descriptors compiled into the generated classes instead.
            excludes += listOf(
                "/frameworks/**",
                "/api_database/**",
                "/shadow/bundletool/com/android/support/migrateToAndroidx/**",
                "**/*.proto",
                "/*.proto",
            )
        }
    }
}

// One Baseline Profile serves both flavors. Without this the plugin reads and regenerates one per
// variant under src/<variant>/generated, which no flavor has, so the app's profile would not ship.
baselineProfile {
    mergeIntoMain = true
}

androidComponents {
    // GitHub and F-Droid ship one APK with libxfarchive for four ABIs; compressed, they cost
    // half as much to download. Installing extracts just the device's own ABI.
    onVariants { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
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

    // RAR 1.x archives only (see productFlavors above): junrar is pure Java but under the UnRAR
    // licence.
    "fullImplementation"(libs.junrar)
    // Carries bundletool and ARSCLib, both relocated under app.local1st.files.vendor.
    implementation(project(path = ":vendor:bundletool-shaded", configuration = "shadedRuntimeElements"))

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Installs the Baseline Profile on devices that don't get it from the store (sideloaded
    // APKs, Android 9-12); ART then compiles those paths ahead of time instead of running them
    // interpreted until the JIT catches up. Regenerate with :app:generateBaselineProfile.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
    // Writes the archives the tests read back.
    testImplementation(libs.commons.compress)
    testImplementation(libs.xz)
}

/**
 * Builds libxfarchive for the machine running the JVM unit tests, from the same CMake project as
 * the app's, so the tests exercise the native readers. CMake comes from the SDK (installed by the
 * Android build) or the PATH; jni.h comes from the NDK, whose copy stands alone on any host.
 */
abstract class HostNativeBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val sdkDir: DirectoryProperty

    @get:Input
    abstract val cmakeVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jniHeader: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val sdkCmake = sdkDir.get().asFile.resolve("cmake/${cmakeVersion.get()}/bin")
        val cmake = sdkCmake.resolve("cmake").takeIf { it.canExecute() }?.path ?: "cmake"
        val ninja = sdkCmake.resolve("ninja").takeIf { it.canExecute() }
        val work = temporaryDir
        val jniDir = work.resolve("jni").apply { mkdirs() }
        jniHeader.get().asFile.copyTo(jniDir.resolve("jni.h"), overwrite = true)
        val buildDir = work.resolve("build")
        exec.exec {
            commandLine(
                buildList {
                    add(cmake)
                    // --fresh: the cache remembers the host SDK path, which an Xcode update removes.
                    addAll(listOf("--fresh", "-S", sourceDir.get().asFile.path, "-B", buildDir.path))
                    if (ninja != null) addAll(listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${ninja.path}"))
                    add("-DCMAKE_BUILD_TYPE=Release")
                    add("-DXF_HOST_BUILD=ON")
                    add("-DXF_JNI_INCLUDE_DIRS=${jniDir.path}")
                },
            )
        }
        exec.exec { commandLine(cmake, "--build", buildDir.path) }
        val out = outputDir.get().asFile
        out.listFiles()?.forEach { it.delete() }
        buildDir.listFiles { f -> f.name.startsWith("libxfarchive.") }?.forEach {
            it.copyTo(out.resolve(it.name), overwrite = true)
        }
    }
}

val hostNativeDir = layout.buildDirectory.dir("intermediates/hostNative")
val buildHostNative = tasks.register<HostNativeBuild>("buildHostNative") {
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    sdkDir.set(androidComponents.sdkComponents.sdkDirectory)
    cmakeVersion.set(CMAKE_VERSION)
    jniHeader.set(
        androidComponents.sdkComponents.ndkDirectory.map { ndk ->
            // One prebuilt toolchain per NDK: darwin-x86_64, linux-x86_64 or windows-x86_64.
            val host = ndk.asFile.resolve("toolchains/llvm/prebuilt").listFiles()!!.first { it.isDirectory }
            ndk.file("toolchains/llvm/prebuilt/${host.name}/sysroot/usr/include/jni.h")
        },
    )
    outputDir.set(hostNativeDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    val nativeDir = hostNativeDir
    inputs.dir(nativeDir)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Djava.library.path=${nativeDir.get().asFile.absolutePath}",
            "--enable-native-access=ALL-UNNAMED",
        )
    }
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
