package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bridge.AndroidBridge
import com.example.ui.BridgeNotification
import com.example.ui.GeminiClientViewModel
import com.example.ui.components.BridgeInspectorBottomSheet
import com.example.ui.components.CollapsibleCommandDock
import com.example.ui.components.TopLoadingBar
import com.example.ui.components.WebErrorBanner
import com.example.ui.theme.GemBridgeTheme
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemBridgeTheme {
                GeminiWrapperScreen()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeminiWrapperScreen(
    viewModel: GeminiClientViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val surfaceBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val chooserTitle = context.getString(R.string.file_chooser_title)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val currentHostRef = remember { AtomicReference<String?>("gemini.google.com") }
    val pendingFileCallbackRef = remember { AtomicReference<ValueCallback<Array<Uri>>?>(null) }

    // Zero-permission system picker result launcher for WebChromeClient.onShowFileChooser
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallbackRef.getAndSet(null) ?: return@rememberLauncherForActivityResult
        val uris = extractSelectedUris(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    // Native JavaScript bridge exposed as `window.AndroidBridge`
    val androidBridge = remember(viewModel) {
        AndroidBridge(
            currentHostProvider = { currentHostRef.get() },
            onCaptured = { capturedText -> viewModel.onResponseCaptured(capturedText) },
            onEvent = { action, payload -> viewModel.onAutomationEvent(action, payload) },
        )
    }

    // Collect one-shot notifications for user feedback
    LaunchedEffect(viewModel) {
        viewModel.notifications.collect { notification ->
            val message = when (notification) {
                is BridgeNotification.ResponseCaptured ->
                    context.getString(R.string.bridge_captured_snackbar, notification.charCount)
                BridgeNotification.NoResponseFound ->
                    context.getString(R.string.bridge_no_response_found)
                BridgeNotification.ExternalLinkOpened ->
                    context.getString(R.string.external_link_opened)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Persist cookies cleanly whenever the activity backgrounds
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                CookieManager.getInstance().flush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pendingFileCallbackRef.getAndSet(null)?.onReceiveValue(null)
            CookieManager.getInstance().flush()
            webViewRef?.apply {
                removeJavascriptInterface(AndroidBridge.BRIDGE_NAME)
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    // System back button collapses the tool dock first, then navigates back inside WebView
    BackHandler(enabled = uiState.isCommandDockExpanded || uiState.canGoBack) {
        when {
            uiState.isCommandDockExpanded -> viewModel.collapseCommandDock()
            webViewRef?.canGoBack() == true -> webViewRef?.goBack()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gemini_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(surfaceBackgroundArgb)

                        configureWebSettings(settings)
                        configurePersistentCookies(this)

                        // Attach native JavaScript bridge: window.AndroidBridge
                        addJavascriptInterface(androidBridge, AndroidBridge.BRIDGE_NAME)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val uri = request?.url ?: return false
                                if (isAllowedWebViewUri(uri)) {
                                    return false
                                }
                                if (uri.scheme.equals("https", ignoreCase = true) ||
                                    uri.scheme.equals("http", ignoreCase = true)
                                ) {
                                    openInExternalBrowser(ctx, uri)
                                    viewModel.onExternalLinkOpened()
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                currentHostRef.set(url?.let { Uri.parse(it).host })
                                viewModel.onPageStarted(
                                    url = url,
                                    canGoBack = view?.canGoBack() == true,
                                    canGoForward = view?.canGoForward() == true,
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentHostRef.set(url?.let { Uri.parse(it).host })
                                CookieManager.getInstance().flush()
                                viewModel.onPageFinished(
                                    url = url,
                                    canGoBack = view?.canGoBack() == true,
                                    canGoForward = view?.canGoForward() == true,
                                )
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                currentHostRef.set(url?.let { Uri.parse(it).host })
                                viewModel.onNavigationStateChanged(
                                    url = url,
                                    canGoBack = view?.canGoBack() == true,
                                    canGoForward = view?.canGoForward() == true,
                                )
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    val description = error?.description?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Network error while loading Gemini."
                                    viewModel.onLoadError(description)
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                viewModel.onProgressChanged(newProgress)
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                if (filePathCallback == null) return false

                                // Cancel any unfinished chooser callback before opening a new one
                                pendingFileCallbackRef.getAndSet(filePathCallback)?.onReceiveValue(null)

                                val pickerIntent = buildDocumentPickerIntent(fileChooserParams)
                                return try {
                                    fileChooserLauncher.launch(
                                        Intent.createChooser(pickerIntent, chooserTitle),
                                    )
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    pendingFileCallbackRef.getAndSet(null)?.onReceiveValue(null)
                                    false
                                }
                            }
                        }

                        webViewRef = this
                        loadUrl(GeminiClientViewModel.GEMINI_HOME_URL)
                    }
                },
                update = { webView ->
                    webViewRef = webView
                },
            )

            // Sleek Material 3 LinearProgressIndicator anchored flush at the top
            TopLoadingBar(
                isLoading = uiState.isLoading,
                progress = uiState.loadingProgress,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // Graceful offline/error recovery overlay
            uiState.errorMessage?.let { message ->
                WebErrorBanner(
                    errorMessage = message,
                    onRetry = {
                        viewModel.dismissError()
                        webViewRef?.reload()
                    },
                    onDismiss = viewModel::dismissError,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // Collapsible floating action button + Phase 2 expandable automation dock
            CollapsibleCommandDock(
                isExpanded = uiState.isCommandDockExpanded,
                canGoBack = uiState.canGoBack,
                canGoForward = uiState.canGoForward,
                capturedCount = uiState.capturedEntries.size,
                onToggleExpanded = viewModel::toggleCommandDock,
                onCaptureResponse = {
                    viewModel.collapseCommandDock()
                    webViewRef?.evaluateJavascript(AndroidBridge.CAPTURE_LATEST_RESPONSE_SCRIPT) { result ->
                        if (result == "\"empty\"" || result == "\"error\"" || result == "null") {
                            viewModel.onNoResponseFound()
                        }
                    }
                },
                onOpenInspector = viewModel::openBridgeInspector,
                onReload = {
                    viewModel.collapseCommandDock()
                    webViewRef?.reload()
                },
                onGoHome = {
                    viewModel.collapseCommandDock()
                    webViewRef?.loadUrl(GeminiClientViewModel.GEMINI_HOME_URL)
                },
                onGoBack = {
                    webViewRef?.takeIf { it.canGoBack() }?.goBack()
                },
                onGoForward = {
                    webViewRef?.takeIf { it.canGoForward() }?.goForward()
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }

    if (uiState.isBridgeInspectorOpen) {
        BridgeInspectorBottomSheet(
            entries = uiState.capturedEntries,
            onDismiss = viewModel::closeBridgeInspector,
            onCopyPayload = { payload ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Response", payload))
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.bridge_copied_snackbar))
                }
            },
            onClearAll = viewModel::clearCapturedEntries,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    @Suppress("DEPRECATION")
    settings.databaseEnabled = true

    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)

    // Harden file/content & transport security while retaining upload content URI access
    settings.allowFileAccess = false
    settings.allowContentAccess = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }

    // Strip "; wv" and WebView version token to prevent Google Sign-In 403: disallowed_useragent
    settings.userAgentString = buildCleanChromeUserAgent(settings.userAgentString)
}

private fun configurePersistentCookies(webView: WebView) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
}

private fun buildCleanChromeUserAgent(defaultUserAgent: String): String {
    val cleaned = defaultUserAgent
        .replace("; wv", "", ignoreCase = true)
        .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        .trim()
    return if (cleaned.contains("Chrome/")) {
        cleaned
    } else {
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.6778.200 Mobile Safari/537.36"
    }
}

private val REGIONAL_GOOGLE_AUTH_HOST_REGEX =
    Regex("^(accounts|consent)\\.google\\.(com|co\\.[a-z]{2}|com\\.[a-z]{2}|[a-z]{2,3})$")

private fun isAllowedWebViewUri(uri: Uri): Boolean {
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "gemini.google.com" ||
        host == "accounts.youtube.com" ||
        host == "g.co" ||
        host.endsWith(".google.com") ||
        REGIONAL_GOOGLE_AUTH_HOST_REGEX.matches(host)
}

private fun buildDocumentPickerIntent(
    fileChooserParams: WebChromeClient.FileChooserParams?,
): Intent {
    val mimeTypeMap = MimeTypeMap.getSingleton()
    val resolvedMimeTypes = fileChooserParams?.acceptTypes
        ?.flatMap { raw -> raw.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.mapNotNull { entry ->
            when {
                entry == "*/*" -> null
                entry.contains("/") -> entry
                entry.startsWith(".") -> mimeTypeMap.getMimeTypeFromExtension(entry.removePrefix(".").lowercase())
                else -> null
            }
        }
        ?.distinct()
        .orEmpty()

    val allowMultiple =
        fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (resolvedMimeTypes.size == 1) resolvedMimeTypes.first() else "*/*"
        if (resolvedMimeTypes.size > 1) {
            putExtra(Intent.EXTRA_MIME_TYPES, resolvedMimeTypes.toTypedArray())
        }
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    }
}

private fun extractSelectedUris(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null

    val clipData = data.clipData
    if (clipData != null && clipData.itemCount > 0) {
        return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
    }

    val singleUri = data.data
    if (singleUri != null) {
        return arrayOf(singleUri)
    }

    return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
}

private fun openInExternalBrowser(context: Context, uri: Uri) {
    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(browserIntent)
    } catch (_: ActivityNotFoundException) {
        // Ignore if no external browser is available on the device
    }
}
