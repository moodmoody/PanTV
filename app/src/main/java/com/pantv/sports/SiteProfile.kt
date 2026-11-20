package com.pantv.sports

/**
 * 站点描述：把地址、播放页规则、媒体规则集中在这里。
 * 页面别处不许再出现裸链接，改版只改这一个文件。
 */
data class SiteProfile(
    val id: String,
    val label: String,
    val home: String,
    val hosts: List<String>,          // 认为属于本站的域名（含播放页分流域名，用后缀匹配）
    val playPath: Regex,              // 播放页路径
    val mediaPath: Regex              // 真实媒体请求
) {
    fun ownsPage(url: String): Boolean =
        hosts.any { h -> url.contains(h, ignoreCase = true) }

    fun isPlayPage(url: String): Boolean = playPath.containsMatchIn(url)

    fun isMedia(url: String): Boolean =
        mediaPath.containsMatchIn(url) && !url.contains(".m3u8?ad", true)
}

object Sites {
    /** 主站：列表页与播放页都归它 */
    val JRS = SiteProfile(
        id = "jrs",
        label = "JRKAN 直播",
        home = "https://www.jrs21.com/",
        hosts = listOf("jrs21.com", "jrs22.com", "jrs13.com", "jrs14.com", "jrs80.com",
            "sportsteam53.com", "sportsteam356.com", "jw1104.com", "bhswh.com"),
        playPath = Regex("/play/", RegexOption.IGNORE_CASE),
        mediaPath = Regex("\\.(m3u8|flv|mp4)(\\?|$)", RegexOption.IGNORE_CASE)
    )

    /** 通用：任何页面上出现 m3u8/flv 都算可播 */
    val GENERIC = SiteProfile(
        id = "generic",
        label = "通用",
        home = JRS.home,
        hosts = emptyList(),
        playPath = Regex("$^"),
        mediaPath = JRS.mediaPath
    )

    fun byId(id: String?): SiteProfile = if (id == "generic") GENERIC else JRS

    /** 只露域名，不把带 token 的地址显示出来 */
    fun mask(url: String): String = runCatching {
        val u = android.net.Uri.parse(url)
        val path = u.path ?: ""
        val file = path.substringAfterLast('/', "")
        if (file.isBlank()) (u.host ?: "未知来源") else "${u.host}/…/$file"
    }.getOrDefault("未知来源")

    /** 页面净化：去掉备用网址、广告、悬浮按钮这类无效信息 */
    const val CLEAN_JS = """
(function(){
  if (window.__ptvClean) return; window.__ptvClean = 1;
  var junk = /备用|网址|下载\s*APP|进群|红包|充值|私信|加群|关注主播|注册送|广告|推广|合作|镜像|收藏本站/i;
  var i, els = document.querySelectorAll('div,section,footer,p,span,a,li,marquee');
  for (i = 0; i < els.length; i++) {
    var el = els[i];
    var t = (el.innerText || '').trim();
    var cs = getComputedStyle(el);
    var floating = cs.position === 'fixed' || cs.position === 'sticky';
    var r = el.getBoundingClientRect();
    // 贴边的小浮层（刷新、回到顶部之类）：有没有字都去掉
    if (floating && r.width < 220 && r.height < 220 && !/直播|播放/.test(t)) {
      el.style.display = 'none';
      continue;
    }
    if (!t) continue;
    if (el.tagName === 'FOOTER') { el.style.display = 'none'; continue; }
    if (junk.test(t) && t.length < 120) { el.style.display = 'none'; continue; }
    if (floating && t.length < 40 && !/直播|播放/.test(t)) { el.style.display = 'none'; continue; }
  }
  // 底部那种「返回首页 www.xxx.com www.yyy.com」的条
  var bars = document.querySelectorAll('body > div, body > p');
  for (i = 0; i < bars.length; i++) {
    var b = bars[i], bt = (b.innerText || '');
    if ((bt.match(/www\./gi) || []).length >= 2 && b.querySelectorAll('a').length >= 2) {
      b.style.display = 'none';
    }
  }
})();
"""
}
