# ---- Retrofit + OkHttp (network) ----
# Retrofit builds service impls reflectively from the interface's generic signatures + annotations;
# R8 fullMode strips these by default, which broke on-device pairing in release (NETWORK) while debug
# (no R8) worked. Keep the metadata + the service interfaces.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ---- kotlinx.serialization ----
# Keep @Serializable classes + their generated $serializer / Companion.serializer() so the
# Retrofit kotlinx converter can (de)serialize DTOs in the R8-minified release build.
-keepattributes *Annotation*
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** { static **$Companion *; }
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.ledgerline.app.data.remote.dto.** { *; }

# Strip Android logging in release.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
# JNA / lazysodium need reflection + native mappings preserved.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**

# PdfBox-Android (pure Java) pulls fontbox and references optional java.awt/beans/imageio APIs.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn javax.imageio.**

# MapLibre GL Android (BSD, libre OSM raster map renderer). The AAR ships consumer
# ProGuard rules, but keep the SDK + annotation-plugin + gestures classes (all under
# org.maplibre.android.**) and native JNI entry points to be safe, and silence
# optional-API warnings.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
