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
# The stock libfmjni.so does FindClass("com/mediatek/FMRadio/FMRadioNative") and
# registers its natives against it, so neither the name nor the members survive
# renaming.
-keep class com.mediatek.FMRadio.FMRadioNative { *; }
# Constructed from native code.
-keep class com.schulzcode.y2player.library.FfmpegMetadata {
    <init>(...);
}
