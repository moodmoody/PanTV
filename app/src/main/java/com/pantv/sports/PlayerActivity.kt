package com.pantv.sports

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var tip: TextView
    private var url = ""
    private var referer = ""
    private var name = "直播"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        url = intent.getStringExtra("url") ?: ""
        referer = intent.getStringExtra("referer") ?: ""
        name = intent.getStringExtra("name") ?: "直播"

        view = findViewById(R.id.player)
        tip = findViewById(R.id.tip)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 12; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        if (referer.isNotBlank()) {
            http.setDefaultRequestProperties(mapOf("Referer" to referer, "Origin" to homeOf(referer)))
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
            .build()

        view.player = player
        view.useController = true
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.controllerAutoShow = true
        view.controllerShowTimeoutMs = 5000

        val item = MediaItem.fromUri(url)
        val source = if (url.contains(".m3u8", true))
            HlsMediaSource.Factory(http).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(http).createMediaSource(item)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                tip.text = when (state) {
                    Player.STATE_BUFFERING -> "缓冲中…"
                    Player.STATE_READY -> ""
                    Player.STATE_ENDED -> "播放结束"
                    else -> ""
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val msg = (error.message ?: "").replace(Regex("https?://\\\\S+")) { Sites.mask(it.value) }
                tip.text = "播放失败：" + (error.errorCodeName ?: "") + "\n" + msg
            }
        })

        tip.text = name
    }

    private fun homeOf(u: String): String = runCatching {
        val uri = android.net.Uri.parse(u)
        uri.scheme + "://" + uri.host
    }.getOrDefault("")

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (player.isPlaying) player.pause() else player.play()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBack(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekForward(); return true }
            KeyEvent.KEYCODE_MENU -> {
                val target = if (view.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.resizeMode = target
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun ExoPlayer.seekBack() = seekTo((currentPosition - 15000).coerceAtLeast(0))
    private fun ExoPlayer.seekForward() = seekTo(currentPosition + 30000)

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
