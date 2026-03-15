# ==================== JSch ====================
# JSch uses reflection for cipher, MAC, key exchange, etc.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ==================== App Models ====================
# Data classes used with SharedPreferences JSON serialization
-keep class com.redarrow.proxy.model.** { *; }
-keep class com.redarrow.proxy.ssh.StoredKey { *; }

# ==================== Service ====================
# Foreground service started by Intent action strings
-keep class com.redarrow.proxy.service.TunnelService { *; }

# ==================== General ====================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Remove verbose logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
