# The Android libraries used by the app ship their own consumer rules. The rules below are for
# the SMB client (smbj and its RPC add-on), which are plain Java libraries without any.

# Packets are dispatched to handler methods found by reflection (mbassador's @Handler).
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep class com.hierynomus.** { *; }

# The FFmpeg decoders: their native code reaches back into these classes by name.
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }

# Optional features that are never used on Android: Kerberos sign-in, expression language
# filters of the event bus, and the JVM-only parts of the helper libraries.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn org.slf4j.impl.**
-dontwarn java.lang.management.**

# The RPC library throws java.rmi exceptions for malformed answers. Android has no java.rmi;
# SmbClient treats the resulting error like any other failed request.
-dontwarn java.rmi.MarshalException
-dontwarn java.rmi.UnmarshalException
