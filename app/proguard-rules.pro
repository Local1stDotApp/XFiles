# Apache Commons Compress optional codecs resolved reflectively
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
-keep class org.tukaani.xz.** { *; }

# Shizuku's provider is instantiated by the framework, and P1 invokes the compiled AIDL
# interfaces directly because Shizuku.newProcess is private in API 13.1.5.
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class moe.shizuku.server.** { *; }

# GeneratedMessageV3 builds field accessor tables by reflecting on generated getter names.
-keepclassmembers class * extends app.local1st.files.vendor.protobuf.GeneratedMessageV3 {
    public *** get*(...);
    public boolean has*(...);
}
-keepclassmembers class * extends app.local1st.files.vendor.protobuf.GeneratedMessageV3$Builder {
    public *** get*(...);
    public boolean has*(...);
    public *** set*(...);
    public *** add*(...);
    public *** clear*(...);
}

-dontwarn app.local1st.files.vendor.**

# bundletool's embedded R8 is stripped from the shaded jar; D8DexMerger keeps referencing it.
-dontwarn shadow.bundletool.com.android.tools.r8.**
