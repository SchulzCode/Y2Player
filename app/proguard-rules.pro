# Android entry points are referenced from the manifest and retained by R8.
#
# Persisted enums are written as their stable `storageId`, but `fromStorage`
# also accepts `Enum.name` as a fallback. Keeping the names makes that fallback
# mean what it says under R8; without it the comparison would be against
# obfuscated names and would silently never match.
-keepnames enum com.schulzcode.y2player.core.model.RepeatMode
-keepnames enum com.schulzcode.y2player.core.model.TrackSortOrder
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
