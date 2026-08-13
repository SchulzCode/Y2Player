package com.schulzcode.y2player.core.model

enum class AlbumSortOrder(val storageId: String) {
    TITLE("title"),
    ARTIST("artist"),
    YEAR_ASCENDING("year_ascending"),
    YEAR_DESCENDING("year_descending");

    companion object {
        fun fromStorage(value: String?): AlbumSortOrder = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: TITLE
    }
}

enum class YearSortOrder(val storageId: String) {
    NEWEST_FIRST("newest_first"),
    OLDEST_FIRST("oldest_first");

    companion object {
        fun fromStorage(value: String?): YearSortOrder = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: NEWEST_FIRST
    }
}
