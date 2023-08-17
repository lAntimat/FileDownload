package com.kzn.filedownload.ui.main.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kzn.filedownload.R
import kotlin.time.ExperimentalTime

class NotificationsHelper(
    private val context: Context,
    private val downloaderUtils: DownloaderUtils,
    private val cancelIntent: PendingIntent
    ) {

    private val channelId by lazy {
        createNotificationChannel(
            context,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
            false,
            "Download channel",
            "Download channel"
        )
    }

    private fun showProgressNotification(
        params: ProgressNotificationParams,
        cancelIntent: PendingIntent
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)

        when (params) {
            is ProgressNotificationParams.LoadingInProgress -> {
                builder.setContentTitle("Loading in progress")
                builder.setSubText(params.timeLeft)
                builder.setContentText(params.progressText)
                builder.setProgress(100, params.progress, false)
            }

            is ProgressNotificationParams.Prepare -> {
                builder.setContentTitle("Loading in progress")
                builder.setProgress(0, 0, true)
            }

            is ProgressNotificationParams.WaitNetwork -> {
                builder.setContentTitle("Waiting network")
                builder.setProgress(0, 0, true)
            }
        }


        builder.addAction(
            NotificationCompat.Action.Builder(
                0, "Cancel", cancelIntent
            ).build()
        )

        val notification = builder.build()
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_PROGRESS_ID, notification)
        return notification
    }

    fun showDownloadFinishedNotification(
        uri: Uri
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle("File downloaded")

        val notification = builder.build()
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_FILE_DOWNLOADED_ID, notification)
        return notification
    }

    val waitingNotification
        get() = showProgressNotification(
            ProgressNotificationParams.WaitNetwork("Wait connection"),
            cancelIntent
        )

    val prepareNotification
        get() = showProgressNotification(
            ProgressNotificationParams.Prepare("File downloading"),
            cancelIntent
        )

    @OptIn(ExperimentalTime::class)
    fun progressNotification(progressText: String, progress: Int, timeLeft: Double) =
        showProgressNotification(
            ProgressNotificationParams.LoadingInProgress(
                "Download in progress",
                progress,
                progressText,
                downloaderUtils.formatTime(timeLeft)
            ),
            cancelIntent
        )

    private fun createNotificationChannel(
        context: Context,
        importance: Int,
        showBadge: Boolean,
        name: String,
        description: String
    ): String {
        val channelId = "${context.packageName}-$name"
        val channel = NotificationChannel(channelId, name, importance)
        channel.description = description
        channel.setShowBadge(showBadge)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        return channelId
    }

    companion object {
        const val NOTIFICATION_PROGRESS_ID = 99
        const val NOTIFICATION_FILE_DOWNLOADED_ID = 100
    }
}