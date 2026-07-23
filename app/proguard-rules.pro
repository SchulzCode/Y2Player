# Android entry points are referenced from the manifest and retained by R8.
# v1.3 stored enum names; newer releases write stable IDs but still reads those legacy names.
-keepnames enum com.schulzcode.y2player.core.model.RepeatMode
-keepnames enum com.schulzcode.y2player.core.model.TrackSortOrder
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
