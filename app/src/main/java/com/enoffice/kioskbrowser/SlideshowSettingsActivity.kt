package com.enoffice.kioskbrowser

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * スライドショー（デジタルサイネージ）の設定画面。
 *  - タブ1「画像・PDF」 : 端末内の画像・PDFを取り込む
 *  - タブ2「Webページ」 : 表示したいURLをリストとして登録する
 *  - タブ3「スライドショー」: 表示する順番・秒数を決める
 */
class SlideshowSettingsActivity : AppCompatActivity() {

    companion object {
        /** RESULT_OK のとき、スライドショーを最初から開始する。 */
        const val RESULT_START = Activity.RESULT_FIRST_USER + 1
        private const val STATE_TAB = "tab"
    }

    private lateinit var store: SlideStore
    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private val thumbCache = HashMap<String, android.graphics.Bitmap?>()

    private lateinit var panes: List<View>
    private lateinit var listMedia: LinearLayout
    private lateinit var listPages: LinearLayout
    private lateinit var listSlides: LinearLayout

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow_settings)
        store = SlideStore(this)
        prefs = Prefs(this)

        // Android 15 以降は端末の画面端まで描画されるので、システムバーの分だけ余白をとる
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        panes = listOf(findViewById(R.id.paneMedia), findViewById(R.id.panePages), findViewById(R.id.paneSlides))
        listMedia = findViewById(R.id.listMedia)
        listPages = findViewById(R.id.listPages)
        listSlides = findViewById(R.id.listSlides)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        listOf(R.string.ss_tab_media, R.string.ss_tab_pages, R.string.ss_tab_slides).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPane(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val initialTab = savedInstanceState?.getInt(STATE_TAB) ?: if (store.slides.isNotEmpty()) 2 else 0
        tabs.getTabAt(initialTab)?.select()
        showPane(initialTab)

        // 画像・PDF
        findViewById<View>(R.id.btnImport).setOnClickListener {
            pickFiles.launch(arrayOf("image/*", "application/pdf"))
        }

        // Webページ
        val urlInput = findViewById<TextInputEditText>(R.id.pageUrl)
        val titleInput = findViewById<TextInputEditText>(R.id.pageTitle)
        findViewById<View>(R.id.btnAddPage).setOnClickListener {
            val url = normalizeUrl(urlInput.text?.toString().orEmpty())
            if (url == null) {
                toast(R.string.invalid_url)
                return@setOnClickListener
            }
            addPage(titleInput.text?.toString().orEmpty(), url)
            urlInput.setText("")
            titleInput.setText("")
        }
        findViewById<View>(R.id.btnFromBookmarks).setOnClickListener { importBookmarks() }

        // スライドショー
        findViewById<View>(R.id.btnAddSlide).setOnClickListener { chooseSlideToAdd() }
        findViewById<View>(R.id.btnBulkDuration).setOnClickListener { bulkDuration() }
        findViewById<MaterialSwitch>(R.id.switchFill).apply {
            isChecked = store.fillScreen
            setOnCheckedChangeListener { _, checked -> store.fillScreen = checked }
        }
        findViewById<View>(R.id.btnPinSlideshow).setOnClickListener {
            if (!ShortcutHelper.isPinSupported(this)) {
                toast(R.string.pin_unsupported)
            } else if (ShortcutHelper.requestPinSlideshow(this)) {
                toast(R.string.pin_requested)
            }
        }
        findViewById<View>(R.id.btnStart).setOnClickListener {
            if (store.buildPlaylist().isEmpty()) {
                toast(R.string.ss_nothing_to_play)
                return@setOnClickListener
            }
            setResult(RESULT_START)
            finish()
        }

        refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, panes.indexOfFirst { it.isVisible }.coerceAtLeast(0))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showPane(index: Int) {
        panes.forEachIndexed { i, v -> v.isVisible = i == index }
        if (index == 2) refreshSlides()
    }

    private fun refreshAll() {
        refreshMedia()
        refreshPages()
        refreshSlides()
    }

    // ------------------------------------------------------------------
    // 画像・PDF
    // ------------------------------------------------------------------

    private fun importFiles(uris: List<Uri>) {
        toast(R.string.ss_importing)
        io.execute {
            val added = ArrayList<MediaFile>()
            var failed = 0
            for (uri in uris) {
                val m = runCatching { copyIn(uri) }.getOrNull()
                if (m == null) failed++ else added += m
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (added.isNotEmpty()) {
                    store.media = store.media + added
                    store.slides = store.slides + added.map { store.newMediaSlide(it) }
                    Toast.makeText(this, getString(R.string.ss_imported, added.size), Toast.LENGTH_LONG).show()
                }
                if (failed > 0) {
                    Toast.makeText(this, getString(R.string.ss_import_failed, failed), Toast.LENGTH_LONG).show()
                }
                refreshAll()
            }
        }
    }

    /** 選んだファイルをアプリ内にコピーする（元ファイルを消しても表示できるように）。 */
    private fun copyIn(uri: Uri): MediaFile? {
        val mime = contentResolver.getType(uri).orEmpty()
        val name = displayName(uri) ?: uri.lastPathSegment ?: "file"
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        val isImage = mime.startsWith("image/")
        if (!isPdf && !isImage) return null

        val id = SlideStore.newId()
        val ext = if (isPdf) "pdf"
        else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: name.substringAfterLast('.', "img")
        val fileName = "$id.$ext"
        val dest = File(store.mediaDir, fileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null

            // 実際に表示できるか確認しておく
            val pages = if (isPdf) {
                SlideImageLoader.pdfPageCount(dest).also { if (it <= 0) error("empty pdf") }
            } else {
                SlideImageLoader.decodeImage(dest, 64, 64) ?: error("cannot decode")
                1
            }
            return MediaFile(
                id = id,
                title = name.substringBeforeLast('.').ifBlank { name },
                fileName = fileName,
                type = if (isPdf) MediaType.PDF else MediaType.IMAGE,
                pageCount = pages,
            )
        } catch (e: Exception) {
            dest.delete()
            return null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun refreshMedia() {
        listMedia.removeAllViews()
        val media = store.media
        findViewById<View>(R.id.emptyMedia).isVisible = media.isEmpty()
        media.forEach { m ->
            val row = newRow(listMedia)
            row.title.text = m.title
            row.sub.text = mediaLabel(m)
            bindThumb(row.thumb, m)
            row.add.isVisible = true
            row.add.setOnClickListener {
                store.addSlide(store.newMediaSlide(m))
                toast(R.string.ss_added_to_slideshow)
                refreshSlides()
            }
            row.edit.setOnClickListener {
                promptText(R.string.ss_rename, m.title) { name ->
                    store.media = store.media.map { if (it.id == m.id) it.copy(title = name) else it }
                    refreshAll()
                }
            }
            row.delete.setOnClickListener {
                confirmDelete(m.title) {
                    store.deleteMedia(m)
                    thumbCache.remove(m.id)
                    refreshAll()
                }
            }
            listMedia.addView(row.view)
        }
    }

    private fun mediaLabel(m: MediaFile) =
        if (m.type == MediaType.PDF) getString(R.string.ss_label_pdf, m.pageCount) else getString(R.string.ss_label_image)

    private fun bindThumb(view: ImageView, m: MediaFile) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        if (thumbCache.containsKey(m.id)) {
            val bmp = thumbCache[m.id]
            if (bmp != null) view.setImageBitmap(bmp) else view.setImageResource(iconOf(m.type))
            return
        }
        view.setImageResource(iconOf(m.type))
        view.tag = m.id
        val size = (56 * resources.displayMetrics.density).toInt()
        io.execute {
            val bmp = SlideImageLoader.thumbnail(store, m, size)
            runOnUiThread {
                thumbCache[m.id] = bmp
                if (bmp != null && view.tag == m.id) view.setImageBitmap(bmp)
            }
        }
    }

    private fun iconOf(type: MediaType) = if (type == MediaType.PDF) R.drawable.ic_pdf else R.drawable.ic_image

    // ------------------------------------------------------------------
    // Webページ
    // ------------------------------------------------------------------

    private fun addPage(title: String, url: String) {
        val t = title.trim().ifBlank { Uri.parse(url).host ?: url }
        val page = WebPage(SlideStore.newId(), t, url)
        store.pages = store.pages + page
        store.addSlide(store.newWebSlide(page))
        toast(R.string.ss_page_added)
        refreshAll()
    }

    private fun importBookmarks() {
        val bookmarks = prefs.bookmarks
        if (bookmarks.isEmpty()) {
            toast(R.string.no_bookmarks)
            return
        }
        val checked = BooleanArray(bookmarks.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ss_from_bookmarks)
            .setMultiChoiceItems(bookmarks.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.ok) { _, _ ->
                bookmarks.filterIndexed { i, _ -> checked[i] }.forEach { addPage(it.title, it.url) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshPages() {
        listPages.removeAllViews()
        val pages = store.pages
        findViewById<View>(R.id.emptyPages).isVisible = pages.isEmpty()
        pages.forEach { p ->
            val row = newRow(listPages)
            row.title.text = p.title
            row.sub.text = p.url
            row.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            row.thumb.setImageResource(R.drawable.ic_language)
            row.add.isVisible = true
            row.add.setOnClickListener {
                store.addSlide(store.newWebSlide(p))
                toast(R.string.ss_added_to_slideshow)
                refreshSlides()
            }
            row.edit.setOnClickListener { editPage(p) }
            row.delete.setOnClickListener {
                confirmDelete(p.title) {
                    store.deletePage(p)
                    refreshAll()
                }
            }
            listPages.addView(row.view)
        }
    }

    private fun editPage(p: WebPage) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val urlField = EditText(this).apply {
            setText(p.url)
            setSingleLine()
            hint = getString(R.string.url_hint)
        }
        val titleField = EditText(this).apply {
            setText(p.title)
            setSingleLine()
            hint = getString(R.string.ss_page_title_hint)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(titleField)
            addView(urlField)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ss_edit_page)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = normalizeUrl(urlField.text.toString())
                if (url == null) {
                    toast(R.string.invalid_url)
                } else {
                    val title = titleField.text.toString().trim().ifBlank { Uri.parse(url).host ?: url }
                    store.pages = store.pages.map { if (it.id == p.id) it.copy(title = title, url = url) else it }
                    refreshAll()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // スライドショー（順番と秒数）
    // ------------------------------------------------------------------

    private fun refreshSlides() {
        listSlides.removeAllViews()
        val slides = store.slides
        val mediaMap = store.media.associateBy { it.id }
        val pageMap = store.pages.associateBy { it.id }
        findViewById<View>(R.id.emptySlides).isVisible = slides.isEmpty()

        slides.forEachIndexed { index, s ->
            val row = newRow(listSlides)
            when (s.kind) {
                SlideKind.MEDIA -> {
                    val m = mediaMap[s.refId] ?: return@forEachIndexed
                    row.title.text = getString(R.string.ss_numbered, index + 1, m.title)
                    row.sub.text = if (m.type == MediaType.PDF) {
                        getString(R.string.ss_sub_pdf, m.pageCount, s.durationSec)
                    } else {
                        getString(R.string.ss_sub_image, s.durationSec)
                    }
                    bindThumb(row.thumb, m)
                }
                SlideKind.WEB -> {
                    val p = pageMap[s.refId] ?: return@forEachIndexed
                    row.title.text = getString(R.string.ss_numbered, index + 1, p.title)
                    row.sub.text = if (s.scroll) {
                        getString(R.string.ss_sub_web_scroll, s.afterScrollSec, s.durationSec)
                    } else {
                        getString(R.string.ss_sub_web_fixed, s.durationSec)
                    }
                    row.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    row.thumb.setImageResource(R.drawable.ic_language)
                }
            }
            row.view.alpha = if (s.enabled) 1f else 0.5f
            row.enabled.isVisible = true
            row.enabled.isChecked = s.enabled
            row.enabled.setOnCheckedChangeListener { _, on ->
                updateSlide(s.id) { it.copy(enabled = on) }
                row.view.alpha = if (on) 1f else 0.5f
            }
            row.up.isVisible = true
            row.down.isVisible = true
            row.up.isEnabled = index > 0
            row.down.isEnabled = index < slides.size - 1
            row.up.setOnClickListener { move(index, -1) }
            row.down.setOnClickListener { move(index, +1) }
            row.main.setOnClickListener { editSlide(s) }
            row.edit.setOnClickListener { editSlide(s) }
            row.delete.setOnClickListener {
                store.slides = store.slides.filterNot { it.id == s.id }
                refreshSlides()
            }
            listSlides.addView(row.view)
        }
    }

    private fun updateSlide(id: String, f: (Slide) -> Slide) {
        store.slides = store.slides.map { if (it.id == id) f(it) else it }
    }

    private fun move(index: Int, delta: Int) {
        val list = store.slides.toMutableList()
        val to = index + delta
        if (to !in list.indices) return
        list.add(to, list.removeAt(index))
        store.slides = list
        refreshSlides()
    }

    private fun chooseSlideToAdd() {
        val media = store.media
        val pages = store.pages
        if (media.isEmpty() && pages.isEmpty()) {
            toast(R.string.ss_library_empty)
            return
        }
        val labels = media.map { "${mediaLabel(it)}  ${it.title}" } +
            pages.map { "${getString(R.string.ss_label_web)}  ${it.title}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ss_add_slide)
            .setItems(labels.toTypedArray()) { _, i ->
                val slide = if (i < media.size) store.newMediaSlide(media[i]) else store.newWebSlide(pages[i - media.size])
                store.addSlide(slide)
                refreshSlides()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editSlide(s: Slide) {
        val isWeb = s.kind == SlideKind.WEB
        val isPdf = !isWeb && store.media.firstOrNull { it.id == s.refId }?.type == MediaType.PDF

        val v = layoutInflater.inflate(R.layout.dialog_slide_edit, null)
        val scroll = v.findViewById<MaterialSwitch>(R.id.editScroll)
        val afterLayout = v.findViewById<TextInputLayout>(R.id.editAfterScrollLayout)
        val after = v.findViewById<TextInputEditText>(R.id.editAfterScroll)
        val durLayout = v.findViewById<TextInputLayout>(R.id.editDurationLayout)
        val dur = v.findViewById<TextInputEditText>(R.id.editDuration)
        val help = v.findViewById<TextView>(R.id.editHelp)

        scroll.isVisible = isWeb
        afterLayout.isVisible = isWeb
        scroll.isChecked = s.scroll
        after.setText(s.afterScrollSec.toString())
        dur.setText(s.durationSec.toString())

        fun updateLabels() {
            durLayout.hint = getString(
                when {
                    isWeb && scroll.isChecked -> R.string.ss_edit_duration_noscroll
                    isWeb -> R.string.ss_edit_duration_web
                    isPdf -> R.string.ss_edit_duration_pdf
                    else -> R.string.ss_edit_duration_image
                }
            )
            afterLayout.isEnabled = scroll.isChecked
            help.text = getString(
                when {
                    isWeb && scroll.isChecked -> R.string.ss_help_web_scroll
                    isWeb -> R.string.ss_help_web_fixed
                    isPdf -> R.string.ss_help_pdf
                    else -> R.string.ss_help_image
                }
            )
        }
        updateLabels()
        scroll.setOnCheckedChangeListener { _, _ -> updateLabels() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ss_edit_slide)
            .setView(v)
            .setPositiveButton(R.string.ok) { _, _ ->
                val d = dur.text?.toString()?.toIntOrNull()?.coerceIn(1, 86_400) ?: s.durationSec
                val a = after.text?.toString()?.toIntOrNull()?.coerceIn(0, 86_400) ?: s.afterScrollSec
                updateSlide(s.id) { it.copy(durationSec = d, afterScrollSec = a, scroll = scroll.isChecked) }
                refreshSlides()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bulkDuration() {
        promptText(R.string.ss_bulk_duration_title, SlideStore.DEFAULT_IMAGE_SEC.toString(), numeric = true) { text ->
            val sec = text.toIntOrNull()?.coerceIn(1, 86_400) ?: return@promptText
            store.slides = store.slides.map { if (it.kind == SlideKind.MEDIA) it.copy(durationSec = sec) else it }
            refreshSlides()
        }
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    private class Row(val view: View) {
        val enabled: MaterialCheckBox = view.findViewById(R.id.rowEnabled)
        val thumb: ImageView = view.findViewById(R.id.rowThumb)
        val main: View = view.findViewById(R.id.rowMain)
        val title: TextView = view.findViewById(R.id.rowTitle)
        val sub: TextView = view.findViewById(R.id.rowSub)
        val up: View = view.findViewById(R.id.rowUp)
        val down: View = view.findViewById(R.id.rowDown)
        val add: View = view.findViewById(R.id.rowAdd)
        val edit: View = view.findViewById(R.id.rowEdit)
        val delete: View = view.findViewById(R.id.rowDelete)
    }

    private fun newRow(parent: LinearLayout) = Row(layoutInflater.inflate(R.layout.item_slide, parent, false))

    private fun promptText(titleRes: Int, initial: String, numeric: Boolean = false, onOk: (String) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) onOk(t)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(name: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ss_delete_title)
            .setMessage(getString(R.string.ss_delete_message, name))
            .setPositiveButton(R.string.delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun normalizeUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s else "https://$s"
    }
}
