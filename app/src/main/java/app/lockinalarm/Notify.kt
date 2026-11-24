package app.lockinalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/** What has to be right for the alarm to work. [fix] opens the settings page for it. */
class Check(val label: String, val ok: Boolean, val hint: String, val fix: Intent?)

object Checks {
    fun nfc(ctx: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(ctx)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun pkg(ctx: Context) = Uri.parse("package:" + ctx.packageName)

    /** Permissions and hardware: set up once. */
    fun setup(ctx: Context): List<Check> = listOf(
        nfc(ctx).let { Check("NFC", it?.isEnabled == true, if (it == null) "this phone has no NFC" else "turn it on to scan tags", if (it == null) null else Intent(Settings.ACTION_NFC_SETTINGS)) },
        Check("Notifications", nm(ctx).areNotificationsEnabled(), "the alarm screen opens through one", Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)),
        Check("Full-screen alarm", Build.VERSION.SDK_INT < 34 || nm(ctx).canUseFullScreenIntent(), "so it shows over the lock screen", if (Build.VERSION.SDK_INT < 34) null else Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))),
        Check("Do Not Disturb access", nm(ctx).isNotificationPolicyAccessGranted, "so it shows while DND is on", Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
    )

    /** Setup plus tonight's state. */
    fun tonight(ctx: Context, a: AlarmDef): List<Check> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val am = ctx.getSystemService(AudioManager::class.java)
        return setup(ctx) + listOf(
            Check("Battery $level%", level >= 25 || bm.isCharging, "plug the phone in", null),
            Check("Alarm volume", a.forceMax || am.getStreamVolume(AudioManager.STREAM_ALARM) * 2 >= am.getStreamMaxVolume(AudioManager.STREAM_ALARM), "turn the alarm volume up", null),
            Check("Silence mode", nm(ctx).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE, "total silence mutes alarms", Intent(Settings.ACTION_SOUND_SETTINGS)),
            Check("Tag", a.tags.isNotEmpty(), "pair a tag", null),
        )
    }
}

object Notify {
    private const val CH_WAKE = "wake"
    private const val CH_PREFLIGHT = "preflight"
    private const val CH_SHOWN = "wake-shown"
    const val ID_RING = 42
    private const val ID_FULL = 43

    /** The alarm screen is in front: its notification would only cover it. */
    var screenShown = false
        private set
    private const val ID_PREFLIGHT = 44

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    fun alarmChannel(ctx: Context): String {
        nm(ctx).createNotificationChannel(NotificationChannel(CH_WAKE, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        return CH_WAKE
    }

    /** The ringing service's notification: full-screen, or a quiet one while the alarm screen is already in front. */
    fun ringing(ctx: Context, a: AlarmDef?): Notification {
        val channel = if (!screenShown) alarmChannel(ctx) else CH_SHOWN.also {
            nm(ctx).createNotificationChannel(NotificationChannel(it, "Alarm (already open)", NotificationManager.IMPORTANCE_LOW))
        }
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(a?.text ?: "LOCK IN")
            .setContentText("Scan your tag to stop it")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(alarmIntent(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (!screenShown) b.setFullScreenIntent(alarmIntent(ctx), true)
        return b.build()
    }

    /** The alarm screen came to the front or was left: the way back has to pop up again. */
    fun shown(ctx: Context, v: Boolean) {
        screenShown = v
        val s = Store.session(ctx)
        when (s.phase) {
            Phase.RINGING -> nm(ctx).notify(ID_RING, ringing(ctx, s.alarmId?.let { Store.alarm(ctx, it) }))
            Phase.AWAKE_ASK -> if (v) cancelFullScreen(ctx) else fullScreen(ctx, s, buzz = false)
            Phase.SUNRISE -> if (v) cancelFullScreen(ctx)
            else -> {}
        }
    }

    /** Opens the alarm screen for the awake check or the sunrise (no sound of its own). */
    fun fullScreen(ctx: Context, s: Session, buzz: Boolean = true) {
        val ask = s.phase == Phase.AWAKE_ASK
        val n = Notification.Builder(ctx, alarmChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ask) "Still up?" else "Good morning soon")
            .setContentText(if (ask) "Answer within a minute or it rings again" else "Scan your tag to get up early")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(alarmIntent(ctx), true)
            .setContentIntent(alarmIntent(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        nm(ctx).notify(ID_FULL, n)
        if (ask && buzz) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 600), -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
            // the phone may be in use: then the full-screen intent is only a heads-up
            try { ctx.startActivity(Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "activity start blocked: $e") }
        }
    }

    fun cancelFullScreen(ctx: Context) = nm(ctx).cancel(ID_FULL)

    fun preflight(ctx: Context) {
        val a = Store.alarms(ctx).filter { Engine.nextAt(it) > 0 }.minByOrNull { Engine.nextAt(it) } ?: return
        val bad = Checks.tonight(ctx, a).filter { !it.ok }
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(Engine.nextAt(a)))
        nm(ctx).createNotificationChannel(NotificationChannel(CH_PREFLIGHT, "Bedtime check", NotificationManager.IMPORTANCE_DEFAULT))
        val text = bad.joinToString("\n") { "${it.label}: ${it.hint}" }
        nm(ctx).notify(ID_PREFLIGHT, Notification.Builder(ctx, CH_PREFLIGHT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (bad.isEmpty()) "All set for $at" else "Fix this before $at")
            .setContentText(if (bad.isEmpty()) "${a.name}: everything checked" else text.replace("\n", " · "))
            .setStyle(Notification.BigTextStyle().bigText(text.ifEmpty { "${a.name}: everything checked" }))
            .setContentIntent(PendingIntent.getActivity(ctx, 11, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }
}
