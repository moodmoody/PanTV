package com.pantv.sports

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 首页：按联赛分组、可折叠。
 * 顺序沿用站点列表顺序（正在播的排在前面），同组内按时间；
 * 条目获得焦点时有金色描边。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var service: SiteService

    private var matches: List<Match> = emptyList()
    private val expanded = HashSet<String>()
    private val collapsed = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        status = findViewById(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()

        val hidden: WebView = findViewById(R.id.hidden)
        service = SiteService(this, hidden, { data ->
            matches = data
            expanded.clear()
            collapsed.clear()
            rebuild()
        }, { msg -> status.text = msg })
        service.init()

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_add).setOnClickListener { manual() }

        refresh()
    }

    private fun refresh() {
        status.text = "正在取比赛…"
        matches = emptyList()
        (list.adapter as Adapter).submit(emptyList())
        service.refresh()
    }

    private fun rebuild() {
        // 保持服务端给的顺序：先出现的是正在进行的联赛
        val leagues = LinkedHashMap<String, MutableList<Match>>()
        for (m in matches) leagues.getOrPut(m.league.ifBlank { "其他" }) { mutableListOf() }.add(m)

        val rows = mutableListOf<Row>()
        var visible = 0
        for ((league, items) in leagues) {
            val open = items.any { it.state == State.LIVE } || !collapsed.contains(league)
            rows.add(Row.Header(league, items.size, open))
            if (open) {
                items.forEach { rows.add(Row.Item(it)); visible++ }
            }
        }
        (list.adapter as Adapter).submit(rows)
        status.text = "共 ${matches.size} 场 · ${leagues.size} 个联赛 · 已展开 $visible 场"
        if (rows.isNotEmpty()) list.requestFocus()
    }

    private fun toggle(league: String) {
        if (collapsed.contains(league)) collapsed.remove(league) else collapsed.add(league)
        rebuild()
    }

    /** 点比赛：先进播放页，把这一场真正的线路读出来 */
    private fun chooseLine(m: Match) {
        status.text = "正在读取 ${m.title} 的线路…"
        service.loadLines(m.playUrl, { lines ->
            status.text = ""
            val names = lines.map { it.label }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(m.title)
                .setItems(names) { _, which -> play(m, lines[which]) }
                .setNegativeButton("取消", null)
                .show()
        }, { err ->
            status.text = err
        })
    }

    private fun play(m: Match, line: Line) {
        status.text = "正在解析 ${m.title} ${line.label}…"
        service.playLine(line.label, { url ->
            status.text = ""
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("referer", m.playUrl)
                    .putExtra("name", "${m.title}　${line.label}")
            )
        }, { err ->
            status.text = err
        })
    }
    private fun manual() {
        val input = android.widget.EditText(this).apply {
            hint = "粘贴 m3u8 / flv 地址"
            setSingleLine(false)
            setPadding(40, 30, 40, 30)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("直接播放地址")
            .setView(input)
            .setPositiveButton("播放") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", u)
                            .putExtra("name", "手动源")
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    sealed class Row {
        data class Header(val league: String, val count: Int, val open: Boolean) : Row()
        data class Item(val match: Match) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(d: List<Row>) {
            rows = d
            notifyDataSetChanged()
            empty.visibility = if (d.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_league, parent, false))
            else ItemVH(inf.inflate(R.layout.item_channel, parent, false))
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> {
                    val vh = h as HeaderVH
                    vh.arrow.text = if (r.open) "▾" else "▸"
                    vh.league.text = r.league
                    vh.count.text = "${r.count} 场"
                    vh.root.setOnClickListener { toggle(r.league) }
                }
                is Row.Item -> {
                    val vh = h as ItemVH
                    vh.title.text = r.match.title
                    vh.meta.text = r.match.meta
                    vh.title.alpha = if (r.match.state == State.ENDED) 0.45f else 1f
                    vh.root.setOnClickListener { chooseLine(r.match) }
                }
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val arrow: TextView = v.findViewById(R.id.arrow)
            val league: TextView = v.findViewById(R.id.league)
            val count: TextView = v.findViewById(R.id.count)
            val root: View = v
        }

        inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.note)
            val root: View = v
        }
    }
}
