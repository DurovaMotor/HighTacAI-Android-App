package com.example.deepchatdemo.light.protocol

import com.example.deepchatdemo.light.domain.LightCommandSettings
import com.example.deepchatdemo.light.domain.LightColor
import org.json.JSONArray
import org.json.JSONObject

data class TaskData(
    val time: Int,
    val items: List<TaskItemData>
) {
    init {
        EStationValidators.requireClipTimeUnits(time)
        EStationValidators.requireTaskItemCount(items.size)
        if (time == 0) {
            items.forEach { item ->
                require(!item.beep) { "Light-off tasks must disable beep." }
                require(item.flashing == null) { "Light-off tasks must encode Flashing as null." }
                require(item.colors.all { it.isOff }) { "Light-off tasks must disable all RGB channels." }
            }
        }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("Time", time)
            .put("Items", JSONArray().also { array ->
                items.forEach { array.put(it.toJson()) }
            })
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun singleLightOn(
            tagId: String,
            settings: LightCommandSettings = LightCommandSettings()
        ): TaskData {
            return TaskData(
                time = settings.clipTimeUnits(),
                items = listOf(
                    TaskItemData(
                        tagId = tagId,
                        beep = settings.beep,
                        colors = listOf(settings.color),
                        flashing = settings.flashing
                    )
                )
            )
        }

        fun lightOff(tagIds: List<String>): TaskData {
            return TaskData(
                time = 0,
                items = tagIds.map { TaskItemData.lightOff(it) }
            )
        }

        fun lightOnBatches(
            tagIds: List<String>,
            settings: LightCommandSettings = LightCommandSettings(),
            batchSize: Int = EStationValidators.DEFAULT_TASK_BATCH_SIZE
        ): List<TaskData> {
            require(batchSize in 1..EStationValidators.MAX_TASK_ITEMS_PER_PACKET) {
                "Batch size must be in 1..${EStationValidators.MAX_TASK_ITEMS_PER_PACKET}."
            }
            return tagIds.chunked(batchSize).map { batch ->
                TaskData(
                    time = settings.clipTimeUnits(),
                    items = batch.map { tagId ->
                        TaskItemData(
                            tagId = tagId,
                            beep = settings.beep,
                            colors = listOf(settings.color),
                            flashing = settings.flashing
                        )
                    }
                )
            }
        }
    }
}

data class TaskItemData(
    val tagId: String,
    val beep: Boolean,
    val colors: List<LightColor>,
    val flashing: Boolean?
) {
    init {
        EStationValidators.requireTagId(tagId)
        require(colors.isNotEmpty()) { "Task item colors must not be empty." }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("TagID", tagId)
            .put("Beep", beep)
            .put("Colors", JSONArray().also { array ->
                colors.forEach { array.put(it.toJson()) }
            })
            .put("Flashing", flashing ?: JSONObject.NULL)
    }

    companion object {
        fun lightOff(tagId: String): TaskItemData {
            return TaskItemData(
                tagId = tagId,
                beep = false,
                colors = listOf(LightColor.Off),
                flashing = null
            )
        }
    }
}

private val LightColor.isOff: Boolean
    get() = !red && !green && !blue

private fun LightColor.toJson(): JSONObject {
    return JSONObject()
        .put("R", red)
        .put("G", green)
        .put("B", blue)
}
