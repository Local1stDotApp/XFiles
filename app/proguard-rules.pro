# Apache Commons Compress optional codecs resolved reflectively
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
-keep class org.tukaani.xz.** { *; }

# Shizuku's provider is instantiated by the framework, and P1 invokes the compiled AIDL
# interfaces directly because Shizuku.newProcess is private in API 13.1.5.
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class moe.shizuku.server.** { *; }
