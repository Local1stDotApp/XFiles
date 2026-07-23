import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

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

    // Archive mode carries prebuilt DEX payloads as resources; removing them avoids mixed
    // DEX/class JARs rejected by D8/R8 and intentionally makes ApkBuildMode.ARCHIVE unusable.
    exclude("com/android/tools/build/bundletool/archive/dex/**")
    exclude("META-INF/services/com.android.tools.r8.internal.WE")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    exclude("META-INF/maven/**")
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
