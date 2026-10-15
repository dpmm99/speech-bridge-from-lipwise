package com.example.speechbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Downloads one model as a foreground service, so it survives leaving the models screen and shows
 * progress in the notification shade with a Cancel action.
 *
 * The foreground type is dataSync, declared in the manifest with FOREGROUND_SERVICE_DATA_SYNC:
 * from Android 14 the type and its permission are mandatory or startForeground fails, and dataSync
 * explicitly covers "data upload or download" and has no runtime prerequisites.
 */
class DownloadService : Service() {

    private var running: String? = null
    private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelRequested = true
            val id = running
            if (id != null) {
                downloaderFor(kindFor(id))?.cancelDownload(modelFor(id) ?: return START_NOT_STICKY)
            }
            return START_NOT_STICKY
        }

        val kind = intent?.getStringExtra(EXTRA_KIND) ?: return stop()
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: return stop()

        // A second request replaces the first; the user has moved on to another model.
        cancelRequested = false
        running = modelId
        val model = modelFor(modelId) ?: return stop()
        startForeground(NOTIFICATION_ID, notification(modelId, 0, 0, true))

        val downloader = downloaderFor(kind) ?: return stop()
        java.lang.Thread({
            val ok = try {
                runBlocking {
                    downloader.download(model) { progress ->
                        if (cancelRequested) {
                            false
                        } else {
                            notify(modelId, progress.bytesDone, progress.bytesTotal)
                            true
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "download failed", e)
                false
            }
            Log.i(TAG, "download " + modelId + " -> " + ok)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "model-download").start()

        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun modelFor(id: String): com.example.speechbridge.models.CatalogModel? {
        val app = application as SpeechBridgeApp
        return app.sttCatalog.find(id) ?: app.llmCatalog.find(id)
    }

    private fun kindFor(id: String): String {
        val app = application as SpeechBridgeApp
        return if (app.sttCatalog.find(id) != null) KIND_STT else KIND_LLM
    }

    private fun downloaderFor(kind: String): com.example.speechbridge.models.ModelDownloader? {
        val app = application as SpeechBridgeApp
        return if (kind == KIND_STT) app.sttDownloader else app.llmDownloader
    }

    private fun notify(modelId: String, done: Long, total: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(modelId, done, total, false))
    }

    private fun notification(modelId: String, done: Long, total: Long, connecting: Boolean): Notification {
        val indeterminate = connecting || total <= 0L
        val percent = if (total <= 0L) 0 else ((done * 100) / total).toInt()

        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, LlmModelsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(shortName(modelId))
            .setContentText(if (indeterminate) "Starting..." else "$percent%  (${mb(done)} of ${mb(total)} MB)")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, "Cancel", cancelIntent).build(),
            )
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Progress while a speech or AI model is downloading"
        manager.createNotificationChannel(channel)
    }

    private fun shortName(id: String): String = id.substringAfterLast('/')

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)
}

private const val TAG = "DownloadService"
private const val CHANNEL_ID = "model-downloads"
private const val NOTIFICATION_ID = 42
private const val ACTION_CANCEL = "com.example.speechbridge.DOWNLOAD_CANCEL"
const val EXTRA_KIND = "kind"
const val EXTRA_MODEL = "model"
const val KIND_STT = "stt"
const val KIND_LLM = "llm"

/** Starts (or replaces) the foreground download for [modelId]. */
fun startModelDownload(context: Context, kind: String, modelId: String) {
    val intent = Intent(context, DownloadService::class.java)
        .putExtra(EXTRA_KIND, kind)
        .putExtra(EXTRA_MODEL, modelId)
    context.startForegroundService(intent)
}

/** Asks the running download to stop. */
fun cancelModelDownload(context: Context) {
    context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
}
