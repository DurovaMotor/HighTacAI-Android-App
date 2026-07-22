package com.example.deepchatdemo.catalog

import android.os.SystemClock
import android.util.Log
import com.example.deepchatdemo.cloud.OpenAiRelayTransport
import com.example.deepchatdemo.config.ApiConfig
import com.example.deepchatdemo.config.ReasoningEffort
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SearchPlanApi(
    private val transport: OpenAiRelayTransport
) {
    suspend fun createSearchPlan(
        rawQuery: String,
        reasoningEffort: ReasoningEffort = ReasoningEffort.fromConfig()
    ): SearchPlan = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "SearchPlan request start: query=${rawQuery.toLogSummary(80)}, " +
                "reasoningEffort=${reasoningEffort.displayName}, " +
                "reasoningIncluded=${reasoningEffort.apiValue != null}"
        )

        try {
            val requestJson = buildRequestJson(rawQuery, reasoningEffort)
            val responseText = executeRequest(requestJson)
            val plan = SearchPlan.fromJson(responseText)
            Log.d(
                TAG,
                "SearchPlan request success: ${plan.toLogSummary()}, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            plan
        } catch (error: Exception) {
            Log.e(
                TAG,
                "SearchPlan request failed: elapsedMs=${SystemClock.elapsedRealtime() - startedAt}, " +
                    "errorType=${error.javaClass.simpleName}, errorMessage=${error.toLogMessage()}"
            )
            throw error
        }
    }

    private fun executeRequest(requestJson: JSONObject): String {
        var lastError: IOException? = null
        repeat(REQUEST_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "SearchPlan HTTP attempt ${attempt + 1}/$REQUEST_ATTEMPTS start")
                return executeSingleRequest(requestJson)
            } catch (error: IOException) {
                lastError = error
                Log.e(
                    TAG,
                    "SearchPlan HTTP attempt ${attempt + 1}/$REQUEST_ATTEMPTS failed: " +
                        "errorType=${error.javaClass.simpleName}, " +
                        "errorMessage=${error.toLogMessage()}"
                )
                if (attempt == REQUEST_ATTEMPTS - 1 || !error.isRetryableRequestError()) {
                    throw error
                }
                sleepBeforeRetry(attempt)
            }
        }
        throw lastError ?: IOException("SearchPlan request failed.")
    }

    private fun executeSingleRequest(requestJson: JSONObject): String {
        val callStartedAt = SystemClock.elapsedRealtime()
        val response = transport.postResponses(requestJson.toString())
        Log.d(
            TAG,
            "SearchPlan HTTP response: statusCode=${response.statusCode}, " +
                "elapsedMs=${SystemClock.elapsedRealtime() - callStartedAt}"
        )
        if (response.statusCode != 200) {
            throw IOException(readApiError(response.statusCode, response.body))
        }
        return runCatching { readResponseText(response.body) }
            .getOrElse { error -> throw IOException(NO_RESPONSE_TEXT_MESSAGE, error) }
            .ifBlank { throw IOException(NO_RESPONSE_TEXT_MESSAGE) }
    }

    private fun buildRequestJson(
        rawQuery: String,
        reasoningEffort: ReasoningEffort
    ): JSONObject {
        val input = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", JSONArray().put(inputTextContent(SYSTEM_INSTRUCTION)))
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(inputTextContent(rawQuery)))
            )

        return JSONObject()
            .put("model", ApiConfig.MODEL)
            .put("input", input)
            .put("store", false)
            .put("max_output_tokens", 800)
            .put("stream", false)
            .apply {
                reasoningEffort.apiValue?.let { effort ->
                    put("reasoning", JSONObject().put("effort", effort))
                }
            }
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "search_plan")
                        .put("strict", true)
                        .put("schema", searchPlanSchema())
                )
            )
    }

    private fun searchPlanSchema(): JSONObject {
        val stringArraySchema = JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "string"))

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "intent",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray()
                                    .put("part_lookup")
                                    .put("price_lookup")
                                    .put("model_parts_lookup")
                                    .put("general_chat")
                                    .put("unknown")
                            )
                    )
                    .put(
                        "queryType",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray()
                                    .put("general")
                                    .put("price")
                                    .put("code")
                                    .put("model")
                                    .put("part_name")
                                    .put("brand")
                                    .put("unknown")
                            )
                    )
                    .put("rawQuery", JSONObject().put("type", "string"))
                    .put("codes", stringArraySchema)
                    .put("codePrefixes", stringArraySchema)
                    .put("models", stringArraySchema)
                    .put("brands", stringArraySchema)
                    .put("partNamesCn", stringArraySchema)
                    .put("partNamesEn", stringArraySchema)
                    .put("keywords", stringArraySchema)
                    .put("mustHave", stringArraySchema)
                    .put("shouldHave", stringArraySchema)
                    .put(
                        "limit",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", 0)
                            .put("maximum", 30)
                    )
                    .put(
                        "confidence",
                        JSONObject()
                            .put("type", "number")
                            .put("minimum", 0)
                            .put("maximum", 1)
                    )
            )
            .put(
                "required",
                JSONArray()
                    .put("intent")
                    .put("queryType")
                    .put("rawQuery")
                    .put("codes")
                    .put("codePrefixes")
                    .put("models")
                    .put("brands")
                    .put("partNamesCn")
                    .put("partNamesEn")
                    .put("keywords")
                    .put("mustHave")
                    .put("shouldHave")
                    .put("limit")
                    .put("confidence")
            )
            .put("additionalProperties", false)
    }

    private fun inputTextContent(text: String): JSONObject {
        return JSONObject()
            .put("type", "input_text")
            .put("text", text)
    }

    private fun readResponseText(bodyText: String): String {
        val root = JSONObject(bodyText)
        if (!root.isNull("output_text")) {
            val outputText = root.optString("output_text").trim()
            if (outputText.isNotBlank()) {
                return outputText
            }
        }

        val output = root.optJSONArray("output") ?: return ""
        val textChunks = mutableListOf<String>()
        for (outputIndex in 0 until output.length()) {
            val content = output
                .optJSONObject(outputIndex)
                ?.optJSONArray("content")
                ?: continue
            for (contentIndex in 0 until content.length()) {
                val text = content
                    .optJSONObject(contentIndex)
                    ?.optString("text")
                    .orEmpty()
                    .trim()
                if (text.isNotBlank()) {
                    textChunks.add(text)
                }
            }
        }
        return textChunks.joinToString(separator = "\n\n").trim()
    }

    private fun readApiError(code: Int, bodyText: String): String {
        val apiMessage = runCatching {
            JSONObject(bodyText)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
        }.getOrDefault("")
        val message = apiMessage.ifBlank {
            bodyText.replace(Regex("\\s+"), " ").trim().take(180)
        }
        return "SearchPlan service returned HTTP $code: ${message.toLogSummary(120)}"
    }

    private fun IOException.isRetryableRequestError(): Boolean {
        val message = message.orEmpty()
        return this is SocketTimeoutException ||
            this is InterruptedIOException ||
            this is ConnectException ||
            message.contains("connection reset", ignoreCase = true) ||
            message.contains("unexpected end", ignoreCase = true) ||
            message.contains("HTTP 502", ignoreCase = true) ||
            message.contains("HTTP 503", ignoreCase = true) ||
            message.contains("HTTP 504", ignoreCase = true)
    }

    private fun sleepBeforeRetry(attempt: Int) {
        val delayMs = RETRY_DELAY_MS.getOrElse(attempt) { RETRY_DELAY_MS.last() }
        Log.d(TAG, "SearchPlan HTTP retry delay: ${delayMs}ms")
        try {
            Thread.sleep(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("SearchPlan request interrupted.", error)
        }
    }

    private fun String.toLogSummary(limit: Int = 200): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
    }

    private fun Throwable.toLogMessage(): String {
        return message.orEmpty().toLogSummary()
    }

    private companion object {
        const val TAG = "HighTacAI"
        const val REQUEST_ATTEMPTS = 2
        val RETRY_DELAY_MS = longArrayOf(1200L)
        const val NO_RESPONSE_TEXT_MESSAGE = "没有收到 SearchPlan 有效回复。"
        const val SYSTEM_INSTRUCTION =
            "你是摩托车配件公司知识库的查询规划器。请把用户问题转换成结构化 SearchPlan JSON。 " +
                "需要提取产品编码、编码前缀、摩托车车型、品牌、中文配件名称、英文配件名称、关键词、must-have 条件和 should-have 条件。 " +
                "必要时扩展常见中英文配件同义词，例如起动马达/启动马达/启动电机/起动机/starter motor，" +
                "刹车盘/制动盘/brake disc，刹车片/制动片/brake pad，空气滤芯/空滤/air filter，" +
                "机油滤芯/油滤/oil filter，皮带/belt，离合器/clutch，大灯/head light，尾灯/tail light，" +
                "转向灯/方向灯/turn signal，后视镜/反光镜/mirror，电门锁/主锁/main lock，线束/wire harness，" +
                "继电器/relay，整流器/rectifier，稳压器/regulator，外壳/cover，挡泥板/fender，坐垫/座垫/seat，" +
                "减震器/shock absorber，轮胎/tire，电池/蓄电池/battery。 " +
                "不要回答用户问题，只输出符合 schema 的有效 JSON。 " +
                "如果用户明显不是在询问摩托车配件，请返回 intent general_chat、queryType general、空数组、limit 0，并给出较高 confidence。"
    }
}
