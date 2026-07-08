package com.example.deepchatdemo.light.protocol

import org.json.JSONArray
import org.json.JSONObject

data class BindTaskData(
    val group: Int,
    val items: List<String>
) {
    init {
        EStationValidators.requireGroup(group)
        EStationValidators.requireBindItemCount(items.size)
        items.forEach { EStationValidators.requireTagId(it) }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("Group", group)
            .put("Items", JSONArray().also { array ->
                items.forEach { array.put(it) }
            })
    }
}

data class GroupData(
    val group: Int,
    val red: Boolean,
    val green: Boolean,
    val blue: Boolean,
    val beep: Boolean,
    val times: Int,
    val flashing: Boolean
) {
    init {
        EStationValidators.requireGroup(group, allowAll = true)
        EStationValidators.requireGroupTimeUnits(times)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("Group", group)
            .put("R", red)
            .put("G", green)
            .put("B", blue)
            .put("Beep", beep)
            .put("Times", times)
            .put("Flashing", flashing)
    }

    companion object {
        fun allOff(): GroupData {
            return GroupData(
                group = EStationValidators.GROUP_ALL,
                red = false,
                green = false,
                blue = false,
                beep = false,
                times = 0,
                flashing = false
            )
        }
    }
}
