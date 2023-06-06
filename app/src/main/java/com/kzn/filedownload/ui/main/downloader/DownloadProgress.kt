package com.kzn.filedownload.ui.main.downloader

data class DownloadProgress(
    val bytesRead: Double = -1.0,
    val contentLength: Double = -1.0,
    val timeLeft: Double = -1.0
) {
    val isEmpty = bytesRead == -1.0
}
