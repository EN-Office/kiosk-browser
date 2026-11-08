package com.enoffice.kioskbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// ----------------------------------------------------------------------
// データ
// ----------------------------------------------------------------------

enum class MediaType { IMAGE, PDF }

/** 端末から取り込んだ画像・PDF（アプリ内に複製して保存）。 */
data class MediaFile(
    val id: String,
    val title: String,
    val fileName: String,
    val type: MediaType,
    val pageCount: Int = 1,
)

/** 登録したWebページ。 */
data class WebPage(val id: String, val title: String, val url: String)

enum class SlideKind { MEDIA, WEB }

/**
 * スライドショーの1枚。画像・PDF・Webページの登録データ（refId）を参照する。
 *
 * - durationSec   … 画像：表示時間 / PDF：1ページあたりの表示時間 / Web：スクロールしない場合の表示時間
 * - scroll        … Web：自動スクロールするか
 * - afterScrollSec… Web：最下部までスクロールし終えてから次へ切り替えるまでの秒数
 */
data class Slide(
    val id: String,
    val kind: SlideKind,
    val refId: String,
    val enabled: Boolean = true,
    val durationSec: Int,
    val scroll: Boolean = true,
    val afterScrollSec: Int = SlideStore.DEFAULT_AFTER_SCROLL_SEC,
)

/** 再生用に展開した1コマ（PDFはページごとに分かれる）。 */
sealed class PlayItem {
    abstract val slideId: String

    data class Image(override val slideId: String, val file: File, val durationSec: Int) : PlayItem()
    data class PdfPage(override val slideId: String, val file: File, val page: Int, val durationSec: Int) : PlayItem()
    data class Web(
        override val slideId: String,
        val url: String,
        val durationSec: Int,
        val scroll: Boolean,
        val afterScrollSec: Int,
    ) : PlayItem()
}

// ----------------------------------------------------------------------
// 保存（SharedPreferences + アプリ内ファイル）
// ----------------------------------------------------------------------

class SlideStore(private val context: Context) {

    companion object {
        const val DEFAULT_IMAGE_SEC = 10
        const val DEFAULT_WEB_SEC = 30
        const val DEFAULT_AFTER_SCROLL_SEC = 5

        private const val KEY_MEDIA = "media"
        private const val KEY_PAGES = "pages"
        private const val KEY_SLIDES = "slides"
        private const val KEY_FILL = "fill_screen"

        fun newId(): String = UUID.randomUUID().toString()
    }

    private val sp = context.getSharedPreferences("kiosk_slides", Context.MODE_PRIVATE)

    val mediaDir: File get() = File(context.filesDir, "slides").apply { mkdirs() }

    fun fileOf(m: MediaFile) = File(mediaDir, m.fileName)

    /** 画像を画面いっぱいに表示する（はみ出す部分は切り取る）。false なら全体が収まるように表示。 */
    var fillScreen: Boolean
        get() = sp.getBoolean(KEY_FILL, false)
        set(value) = sp.edit().putBoolean(KEY_FILL, value).apply()

    var media: List<MediaFile>
        get() = readArray(KEY_MEDIA) { o ->
            MediaFile(
                id = o.getString("id"),
                title = o.optString("title"),
                fileName = o.getString("file"),
                type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrDefault(MediaType.IMAGE),
                pageCount = o.optInt("pages", 1).coerceAtLeast(1),
            )
        }
        set(value) = writeArray(KEY_MEDIA, value) {
            JSONObject().put("id", it.id).put("title", it.title).put("file", it.fileName)
                .put("type", it.type.name).put("pages", it.pageCount)
        }

    var pages: List<WebPage>
        get() = readArray(KEY_PAGES) { o ->
            WebPage(o.getString("id"), o.optString("title"), o.getString("url"))
        }.filter { it.url.isNotBlank() }
        set(value) = writeArray(KEY_PAGES, value) {
            JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
        }

    var slides: List<Slide>
        get() = readArray(KEY_SLIDES) { o ->
            Slide(
                id = o.getString("id"),
                kind = runCatching { SlideKind.valueOf(o.optString("kind")) }.getOrDefault(SlideKind.MEDIA),
                refId = o.getString("ref"),
                enabled = o.optBoolean("enabled", true),
                durationSec = o.optInt("duration", DEFAULT_IMAGE_SEC).coerceAtLeast(1),
                scroll = o.optBoolean("scroll", true),
                afterScrollSec = o.optInt("afterScroll", DEFAULT_AFTER_SCROLL_SEC).coerceAtLeast(0),
            )
        }
        set(value) = writeArray(KEY_SLIDES, value) {
            JSONObject().put("id", it.id).put("kind", it.kind.name).put("ref", it.refId)
                .put("enabled", it.enabled).put("duration", it.durationSec)
                .put("scroll", it.scroll).put("afterScroll", it.afterScrollSec)
        }

    fun newMediaSlide(m: MediaFile) =
        Slide(newId(), SlideKind.MEDIA, m.id, durationSec = DEFAULT_IMAGE_SEC)

    fun newWebSlide(p: WebPage) =
        Slide(newId(), SlideKind.WEB, p.id, durationSec = DEFAULT_WEB_SEC)

    fun addSlide(slide: Slide) {
        slides = slides + slide
    }

    fun deleteMedia(m: MediaFile) {
        fileOf(m).delete()
        media = media.filterNot { it.id == m.id }
        slides = slides.filterNot { it.kind == SlideKind.MEDIA && it.refId == m.id }
    }

    fun deletePage(p: WebPage) {
        pages = pages.filterNot { it.id == p.id }
        slides = slides.filterNot { it.kind == SlideKind.WEB && it.refId == p.id }
    }

    /** 有効なスライドを再生順に展開する（PDFはページごと）。 */
    fun buildPlaylist(): List<PlayItem> {
        val mediaMap = media.associateBy { it.id }
        val pageMap = pages.associateBy { it.id }
        val out = ArrayList<PlayItem>()
        for (s in slides) {
            if (!s.enabled) continue
            when (s.kind) {
                SlideKind.MEDIA -> {
                    val m = mediaMap[s.refId] ?: continue
                    val f = fileOf(m)
                    if (!f.exists()) continue
                    if (m.type == MediaType.PDF) {
                        for (p in 0 until m.pageCount) out += PlayItem.PdfPage(s.id, f, p, s.durationSec)
                    } else {
                        out += PlayItem.Image(s.id, f, s.durationSec)
                    }
                }
                SlideKind.WEB -> {
                    val p = pageMap[s.refId] ?: continue
                    out += PlayItem.Web(s.id, p.url, s.durationSec, s.scroll, s.afterScrollSec)
                }
            }
        }
        return out
    }

    private fun <T> readArray(key: String, parse: (JSONObject) -> T): List<T> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun <T> writeArray(key: String, list: List<T>, toJson: (T) -> JSONObject) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}

// ----------------------------------------------------------------------
// 画像・PDFの読み込み（画面サイズに合わせて縮小して読み込む）
// ----------------------------------------------------------------------

object SlideImageLoader {

    /** 端末の描画上限を超えないようにする最大辺。 */
    private const val MAX_SIDE = 4096

    fun decodeImage(file: File, targetW: Int, targetH: Int): Bitmap? {
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        fun sampleFor(w: Int, h: Int): Int {
            var s = 1
            while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
            while (max(w, h) / s > MAX_SIDE) s *= 2
            return s
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder は写真の向き（EXIF）も反映してくれる
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            BitmapFactory.decodeFile(file.path, opts)
        }
    }

    fun pdfPageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }

    /** PDFの1ページを、画面に収まる大きさで白背景に描画する。 */
    fun renderPdfPage(file: File, pageIndex: Int, targetW: Int, targetH: Int): Bitmap? {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = min(targetW.toFloat() / page.width, targetH.toFloat() / page.height)
                    var w = (page.width * scale).toInt().coerceAtLeast(1)
                    var h = (page.height * scale).toInt().coerceAtLeast(1)
                    if (max(w, h) > MAX_SIDE) {
                        val r = MAX_SIDE.toFloat() / max(w, h)
                        w = (w * r).toInt().coerceAtLeast(1)
                        h = (h * r).toInt().coerceAtLeast(1)
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    fun load(item: PlayItem, targetW: Int, targetH: Int): Bitmap? = when (item) {
        is PlayItem.Image -> decodeImage(item.file, targetW, targetH)
        is PlayItem.PdfPage -> renderPdfPage(item.file, item.page, targetW, targetH)
        is PlayItem.Web -> null
    }

    /** 一覧表示用の小さなサムネイル。 */
    fun thumbnail(store: SlideStore, m: MediaFile, size: Int): Bitmap? = runCatching {
        val f = store.fileOf(m)
        if (m.type == MediaType.PDF) renderPdfPage(f, 0, size, size) else decodeImage(f, size, size)
    }.getOrNull()
}
