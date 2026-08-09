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
# Retrofit kotlinx converter can (de)serialize models in the R8-minified release build.
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
# Belt-and-braces for our serialized DTOs + domain models (Files + Finance + account).
-keep,includedescriptorclasses class de.ledgerline.app.data.remote.** { *; }
-keep,includedescriptorclasses class de.ledgerline.app.domain.model.** { *; }

# Strip Android logging in release.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
