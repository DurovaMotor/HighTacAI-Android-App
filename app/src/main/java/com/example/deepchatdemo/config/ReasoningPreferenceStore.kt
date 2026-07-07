package com.example.deepchatdemo.config

import android.content.Context

class ReasoningPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): ReasoningEffort {
        ensureCurrentDefault()
        val savedValue = preferences.getString(KEY_REASONING_EFFORT, null)
        return ReasoningEffort.fromStoredValue(savedValue) ?: ReasoningEffort.XHIGH
    }

    fun save(effort: ReasoningEffort) {
        preferences.edit()
            .putString(KEY_REASONING_EFFORT, effort.displayName)
            .putInt(KEY_DEFAULT_VERSION, DEFAULT_VERSION)
            .apply()
    }

    private fun ensureCurrentDefault() {
        if (preferences.getInt(KEY_DEFAULT_VERSION, 0) >= DEFAULT_VERSION) return
        preferences.edit()
            .putString(KEY_REASONING_EFFORT, ReasoningEffort.XHIGH.displayName)
            .putInt(KEY_DEFAULT_VERSION, DEFAULT_VERSION)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "hightac_reasoning_preferences"
        const val KEY_REASONING_EFFORT = "reasoning_effort"
        const val KEY_DEFAULT_VERSION = "reasoning_default_version"
        const val DEFAULT_VERSION = 2
    }
}
