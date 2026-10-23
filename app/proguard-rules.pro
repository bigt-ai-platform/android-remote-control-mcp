# R8 / resource-shrinking keep rules for release builds.
#
# The release build enables minification and resource shrinking (see app/build.gradle.kts).
# AndroidX / Compose / Hilt / DataStore / WorkManager ship their own consumer rules, so this
# file only covers the JNI bridges and the reflection-heavy libraries that cannot be
# discovered statically. Everything else is intentionally left shrinkable.

# Keep annotations + generic signatures: kotlinx.serialization, Jackson and Ktor inspect them.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# This is an open-source app: keep readable class/method names in stack traces and crash
# reports (R8 still shrinks unused code and resources). The size cost is well under 1 MB.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Preserve the name of every class declaring a native method (JNI looks methods up by name).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# JNI bridges
# ---------------------------------------------------------------------------

# ONNX Runtime: JNI resolves ai.onnxruntime.* classes from native code. The AAR only ships
# telemetry keep rules, so keep the runtime surface here (privacy-mode PII detection).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ngrok-java: native session/forwarder classes are looked up from the Rust JNI layer.
-keep class com.ngrok.** { *; }
-dontwarn com.ngrok.**

# ---------------------------------------------------------------------------
# Reflection / JCA providers
# ---------------------------------------------------------------------------

# BouncyCastle is used through the JCA provider, which instantiates algorithm classes by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Jackson is pulled by java-jwt (JWT payload binding) and is instantiated via ServiceLoader.
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Ktor server on Netty: Netty touches its own classes reflectively (platform detection,
# thread factories, optional native transports).
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**

# ktor-server-core depends on kotlin-reflect (config loading). Keep it intact.
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# Ktor server/client: the plugin pipeline and engine factories are wired through reflection and
# kotlinx.serialization, so R8's full mode over-shrinks the pipeline (the server binds but drops
# every request). Keep the runtime surface, and keep the ServiceLoader interfaces (below) so
# discovery files stay valid.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep interface kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class io.ktor.server.config.HoconConfigLoader { *; }
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-dontwarn coil3.**

# MCP Kotlin SDK: tools/prompts are serialized through kotlinx.serialization and the server
# dispatches on SDK classes; keep the SDK surface to avoid breaking tool discovery.
-keep class io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.danielealbano.androidremotecontrolmcp.**$$serializer { *; }
-keepclassmembers class com.danielealbano.androidremotecontrolmcp.** {
    *** Companion;
}
-keepclasseswithmembers class com.danielealbano.androidremotecontrolmcp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Optional dependencies referenced by the libraries above (never on Android).
# ---------------------------------------------------------------------------
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.typesafe.config.**
-dontwarn lombok.**
