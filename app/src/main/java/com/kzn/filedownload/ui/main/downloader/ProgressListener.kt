package ru.mail.im.updates.downloader

interface ProgressListener {
    suspend fun update(bytesRead: Long, contentLength: Long, timeLeft: Long)
}