package com.screenkit.screenrecorder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.screenkit.screenrecorder.data.CaptureKind
import com.screenkit.screenrecorder.data.CaptureSink
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.services.BlackScreenBypass
import com.screenkit.screenrecorder.services.FloatingOverlayService
import com.screenkit.screenrecorder.services.ScreenCaptureService
import com.screenkit.screenrecorder.ui.BrushOverlay
import com.screenkit.screenrecorder.ui.TouchFeedbackOverlay
import java.io.OutputStream

/**
 * Built-in Anti-Black-Screen Web Capture Browser.
 *
 * Websites opened in external browsers can trigger black screenshots/recordings via:
 * - Browser-level FLAG_SECURE (Incognito, Payment sheets, credential prompts)
 * - Hardware-protected EME/DRM SurfaceView overlay planes (HW_SECURE_ALL)
 * - JS blur / visibilitychange blackout scripts when an overlay or consent sheet appears
 * - WebGL canvas buffers cleared without preserveDrawingBuffer
 *
 * This activity renders pages inside ScreenKit's own hardware-accelerated window with FLAG_SECURE
 * stripped, injects JS/EME/WebGL bypass hooks, and provides Direct Hardware PixelCopy screenshots
 * that do not even require MediaProjection.
 */
class WebCaptureActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var urlInput: EditText? = null
    private var recordBtn: TextView? = null
    private var progressBar: ProgressBar? = null
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlackScreenBypass.prepareWindowForCapture(window)
        BlackScreenBypass.trySystemSecureBypass(this)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                CaptureIntents.startScreenCapture(
                    this,
                    CaptureContract.MODE_SCREEN_RECORDING,
                    result.resultCode,
                    data,
                    null,
                )
                updateRecordButtonState()
            } else {
                CaptureIntents.toast(this, "Recording cancelled.")
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D10"))
        }

        // Top Bar
        val topBar = buildTopBar()
        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Thin loading progress bar
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        progressBar = progress
        root.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)),
        )

        // WebView container
        val webContainer = FrameLayout(this)
        val wv = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = settings.userAgentString.replace("; wv", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    BlackScreenBypass.prepareWindowForCapture(window)
                    if (!url.isNullOrBlank()) {
                        urlInput?.setText(url)
                        SettingsStore.setLastWebUrl(this@WebCaptureActivity, url)
                    }
                    view?.evaluateJavascript(BlackScreenBypass.WEB_BYPASS_INJECTION_JS, null)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    view?.evaluateJavascript(BlackScreenBypass.WEB_BYPASS_INJECTION_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(BlackScreenBypass.WEB_BYPASS_INJECTION_JS, null)
                    updateRecordButtonState()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    return scheme != "http" && scheme != "https"
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar?.let { bar ->
                        if (newProgress in 1..99) {
                            bar.visibility = View.VISIBLE
                            bar.progress = newProgress
                        } else {
                            bar.visibility = View.GONE
                        }
                    }
                    view?.evaluateJavascript(BlackScreenBypass.WEB_BYPASS_INJECTION_JS, null)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }
        webView = wv
        webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            webContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)

        val initialUrl = resolveInitialUrl(intent)
        urlInput?.setText(initialUrl)
        wv.loadUrl(initialUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = resolveInitialUrl(intent)
        urlInput?.setText(url)
        webView?.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        BlackScreenBypass.prepareWindowForCapture(window)
        updateRecordButtonState()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            TouchFeedbackOverlay.recordTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun buildTopBar(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121A1F"))
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }

        // Row 1: URL input + Go + Close
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = makeChip("←", false) {
            if (webView?.canGoBack() == true) webView?.goBack() else finish()
        }
        urlRow.addView(backBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(6) })

        val input = EditText(this).apply {
            hint = "Enter website URL or search…"
            setHintTextColor(Color.parseColor("#72817A"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#192329"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#2A383D"))
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    navigateToInput()
                    true
                } else {
                    false
                }
            }
        }
        urlInput = input
        urlRow.addView(input, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(6) })

        val goBtn = makeChip("Go", true) { navigateToInput() }
        urlRow.addView(goBtn, LinearLayout.LayoutParams(dp(42), dp(34)).apply { rightMargin = dp(6) })

        val closeBtn = makeChip("✕", false) { finish() }
        urlRow.addView(closeBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        container.addView(
            urlRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Row 2: Bypass status + Direct Hardware Screenshot + Record + Brush
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            topPadding(dp(7))
        }

        val statusLabel = TextView(this).apply {
            text = "● BYPASS ON"
            textSize = 9.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.parseColor("#B7F36B"))
        }
        actionRow.addView(
            statusLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val directShotBtn = makeChip("📸 Direct Shot", true) {
            captureDirectHardwareScreenshot()
        }
        actionRow.addView(
            directShotBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30),
            ).apply { rightMargin = dp(6) },
        )

        val recBtn = makeChip("● Record", false) {
            toggleWebRecording()
        }
        recordBtn = recBtn
        actionRow.addView(
            recBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30),
            ).apply { rightMargin = dp(6) },
        )

        val brushBtn = makeChip("✎ Brush", false) {
            BrushOverlay.toggle(this)
        }
        actionRow.addView(
            brushBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)),
        )

        container.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        return container
    }

    private fun LinearLayout.topPadding(px: Int) {
        setPadding(paddingLeft, px, paddingRight, paddingBottom)
    }

    private fun navigateToInput() {
        val raw = urlInput?.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val normalized = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(raw)}"
        }
        urlInput?.setText(normalized)
        SettingsStore.setLastWebUrl(this, normalized)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlInput?.windowToken, 0)
        webView?.loadUrl(normalized)
    }

    /**
     * Takes a screenshot directly from the WebView hardware surface via PixelCopy / Canvas without
     * using MediaProjection — 100% immune to OS FLAG_SECURE blackouts and overlay bubbles.
     */
    private fun captureDirectHardwareScreenshot() {
        val target = webView ?: return
        FloatingOverlayService.setHiddenForScreenshot(this, true)
        TouchFeedbackOverlay.setTemporarilyHiddenForScreenshot(true)

        target.postDelayed({
            BlackScreenBypass.captureViewDirectly(this, target) { bitmap ->
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                TouchFeedbackOverlay.setTemporarilyHiddenForScreenshot(false)

                if (bitmap == null) {
                    CaptureIntents.toast(this, "Could not capture the webpage.")
                    return@captureViewDirectly
                }
                Thread {
                    val savedUri = saveBitmapToSink(bitmap)
                    bitmap.recycle()
                    runOnUiThread {
                        if (savedUri != null) {
                            CaptureIntents.toast(
                                this,
                                "Bypass screenshot saved to ${SettingsStore.describeSaveLocation(this)}.",
                            )
                        } else {
                            CaptureIntents.toast(this, "Could not save the screenshot.")
                        }
                    }
                }.start()
            }
        }, 80L)
    }

    private fun saveBitmapToSink(bitmap: Bitmap): android.net.Uri? {
        val sink = CaptureSink.create(this, CaptureKind.SCREENSHOT, ".png") ?: return null
        val stream: OutputStream = sink.openStreamForWrite(this) ?: run {
            sink.discard(this)
            return null
        }
        return try {
            stream.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("PNG compression failed")
                }
                out.flush()
            }
            sink.publish(this)
        } catch (_: Exception) {
            sink.discard(this)
            null
        }
    }

    private fun toggleWebRecording() {
        if (ScreenCaptureService.isRecording) {
            CaptureIntents.stopScreenCapture(this)
            CaptureIntents.toast(this, "Saving the screen recording…")
            recordBtn?.postDelayed({ updateRecordButtonState() }, 400L)
            return
        }
        val consent = CaptureIntents.projectionConsentIntent(this)
        if (consent != null) {
            runCatching { projectionLauncher.launch(consent) }
        }
    }

    private fun updateRecordButtonState() {
        val btn = recordBtn ?: return
        val recording = ScreenCaptureService.isRecording
        btn.text = if (recording) "■ Stop" else "● Record"
        btn.setTextColor(if (recording) Color.WHITE else Color.WHITE)
        btn.background = GradientDrawable().apply {
            setColor(if (recording) Color.parseColor("#E54655") else Color.parseColor("#2A383D"))
            cornerRadius = dp(9).toFloat()
        }
    }

    private fun resolveInitialUrl(intent: Intent?): String {
        val explicit = intent?.getStringExtra(CaptureContract.EXTRA_WEB_URL)?.trim()
        if (!explicit.isNullOrEmpty()) return explicit

        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val urlRegex = Regex("https?://[^\\s]+")
            val match = urlRegex.find(shared)?.value
            if (!match.isNullOrBlank()) return match
        }
        return SettingsStore.lastWebUrl(this)
    }

    private fun makeChip(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (accent) Color.parseColor("#10170D") else Color.WHITE)
            gravity = Gravity.CENTER
            val padH = dp(10)
            setPadding(padH, 0, padH, 0)
            background = GradientDrawable().apply {
                setColor(if (accent) Color.parseColor("#B7F36B") else Color.parseColor("#2A383D"))
                cornerRadius = dp(9).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
