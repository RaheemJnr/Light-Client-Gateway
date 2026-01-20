# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/raheemjnr/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep the JNI bridge class and all its members (external funs)
-keep class com.nervosnetwork.ckblightclient.LightClientNative {
    *;
}

# Keep the JNI callback interface and its methods
# Native code calls "onStatusChange" by name, so it MUST NOT be renamed.
-keep interface com.nervosnetwork.ckblightclient.LightClientNative$StatusCallback {
    *;
}

# Ensure Kotlinx Serialization works correctly with R8
-keepattributes *Annotation*, InnerClasses
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ref.Reference

# Keep the data classes used in API models just in case serialization needs them via reflection
-keep class com.rjnr.pocketnode.data.gateway.models.** { *; }
