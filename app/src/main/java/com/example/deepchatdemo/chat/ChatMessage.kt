package com.example.deepchatdemo.chat

import android.net.Uri

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String = "",
    val imageUri: Uri? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false
)

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}
