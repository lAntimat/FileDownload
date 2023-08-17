package com.kzn.filedownload.ui.main.downloader

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val s = "FileDownloadWorker"

class FileDownloadWorker constructor(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    private val downloaderUtils by lazy {
        DownloaderUtils(
            context,
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )
    }

    private val cancelIntent by lazy {
        WorkManager.getInstance(context).createCancelPendingIntent(id)
    }

    private val notificationsHelper = NotificationsHelper(
        context,
        downloaderUtils,
        cancelIntent
    )

    private var progressUpdater: ProgressUpdater =
        ProgressUpdater()

    private val mainScope = MainScope()

    private var downloadedSize = 0.0
    private var waitNetwork = false

    private val fileUrl by lazy { inputData.getString(KEY_FILE_URL) ?: "" }
    private val fileName by lazy { inputData.getString(KEY_FILE_NAME) ?: "" }
    private val okHttp by lazy { initOkHttp() }

    init {
        progressUpdater
            .updateDownloadInfo
            .onEach {
                if (!it.isEmpty && !waitNetwork && !isStopped) {
                    updateProgress(
                        it.bytesRead + downloadedSize,
                        it.contentLength + downloadedSize, it.timeLeft
                    )
                }
            }.launchIn(mainScope)
    }

    private suspend fun updateProgress(bytesRead: Double, contentLength: Double, timeLeft: Double) {
        val progress = PROGRESS_MAX * bytesRead / contentLength.coerceAtLeast(1.0)
        val progressText = formatProgressText(progress, bytesRead, contentLength)
        log("Progress = $progress progressText = $progressText}")
        setForegroundInfo(
            notificationsHelper.progressNotification(
                progressText,
                progress.toInt(),
                timeLeft
            )
        )
    }

    private fun formatProgressText(
        progress: Double,
        bytesRead: Double,
        contentLength: Double
    ) = when {
        progress == 0.0 -> {
            ""
        }

        contentLength == -1.0 -> {
            downloaderUtils.formatBytesToText(bytesRead)
        }

        else -> {
            "${downloaderUtils.formatBytesToText(bytesRead)} / ${
                downloaderUtils.formatBytesToText(
                    contentLength
                )
            }"
        }
    }

    override suspend fun doWork(): Result {
        val file = createFile()
        return try {
            log("Download start")
            setForegroundInfo(notificationsHelper.prepareNotification)
            downloadToFile(file, false)
        } catch (error: Throwable) {
            handleError(error, file)
            cleanup(file)
        }
    }

    private fun createFile(): File {
        val file = File(context.cacheDir, fileName)
        if (file.exists()) {
            file.delete()
            file.createNewFile()
        }
        return file
    }

    private suspend fun downloadToFile(file: File, append: Boolean): Result =
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(file, append)
                val outputStream = FileOutputStream(file, append)
                val response = okHttp.newCall(request).execute()
                writeToFile(response, outputStream)
                return@withContext onSuccess(file)
            } catch (e: IOException) {
                waitInternetConnection()
                downloadToFile(file, append = true)
            }
        }

    private suspend fun writeToFile(response: Response, outputStream: FileOutputStream) =
        withContext(Dispatchers.IO) {
            if (!response.isSuccessful) {
                val errorText = "Response is unsuccessful. Error code: ${response.code}"
                log(errorText)
                throw Throwable(errorText)
            }

            val input = BufferedInputStream(response.body?.byteStream())
            val dataBuffer = ByteArray(DATA_READ_SIZE)
            var readBytes: Int
            var totalReadBytes = 0.0
            while (input.read(dataBuffer).also { bytes -> readBytes = bytes } != -1) {
                outputStream.write(dataBuffer, 0, readBytes)
                totalReadBytes += readBytes
                //delay(10) //for presentation
                progressUpdater.updateProgress(
                    contentLength = response.body?.contentLength() ?: -1,
                    totalBytesRead = totalReadBytes
                )
            }
        }

    private fun onSuccess(file: File): Result {
        val fileUri = file.toUri()
        mainScope.cancel()
        progressUpdater.cancel()
        notificationsHelper.showDownloadFinishedNotification(fileUri)
        log("Worker result success $fileUri")
        return Result.success(workDataOf(KEY_FILE_URI to fileUri.toString()))
    }

    private fun handleError(
        e: Throwable,
        file: File
    ): Result {
        log(e.message.toString())
        return cleanup(file)
    }

    private fun Request.Builder.addRangeHeader(tempFile: File?) {
        if (tempFile != null && tempFile.exists()) {
            val fileSize = tempFile.length()
            if (fileSize > 0) {
                downloadedSize = tempFile.length().toDouble()
                addHeader("Range", "bytes=${fileSize}-")
            }
        }
    }

    private fun buildRequest(file: File?, append: Boolean): Request {
        val request = Request.Builder().apply {
            if (append) {
                addRangeHeader(file)
            }
            url(fileUrl)
        }.build()
        return request
    }

    private fun cleanup(file: File): Result {
        progressUpdater.cancel()
        mainScope.cancel()
        file.delete()
        return Result.failure()
    }

    private fun initOkHttp() = OkHttpClient()
        .newBuilder()
        .addNetworkInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.HEADERS)
        }).build()

    private suspend fun showWaitingNotification() {
        setForegroundInfo(
            notificationsHelper.waitingNotification
        )
    }

    private suspend fun setForegroundInfo(notification: Notification) {
        setForeground(
            ForegroundInfo(
                NotificationsHelper.NOTIFICATION_PROGRESS_ID,
                notification
            )
        )
    }

    private suspend fun waitInternetConnection() {
        log("Wait internet connection")
        waitNetwork = true
        showWaitingNotification()
        downloaderUtils.awaitNetworkAvailable()
        waitNetwork = false
    }

    private suspend fun showErrorToast() = withContext(Dispatchers.Main) {
        Toast.makeText(context, "error", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val WORK_NAME: String = "UpdateDownloadWork"
        private const val KEY_FILE_URL = "key_file_url"
        private const val KEY_FILE_NAME = "key_file_name"
        private const val KEY_FILE_URI = "key_file_uri"
        private const val PROGRESS_MAX = 100
        private const val DATA_READ_SIZE = 1024

        fun startWork(context: Context, url: String) {
            val workManager = WorkManager.getInstance(context)
            val data = Data.Builder().apply {
                putString(KEY_FILE_NAME, DownloaderUtils.fileNameFromUrl(url))
                putString(KEY_FILE_URL, url)
            }

            val fileDownloadWorker = OneTimeWorkRequestBuilder<FileDownloadWorker>()
                .setInputData(data.build())
                //.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, fileDownloadWorker)
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun getState(context: Context): WorkInfo.State? {
            return WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                .get().firstOrNull()?.state
        }
    }
}

fun log(msg: String) {
    Log.d("FileDownloadWorker", msg)
}
