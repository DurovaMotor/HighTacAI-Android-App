package com.example.deepchatdemo.config

import com.example.deepchatdemo.BuildConfig

object ApiConfig {
    val MODEL: String
        get() = BuildConfig.OPENAI_MODEL

    val REASONING_EFFORT: String
        get() = BuildConfig.OPENAI_REASONING_EFFORT
}
