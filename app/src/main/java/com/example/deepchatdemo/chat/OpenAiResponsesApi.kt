package com.example.deepchatdemo.chat

import android.os.SystemClock
import android.util.Log
import com.example.deepchatdemo.catalog.PartItem
import com.example.deepchatdemo.catalog.ScoredPartItem
import com.example.deepchatdemo.catalog.SearchPlan
import com.example.deepchatdemo.config.ApiConfig
import com.example.deepchatdemo.config.ReasoningEffort
import com.example.deepchatdemo.platform.network.PlatformMobileApiRoute
import com.example.deepchatdemo.platform.network.PlatformMobileApiTransport
import java.io.InterruptedIOException
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OpenAiResponsesApi(
    private val transport: PlatformMobileApiTransport
) {
    val hasApprovedDeviceToken: Boolean
        get() = transport.hasApprovedDeviceToken()

    suspend fun sendChat(
        messages: List<ChatMessage>,
        latestImageDataUrl: String? = null,
        latestImagePrompt: String? = null,
        retrievedParts: List<ScoredPartItem> = emptyList(),
        searchPlan: SearchPlan? = null,
        reasoningEffort: ReasoningEffort = ReasoningEffort.fromConfig()
    ): String = withContext(Dispatchers.IO) {
        val hasImage = !latestImageDataUrl.isNullOrBlank()
        val imageByteSize = latestImageDataUrl?.compressedImageByteSize() ?: 0
        val requestStartedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "Request start: anonymousAndroidAccess=true, " +
                "model=${ApiConfig.MODEL}, reasoningEffort=${reasoningEffort.displayName}, " +
                "reasoningIncluded=${reasoningEffort.apiValue != null}, " +
                "messageCount=${messages.count { !it.isLoading && !it.isError }}, " +
                "hasImage=$hasImage, imageBytes=$imageByteSize, " +
                "retrievedParts=${retrievedParts.size}, searchPlanIntent=${searchPlan?.intent ?: "none"}"
        )

        try {
            val requestJson = buildRequestJson(
                model = ApiConfig.MODEL,
                messages = messages,
                latestImageDataUrl = latestImageDataUrl,
                latestImagePrompt = latestImagePrompt,
                retrievedParts = retrievedParts,
                searchPlan = searchPlan,
                maxOutputTokens = if (hasImage) IMAGE_MAX_OUTPUT_TOKENS else TEXT_MAX_OUTPUT_TOKENS,
                reasoningEffort = reasoningEffort
            )
            val content = executeRequest(requestJson)
            if (content.isNotBlank()) {
                Log.d(
                    TAG,
                    "Request end: success=true, elapsedMs=${requestStartedAt.elapsedMs()}, " +
                        "responseSummary=${content.toLogSummary()}"
                )
                return@withContext content
            }

            if (hasImage) {
                val retryRequestJson = buildRequestJson(
                    model = ApiConfig.MODEL,
                    messages = messages,
                    latestImageDataUrl = latestImageDataUrl,
                    latestImagePrompt = RETRY_IMAGE_PROMPT,
                    retrievedParts = retrievedParts,
                    searchPlan = searchPlan,
                    maxOutputTokens = RETRY_IMAGE_MAX_OUTPUT_TOKENS,
                    reasoningEffort = reasoningEffort
                )
                val retryContent = executeRequest(retryRequestJson)
                if (retryContent.isNotBlank()) {
                    Log.d(
                        TAG,
                        "Request end: success=true, elapsedMs=${requestStartedAt.elapsedMs()}, " +
                            "responseSummary=${retryContent.toLogSummary()}"
                    )
                    return@withContext retryContent
                }
            }

            throw IOException(NO_RESPONSE_TEXT_MESSAGE)
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Request end: success=false, elapsedMs=${requestStartedAt.elapsedMs()}, " +
                    "errorType=${error.javaClass.simpleName}, errorMessage=${error.toLogMessage()}"
            )
            throw error
        }
    }

    private fun executeRequest(requestJson: JSONObject): String {
        var lastError: IOException? = null
        repeat(REQUEST_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "HTTP attempt ${attempt + 1}/$REQUEST_ATTEMPTS start")
                return executeSingleRequest(requestJson)
            } catch (error: IOException) {
                lastError = error
                Log.e(
                    TAG,
                    "HTTP attempt ${attempt + 1}/$REQUEST_ATTEMPTS failed: " +
                        "errorType=${error.javaClass.simpleName}, " +
                        "errorMessage=${error.toLogMessage()}"
                )
                if (attempt == REQUEST_ATTEMPTS - 1 || !error.isRetryableRequestError()) {
                    throw error
                }
                sleepBeforeRetry(attempt)
            }
        }

        throw lastError ?: IOException("Request failed.")
    }

    private fun executeSingleRequest(requestJson: JSONObject): String {
        val callStartedAt = SystemClock.elapsedRealtime()
        val response = transport.postJson(
            route = PlatformMobileApiRoute.OPENAI_RESPONSES,
            jsonBody = requestJson.toString()
        )
        Log.d(
            TAG,
            "HTTP response: statusCode=${response.statusCode}, elapsedMs=${callStartedAt.elapsedMs()}"
        )

        if (response.statusCode != 200) {
            throw IOException(readApiError(response.statusCode, response.body))
        }

        return runCatching {
            readResponseText(response.body)
        }.getOrElse { error ->
            throw IOException(NO_RESPONSE_TEXT_MESSAGE, error)
        }
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

    private fun buildRequestJson(
        model: String,
        messages: List<ChatMessage>,
        latestImageDataUrl: String?,
        latestImagePrompt: String?,
        retrievedParts: List<ScoredPartItem>,
        searchPlan: SearchPlan?,
        maxOutputTokens: Int,
        reasoningEffort: ReasoningEffort
    ): JSONObject {
        val cleanMessages = messages
            .filter { !it.isLoading && !it.isError }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        val imageDataUrl = latestImageDataUrl?.takeIf { it.isNotBlank() }
        val latestImageMessageId = if (imageDataUrl == null) {
            null
        } else {
            cleanMessages.lastOrNull { it.role == ChatRole.USER && it.imageUri != null }?.id
        }

        val input = JSONArray().put(
            JSONObject()
                .put("role", "system")
                .put("content", JSONArray().put(inputTextContent(SYSTEM_INSTRUCTION)))
        )

        val partsContext = buildPartsContext(cleanMessages, retrievedParts, searchPlan)
        if (partsContext.isNotBlank()) {
            input.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", JSONArray().put(inputTextContent(partsContext)))
            )
        }

        cleanMessages.forEach { message ->
            val content = JSONArray()
            val text = if (message.id == latestImageMessageId && message.content.isBlank()) {
                latestImagePrompt.orEmpty()
            } else {
                message.content
            }.trim()

            if (text.isNotBlank()) {
                content.put(inputTextContent(text))
            }

            if (message.id == latestImageMessageId && imageDataUrl != null) {
                content.put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", imageDataUrl)
                )
            }

            if (content.length() > 0) {
                input.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", content)
                )
            }
        }

        return JSONObject()
            .put("model", model)
            .put("input", input)
            .put("store", false)
            .put("max_output_tokens", maxOutputTokens)
            .put("stream", false)
            .apply {
                reasoningEffort.apiValue?.let { effort ->
                    put("reasoning", JSONObject().put("effort", effort))
                }
            }
    }

    private fun buildPartsContext(
        cleanMessages: List<ChatMessage>,
        retrievedParts: List<ScoredPartItem>,
        searchPlan: SearchPlan?
    ): String {
        val shouldInclude = retrievedParts.isNotEmpty() || searchPlan?.isPartsIntent() == true
        if (!shouldInclude) return ""

        val rawQuery = searchPlan?.rawQuery
            ?.takeIf { it.isNotBlank() }
            ?: cleanMessages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val builder = StringBuilder()
        builder.appendLine("公司摩托车配件库检索结果：")
        builder.appendLine()
        builder.appendLine("用户问题：")
        builder.appendLine(rawQuery.ifBlank { "-" })
        builder.appendLine()

        if (searchPlan != null) {
            builder.appendLine("检索计划：")
            builder.appendLine("intent=${searchPlan.intent}")
            builder.appendLine("queryType=${searchPlan.queryType}")
            builder.appendLine("codes=${searchPlan.codes.joinToCatalogText()}")
            builder.appendLine("codePrefixes=${searchPlan.codePrefixes.joinToCatalogText()}")
            builder.appendLine("models=${searchPlan.models.joinToCatalogText()}")
            builder.appendLine("brands=${searchPlan.brands.joinToCatalogText()}")
            builder.appendLine("partNamesCn=${searchPlan.partNamesCn.joinToCatalogText()}")
            builder.appendLine("partNamesEn=${searchPlan.partNamesEn.joinToCatalogText()}")
            builder.appendLine("mustHave=${searchPlan.mustHave.joinToCatalogText()}")
            builder.appendLine("shouldHave=${searchPlan.shouldHave.joinToCatalogText()}")
            builder.appendLine()
        }

        builder.appendLine("匹配配件：")
        if (retrievedParts.isEmpty()) {
            builder.appendLine("我没有在公司配件库中找到明确匹配的产品。请提供更准确的产品编码、车型、配件名称或图片。")
        } else {
            retrievedParts.take(SearchPlan.MAX_LIMIT).forEachIndexed { index, scoredPart ->
                val part = scoredPart.part
                builder.appendLine("${index + 1}. 匹配分：${scoredPart.score}")
                builder.appendLine("   产品编码：${part.code.catalogText()}")
                builder.appendLine("   产品中文名称：${part.nameCn.catalogText()}")
                builder.appendLine("   产品英文名称：${part.nameEn.catalogText()}")
                builder.appendLine("   品牌：${part.brand.catalogText()}")
                builder.appendLine("   通用车型：${part.models.catalogText()}")
                builder.appendLine("   规格型号：${part.spec.catalogText()}")
                builder.appendLine("   单位（中文）：${part.unit.catalogText()}")
                builder.appendLine("   销售单价/元（标准价）：${part.standardPrice.catalogNumber()}")
                builder.appendLine("   销售单价/元（最新价）：${part.latestPrice.catalogNumber()}")
                builder.appendLine("   售价最新日期：${part.latestPriceDate.catalogText()}")
                builder.appendLine("   售价最新数量：${part.latestPriceQty.catalogNumber()}")
                builder.appendLine("   每箱数量(CTN)：${part.ctnQty.catalogNumber()}")
                builder.appendLine("   毛重(KG)：${part.grossWeightKg.catalogNumber()}")
                builder.appendLine("   尺寸：${part.catalogSizeText()}")
                builder.appendLine("   体积(CBM)：${part.volumeCbm.catalogNumber()}")
                builder.appendLine("   备注：${part.remark.catalogText()}")
            }
        }
        builder.appendLine()
        builder.appendLine("回答规则：")
        builder.appendLine("- 优先基于上面的公司配件库结果回答。")
        builder.appendLine("- 如果公司配件库中没有相关字段记录，请回答“暂无记录”，不要编造产品编码、价格、车型、库存或适配关系。")
        builder.appendLine("- 如果没有明确匹配结果，请说明没有在公司配件库中找到明确匹配的产品，并请用户补充产品编码、车型、配件名称或图片。")
        builder.appendLine("- 如果候选结果不确定，请让用户确认产品编码、车型或图片。")
        return builder.toString()
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
        val problemMessage = runCatching {
            val root = JSONObject(bodyText)
            listOf(root.optString("title"), root.optString("detail"))
                .filter { it.isNotBlank() }
                .joinToString(separator = ": ")
        }.getOrDefault("")

        val loggedMessage = when {
            apiMessage.isNotBlank() -> apiMessage
            problemMessage.isNotBlank() -> problemMessage
            bodyText.isNotBlank() -> bodyText.cleanErrorSnippet()
            else -> "HTTP $code with empty response body"
        }
        Log.e(
            TAG,
            "HTTP error: statusCode=$code, errorType=HttpStatus, " +
                "errorMessage=${loggedMessage.toLogSummary()}"
        )
        return "$HTTP_ERROR_MESSAGE HTTP $code"
    }

    private fun String.cleanErrorSnippet(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
    }

    private fun String.compressedImageByteSize(): Int {
        val base64 = substringAfter(',', missingDelimiterValue = this).trim()
        if (base64.isBlank()) return 0
        val padding = base64.takeLast(2).count { it == '=' }
        return ((base64.length * 3) / 4 - padding).coerceAtLeast(0)
    }

    private fun String.toLogSummary(limit: Int = 200): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
    }

    private fun Throwable.toLogMessage(): String {
        return message.orEmpty().toLogSummary()
    }

    private fun List<String>.joinToCatalogText(): String {
        return filter { it.isNotBlank() }
            .joinToString(separator = ", ")
            .ifBlank { "-" }
    }

    private fun String?.catalogText(): String {
        return this?.trim()?.takeIf { it.isNotBlank() } ?: "-"
    }

    private fun Double?.catalogNumber(): String {
        return this?.let { value ->
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        } ?: "-"
    }

    private fun PartItem.catalogSizeText(): String {
        if (lengthCm == null && widthCm == null && heightCm == null) return "-"
        return "${lengthCm.catalogNumber()} x ${widthCm.catalogNumber()} x ${heightCm.catalogNumber()} CM"
    }

    private fun Long.elapsedMs(): Long = SystemClock.elapsedRealtime() - this

    private fun sleepBeforeRetry(attempt: Int) {
        val delayMs = RETRY_DELAY_MS.getOrElse(attempt) { RETRY_DELAY_MS.last() }
        Log.d(TAG, "HTTP retry delay: ${delayMs}ms")
        try {
            Thread.sleep(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Request interrupted.", error)
        }
    }

    private companion object {
        const val TAG = "HighTacAI"
        const val TEXT_MAX_OUTPUT_TOKENS = 1024
        const val IMAGE_MAX_OUTPUT_TOKENS = 1024
        const val RETRY_IMAGE_MAX_OUTPUT_TOKENS = 1536
        const val REQUEST_ATTEMPTS = 3
        val RETRY_DELAY_MS = longArrayOf(1500L, 4000L)
        const val HTTP_ERROR_MESSAGE =
            "HighTac AI 服务返回错误，请检查 API 配置或稍后重试。"
        const val NO_RESPONSE_TEXT_MESSAGE = "没有收到有效回复，请稍后重试。"
        const val RETRY_IMAGE_PROMPT =
            "请识别并描述这张摩托车配件图片。请用简体中文回答配件类型、可见细节、可能的安装位置或车型线索，并说明下一步需要确认的信息。"
        const val SYSTEM_INSTRUCTION =
            "你是 HighTac AI，服务于摩托车配件外贸场景。你可以回答通用问题，也可以帮助识别摩托车配件。 " +
                "当系统提供公司配件库检索结果时，必须优先基于这些结果回答。 " +
                "如果公司配件库中没有相关字段记录，请回答“暂无记录”，不要编造产品编码、价格、车型、库存或适配关系。 " +
                "如果找到多个候选结果，请帮助用户比较，并在不确定时要求用户补充产品编码、车型或图片。 " +
                "当用户发送摩托车配件图片时，请识别配件类型、可见细节、可能车型线索，并在不确定时提出后续确认问题。 " +
                "除非用户明确要求其他语言，默认始终使用简体中文回答。 " +
                "回答结构要清晰，适合在手机聊天界面阅读。"
    }
}
