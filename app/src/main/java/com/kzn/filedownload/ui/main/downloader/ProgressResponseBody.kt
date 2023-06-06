package com.kzn.filedownload.ui.main.downloader

import android.os.SystemClock
import java.io.IOException
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
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

@OptIn(FlowPreview::class)
class ProgressResponseBody : ResponseBody() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private var responseBody: ResponseBody? = null
    private var prevTime = SystemClock.elapsedRealtime()
    private var prevBytes = 1.0

    private var _downloadedBytesFlow = MutableStateFlow(0.0)

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
                            bytesRead = it,
                            contentLength = contentLength().toDouble()
                        )
                    )
                }
        }

        coroutineScope.launch {
            _downloadedBytesFlow
                .sample(TIME_LEFT_UPDATE_DELAY)
                .cancellable()
                .collect { bytesRead ->
                    val currentTime = SystemClock.elapsedRealtime()
                    var timeLeft = 0.0

                    val deltaTime = currentTime - prevTime
                    val deltaBytes = bytesRead - prevBytes

                    prevTime = currentTime
                    prevBytes = bytesRead

                    val speed = deltaTime / deltaBytes.coerceAtLeast(1.0)
                    if (speed > 0) {
                        timeLeft = ((contentLength() - bytesRead) * speed)
                    }
                    _updateDownloadInfo.emit(_updateDownloadInfo.value.copy(timeLeft = timeLeft))
                }
        }
    }

    fun init(responseBody: ResponseBody?) {
        this.responseBody = responseBody
    }

    fun cancel() {
        coroutineScope.cancel()
    }

    override fun contentType(): MediaType? {
        return responseBody?.contentType()
    }

    override fun contentLength(): Long {
        return responseBody?.contentLength() ?: 0L
    }

    override fun source(): BufferedSource {
        val bufferedSource = responseBody?.let {
            source(it.source()).buffer()
        }
        return bufferedSource ?: throw IllegalStateException("buffered source is null")
    }

    private fun source(source: Source): Source {
        var totalBytesRead = 0.0
        return object : ForwardingSource(source) {
            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                coroutineScope.launch {
                    _downloadedBytesFlow.emit(totalBytesRead)
                }
                return bytesRead
            }
        }
    }

    private companion object {
        const val TIME_LEFT_UPDATE_DELAY = 5000L
        const val PROGRESS_UPDATE_DELAY = 1000L
    }
}