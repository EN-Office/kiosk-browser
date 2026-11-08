package com.enoffice.kioskbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/** 設定とブックマークの保存（SharedPreferences）。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("kiosk_browser", Context.MODE_PRIVATE)

    /** 最後に表示していたURL（次回起動時に復元）。 */
    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(value) = sp.edit().putString(KEY_LAST_URL, value).apply()

    /** 自動スクロールモード（次回起動時に復元）。 */
    var autoScroll: Boolean
        get() = sp.getBoolean(KEY_AUTO_SCROLL, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SCROLL, value).apply()

    /** スライドショー再生中か（次回起動時・再起動時に再開）。 */
    var slideshowOn: Boolean
        get() = sp.getBoolean(KEY_SLIDESHOW, false)
        set(value) = sp.edit().putBoolean(KEY_SLIDESHOW, value).apply()

    /** スクロール速度（CSSピクセル/秒）。 */
    var speed: Int
        get() = sp.getInt(KEY_SPEED, 60)
        set(value) = sp.edit().putInt(KEY_SPEED, value).apply()

    /** ページ表示後、スクロールを始めるまでの秒数。 */
    var topPauseSec: Int
        get() = sp.getInt(KEY_TOP_PAUSE, 3)
        set(value) = sp.edit().putInt(KEY_TOP_PAUSE, value).apply()

    /** 最下部に着いてから更新するまでの秒数。 */
    var bottomPauseSec: Int
        get() = sp.getInt(KEY_BOTTOM_PAUSE, 3)
        set(value) = sp.edit().putInt(KEY_BOTTOM_PAUSE, value).apply()

    /** スクロールする必要がない（1画面に収まる）ページを表示し続ける秒数。 */
    var staySec: Int
        get() = sp.getInt(KEY_STAY, 30)
        set(value) = sp.edit().putInt(KEY_STAY, value).apply()

    var bookmarks: List<Bookmark>
        get() {
            val raw = sp.getString(KEY_BOOKMARKS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Bookmark(o.optString("title"), o.optString("url"))
                }.filter { it.url.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
            sp.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
        }

    private companion object {
        const val KEY_LAST_URL = "last_url"
        const val KEY_AUTO_SCROLL = "auto_scroll"
        const val KEY_SPEED = "speed"
        const val KEY_TOP_PAUSE = "top_pause"
        const val KEY_BOTTOM_PAUSE = "bottom_pause"
        const val KEY_STAY = "stay"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_SLIDESHOW = "slideshow"
    }
}
