package com.kzn.filedownload.ui.main.downloader

sealed class ProgressNotificationParams {
    data class Prepare(val text: String): ProgressNotificationParams()
    data class WaitNetwork(val text: String): ProgressNotificationParams()
    data class LoadingInProgress(
        val text: String,
        val progress: Int,
        val progressText: String,
        val timeLeft: String
    ): ProgressNotificationParams()
}
