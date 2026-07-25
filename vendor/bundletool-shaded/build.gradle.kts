import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

// Packages the Android boot classpath owns. A class shipped under one of them shadows a platform
// type: R8 stops treating it as a library type and minifies it, which is how ARSCLib's copy of
// android.content.res.XmlResourceParser silently broke FileProvider in release builds. Kept out of
// the task's configuration so the check below closes over a plain list, not this script.
val platformPackages = listOf(
    "android/", "dalvik/", "java/", "javax/crypto/", "javax/net/", "javax/security/", "javax/sql/",
    "javax/xml/", "junit/", "org/apache/http/", "org/json/", "org/w3c/dom/", "org/xml/sax/",
    "org/xmlpull/",
)

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.bundletool)
    implementation(libs.arsclib)
    implementation(libs.aapt2.proto)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.guava)
    implementation(libs.failureaccess)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    implementation(libs.jose4j)
    implementation(libs.slf4j)
    implementation(libs.auto.value.annotations)
    implementation(libs.error.prone.annotations)
}

configurations.configureEach {
    // Guava's metadata asks for a newer annotation-only jar; bundletool's ABI set owns this pin.
    resolutionStrategy.force(
        "com.google.errorprone:error_prone_annotations:${libs.versions.errorProneAnnotations.get()}",
    )
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    relocate("com.google.protobuf", "app.local1st.files.vendor.protobuf")
    relocate("com.google.common", "app.local1st.files.vendor.guava")
    relocate("com.google.thirdparty", "app.local1st.files.vendor.guava.thirdparty")
    relocate("dagger", "app.local1st.files.vendor.dagger")
    relocate("javax.inject", "app.local1st.files.vendor.javax.inject")
    relocate("org.jose4j", "app.local1st.files.vendor.jose4j")
    relocate("org.slf4j", "app.local1st.files.vendor.slf4j")
    relocate("com.google.auto.value", "app.local1st.files.vendor.auto.value")
    relocate("com.google.errorprone", "app.local1st.files.vendor.errorprone")
    relocate("com.google.gson", "app.local1st.files.vendor.gson")
    relocate("com.google.j2objc", "app.local1st.files.vendor.j2objc")
    relocate("javax.annotation", "app.local1st.files.vendor.javax.annotation")
    relocate("org.checkerframework", "app.local1st.files.vendor.checkerframework")
    relocate("android.aapt.pb.internal", "app.local1st.files.vendor.aapt.internal")
    relocate("com.reandroid", "app.local1st.files.vendor.arsclib")

    // ARSCLib compiles copies of six platform types into its jar. Off the boot classpath R8 treats
    // them as program classes and minifies them: android.content.res.XmlResourceParser became an
    // interface with no live implementors, so R8 proved every value of that type null and reduced
    // FileProvider.parsePathStrategy to `throw null` — every open-with and share crashed in release.
    // Dropping the copies lets ARSCLib's bytecode link against the interfaces the device provides.
    exclude(
        "android/content/res/XmlResourceParser.class",
        "android/util/AttributeSet.class",
        "org/xmlpull/v1/**",
    )

    // Archive mode carries prebuilt DEX payloads as resources; removing them avoids mixed
    // DEX/class JARs rejected by D8/R8 and intentionally makes ApkBuildMode.ARCHIVE unusable.
    exclude("com/android/tools/build/bundletool/archive/dex/**")
    exclude("META-INF/services/com.android.tools.r8.internal.WE")

    // bundletool ships its own obfuscated R8, whose internals collide case-insensitively
    // (A.class beside a.class). R8 then rejects the whole jar as a duplicate type whenever the
    // build machine's filesystem is case-insensitive, as the macOS CI runner's is. Dagger
    // instantiates D8DexMerger while assembling the BuildApks component, so the public API it
    // links against has to survive; the internals only matter to run a merge, and bundletool
    // merges dex solely for multi-feature-module bundles with minSdk < 21 — device-targeted
    // splits never merge, and universal mode renames shards instead from minSdk 21 up.
    // AabConverter turns the resulting LinkageError into an ordinary install failure.
    // Every collision sits in R8's obfuscated internals: these packages, plus the single-letter
    // classes of the API package. What is left is R8's public API, which is uniquely named.
    listOf("internal", "code", "graph", "shaking", "naming", "synthesis", "utils").forEach {
        exclude("shadow/bundletool/com/android/tools/r8/$it/**")
    }
    exclude("shadow/bundletool/com/android/tools/r8/?.class")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    exclude("META-INF/maven/**")

    // A dependency bump must not sneak another platform class back in: relocate it, or exclude it
    // here if the device already provides that exact type.
    val shadowedRoots = platformPackages
    doLast {
        val shadowed = ZipFile(archiveFile.get().asFile).use { jar ->
            jar.entries().asSequence()
                .map { it.name }
                .filter { name -> name.endsWith(".class") && shadowedRoots.any(name::startsWith) }
                .toList()
        }
        check(shadowed.isEmpty()) {
            "${archiveFileName.get()} shadows Android platform classes: ${shadowed.joinToString()}"
        }
    }
}

val shadedRuntimeElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
    outgoing.artifact(shadowJar)
}
