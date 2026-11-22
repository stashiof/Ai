package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class CapturedBridgeEntry(
    val id: Long,
    val text: String,
    val timestamp: String,
    val source: String = "onResponseCaptured",
)

data class GeminiClientUiState(
    val currentUrl: String = GeminiClientViewModel.GEMINI_HOME_URL,
    val loadingProgress: Int = 0,
    val isLoading: Boolean = true,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isCommandDockExpanded: Boolean = false,
    val isBridgeInspectorOpen: Boolean = false,
    val errorMessage: String? = null,
    val capturedEntries: List<CapturedBridgeEntry> = emptyList(),
)

sealed interface BridgeNotification {
    data class ResponseCaptured(val charCount: Int) : BridgeNotification
    data object NoResponseFound : BridgeNotification
    data object ExternalLinkOpened : BridgeNotification
}

class GeminiClientViewModel : ViewModel() {
    private val entryIdCounter = AtomicLong(1L)

    private val _uiState = MutableStateFlow(GeminiClientUiState())
    val uiState: StateFlow<GeminiClientUiState> = _uiState.asStateFlow()

    private val _notifications = MutableSharedFlow<BridgeNotification>(extraBufferCapacity = 8)
    val notifications: SharedFlow<BridgeNotification> = _notifications.asSharedFlow()

    fun onProgressChanged(progress: Int) {
        val clamped = progress.coerceIn(0, 100)
        _uiState.update { state ->
            state.copy(
                loadingProgress = clamped,
                isLoading = clamped in 1..99,
            )
        }
    }

    fun onPageStarted(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                isLoading = true,
                errorMessage = null,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    fun onPageFinished(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                loadingProgress = 100,
                isLoading = false,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    fun onNavigationStateChanged(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    fun onLoadError(description: String) {
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                errorMessage = description,
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun toggleCommandDock() {
        _uiState.update { it.copy(isCommandDockExpanded = !it.isCommandDockExpanded) }
    }

    fun collapseCommandDock() {
        _uiState.update { it.copy(isCommandDockExpanded = false) }
    }

    fun openBridgeInspector() {
        _uiState.update {
            it.copy(
                isCommandDockExpanded = false,
                isBridgeInspectorOpen = true,
            )
        }
    }

    fun closeBridgeInspector() {
        _uiState.update { it.copy(isBridgeInspectorOpen = false) }
    }

    fun onResponseCaptured(text: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = CapturedBridgeEntry(
            id = entryIdCounter.getAndIncrement(),
            text = text,
            timestamp = timestamp,
        )
        _uiState.update { state ->
            state.copy(
                capturedEntries = (listOf(entry) + state.capturedEntries).take(MAX_LOG_ENTRIES),
            )
        }
        viewModelScope.launch {
            _notifications.emit(BridgeNotification.ResponseCaptured(text.length))
        }
    }

    fun onAutomationEvent(action: String, payload: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = CapturedBridgeEntry(
            id = entryIdCounter.getAndIncrement(),
            text = payload.ifEmpty { action },
            timestamp = timestamp,
            source = action,
        )
        _uiState.update { state ->
            state.copy(
                capturedEntries = (listOf(entry) + state.capturedEntries).take(MAX_LOG_ENTRIES),
            )
        }
    }

    fun onNoResponseFound() {
        viewModelScope.launch {
            _notifications.emit(BridgeNotification.NoResponseFound)
        }
    }

    fun onExternalLinkOpened() {
        viewModelScope.launch {
            _notifications.emit(BridgeNotification.ExternalLinkOpened)
        }
    }

    fun clearCapturedEntries() {
        _uiState.update { it.copy(capturedEntries = emptyList()) }
    }

    companion object {
        const val GEMINI_HOME_URL = "https://gemini.google.com/app"
        private const val MAX_LOG_ENTRIES = 50
    }
}
