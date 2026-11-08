package com.enoffice.kioskbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** ホーム画面ショートカット（ピン留め）と、アプリアイコン長押しメニュー（動的ショートカット）。 */
object ShortcutHelper {

    private val COLORS = intArrayOf(
        0xFF1E6FD9.toInt(), 0xFF0E9F6E.toInt(), 0xFFD97706.toInt(),
        0xFFDC2626.toInt(), 0xFF7C3AED.toInt(), 0xFF0891B2.toInt()
    )

    fun launchIntent(context: Context, url: String, autoScroll: Boolean?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_URL, url)
            if (autoScroll != null) putExtra(MainActivity.EXTRA_AUTO_SCROLL, autoScroll)
        }

    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** ブックマークをホーム画面に追加する（ランチャーの確認ダイアログが出る）。 */
    fun requestPin(context: Context, bookmark: Bookmark, autoScroll: Boolean): Boolean {
        if (!isPinSupported(context)) return false
        val id = "pin_" + Integer.toHexString(bookmark.url.hashCode()) + if (autoScroll) "_auto" else "_normal"
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(bookmark.title.ifBlank { bookmark.url })
            .setLongLabel(bookmark.title.ifBlank { bookmark.url })
            .setIcon(letterIcon(context, bookmark.title.ifBlank { bookmark.url }))
            .setIntent(launchIntent(context, bookmark.url, autoScroll))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** 「スライドショーを再生」するショートカットをホーム画面に追加する。 */
    fun requestPinSlideshow(context: Context): Boolean {
        if (!isPinSupported(context)) return false
        val label = context.getString(R.string.ss_shortcut_label)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_SLIDESHOW, true)
        }
        val info = ShortcutInfoCompat.Builder(context, "pin_slideshow")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(letterIcon(context, label))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** アプリアイコン長押しで出るショートカットをブックマークと同期する。 */
    fun updateDynamicShortcuts(context: Context, bookmarks: List<Bookmark>) {
        runCatching {
            val max = minOf(4, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
            val list = bookmarks.take(max).map { bm ->
                ShortcutInfoCompat.Builder(context, "dyn_" + Integer.toHexString(bm.url.hashCode()))
                    .setShortLabel(bm.title.ifBlank { bm.url })
                    .setIcon(letterIcon(context, bm.title.ifBlank { bm.url }))
                    .setIntent(launchIntent(context, bm.url, null))
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        }
    }

    /** タイトルの頭文字を描いたアダプティブアイコン。 */
    private fun letterIcon(context: Context, title: String): IconCompat {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(COLORS[Math.floorMod(title.hashCode(), COLORS.size)])

        val trimmed = title.trim()
        val letter = if (trimmed.isEmpty()) "W"
        else String(Character.toChars(trimmed.codePointAt(0))).uppercase()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
