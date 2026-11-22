# JNI entry points are resolved by their class and method names.
-keep class me.lampu.lampcord.shared.voice.NativeVoice { *; }

# Rust core (UniFFI + JNA). JNA reads Pointer.peer and calls back into Java by reflection,
# so R8 must keep its members; otherwise Native.<clinit> fails with UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class uniffi.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
