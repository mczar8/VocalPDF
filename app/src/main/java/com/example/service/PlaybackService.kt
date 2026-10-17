package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.VocalPdfApplication
import com.example.util.AppLanguage

/**
 * Service de premier plan : maintient la lecture écran éteint et affiche les commandes
 * dans la notification. Démarré uniquement quand une lecture commence.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "vocalpdf_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.START"
        const val ACTION_QUIT = "com.example.service.QUIT"
        const val ACTION_TOGGLE_PLAY = "com.example.service.TOGGLE_PLAY"
        const val ACTION_NEXT = "com.example.service.NEXT"
        const val ACTION_PREV = "com.example.service.PREV"
        const val ACTION_QUIT_APP_BROADCAST = "com.example.vocalpdf.ACTION_QUIT_APP"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SENTENCE = "extra_sentence"
        private const val EXTRA_PAGE_INFO = "extra_page_info"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Affiche / met à jour la notification. Le service n'est démarré qu'au lancement
         * d'une lecture (l'app est alors au premier plan) ; ensuite on met simplement
         * la notification à jour, ce qui est autorisé même en arrière-plan.
         */
        @SuppressLint("MissingPermission") // vérifiée par canPostNotifications()
        fun show(context: Context, title: String, sentence: String, pageInfo: String, isPlaying: Boolean) {
            if (!isRunning) {
                if (!isPlaying) return
                val intent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SENTENCE, sentence)
                    putExtra(EXTRA_PAGE_INFO, pageInfo)
                    putExtra(EXTRA_IS_PLAYING, true)
                }
                runCatching { ContextCompat.startForegroundService(context, intent) }
                return
            }
            if (!canPostNotifications(context)) return
            val notification = buildNotification(context, title, sentence, pageInfo, isPlaying)
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        }

        fun stop(context: Context) {
            if (isRunning) runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }

        private fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun servicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, PlaybackService::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun buildNotification(
            context: Context,
            title: String,
            sentence: String,
            pageInfo: String,
            isPlaying: Boolean
        ): Notification {
            val res = AppLanguage.localizedContext(context)
            ensureChannel(context, res)

            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val heading = if (pageInfo.isNotEmpty()) "$title • $pageInfo" else title

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vocalpdf)
                .setContentTitle(heading)
                .setContentText(sentence)
                .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(heading).bigText(sentence))
                .setContentIntent(openApp)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                    android.R.drawable.ic_media_previous,
                    res.getString(R.string.action_previous),
                    servicePendingIntent(context, 1, ACTION_PREV)
                )
                .addAction(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    res.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                    servicePendingIntent(context, 2, ACTION_TOGGLE_PLAY)
                )
                .addAction(
                    android.R.drawable.ic_media_next,
                    res.getString(R.string.action_next),
                    servicePendingIntent(context, 3, ACTION_NEXT)
                )
                .addAction(
                    R.drawable.ic_notification_close_red,
                    res.getString(R.string.action_quit),
                    servicePendingIntent(context, 4, ACTION_QUIT)
                )
                .build()
        }

        private fun ensureChannel(context: Context, res: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                res.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = res.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private val ttsManager get() = (application as VocalPdfApplication).ttsManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val res = AppLanguage.localizedContext(this)
                val notification = buildNotification(
                    this,
                    intent.getStringExtra(EXTRA_TITLE) ?: res.getString(R.string.app_name),
                    intent.getStringExtra(EXTRA_SENTENCE) ?: res.getString(R.string.notification_default_text),
                    intent.getStringExtra(EXTRA_PAGE_INFO).orEmpty(),
                    intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isRunning = true
                // La lecture a pu être mise en pause pendant le démarrage du service
                if (!ttsManager.isPlaying.value) stopSelf()
            }

            ACTION_TOGGLE_PLAY -> ttsManager.togglePlayPause()
            ACTION_NEXT -> ttsManager.nextSentence()
            ACTION_PREV -> ttsManager.previousSentence()

            ACTION_QUIT -> {
                ttsManager.stop()
                sendBroadcast(Intent(ACTION_QUIT_APP_BROADCAST).setPackage(packageName))
                stopSelf()
            }

            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
