package com.kzn.filedownload.ui.main.downloader

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ProgressUpdater {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private var prevTime = SystemClock.elapsedRealtime()
    private var prevBytes = 1.0

    private var _downloadedBytesFlow = MutableStateFlow(Update())

    private var _updateDownloadInfo = MutableStateFlow(DownloadProgress())
    val updateDownloadInfo get() = _updateDownloadInfo

    init {
        coroutineScope.launch {
            _downloadedBytesFlow
                .sample(PROGRESS_UPDATE_DELAY)
                .cancellable()
                .collect {
                    _updateDownloadInfo.emit(
                        _updateDownloadInfo.value.copy(
                            bytesRead = it.totalBytesRead.coerceAtLeast(0.0),
                            contentLength = it.contentLength.toDouble()
                        )
                    )
                }
        }

        coroutineScope.launch {
            _downloadedBytesFlow
                .sample(TIME_LEFT_UPDATE_DELAY)
                .cancellable()
                .collect { update ->
                    val currentTime = SystemClock.elapsedRealtime()
                    var timeLeft = 0.0

                    val deltaTime = currentTime - prevTime
                    prevTime = currentTime

                    val deltaBytes = update.totalBytesRead - prevBytes
                    prevBytes = update.totalBytesRead

                    val speed = deltaTime / deltaBytes.coerceAtLeast(1.0)
                    if (speed > 0) {
                        timeLeft = ((update.contentLength - update.totalBytesRead) * speed)
                    }
                    _updateDownloadInfo.emit(_updateDownloadInfo.value.copy(timeLeft = timeLeft))
                }
        }
    }

    fun cancel() {
        coroutineScope.cancel()
    }

    suspend fun updateProgress(contentLength: Long, totalBytesRead: Double) {
        _downloadedBytesFlow.emit(
            Update(
                contentLength, totalBytesRead
            )
        )
    }

    private data class Update(
        val contentLength: Long = 0,
        val totalBytesRead: Double = 0.0
    )

    private companion object {
        const val TIME_LEFT_UPDATE_DELAY = 5000L
        const val PROGRESS_UPDATE_DELAY = 1000L
    }
}