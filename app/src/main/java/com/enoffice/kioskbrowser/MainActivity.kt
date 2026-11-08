package com.enoffice.kioskbrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.app.Activity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.enoffice.kioskbrowser.URL"
        const val EXTRA_AUTO_SCROLL = "com.enoffice.kioskbrowser.AUTO_SCROLL"
        const val EXTRA_SLIDESHOW = "com.enoffice.kioskbrowser.SLIDESHOW"
        private const val WELCOME_URL = "file:///android_asset/welcome.html"
        private const val BRIDGE_NAME = "KioskBridge"
        /** 読み込みが終わらない場合に再読み込みするまでの時間 */
        private const val LOAD_TIMEOUT_MS = 60_000L
        /** スライドショーで表示できなかったスライドを飛ばすまでの時間 */
        private const val SKIP_DELAY_MS = 3_000L
        private const val FADE_MS = 400L
    }

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var touchBlocker: View
    private lateinit var menuButton: FloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var slideImage: ImageView

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

    // ---- スライドショーの状態 ----
    private var slideshowOn = false
    private var playlist: List<PlayItem> = emptyList()
    private var playIndex = -1
    private var currentItem: PlayItem? = null
    /** 画面に表示中の画像（同じ画像が続くときは読み込み直さない）。 */
    private var shownItem: PlayItem? = null
    private var slideGen = 0
    private var webSlideFailed = false
    private val decoder = Executors.newSingleThreadExecutor()

    private var menuDialog: AlertDialog? = null

    /** 自動スクロールの1周が終わったとき：通常は再読み込み、スライドショー中は次のスライドへ。 */
    private val reloadRunnable = Runnable { onCycleEnd() }
    private val noScrollRunnable = Runnable { if (cycleActive && !frameScrolling) onCycleEnd() }
    private val loadTimeoutRunnable = Runnable { if (cycleActive) onCycleEnd() }
    private val nextSlideRunnable = Runnable { nextSlide() }

    /** 画面操作を受け付けないモード（自動スクロール or スライドショー）。 */
    private val locked get() = autoScroll || slideshowOn

    /** 現在のページで「スクロール→次へ」の制御を行うか。 */
    private val cycleActive get() = if (slideshowOn) currentItem is PlayItem.Web else autoScroll

    /** 現在のページを自動スクロールさせるか。 */
    private val scrollEnabled get() = if (slideshowOn) (currentItem as? PlayItem.Web)?.scroll == true else autoScroll

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when {
                result.resultCode == SlideshowSettingsActivity.RESULT_START -> startSlideshow(fromStart = true)
                // 設定画面を開く前に再生していたら、変更を反映して再開
                prefs.slideshowOn -> startSlideshow(fromStart = false)
                else -> if (webView.url.isNullOrBlank() || webView.url == "about:blank") restoreBrowser()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        webView = findViewById(R.id.webView)
        touchBlocker = findViewById(R.id.touchBlocker)
        menuButton = findViewById(R.id.menuButton)
        progress = findViewById(R.id.progress)
        slideImage = findViewById(R.id.slideImage)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        setupWebView()
        setupTouchBlocker()
        menuButton.setOnClickListener { showMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!locked && webView.canGoBack()) webView.goBack() else showMenu()
            }
        })

        applyAutoScroll(prefs.autoScroll, restartCycle = false)

        if (!handleLaunchIntent(intent)) {
            if (prefs.slideshowOn) startSlideshow(fromStart = true) else restoreBrowser()
        }
        ShortcutHelper.updateDynamicShortcuts(this, prefs.bookmarks)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** 最後に開いていたページ（なければ案内ページ）を表示する。 */
    private fun restoreBrowser() {
        val last = prefs.lastUrl
        if (last.isNullOrBlank()) {
            webView.loadUrl(WELCOME_URL)
            handler.post { showMenu() }
        } else {
            webView.loadUrl(last)
        }
    }

    /** ショートカットから起動された場合、そのURL（と自動スクロール設定）で開く。 */
    private fun handleLaunchIntent(intent: Intent?): Boolean {
        val i = intent ?: return false
        if (i.getBooleanExtra(EXTRA_SLIDESHOW, false)) {
            i.removeExtra(EXTRA_SLIDESHOW)
            menuDialog?.dismiss()
            startSlideshow(fromStart = true)
            return true
        }
        val url = i.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) return false
        if (slideshowOn) stopSlideshow()
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
                if (isBlank(url)) return
                if (cycleActive) handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                handler.removeCallbacks(loadTimeoutRunnable)
                if (!documentStartSupported) view.evaluateJavascript(injectionScript, null)
                CookieManager.getInstance().flush()
                if (isBlank(url)) return
                if (slideshowOn) {
                    if (currentItem !is PlayItem.Web) return
                    if (webSlideFailed) {
                        // 通信エラーなどで表示できない → エラー画面は見せずに次のスライドへ
                        handler.removeCallbacks(nextSlideRunnable)
                        handler.postDelayed(nextSlideRunnable, SKIP_DELAY_MS)
                        return
                    }
                    hideSlideImage()
                    startCycle()
                    return
                }
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    prefs.lastUrl = url
                }
                if (autoScroll) startCycle()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // 店頭モニター向け：通信エラー時は「表示時間」経過後に自動で再読み込みされる
                // （onPageFinished → startCycle → noScrollRunnable）。
                // スライドショー中はエラー画面を出さずに次へ進む。
                if (slideshowOn && request.isForMainFrame && currentItem is PlayItem.Web) {
                    webSlideFailed = true
                    showBlackCover()
                }
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
                progress.isVisible = newProgress < 100 && !locked
            }
        }
    }

    private fun openExternal(url: String) {
        if (locked) return // 自動スクロール・スライドショー中は他アプリを開かない
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
        if (slideshowOn) {
            // 今のスライドを表示し直す
            playIndex = (playIndex - 1).coerceAtLeast(-1)
            shownItem = null
            nextSlide()
            return
        }
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
        decoder.shutdownNow()
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
                if (cycleActive && cycleStarted && scrollEnabled) sendStart(proxy)
            }
            "scrolling" -> if (json.optInt("cycle", -1) == cycleId) {
                frameScrolling = true
                handler.removeCallbacks(noScrollRunnable)
            }
            "bottom" -> if (cycleActive && json.optInt("cycle", -1) == cycleId && !bottomReached) {
                bottomReached = true
                val web = currentItem as? PlayItem.Web
                val pauseSec = if (slideshowOn && web != null) web.afterScrollSec else prefs.bottomPauseSec
                handler.postDelayed(reloadRunnable, pauseSec * 1000L)
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
        val scroll = scrollEnabled
        if (scroll) frames.forEach { sendStart(it) }
        // どのフレームもスクロールしない（1画面に収まる / スクロールOFF）場合の表示時間
        val web = currentItem as? PlayItem.Web
        val staySec = if (slideshowOn && web != null) web.durationSec else prefs.staySec
        val waitSec = (if (scroll) prefs.topPauseSec else 0) + staySec
        handler.removeCallbacks(noScrollRunnable)
        handler.postDelayed(noScrollRunnable, waitSec * 1000L)
    }

    private fun onCycleEnd() {
        if (slideshowOn) nextSlide() else reloadPage()
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
        updateLockUi()
        // スライドショー中は設定の保存だけ（スライドショー終了後の通常表示に反映）
        if (slideshowOn) return
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

    private fun updateLockUi() {
        touchBlocker.isVisible = locked
        menuButton.isVisible = !locked
        if (locked) progress.isVisible = false
    }

    private fun isBlank(url: String?) = url.isNullOrBlank() || url == "about:blank"

    // ------------------------------------------------------------------
    // スライドショー（デジタルサイネージ）
    //   画像   … 設定した秒数だけ表示して次へ
    //   PDF    … 1ページずつ、設定した秒数で次のページ → 最終ページの次は次のスライドへ
    //   Web    … 自動スクロールONなら下までスクロールし、スクロール完了後n秒で次へ
    //             （1画面に収まる / スクロールOFFのときは表示時間で次へ）
    //   最後まで行ったら先頭に戻って繰り返す
    // ------------------------------------------------------------------

    private fun startSlideshow(fromStart: Boolean) {
        val list = SlideStore(this).buildPlaylist()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.ss_nothing_to_play, Toast.LENGTH_LONG).show()
            if (slideshowOn) stopSlideshow() else prefs.slideshowOn = false
            if (isBlank(webView.url)) restoreBrowser()
            return
        }
        menuDialog?.dismiss()
        if (!slideshowOn) {
            // 通常表示の自動スクロールを止めてから切り替える
            handler.removeCallbacks(reloadRunnable)
            handler.removeCallbacks(noScrollRunnable)
            handler.removeCallbacks(loadTimeoutRunnable)
            frames.forEach { post(it, JSONObject().put("cmd", "stop")) }
        }
        slideshowOn = true
        prefs.slideshowOn = true
        updateLockUi()
        playlist = list
        playIndex = if (fromStart) -1 else (playIndex - 1).coerceIn(-1, list.size - 2)
        shownItem = null
        nextSlide()
    }

    private fun stopSlideshow() {
        slideshowOn = false
        prefs.slideshowOn = false
        slideGen++
        handler.removeCallbacks(nextSlideRunnable)
        resetCycle()
        currentItem = null
        shownItem = null
        playlist = emptyList()
        slideImage.animate().cancel()
        slideImage.setImageDrawable(null)
        slideImage.isVisible = false
        updateLockUi()
    }

    /** 設定画面を開いている間は再生を止めておく。 */
    private fun pauseSlideshow() {
        slideGen++
        handler.removeCallbacks(nextSlideRunnable)
        resetCycle()
        frames.forEach { post(it, JSONObject().put("cmd", "stop")) }
        currentItem = null
        showBlackCover()
        webView.loadUrl("about:blank")
    }

    private fun nextSlide() {
        if (!slideshowOn || playlist.isEmpty() || isFinishing) return
        handler.removeCallbacks(nextSlideRunnable)
        handler.removeCallbacks(reloadRunnable)
        handler.removeCallbacks(noScrollRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
        frames.forEach { post(it, JSONObject().put("cmd", "stop")) }

        playIndex = (playIndex + 1) % playlist.size
        val item = playlist[playIndex]
        currentItem = item
        webSlideFailed = false
        val gen = ++slideGen

        when (item) {
            is PlayItem.Web -> {
                // 読み込みが終わるまでは前の画像を表示したまま（onPageFinished で切り替え）
                cycleStarted = false
                shownItem = null
                webView.loadUrl(item.url)
            }
            is PlayItem.Image, is PlayItem.PdfPage -> {
                val durationMs = (if (item is PlayItem.Image) item.durationSec else (item as PlayItem.PdfPage).durationSec) * 1000L
                if (item == shownItem && slideImage.isVisible) {
                    // 同じ画像が続く（スライドが1枚だけ等）→ 読み込み直さない
                    handler.postDelayed(nextSlideRunnable, durationMs)
                    return
                }
                val (w, h) = screenSize()
                decoder.execute {
                    val bmp = runCatching { SlideImageLoader.load(item, w, h) }.getOrNull()
                    runOnUiThread {
                        if (gen != slideGen || !slideshowOn || isDestroyed) return@runOnUiThread
                        if (bmp == null) {
                            // ファイルが壊れている等 → 飛ばす
                            handler.postDelayed(nextSlideRunnable, SKIP_DELAY_MS)
                        } else {
                            showSlideBitmap(item, bmp)
                            handler.postDelayed(nextSlideRunnable, durationMs)
                        }
                    }
                }
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val w = window.decorView.width.takeIf { it > 0 } ?: dm.widthPixels
        val h = window.decorView.height.takeIf { it > 0 } ?: dm.heightPixels
        return w to h
    }

    private fun showSlideBitmap(item: PlayItem, bmp: Bitmap) {
        val fill = item is PlayItem.Image && SlideStore(this).fillScreen
        val apply = {
            slideImage.scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            slideImage.setImageBitmap(bmp)
            shownItem = item
        }
        slideImage.animate().cancel()
        if (slideImage.isVisible && slideImage.drawable != null) {
            // 画像 → 画像：一度暗くしてから切り替える
            slideImage.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction {
                apply()
                slideImage.animate().alpha(1f).setDuration(FADE_MS / 2).start()
            }.start()
        } else {
            apply()
            if (!slideImage.isVisible) slideImage.alpha = 0f
            slideImage.isVisible = true
            slideImage.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        // 裏で再生中のWebページ（動画・音声など）を止める
        if (!isBlank(webView.url)) webView.loadUrl("about:blank")
    }

    private fun hideSlideImage() {
        if (!slideImage.isVisible) return
        slideImage.animate().cancel()
        slideImage.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            slideImage.isVisible = false
            slideImage.setImageDrawable(null)
            slideImage.alpha = 1f
        }.start()
    }

    /** 黒い画面で覆う（エラー画面などを見せない）。 */
    private fun showBlackCover() {
        slideImage.animate().cancel()
        slideImage.alpha = 1f
        slideImage.isVisible = true
    }

    private fun openSlideshowSettings() {
        menuDialog?.dismiss()
        if (slideshowOn) pauseSlideshow()
        settingsLauncher.launch(Intent(this, SlideshowSettingsActivity::class.java))
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
                if (slideshowOn) stopSlideshow()
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

        // スライドショー
        view.findViewById<MaterialSwitch>(R.id.switchSlideshow).apply {
            isChecked = slideshowOn
            setOnCheckedChangeListener { sw, checked ->
                if (checked) {
                    if (SlideStore(this@MainActivity).buildPlaylist().isEmpty()) {
                        sw.isChecked = false
                        Toast.makeText(this@MainActivity, R.string.ss_nothing_to_play, Toast.LENGTH_LONG).show()
                    } else {
                        startSlideshow(fromStart = true) // メニューも閉じる
                    }
                } else if (slideshowOn) {
                    stopSlideshow()
                    restoreBrowser()
                }
            }
        }
        view.findViewById<View>(R.id.btnSlideshowSettings).setOnClickListener { openSlideshowSettings() }

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
                        if (slideshowOn) stopSlideshow()
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
