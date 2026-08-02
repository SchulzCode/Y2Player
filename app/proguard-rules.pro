# fromStorage falls back to Enum.name. Without these the fallback compares
# against obfuscated names and silently never matches.
-keepnames enum com.schulzcode.y2player.core.model.RepeatMode
-keepnames enum com.schulzcode.y2player.core.model.TrackSortOrder
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JNI_OnLoad registers by this exact class name.
-keep class com.schulzcode.y2player.playback.NativeAudio { *; }
# Constructed from native code.
-keep class com.schulzcode.y2player.library.FfmpegMetadata {
    <init>(...);
}
