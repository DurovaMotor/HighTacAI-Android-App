package com.example.deepchatdemo.light.protocol

object EStationTopics {
    enum class Kind(val path: String) {
        Result("result"),
        Heartbeat("heartbeat"),
        Task("task"),
        Bind("bind"),
        Group("group"),
        Ota("ota")
    }

    data class ParsedTopic(
        val stationId: String,
        val kind: Kind
    )

    fun resultTopic(stationId: String): String = topic(stationId, Kind.Result)

    fun heartbeatTopic(stationId: String): String = topic(stationId, Kind.Heartbeat)

    fun taskTopic(stationId: String): String = topic(stationId, Kind.Task)

    fun bindTopic(stationId: String): String = topic(stationId, Kind.Bind)

    fun groupTopic(stationId: String): String = topic(stationId, Kind.Group)

    fun otaTopic(stationId: String): String = topic(stationId, Kind.Ota)

    fun subscriptionTopics(stationId: String): List<String> {
        EStationValidators.requireStationId(stationId)
        return listOf(resultTopic(stationId), heartbeatTopic(stationId))
    }

    fun parse(topic: String): ParsedTopic? {
        val parts = topic.split("/")
        if (parts.size != 4 || parts[0].isNotEmpty() || parts[1] != "estation") {
            return null
        }

        val stationId = parts[2]
        if (!EStationValidators.isValidStationId(stationId)) return null

        val kind = Kind.entries.firstOrNull { it.path == parts[3] } ?: return null
        return ParsedTopic(stationId = stationId, kind = kind)
    }

    fun isExpectedTopic(topic: String, stationId: String, kind: Kind): Boolean {
        EStationValidators.requireStationId(stationId)
        return parse(topic) == ParsedTopic(stationId = stationId, kind = kind)
    }

    private fun topic(stationId: String, kind: Kind): String {
        val normalized = EStationValidators.requireStationId(stationId)
        return "/estation/$normalized/${kind.path}"
    }
}
