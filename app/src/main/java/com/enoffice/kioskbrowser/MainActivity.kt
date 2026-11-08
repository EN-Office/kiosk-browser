package com.enoffice.kioskbrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.enoffice.kioskbrowser.URL"
        const val EXTRA_AUTO_SCROLL = "com.enoffice.kioskbrowser.AUTO_SCROLL"
        private const val WELCOME_URL = "file:///android_asset/welcome.html"
        private const val BRIDGE_NAME = "KioskBridge"
        /** 読み込みが終わらない場合に再読み込みするまでの時間 */
        private const val LOAD_TIMEOUT_MS = 60_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var touchBlocker: View
    private lateinit var menuButton: FloatingActionButton
    private lateinit var progress: LinearProgressIndicator

    private val handler = Handler(Looper.getMainLooper())
    private var injectionScript = ""
    private var documentStartSupported = false
    private var webViewDestroyed = false

    /** 現在のページで KioskBridge に接続してきたフレーム（iframe含む）。 */
    private val frames = LinkedHashSet<JavaScriptReplyProxy>()

    // ---- 自動スクロールの状態 ----
    private var autoScroll = false
    private var cycleStarted = false
    private var cycleId = 0
    private var bottomReached = false
    private var frameScrolling = false

    private var menuDialog: AlertDialog? = null

    private val reloadRunnable = Runnable { reloadPage() }
    private val noScrollRunnable = Runnable { if (autoScroll && !frameScrolling) reloadPage() }
    private val loadTimeoutRunnable = Runnable { if (autoScroll) reloadPage() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        webView = findViewById(R.id.webView)
        touchBlocker = findViewById(R.id.touchBlocker)
        menuButton = findViewById(R.id.menuButton)
        progress = findViewById(R.id.progress)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        setupWebView()
        setupTouchBlocker()
        menuButton.setOnClickListener { showMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!autoScroll && webView.canGoBack()) webView.goBack() else showMenu()
            }
        })

        applyAutoScroll(prefs.autoScroll, restartCycle = false)

        if (!handleLaunchIntent(intent)) {
            val last = prefs.lastUrl
            if (last.isNullOrBlank()) {
                webView.loadUrl(WELCOME_URL)
                handler.post { showMenu() }
            } else {
                webView.loadUrl(last)
            }
        }
        ShortcutHelper.updateDynamicShortcuts(this, prefs.bookmarks)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** ショートカットから起動された場合、そのURL（と自動スクロール設定）で開く。 */
    private fun handleLaunchIntent(intent: Intent?): Boolean {
        val i = intent ?: return false
        val url = i.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) return false
        if (i.hasExtra(EXTRA_AUTO_SCROLL)) {
            applyAutoScroll(i.getBooleanExtra(EXTRA_AUTO_SCROLL, false), restartCycle = false)
        }
        // 同じインテントで再度開かないよう消費する
        i.removeExtra(EXTRA_URL)
        menuDialog?.dismiss()
        webView.loadUrl(url)
        return true
    }

    // ------------------------------------------------------------------
    // 全画面表示
    // ------------------------------------------------------------------

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun setupWebView() {
        injectionScript = assets.open("kiosk.js").bufferedReader().use { it.readText() }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            // target="_blank" や window.open も同じ画面で開く
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // GAS は script.google.com と googleusercontent.com をまたぐので必須
            setAcceptThirdPartyCookies(webView, true)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf("*")) { _, message, _, _, replyProxy ->
                onBridgeMessage(message.data, replyProxy)
            }
        }
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            // すべてのフレーム（GAS の内側 iframe も含む）に注入
            WebViewCompat.addDocumentStartJavaScript(webView, injectionScript, setOf("*"))
        } else {
            Toast.makeText(this, R.string.webview_too_old, Toast.LENGTH_LONG).show()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return false
                if (scheme in setOf("http", "https", "about", "file", "data", "blob", "javascript")) return false
                openExternal(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                resetCycle()
                if (autoScroll) handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                handler.removeCallbacks(loadTimeoutRunnable)
                if (!documentStartSupported) view.evaluateJavascript(injectionScript, null)
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    prefs.lastUrl = url
                }
                CookieManager.getInstance().flush()
                if (autoScroll) startCycle()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // 店頭モニター向け：通信エラー時は「表示時間」経過後に自動で再読み込みされる
                // （onPageFinished → startCycle → noScrollRunnable）。
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 長時間稼働中にレンダラーが落ちてもアプリごと落ちないようにする
                destroyWebView()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                progress.isVisible = newProgress < 100 && !autoScroll
            }
        }
    }

    private fun openExternal(url: String) {
        if (autoScroll) return // 自動スクロール中は他アプリを開かない
        try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 対応アプリがない場合は無視
        }
    }

    private fun reloadPage() {
        handler.removeCallbacks(reloadRunnable)
        handler.removeCallbacks(noScrollRunnable)
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") {
            prefs.lastUrl?.let { webView.loadUrl(it) }
        } else {
            webView.reload()
        }
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        handler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        if (!webViewDestroyed) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        menuDialog?.dismiss()
        destroyWebView()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 自動スクロール（ネイティブ側の制御）
    //   1. ページ読み込み完了 → 全フレームに start を送る
    //   2. 各フレームは「開始前の待ち時間」後にスクロールし、最下部で bottom を返す
    //   3. bottom を受けたら「最下部での待ち時間」後に再読み込み → 1 に戻る
    //   4. どのフレームもスクロールしない（1画面に収まる）場合は「表示時間」後に再読み込み
    // ------------------------------------------------------------------

    private fun onBridgeMessage(data: String?, proxy: JavaScriptReplyProxy) {
        val json = try {
            JSONObject(data ?: return)
        } catch (e: Exception) {
            return
        }
        when (json.optString("t")) {
            "ready" -> {
                frames.add(proxy)
                if (autoScroll && cycleStarted) sendStart(proxy)
            }
            "scrolling" -> if (json.optInt("cycle", -1) == cycleId) {
                frameScrolling = true
                handler.removeCallbacks(noScrollRunnable)
            }
            "bottom" -> if (autoScroll && json.optInt("cycle", -1) == cycleId && !bottomReached) {
                bottomReached = true
                handler.postDelayed(reloadRunnable, prefs.bottomPauseSec * 1000L)
            }
        }
    }

    private fun post(proxy: JavaScriptReplyProxy, json: JSONObject) {
        runCatching { proxy.postMessage(json.toString()) }
    }

    private fun sendStart(proxy: JavaScriptReplyProxy) {
        post(
            proxy,
            JSONObject()
                .put("cmd", "start")
                .put("cycle", cycleId)
                .put("speed", prefs.speed)
                .put("topPause", prefs.topPauseSec * 1000)
        )
    }

    private fun startCycle() {
        if (cycleStarted) return
        cycleStarted = true
        cycleId++
        bottomReached = false
        frameScrolling = false
        frames.forEach { sendStart(it) }
        handler.removeCallbacks(noScrollRunnable)
        handler.postDelayed(noScrollRunnable, (prefs.topPauseSec + prefs.staySec) * 1000L)
    }

    private fun resetCycle() {
        cycleStarted = false
        bottomReached = false
        frameScrolling = false
        frames.clear()
        handler.removeCallbacks(reloadRunnable)
        handler.removeCallbacks(noScrollRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
    }

    private fun applyAutoScroll(on: Boolean, restartCycle: Boolean) {
        autoScroll = on
        prefs.autoScroll = on
        touchBlocker.isVisible = on
        menuButton.isVisible = !on
        if (on) {
            progress.isVisible = false
            if (restartCycle) {
                cycleStarted = false
                if (webView.progress >= 100) startCycle()
            }
        } else {
            cycleStarted = false
            handler.removeCallbacks(reloadRunnable)
            handler.removeCallbacks(noScrollRunnable)
            handler.removeCallbacks(loadTimeoutRunnable)
            frames.forEach { post(it, JSONObject().put("cmd", "stop")) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchBlocker() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                showMenu()
                return true
            }
        })
        // 自動スクロール中はすべてのタッチをここで受け止め、WebView には渡さない
        touchBlocker.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    // ------------------------------------------------------------------
    // メニュー（ダブルタップ / メニューボタン / 戻るキー）
    // ------------------------------------------------------------------

    private fun showMenu() {
        if (menuDialog?.isShowing == true || isFinishing) return

        val view = layoutInflater.inflate(R.layout.dialog_menu, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        // URL入力
        val urlInput = view.findViewById<TextInputEditText>(R.id.urlInput)
        urlInput.setText(webView.url?.takeIf { it.startsWith("http") } ?: "")
        fun openFromInput() {
            val url = normalizeUrl(urlInput.text?.toString().orEmpty())
            if (url == null) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            } else {
                webView.loadUrl(url)
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.btnOpen).setOnClickListener { openFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openFromInput(); true
            } else false
        }

        // 自動スクロール
        view.findViewById<MaterialSwitch>(R.id.switchAutoScroll).apply {
            isChecked = autoScroll
            setOnCheckedChangeListener { _, checked -> applyAutoScroll(checked, restartCycle = true) }
        }
        bindSlider(view, R.id.sliderSpeed, R.id.labelSpeed, R.string.label_speed, prefs.speed) {
            prefs.speed = it
            frames.forEach { f -> post(f, JSONObject().put("cmd", "speed").put("speed", it)) }
        }
        bindSlider(view, R.id.sliderTopPause, R.id.labelTopPause, R.string.label_top_pause, prefs.topPauseSec) {
            prefs.topPauseSec = it
        }
        bindSlider(view, R.id.sliderBottomPause, R.id.labelBottomPause, R.string.label_bottom_pause, prefs.bottomPauseSec) {
            prefs.bottomPauseSec = it
        }
        bindSlider(view, R.id.sliderStay, R.id.labelStay, R.string.label_stay, prefs.staySec) {
            prefs.staySec = it
        }

        // ブックマーク一覧
        val list = view.findViewById<LinearLayout>(R.id.bookmarkList)
        val empty = view.findViewById<TextView>(R.id.emptyBookmarks)
        fun refresh() {
            list.removeAllViews()
            val bookmarks = prefs.bookmarks
            empty.isVisible = bookmarks.isEmpty()
            bookmarks.forEach { bm ->
                val row = layoutInflater.inflate(R.layout.item_bookmark, list, false)
                row.findViewById<TextView>(R.id.bmTitle).text = bm.title
                row.findViewById<TextView>(R.id.bmUrl).text = bm.url
                row.findViewById<View>(R.id.bmMain).apply {
                    setOnClickListener {
                        webView.loadUrl(bm.url)
                        dialog.dismiss()
                    }
                    setOnLongClickListener {
                        renameBookmark(bm) { refresh() }
                        true
                    }
                }
                row.findViewById<View>(R.id.bmPin).setOnClickListener { pinBookmark(bm) }
                row.findViewById<View>(R.id.bmDelete).setOnClickListener {
                    saveBookmarks(prefs.bookmarks.filterNot { it.url == bm.url })
                    refresh()
                }
                list.addView(row)
            }
        }
        refresh()

        view.findViewById<View>(R.id.btnBookmark).setOnClickListener {
            addCurrentBookmark()
            refresh()
        }
        view.findViewById<View>(R.id.btnReload).setOnClickListener {
            dialog.dismiss()
            reloadPage()
        }
        view.findViewById<View>(R.id.btnExit).setOnClickListener {
            dialog.dismiss()
            finishAndRemoveTask()
        }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            menuDialog = null
            enterFullscreen()
        }
        menuDialog = dialog
        dialog.show()
    }

    private fun bindSlider(
        root: View, sliderId: Int, labelId: Int, labelRes: Int, initial: Int, onChange: (Int) -> Unit
    ) {
        val slider = root.findViewById<Slider>(sliderId)
        val label = root.findViewById<TextView>(labelId)
        val step = slider.stepSize
        // Slider は stepSize の倍数以外を設定すると例外になるので丸める
        val snapped = (((initial - slider.valueFrom) / step).roundToInt() * step + slider.valueFrom)
            .coerceIn(slider.valueFrom, slider.valueTo)
        slider.value = snapped
        label.text = getString(labelRes, snapped.toInt())
        slider.addOnChangeListener { _, value, fromUser ->
            label.text = getString(labelRes, value.toInt())
            if (fromUser) onChange(value.toInt())
        }
    }

    private fun normalizeUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s else "https://$s"
    }

    // ------------------------------------------------------------------
    // ブックマーク / ショートカット
    // ------------------------------------------------------------------

    private fun saveBookmarks(list: List<Bookmark>) {
        prefs.bookmarks = list
        ShortcutHelper.updateDynamicShortcuts(this, list)
    }

    private fun addCurrentBookmark() {
        val url = webView.url
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, R.string.bookmark_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val current = prefs.bookmarks
        if (current.any { it.url == url }) {
            Toast.makeText(this, R.string.bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        val title = webView.title?.takeIf { it.isNotBlank() && it != url } ?: Uri.parse(url).host ?: url
        saveBookmarks(current + Bookmark(title, url))
        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun renameBookmark(bm: Bookmark, onDone: () -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(bm.title)
            setSingleLine()
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveBookmarks(prefs.bookmarks.map { if (it.url == bm.url) it.copy(title = name) else it })
                    onDone()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pinBookmark(bm: Bookmark) {
        if (!ShortcutHelper.isPinSupported(this)) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_mode_title)
            .setItems(arrayOf(getString(R.string.pin_mode_auto), getString(R.string.pin_mode_normal))) { _, which ->
                if (ShortcutHelper.requestPin(this, bm, autoScroll = which == 0)) {
                    Toast.makeText(this, R.string.pin_requested, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
