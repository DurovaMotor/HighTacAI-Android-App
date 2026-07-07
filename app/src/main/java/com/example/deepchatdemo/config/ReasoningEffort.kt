package com.example.deepchatdemo.config

enum class ReasoningEffort(
    val apiValue: String?,
    val displayName: String,
    val glowLevel: Float
) {
    NONE(null, "none", 0.05f),
    MINIMAL("minimal", "minimal", 0.20f),
    LOW("low", "low", 0.35f),
    MEDIUM("medium", "medium", 0.55f),
    HIGH("high", "high", 0.75f),
    XHIGH("xhigh", "xhigh", 1.00f);

    companion object {
        fun fromConfig(): ReasoningEffort {
            return fromValue(ApiConfig.REASONING_EFFORT) ?: MEDIUM
        }

        fun fromStoredValue(value: String?): ReasoningEffort? {
            return fromValue(value)
        }

        private fun fromValue(value: String?): ReasoningEffort? {
            val normalized = value
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            return entries.firstOrNull { effort ->
                effort.displayName == normalized ||
                    effort.apiValue == normalized ||
                    effort.name.lowercase() == normalized
            }
        }
    }
}
