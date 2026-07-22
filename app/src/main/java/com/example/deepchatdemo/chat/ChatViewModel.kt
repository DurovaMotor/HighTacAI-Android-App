package com.example.deepchatdemo.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.deepchatdemo.catalog.PartsCatalogRepository
import com.example.deepchatdemo.catalog.PartsSearchEngine
import com.example.deepchatdemo.catalog.ScoredPartItem
import com.example.deepchatdemo.catalog.SearchPlan
import com.example.deepchatdemo.catalog.SearchPlanApi
import com.example.deepchatdemo.config.ReasoningEffort
import com.example.deepchatdemo.config.ReasoningPreferenceStore
import com.example.deepchatdemo.platform.network.AndroidPlatformMobileApiTransportFactory
import com.example.deepchatdemo.platform.network.PlatformMobileAuthorizationException
import com.example.deepchatdemo.utils.ImageUtils
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val partsCatalogRepository: PartsCatalogRepository,
    private val reasoningPreferenceStore: ReasoningPreferenceStore,
    private val openAiResponsesApi: OpenAiResponsesApi,
    private val searchPlanApi: SearchPlanApi
) : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()

    var inputText by mutableStateOf("")
        private set

    var selectedImageUri by mutableStateOf<Uri?>(null)
        private set

    var isSending by mutableStateOf(false)
        private set

    var selectedReasoningEffort by mutableStateOf(reasoningPreferenceStore.load())
        private set

    private var nextId = 1L

    init {
        messages.add(
            ChatMessage(
                id = nextMessageId(),
                role = ChatRole.ASSISTANT,
                content = "你好，我是 HighTac AI。可以帮你查询公司摩托车配件资料，也能回答配件、车型和维修相关问题。"
            )
        )
    }

    val hasPlatformAccess: Boolean
        get() = openAiResponsesApi.hasApprovedDeviceToken

    fun onInputTextChange(value: String) {
        inputText = value
    }

    fun onImageSelected(uri: Uri?) {
        selectedImageUri = uri
    }

    fun clearSelectedImage() {
        selectedImageUri = null
    }

    fun addErrorMessage(content: String) {
        messages.add(
            ChatMessage(
                id = nextMessageId(),
                role = ChatRole.ASSISTANT,
                content = content,
                isError = true
            )
        )
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        if (effort == selectedReasoningEffort) return
        selectedReasoningEffort = effort
        reasoningPreferenceStore.save(effort)
        Log.d(TAG, "selectedReasoningEffort=${effort.displayName}")
    }

    fun sendMessage(context: Context) {
        if (isSending) return

        val text = inputText.trim()
        val imageUri = selectedImageUri
        if (text.isBlank() && imageUri == null) return

        inputText = ""
        selectedImageUri = null
        val userMessage = ChatMessage(
            id = nextMessageId(),
            role = ChatRole.USER,
            content = text,
            imageUri = imageUri
        )
        messages.add(userMessage)

        val loadingId = nextMessageId()
        messages.add(
            ChatMessage(
                id = loadingId,
                role = ChatRole.ASSISTANT,
                content = "HighTac AI 正在分析...",
                isLoading = true
            )
        )

        val appContext = context.applicationContext
        val historyMessages = messages
            .filter { !it.isLoading && !it.isError }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            .filterNot { it.id == userMessage.id }
            .dropWhile { it.role != ChatRole.USER }
            .takeLast((RECENT_CONTEXT_MESSAGE_LIMIT - 1).coerceAtLeast(0))
            .map { message ->
                if (message.imageUri == null) {
                    message
                } else {
                    message.copy(imageUri = null)
                }
            }
        val messagesForRequest = historyMessages + userMessage
        val reasoningEffort = selectedReasoningEffort

        isSending = true
        viewModelScope.launch {
            val result = runCatching {
                val knowledgeContext = prepareKnowledgeContext(text, reasoningEffort)
                val imageDataUrl = imageUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        ImageUtils.imageUriToBase64DataUrl(appContext, uri)
                    }
                }

                Log.d(
                    TAG,
                    "Final GPT candidates: count=${knowledgeContext.retrievedParts.size}, " +
                        "searchPlanIntent=${knowledgeContext.searchPlan?.intent ?: "none"}, " +
                        "reasoningEffort=${reasoningEffort.displayName}"
                )

                openAiResponsesApi.sendChat(
                    messages = messagesForRequest,
                    latestImageDataUrl = imageDataUrl,
                    latestImagePrompt = text.ifBlank { DEFAULT_IMAGE_PROMPT },
                    retrievedParts = knowledgeContext.retrievedParts,
                    searchPlan = knowledgeContext.searchPlan,
                    reasoningEffort = reasoningEffort
                )
            }

            replaceLoadingMessage(
                loadingId = loadingId,
                replacement = result.fold(
                    onSuccess = { reply ->
                        ChatMessage(
                            id = nextMessageId(),
                            role = ChatRole.ASSISTANT,
                            content = reply
                        )
                    },
                    onFailure = { error ->
                        Log.e(
                            TAG,
                            "Send failed: errorType=${error.javaClass.simpleName}, " +
                                "errorMessage=${error.toLogMessage()}"
                        )
                        ChatMessage(
                            id = nextMessageId(),
                            role = ChatRole.ASSISTANT,
                            content = friendlyErrorMessage(error, hasImage = imageUri != null),
                            isError = true
                        )
                    }
                )
            )

            isSending = false
        }
    }

    private suspend fun prepareKnowledgeContext(
        rawText: String,
        reasoningEffort: ReasoningEffort
    ): KnowledgeContext {
        if (rawText.isBlank()) {
            return KnowledgeContext()
        }

        val planned = runCatching {
            searchPlanApi.createSearchPlan(
                rawQuery = rawText,
                reasoningEffort = reasoningEffort
            )
        }

        val plan = planned.getOrNull()
        if (plan != null) {
            Log.d(TAG, "SearchPlan intent=${plan.intent}, ${plan.toLogSummary()}")
            if (!plan.isPartsIntent()) {
                return KnowledgeContext()
            }

            val parts = partsCatalogRepository.loadParts()
            val results = PartsSearchEngine.search(parts, plan)
            Log.d(
                TAG,
                "Parts search results for SearchPlan: count=${results.size}, " +
                    "topScore=${results.firstOrNull()?.score ?: 0}"
            )
            return KnowledgeContext(
                searchPlan = plan,
                retrievedParts = results
            )
        }

        val error = planned.exceptionOrNull()
        Log.e(
            TAG,
            "SearchPlan fallback start: errorType=${error?.javaClass?.simpleName}, " +
                "errorMessage=${error?.toLogMessage().orEmpty()}"
        )
        val fallbackPlan = PartsSearchEngine.createSimplePlan(rawText)
        val parts = partsCatalogRepository.loadParts()
        val results = PartsSearchEngine.simpleSearch(parts, rawText)
        Log.d(
            TAG,
            "Parts fallback results: count=${results.size}, topScore=${results.firstOrNull()?.score ?: 0}"
        )
        if (!fallbackPlan.isPartsIntent() && results.isEmpty()) {
            Log.d(TAG, "SearchPlan fallback treated as general chat")
            return KnowledgeContext()
        }
        return KnowledgeContext(
            searchPlan = fallbackPlan,
            retrievedParts = results
        )
    }

    private fun friendlyErrorMessage(error: Throwable, hasImage: Boolean): String {
        val message = error.message.orEmpty()
        return when {
            error is PlatformMobileAuthorizationException ->
                message.ifBlank { PLATFORM_REGISTRATION_PENDING_MESSAGE }
            message == IMAGE_TOO_LARGE_MESSAGE -> IMAGE_TOO_LARGE_MESSAGE
            message.startsWith(IMAGE_PROCESSING_FAILED_PREFIX) -> IMAGE_PROCESSING_FAILED_MESSAGE
            message.startsWith(HTTP_ERROR_MESSAGE) -> HTTP_ERROR_MESSAGE
            message == NO_RESPONSE_TEXT_MESSAGE -> NO_RESPONSE_TEXT_MESSAGE
            error.hasCause<SocketTimeoutException>() ||
                error.hasCause<InterruptedIOException>() ||
                error.hasMessageContaining("timeout") -> {
                if (hasImage) {
                    IMAGE_RECOGNITION_TIMEOUT_MESSAGE
                } else {
                    REQUEST_TIMEOUT_MESSAGE
                }
            }
            error.hasCause<UnknownHostException>() -> NETWORK_FAILED_MESSAGE
            error.hasCause<ConnectException>() ||
                error.hasMessageContaining("connection reset") ||
                error.hasMessageContaining("unexpected end") -> NETWORK_FAILED_MESSAGE
            else -> HTTP_ERROR_MESSAGE
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.hasMessageContaining(value: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val text = current.message.orEmpty()
            if (text.contains(value, ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.toLogMessage(): String {
        return message.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }

    private fun replaceLoadingMessage(loadingId: Long, replacement: ChatMessage) {
        val index = messages.indexOfFirst { it.id == loadingId }
        if (index >= 0) {
            messages[index] = replacement
        } else {
            messages.add(replacement)
        }
    }

    private fun nextMessageId(): Long = nextId++

    private data class KnowledgeContext(
        val searchPlan: SearchPlan? = null,
        val retrievedParts: List<ScoredPartItem> = emptyList()
    )

    companion object {
        private const val TAG = "HighTacAI"
        private const val RECENT_CONTEXT_MESSAGE_LIMIT = 8
        private const val DEFAULT_IMAGE_PROMPT =
            "请识别并描述这张摩托车配件图片。"
        private const val PLATFORM_REGISTRATION_PENDING_MESSAGE =
            "设备正在自动注册，请稍候重试。"
        private const val NETWORK_FAILED_MESSAGE =
            "网络连接失败，请检查网络后重试。"
        private const val REQUEST_TIMEOUT_MESSAGE =
            "请求超时，请稍后重试。"
        private const val IMAGE_RECOGNITION_TIMEOUT_MESSAGE =
            "图片识别超时，请尝试更小或更清晰的图片。"
        private const val HTTP_ERROR_MESSAGE =
            "HighTac AI 服务返回错误，请检查 API 配置或稍后重试。"
        private const val IMAGE_PROCESSING_FAILED_PREFIX = "图片处理失败"
        private const val IMAGE_PROCESSING_FAILED_MESSAGE =
            "图片处理失败，请换一张图片重试。"
        private const val IMAGE_TOO_LARGE_MESSAGE =
            "图片压缩后仍然过大，请换一张图片重试。"
        private const val NO_RESPONSE_TEXT_MESSAGE = "没有收到有效回复，请稍后重试。"

        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        val applicationContext = context.applicationContext
                        val transport = AndroidPlatformMobileApiTransportFactory.create(
                            applicationContext
                        )
                        return ChatViewModel(
                            partsCatalogRepository = PartsCatalogRepository(applicationContext),
                            reasoningPreferenceStore = ReasoningPreferenceStore(applicationContext),
                            openAiResponsesApi = OpenAiResponsesApi(transport),
                            searchPlanApi = SearchPlanApi(transport)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
