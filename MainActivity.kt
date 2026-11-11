package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

data class Track(val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val tracks = mutableListOf<Track>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 16)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "♫ เครื่องเล่นเพลง"; textSize = 25f; setTextColor(0xFFFFFFFF.toInt())
        }
        status = TextView(this).apply {
            text = "กำลังเชื่อมต่อเครื่องเล่น..."; textSize = 14f; setTextColor(0xFFB0BEC5.toInt())
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val play = button("เล่น / พัก") { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        val shuffle = button("สุ่ม") { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }
        val repeat = button("วนเพลง") { controller?.repeatMode = when(controller?.repeatMode) { 1 -> 2; 2 -> 0; else -> 1 } }
        controls.addView(play); controls.addView(shuffle); controls.addView(repeat)
        layout.addView(title); layout.addView(status); layout.addView(controls)
        addSlider(layout, "เบส", 0, 1000) { value -> try { bass?.setStrength(value.toShort()) } catch (_: Exception) {} }
        addSlider(layout, "แหลม", 0, 1000) { value -> setTreble(value) }
        val scan = button("สแกนเพลงในโทรศัพท์") { checkPermissionAndScan() }
        layout.addView(scan)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try { controller = controllerFuture?.get(); status.text = "พร้อมเล่นเพลงออฟไลน์" }
            catch (_: Exception) { status.text = "เชื่อมต่อเครื่องเล่นไม่สำเร็จ" }
        }, ContextCompat.getMainExecutor(this))
        checkPermissionAndScan()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { action() }
    }

    private fun addSlider(parent: LinearLayout, label: String, min: Int, max: Int, change: (Int) -> Unit) {
        val text = TextView(this).apply { this.text = "$label: 0"; setTextColor(0xFFFFFFFF.toInt()) }
        val seek = SeekBar(this).apply { this.max = max; progress = 0 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                text.text = "$label: ${p / 10}%"; if (user) change(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(text); parent.addView(seek)
    }

    private fun setTreble(value: Int) {
        try {
            val eq = equalizer ?: return
            val band = (eq.numberOfBands - 1).toShort()
            val range = eq.bandLevelRange
            val level = (range[0] + (range[1] - range[0]) * value / 1000).toShort()
            eq.setBandLevel(band, level)
        } catch (_: Exception) {}
    }

    private fun checkPermissionAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) scanMusic()
        else ActivityCompat.requestPermissions(this, arrayOf(permission), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) scanMusic()
        else status.text = "ต้องอนุญาตให้เข้าถึงไฟล์เสียงก่อน"
    }

    private fun scanMusic() {
        tracks.clear(); list.removeAllViews()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c: Cursor ->
                val ti = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val ar = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(collection, c.getLong(id).toString())
                    tracks.add(Track(c.getString(ti) ?: "ไม่ทราบชื่อ", c.getString(ar) ?: "", uri))
                }
            }
            tracks.forEachIndexed { index, track ->
                val b = Button(this).apply {
                    text = "${track.title}\n${track.artist}"
                    setOnClickListener { playFrom(index) }
                }
                list.addView(b, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            status.text = "พบเพลง ${tracks.size} เพลง"
        } catch (_: Exception) { status.text = "สแกนเพลงไม่สำเร็จ" }
    }

    private fun playFrom(index: Int) {
        val player = controller ?: return
        val items = tracks.map { MediaItem.fromUri(it.uri) }
        player.setMediaItems(items, index, 0L)
        player.prepare(); player.play()
        try {
            equalizer?.release(); bass?.release()
            val session = player.audioSessionId
            if (session != 0) {
                equalizer = Equalizer(0, session).apply { enabled = true }
                bass = BassBoost(0, session).apply { enabled = true; setStrength(500.toShort()) }
            }
        } catch (_: Exception) { status.text = "มือถือไม่รองรับเอฟเฟกต์เสียงบางชนิด" }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        equalizer?.release(); bass?.release()
        super.onDestroy()
    }
}
