package com.pantv.sports

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 内置浏览器：
 *   1. 注入方向键导航（电视没有触屏）
 *   2. 净化页面：备用网址、广告、悬浮按钮一律不显示
 *   3. 播放页在顶层打开，媒体请求截下来交给原生播放器
 *   4. 对外只露比赛名，不露带 token 的裸地址
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var site: SiteProfile = Sites.JRS
    private val caught = LinkedHashSet<String>()
    private var injected = false

    private val navJs = """
        (function(){
          if (window.__ptv) return;
          var st = document.createElement('style');
          st.textContent = '.__ptv_on{outline:4px solid #FFC53D !important;outline-offset:2px;background:rgba(255,197,61,.18) !important;}';
          document.head.appendChild(st);
          window.__ptv = {
            idx: -1, els: [],
            collect: function(){
              var all = document.querySelectorAll('a,button,[onclick],[role=button],.btn,input[type=button]');
              var links = [], play = [], other = [];
              for (var i=0;i<all.length;i++){
                var e = all[i], r = e.getBoundingClientRect();
                if (r.width < 12 || r.height < 12) continue;
                if (e.offsetParent === null) continue;
                if (e.closest && e.closest('[style*="display: none"]')) continue;
                var href = e.getAttribute ? (e.getAttribute('href') || '') : '';
                var txt = (e.textContent || '') + ' ' + (e.className || '') + ' ' + (e.id || '');
                if (/refresh|reload|back|top|share|icon/i.test(txt)) continue;
                if (/备用|网址|广告|下载/i.test(txt)) continue;
                if (/\/play\//i.test(href)) links.push(e);
                else if (/直播|播放|开始|进入|观看/i.test(txt)) play.push(e);
                else other.push(e);
              }
              this.els = links.concat(play).concat(other);
              if (this.idx < 0 && this.els.length) { this.idx = 0; this.paint(); }
            },
            paint: function(){
              var old = document.querySelectorAll('.__ptv_on');
              for (var i=0;i<old.length;i++) old[i].classList.remove('__ptv_on');
              var e = this.els[this.idx];
              if (e) { e.classList.add('__ptv_on'); e.scrollIntoView({block:'center'}); }
            },
            move: function(d){
              if (!this.els.length) this.collect();
              if (!this.els.length) return;
              this.idx = Math.max(0, Math.min(this.els.length - 1, this.idx + d));
              this.paint();
            },
            click: function(){
              if (!this.els.length) this.collect();
              var e = this.els[this.idx];
              if (!e) return;
              var href = e.getAttribute && (e.getAttribute('href') || '');
              if (href && /\/play\//i.test(href)) { try { PanTV.openPlay(href); return; } catch(err){} }
              e.click();
            }
          };
          document.addEventListener('click', function(ev){
            var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
            if (a && /\/play\//i.test(a.getAttribute('href') || '')) {
              ev.preventDefault();
              try { PanTV.openPlay(a.href); } catch(err){}
            }
          }, true);
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface
        fun openPlay(href: String) {
            runOnUiThread {
                val abs = if (href.startsWith("http")) href else site.home.trimEnd('/') + "/" + href.trimStart('/')
                web.loadUrl(abs)
                status.text = "正在进入播放页…"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        site = Sites.byId(intent.getStringExtra("site"))
        WebView.setWebContentsDebuggingEnabled(true)

        web = findViewById(R.id.web)
        status = findViewById(R.id.status)
        web.isFocusable = true

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 12; SmartTV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        web.addJavascriptInterface(Bridge(), "PanTV")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (site.isMedia(u) && caught.add(u)) runOnUiThread { play(u) }
                return null
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                injected = false
                status.text = "加载中…"
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                v?.evaluateJavascript(Sites.CLEAN_JS, null)
                v?.evaluateJavascript(navJs, null)
                v?.evaluateJavascript("__ptv.collect();__ptv.idx=0;__ptv.paint();", null)
                injected = true
                status.text = "↑↓ 选条目　确定键 播放　返回键 回上一页"
            }
        }

        web.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }

        web.loadUrl(intent.getStringExtra("url") ?: site.home)
    }

    private fun handleKey(keyCode: Int): Boolean {
        if (!injected) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { web.evaluateJavascript("__ptv.move(1)", null); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { web.evaluateJavascript("__ptv.move(-1)", null); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { web.evaluateJavascript("__ptv.click()", null); true }
            else -> false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    /** 拿到媒体地址就交给原生播放器，界面上只显示比赛名 */
    private fun play(url: String) {
        val title = web.title?.takeIf { it.isNotBlank() } ?: site.label
        status.text = "已接管播放"
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("referer", web.url ?: site.home)
                .putExtra("name", title)
                .putExtra("site", site.id)
        )
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
