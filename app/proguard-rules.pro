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
