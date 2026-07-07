package com.example.deepchatdemo.config

import com.example.deepchatdemo.BuildConfig

object ApiConfig {
    val BASE_URL: String
        get() = BuildConfig.OPENAI_BASE_URL

    val MODEL: String
        get() = BuildConfig.OPENAI_MODEL

    val REASONING_EFFORT: String
        get() = BuildConfig.OPENAI_REASONING_EFFORT

    val apiKey: String
        get() = BuildConfig.OPENAI_API_KEY

    val JIANDAOYUN_BASE_URL: String
        get() = BuildConfig.JIANDAOYUN_BASE_URL

    val jiandaoYunApiKey: String
        get() = BuildConfig.JIANDAOYUN_API_KEY

    val jiandaoYunAppId: String
        get() = BuildConfig.JIANDAOYUN_APP_ID

    val jiandaoYunEntryId: String
        get() = BuildConfig.JIANDAOYUN_ENTRY_ID
}
