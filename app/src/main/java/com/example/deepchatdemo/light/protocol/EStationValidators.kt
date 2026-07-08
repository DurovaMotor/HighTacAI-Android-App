package com.example.deepchatdemo.light.protocol

object EStationValidators {
    const val STATION_ID_PATTERN = "^90A9F[0-9A-F]{7}$"
    const val TAG_ID_PATTERN = "^AD1[0-9A-F]{9}$"
    const val DEFAULT_TASK_BATCH_SIZE = 20
    const val MAX_TASK_ITEMS_PER_PACKET = 59
    const val MAX_BIND_ITEMS_PER_PACKET = 60
    const val MAX_CLIP_TIME_UNITS = 36
    const val GROUP_ALL = 255
    const val MAX_GROUP = 254
    const val MAX_GROUP_TIME_UNITS = 250

    private val stationIdRegex = Regex(STATION_ID_PATTERN)
    private val tagIdRegex = Regex(TAG_ID_PATTERN)

    fun normalizeStationId(stationId: String): String {
        return stationId.trim().uppercase()
    }

    fun normalizeTagId(tagId: String): String {
        return tagId.trim().uppercase()
    }

    fun isValidStationId(stationId: String): Boolean {
        return stationIdRegex.matches(stationId)
    }

    fun isValidTagId(tagId: String): Boolean {
        return tagIdRegex.matches(tagId)
    }

    fun requireStationId(stationId: String): String {
        require(isValidStationId(stationId)) {
            "Station ID must match $STATION_ID_PATTERN."
        }
        return stationId
    }

    fun requireTagId(tagId: String): String {
        require(isValidTagId(tagId)) {
            "Tag ID must match $TAG_ID_PATTERN."
        }
        return tagId
    }

    fun requireTaskItemCount(count: Int): Int {
        require(count in 1..MAX_TASK_ITEMS_PER_PACKET) {
            "Task item count must be in 1..$MAX_TASK_ITEMS_PER_PACKET."
        }
        return count
    }

    fun requireBindItemCount(count: Int): Int {
        require(count in 1..MAX_BIND_ITEMS_PER_PACKET) {
            "Bind item count must be in 1..$MAX_BIND_ITEMS_PER_PACKET."
        }
        return count
    }

    fun requireClipTimeUnits(timeUnits: Int): Int {
        require(timeUnits in 0..MAX_CLIP_TIME_UNITS) {
            "Clip light time units must be in 0..$MAX_CLIP_TIME_UNITS."
        }
        return timeUnits
    }

    fun durationSecondsToClipTimeUnits(durationSeconds: Int): Int {
        require(durationSeconds > 0) { "Duration seconds must be positive." }
        return ((durationSeconds + 4) / 5).coerceIn(1, MAX_CLIP_TIME_UNITS)
    }

    fun requireGroup(group: Int, allowAll: Boolean = false): Int {
        val max = if (allowAll) GROUP_ALL else MAX_GROUP
        require(group in 0..max) { "Group must be in 0..$max." }
        return group
    }

    fun requireGroupTimeUnits(timeUnits: Int): Int {
        require(timeUnits in 0..MAX_GROUP_TIME_UNITS) {
            "Group time units must be in 0..$MAX_GROUP_TIME_UNITS."
        }
        return timeUnits
    }
}
