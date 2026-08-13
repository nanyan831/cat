package com.example.catlifepet.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ChatScreenStatus { LOADING, READY, OFFLINE, SESSION_EXPIRED, ERROR }

data class ChatUiState(
    val status: ChatScreenStatus = ChatScreenStatus.LOADING,
    val conversations: List<ChatConversation> = emptyList(),
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val assistantDraft: String = "",
    val assistantDraftId: String? = null,
    val generating: Boolean = false,
    val message: String? = null
)

class ChatViewModel(private val source: ChatDataSource) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private var messagesJob: Job? = null
    private var sendJob: Job? = null
    private var draftFlushJob: Job? = null
    private var draftMessageId: String? = null
    private val draftBuffer = StringBuilder()

    init {
        viewModelScope.launch {
            source.observeConversations().collectLatest { conversations ->
                mutableState.update { it.copy(conversations = conversations) }
            }
        }
        load()
    }

    fun load(preferredConversationId: String? = mutableState.value.conversationId) {
        if (sendJob?.isActive == true) return
        mutableState.update { it.copy(status = ChatScreenStatus.LOADING, message = null) }
        viewModelScope.launch { applyLoadResult(source.loadWorkspace(preferredConversationId)) }
    }

    fun selectConversation(conversationId: String) {
        if (conversationId == mutableState.value.conversationId || sendJob?.isActive == true) return
        mutableState.update { it.copy(status = ChatScreenStatus.LOADING, message = null) }
        viewModelScope.launch { applyLoadResult(source.selectConversation(conversationId)) }
    }

    fun newConversation() {
        if (sendJob?.isActive == true) return
        mutableState.update { it.copy(status = ChatScreenStatus.LOADING, message = null) }
        viewModelScope.launch { applyLoadResult(source.createConversation()) }
    }

    fun deleteCurrentConversation() {
        val id = mutableState.value.conversationId ?: return
        if (sendJob?.isActive == true) return
        mutableState.update { it.copy(status = ChatScreenStatus.LOADING, message = null) }
        viewModelScope.launch { applyLoadResult(source.deleteConversation(id)) }
    }

    fun send(content: String, clientMessageId: String = UUID.randomUUID().toString()) {
        val conversationId = mutableState.value.conversationId ?: return
        val normalized = content.trim()
        if (normalized.isEmpty() || sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            resetDraftBuffer()
            mutableState.update {
                it.copy(generating = true, assistantDraft = "", assistantDraftId = null, message = null)
            }
            source.sendMessage(conversationId, normalized, clientMessageId).collect { event ->
                when (event) {
                    is ChatSendEvent.Delta -> appendAssistantDelta(event.messageId, event.text)
                    is ChatSendEvent.Completed -> {
                        flushAssistantDraft()
                        resetDraftBuffer()
                        mutableState.update {
                            it.copy(generating = false, assistantDraft = "", assistantDraftId = null)
                        }
                    }
                    is ChatSendEvent.Failure -> {
                        resetDraftBuffer()
                        mutableState.update {
                            it.copy(
                                status = if (event.sessionExpired) ChatScreenStatus.SESSION_EXPIRED else it.status,
                                generating = false,
                                assistantDraft = "",
                                assistantDraftId = null,
                                message = event.message
                            )
                        }
                    }
                }
            }
            resetDraftBuffer()
            mutableState.update { it.copy(generating = false) }
        }
    }

    fun retry(message: ChatMessage) {
        val clientId = message.clientMessageId ?: return
        send(message.content, clientId)
    }

    fun stopGenerating() {
        sendJob?.cancel()
        sendJob = null
        resetDraftBuffer()
        mutableState.update {
            it.copy(
                generating = false,
                assistantDraft = "",
                assistantDraftId = null,
                message = "已停止生成，原消息已保留。"
            )
        }
    }

    private fun appendAssistantDelta(messageId: String, text: String) {
        draftMessageId = messageId
        draftBuffer.append(text)
        if (draftFlushJob?.isActive == true) return
        draftFlushJob = viewModelScope.launch {
            while (draftBuffer.isNotEmpty()) {
                delay(DRAFT_FLUSH_INTERVAL_MS)
                flushAssistantDraft()
            }
        }
    }

    private fun flushAssistantDraft() {
        if (draftBuffer.isEmpty()) return
        val chunk = draftBuffer.toString()
        draftBuffer.clear()
        mutableState.update {
            it.copy(
                assistantDraftId = draftMessageId,
                assistantDraft = it.assistantDraft + chunk
            )
        }
    }

    private fun resetDraftBuffer() {
        draftFlushJob?.cancel()
        draftFlushJob = null
        draftMessageId = null
        draftBuffer.clear()
    }

    private suspend fun applyLoadResult(result: ChatLoadResult) {
        when (result) {
            is ChatLoadResult.Ready -> {
                bindMessages(result.conversationId)
                mutableState.update {
                    it.copy(
                        status = if (result.offline) ChatScreenStatus.OFFLINE else ChatScreenStatus.READY,
                        conversationId = result.conversationId,
                        message = if (result.offline) "当前离线，显示最近聊天记录。" else null
                    )
                }
            }
            ChatLoadResult.SessionExpired -> mutableState.update {
                it.copy(status = ChatScreenStatus.SESSION_EXPIRED, message = "登录已失效，请重新登录。")
            }
            is ChatLoadResult.Failure -> mutableState.update {
                it.copy(status = ChatScreenStatus.ERROR, message = result.message)
            }
        }
    }

    private fun bindMessages(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            source.observeMessages(conversationId).collectLatest { messages ->
                mutableState.update { it.copy(messages = messages) }
            }
        }
    }

    class Factory(private val source: ChatDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(source) as T
        }
    }

    private companion object {
        const val DRAFT_FLUSH_INTERVAL_MS = 180L
    }
}
