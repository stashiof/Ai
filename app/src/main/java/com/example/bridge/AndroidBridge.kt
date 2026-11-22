package com.example.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Native JavaScript bridge exposed to the Gemini WebView as `window.AndroidBridge`.
 *
 * Phase 1 provides [onResponseCaptured] and an extensible [onAutomationEvent] hook.
 * Only callbacks originating from the trusted `gemini.google.com` host are accepted.
 */
class AndroidBridge(
    private val currentHostProvider: () -> String?,
    private val onCaptured: (String) -> Unit,
    private val onEvent: (action: String, payload: String) -> Unit = { _, _ -> },
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Receives captured response text from JavaScript running inside Gemini.
     * Callable from JS as: `window.AndroidBridge.onResponseCaptured(text)`
     */
    @JavascriptInterface
    fun onResponseCaptured(text: String?) {
        if (!isTrustedBridgeOrigin()) {
            Log.w(TAG, "Ignored onResponseCaptured call from untrusted host: ${currentHostProvider()}")
            return
        }

        val sanitized = text
            ?.trim()
            ?.take(MAX_PAYLOAD_CHARS)
            .orEmpty()

        if (sanitized.isEmpty()) return

        mainHandler.post {
            onCaptured(sanitized)
        }
    }

    /**
     * Generic Phase 2 automation hook for structured app-control actions.
     * Callable from JS as: `window.AndroidBridge.onAutomationEvent(action, payload)`
     */
    @JavascriptInterface
    fun onAutomationEvent(action: String?, payload: String?) {
        if (!isTrustedBridgeOrigin()) {
            Log.w(TAG, "Ignored onAutomationEvent call from untrusted host: ${currentHostProvider()}")
            return
        }

        val cleanAction = action?.trim()?.take(MAX_ACTION_CHARS).orEmpty()
        val cleanPayload = payload?.trim()?.take(MAX_PAYLOAD_CHARS).orEmpty()
        if (cleanAction.isEmpty()) return

        mainHandler.post {
            onEvent(cleanAction, cleanPayload)
        }
    }

    private fun isTrustedBridgeOrigin(): Boolean {
        val host = currentHostProvider()?.lowercase() ?: return false
        return host == ALLOWED_BRIDGE_HOST
    }

    companion object {
        private const val TAG = "AndroidBridge"
        const val BRIDGE_NAME = "AndroidBridge"
        private const val ALLOWED_BRIDGE_HOST = "gemini.google.com"
        private const val MAX_PAYLOAD_CHARS = 100_000
        private const val MAX_ACTION_CHARS = 128

        /**
         * Resilient DOM extraction script that locates the latest Gemini assistant response
         * and forwards its text to `window.AndroidBridge.onResponseCaptured(...)`.
         */
        val CAPTURE_LATEST_RESPONSE_SCRIPT: String =
            """
            (function() {
                try {
                    const selectors = [
                        'message-content .markdown',
                        'model-response .markdown',
                        'model-response',
                        '[data-message-author-role="model"]',
                        '.model-response-text',
                        '.response-container-content'
                    ];
                    let text = '';
                    for (const sel of selectors) {
                        const nodes = document.querySelectorAll(sel);
                        if (nodes && nodes.length > 0) {
                            const last = nodes[nodes.length - 1];
                            const candidate = (last.innerText || last.textContent || '').trim();
                            if (candidate.length > 0) {
                                text = candidate;
                                break;
                            }
                        }
                    }
                    if (!text) {
                        const selection = window.getSelection ? window.getSelection().toString().trim() : '';
                        if (selection.length > 0) {
                            text = selection;
                        }
                    }
                    if (text && window.AndroidBridge && typeof window.AndroidBridge.onResponseCaptured === 'function') {
                        window.AndroidBridge.onResponseCaptured(text);
                        return 'captured';
                    }
                    return 'empty';
                } catch (e) {
                    return 'error';
                }
            })();
            """.trimIndent()
    }
}
