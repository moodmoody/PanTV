package com.pantv.sports

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 观看入口：一条记录就是一个可以直接开播的地址，或者一个可以进去嗅探的站点 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val kind: String,          // "site" 用浏览器嗅探，"stream" 直接播
    val note: String = ""
)

object ChannelStore {
    private const val PREF = "pantv_channels"
    private const val KEY = "list"

    /** 首次启动时从 assets 里读预置入口 */
    fun seedFromAssets(context: Context) {
        if (load(context).isNotEmpty()) return
        val raw = context.assets.open("sources.json").bufferedReader().use { it.readText() }
        save(context, parse(raw))
    }

    fun parse(raw: String): List<Channel> {
        val out = mutableListOf<Channel>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            out.add(
                Channel(
                    id = o.optString("id", "c$i"),
                    name = o.optString("name", "未命名"),
                    url = o.optString("url", ""),
                    kind = o.optString("kind", "site"),
                    note = o.optString("note", "")
                )
            )
        }
        return out
    }

    fun load(context: Context): List<Channel> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("kind", it.kind)
                    .put("note", it.note)
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** 直播站本身；嗅探模式从这里进（地址统一由 Sites 提供） */
    val DEFAULT_SITE get() = Sites.JRS.home
}
