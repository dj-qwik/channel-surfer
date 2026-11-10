# Channel Surf release shrinking rules.
# Media3, OkHttp, Room, WorkManager and kotlinx-serialization ship consumer rules.
# The app uses only the kotlinx JsonElement tree API (no generated serializers), so no extra keeps are needed.

# WorkManager instantiates workers reflectively by class name.
-keep class com.channelsurf.work.** extends androidx.work.ListenableWorker { <init>(...); }

# Keep source/line info for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Cast framework instantiates the options provider by name from the manifest.
-keep class com.channelsurf.cast.CastOptionsProvider { <init>(); }
