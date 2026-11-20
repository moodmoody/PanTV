package com.pantv.sports

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** 播放页里的一条线路（主播解说① / 高清直播④ …） */
data class Line(val index: Int, val label: String)

/** 比赛状态 */
enum class State { LIVE, SOON, ENDED }

/** 一场比赛：入口是它的播放页 */
data class Match(
    val lid: String,
    val title: String,
    val league: String,
    val time: String,
    val state: State,
    val statusText: String,
    val playUrl: String
) {
    val meta: String
        get() = listOf(league, time, statusText).filter { it.isNotBlank() }.joinToString("　·　")
}

/**
 * 站点封装：
 *   列表页只负责给出「这一场的播放页地址」；
 *   真正的线路在播放页里，进去之后才列出来（主播解说①、高清直播④…）。
 */
class SiteService(
    private val context: Context,
    private val web: WebView,
    private val onMatches: (List<Match>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: ((String) -> Unit)? = null
    private var polling = false
    private var waitingLines = false
    private var linesReady: ((List<Line>) -> Unit)? = null
    private var pendingClick = ""

    private val ua = "Mozilla/5.0 (Linux; Android 12; SmartTV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = ua
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                val u = r?.url?.toString() ?: return null
                if (Sites.JRS.isMedia(u)) {
                    val cb = pending
                    if (cb != null) { pending = null; main.post { cb(u) } }
                }
                return null
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                when {
                    waitingLines -> { waitingLines = false; collectLines() }
                    pendingClick.isNotBlank() -> {
                        val label = pendingClick
                        pendingClick = ""
                        v?.evaluateJavascript(clickLineJs(label), null)
                    }
                    pending != null -> v?.evaluateJavascript(FOLLOW_IFRAME_JS, null)
                }
            }
        }
    }

    // ── 列表 ──
    fun refresh() {
        pending = null
        waitingLines = false
        linesReady = null
        web.loadUrl(Sites.JRS.home)
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        var rounds = 0
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                rounds++
                web.evaluateJavascript(EXTRACT_JS) { raw ->
                    if (!polling) return@evaluateJavascript
                    val parsed = parseMatches(raw)
                    if (parsed.isNotEmpty()) { polling = false; onMatches(parsed) }
                }
                if (rounds >= 50) { polling = false; onError("加载超时，点刷新重试") }
                else main.postDelayed(this, 500)
            }
        }
        main.postDelayed(tick, 900)
    }

    private fun parseMatches(raw: String?): List<Match> {
        val text = unescape(raw ?: "")
        val out = mutableListOf<Match>()
        for (line in text.split("\n")) {
            val row = line.trim()
            if (!row.contains("\t")) continue
            val p = row.split("\t")
            if (p.size < 3) continue
            val lid = p[0].trim()
            val body = p[1].trim()
            val hrefs = p[2].split("|").map { it.trim() }.filter { it.startsWith("http") }
            if (lid.isBlank() || hrefs.isEmpty() || body.isBlank()) continue

            val league = body.substringBefore(" ").trim()
            val timeRx = Regex("\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}").find(body)
            val time = timeRx?.value ?: ""
            val rest = if (timeRx != null) body.substring(timeRx.range.last + 1).trim() else body

            val minute = Regex("(\\d+)'").find(rest)?.value
            val liveMark = minute != null || rest.contains("中场") || rest.contains("上半") ||
                rest.contains("下半") || rest.contains("节")

            var start: Long? = null
            if (timeRx != null) {
                val g = Regex("(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})").find(time)
                if (g != null) {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.MONTH, g.groupValues[1].toInt() - 1)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, g.groupValues[2].toInt())
                    cal.set(java.util.Calendar.HOUR_OF_DAY, g.groupValues[3].toInt())
                    cal.set(java.util.Calendar.MINUTE, g.groupValues[4].toInt())
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    start = cal.timeInMillis
                }
            }
            val now = System.currentTimeMillis()
            val state: State
            val status: String
            when {
                liveMark -> { state = State.LIVE; status = minute ?: "进行中" }
                start != null && now - start > 3 * 3600_000L -> { state = State.ENDED; status = "已结束" }
                start != null && now >= start -> { state = State.LIVE; status = "进行中" }
                else -> { state = State.SOON; status = "未开始" }
            }
            if (state == State.ENDED) continue

            val teams = rest
                .replace(Regex("\\d+'"), " ")
                .replace(Regex("未开始|已结束|完场|中场|半场|上半|下半|第[一二三四]节"), " ")
                .replace(Regex("直播[①②③④⑤⑥⑦⑧⑨0-9]*"), " ")
                .replace(Regex("\\b\\d+\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            out.add(Match(lid, teams.ifBlank { "比赛 $lid" }, league, time, state, status, hrefs.first()))
        }
        return out.sortedWith(compareBy({ it.state.ordinal }, { rank(it.league) }, { it.time }))
    }

    private fun rank(league: String): Int {
        val top = listOf("NBA", "英超", "西甲", "意甲", "德甲", "法甲", "欧冠", "中超",
            "CBA", "美职联", "MLS", "日职", "亚冠")
        val i = top.indexOfFirst { league.contains(it, true) || it.contains(league, true) }
        return if (i >= 0) i else 100
    }

    // ── 播放页：取线路 ──
    fun loadLines(playUrl: String, onLines: (List<Line>) -> Unit, onFail: (String) -> Unit) {
        linesReady = onLines
        waitingLines = true
        polling = false
        web.loadUrl(playUrl)
        main.postDelayed({
            if (waitingLines) {
                waitingLines = false
                linesReady = null
                onFail("播放页没打开，换一场试试")
            }
        }, 20000)
    }

    private fun collectLines() {
        web.evaluateJavascript(LINES_JS) { raw ->
            val text = unescape(raw ?: "")
            val lines = mutableListOf<Line>()
            for (l in text.split("\n")) {
                val t = l.trim()
                if (t.isBlank()) continue
                lines.add(Line(lines.size + 1, t))
            }
            val cb = linesReady
            linesReady = null
            if (lines.isEmpty()) onError("这一场没有可用线路")
            else cb?.invoke(lines)
        }
    }

    /** 选定线路：在播放页里点它，然后截 m3u8 */
    fun playLine(label: String, onUrl: (String) -> Unit, onFail: (String) -> Unit) {
        pending = onUrl
        pendingClick = label
        // 直接在当前播放页里点，不用重新加载
        web.evaluateJavascript(clickLineJs(label), null)
        main.postDelayed({
            if (pending != null) { pending = null; onFail("这条线路没响应，换一条试试") }
        }, 25000)
    }

    private fun clickLineJs(label: String): String {
        val safe = label.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  var target = '$safe';
  var links = document.querySelectorAll('a,button,[onclick],div,span');
  for (var i = 0; i < links.length; i++) {
    var e = links[i];
    var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
    if (t === target && e.offsetParent !== null) { e.click(); return 'ok'; }
  }
  var ifr = document.querySelector('iframe');
  return ifr ? 'iframe-only' : 'not-found';
})()
"""
    }

    private fun unescape(raw: String): String =
        raw.replace("\\n", "\n").replace("\\t", "\t").replace("\\/", "/")
            .replace("\\\"", "\"").trim().trim('"')

    companion object {
        /** 列表页：lid \t 文本 \t 播放页链接 */
        private const val EXTRACT_JS = """
(function(){
  var out = [];
  var blocks = document.querySelectorAll('[data-lid]');
  for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i];
    if (b.offsetParent === null) continue;
    var rect = b.getBoundingClientRect();
    if (rect.height < 10) continue;
    var cs = window.getComputedStyle(b);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    var links = [].slice.call(b.querySelectorAll('a[href*="/play/"]'))
      .map(function(a){ return a.getAttribute('href'); })
      .filter(function(h){ return h && h.indexOf('http') === 0; });
    if (!links.length) continue;
    var txt = (b.innerText || '').replace(/[\s\r\n]+/g, ' ').trim();
    if (!txt) continue;
    out.push(b.getAttribute('data-lid') + '\t' + txt + '\t' + links.join('|'));
  }
  return out.join('\n');
})()
"""

        /** 播放页：把线路名抓出来（主播解说①、高清直播④…） */
        private const val LINES_JS = """
(function(){
  var seen = {}, out = [];
  var cand = document.querySelectorAll('a,button,[onclick],li,span,div');
  for (var i = 0; i < cand.length; i++) {
    var e = cand[i];
    if (e.offsetParent === null) continue;
    var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
    if (!t || t.length > 12) continue;
    if (!/(解说|直播|高清|线路|标清|蓝光|原声|超清)/.test(t)) continue;
    if (seen[t]) continue;
    var clickable = e.tagName === 'A' || e.tagName === 'BUTTON' || e.hasAttribute('onclick') ||
                    (e.parentElement && e.parentElement.tagName === 'A');
    if (!clickable) continue;
    seen[t] = 1;
    out.push(t);
  }
  return out.join('\n');
})()
"""
        private const val FOLLOW_IFRAME_JS = """
(function(){
  var f = document.querySelector('iframe');
  if (f && f.src) { location.href = f.src; }
})()
"""
    }
}
