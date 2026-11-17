# JNI entry points implemented in Rust (core/src/jni.rs): R8 must not rename or strip them.
-keepclasseswithmembernames class app.dnsly.core.NativeCore {
    native <methods>;
}

# Passed to Rust as a GlobalRef; the forwarder calls onQueryTimeout() through JNI.
-keep class app.dnsly.core.ForwarderListener {
    *;
}
