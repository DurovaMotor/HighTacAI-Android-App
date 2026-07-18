package com.example.deepchatdemo.platform.integration

import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.LightStatus
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.Tag

object PlatformLightMapper {
    fun bindings(snapshot: Iterable<Binding>): List<LightBinding> {
        return snapshot
            .filter { it.isActive && it.unboundAt == null }
            .sortedWith(
                compareByDescending<Binding> { it.boundAt }
                    .thenBy { it.id }
            )
            .map { binding ->
                LightBinding(
                    id = binding.id.toString(),
                    itemCode = binding.productCode,
                    itemName = binding.productName,
                    tagId = binding.tagId,
                    stationId = binding.stationId,
                    createdAtMillis = binding.boundAt.toEpochMilli(),
                    updatedAtMillis = binding.boundAt.toEpochMilli()
                )
            }
    }

    fun statuses(snapshot: Iterable<Tag>): Map<String, LightStatus> {
        return snapshot.mapNotNull { tag ->
            val stationId = tag.stationId ?: return@mapNotNull null
            tag.tagId to LightStatus(
                tagId = tag.tagId,
                stationId = stationId,
                version = tag.firmwareVersion,
                batteryVoltage = tag.batteryVoltage,
                batteryLevel = tag.batteryLevel,
                group = tag.groupNo,
                onlineAtMillis = tag.lastSeenAt?.toEpochMilli(),
                lastResultType = tag.lastResultType
            )
        }.toMap()
    }
}
