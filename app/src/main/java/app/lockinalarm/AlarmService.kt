package app.lockinalarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

object Sounds {
    fun defaultUri(ctx: Context): Uri? = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    /** The app's own tone: always readable, also before the first unlock. */
    fun ownTone(ctx: Context): Uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.lockin_tone}")

    fun uri(ctx: Context, a: AlarmDef): Uri? = when (val k = a.sound) {
        null -> defaultUri(ctx)
        "file" -> Store.soundFile(ctx, a.id).takeIf { it.exists() }?.let(Uri::fromFile)
        else -> Uri.parse(k)
    }

    /** The phone's own alarm sounds: (title, uri). */
    fun system(ctx: Context): List<Pair<String, String>> {
        val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_ALARM) }
        val c = rm.cursor
        val out = mutableListOf<Pair<String, String>>()
        while (c.moveToNext()) out += c.getString(RingtoneManager.TITLE_COLUMN_INDEX) to rm.getRingtoneUri(c.position).toString()
        return out
    }
}

/**
 * Plays the alarm while the session is RINGING: the chosen sound, else the phone's default, else the app's own tone, so
 * it never goes silent. Alarm volume is forced to max unless turned off.
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var playing: String? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var volumeBefore = -1

    private val enforce = object : Runnable {
        override fun run() {
            val s = Store.session(this@AlarmService)
            val a = s.alarmId?.let { Store.alarm(this@AlarmService, it) }
            if (a != null) {
                val v = a.buildUp.volume(System.currentTimeMillis() - startedAt)
                try { player?.setVolume(v, v) } catch (_: IllegalStateException) {}
                if (a.forceMax && !s.silent) {
                    val am = getSystemService(AudioManager::class.java)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Store.session(this)
        val a = s.alarmId?.let { Store.alarm(this, it) }
        // startForegroundService demands this even if we stop right away
        foreground(Notify.ringing(this, a))
        if (s.phase != Phase.RINGING || a == null) { stopSelf(); return START_NOT_STICKY }
        if (playing != key(s)) { release(); start(s, a) }
        // the full-screen intent usually opens it; this covers the phone being unlocked and in use
        try { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "activity start blocked: $e") }
        return START_STICKY
    }

    private fun foreground(n: Notification) = try {
        startForeground(Notify.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } catch (e: IllegalStateException) {
        // Android 15+ refuses media playback for some seconds after a boot; exact-alarm apps have their own type
        if (Build.VERSION.SDK_INT < 34) throw e
        Log.w(TAG, "media playback refused, using system exempted: $e")
        startForeground(Notify.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    private fun key(s: Session) = "${s.alarmId}:${s.ringSince}:${s.silent}"

    private fun start(s: Session, a: AlarmDef) {
        playing = key(s)
        startedAt = System.currentTimeMillis()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lockin:alarm").apply { acquire(60 * 60_000L) }
        if (volumeBefore < 0) volumeBefore = getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        if (!s.silent) {
            player = listOfNotNull(Sounds.uri(this, a), Sounds.defaultUri(this), Sounds.ownTone(this)).distinct().firstNotNullOfOrNull { uri ->
                val p = MediaPlayer()
                try {
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    p.setDataSource(this, uri)
                    p.isLooping = true
                    // a sound that breaks while playing is replaced by the own tone
                    p.setOnErrorListener { _, what, extra -> Log.w(TAG, "player error $what/$extra"); fallback(); true }
                    p.prepare()
                    val v = a.buildUp.volume(0)
                    p.setVolume(v, v)
                    p.start()
                    p
                } catch (e: Exception) { Log.w(TAG, "sound $uri failed: $e"); p.release(); null }
            }
        }
        if (a.vibrate) getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
        handler.post(enforce)
        Log.i(TAG, "sound started alarm=${a.id} silent=${s.silent} playing=${player != null}")
    }

    private fun fallback() {
        player?.release()
        player = MediaPlayer.create(this, Sounds.ownTone(this), null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(), 0)?.apply { isLooping = true; start() }
    }

    private fun release() {
        handler.removeCallbacks(enforce)
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
        playing = null
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        release()
        if (volumeBefore >= 0) getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volumeBefore, 0)
        Log.i(TAG, "service stopped")
        super.onDestroy()
    }
}
