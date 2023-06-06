package com.kzn.filedownload.ui.main.downloader

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.internal.headersContentLength
import okhttp3.logging.HttpLoggingInterceptor
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.time.ExperimentalTime

@ExperimentalTime
class FileDownloadWorker constructor(
    private val context: Context,
    private val workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    private val notificationsHelper = NotificationsHelper(context)
    private val coroutineScope = MainScope()
    private val downloaderUtils by lazy {
        DownloaderUtils(
            context,
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )
    }

    private var tempFile: File? = null
    private var downloadedSize = 0.0
    private var tempCall: Call? = null
    private var progressResponseBody: ProgressResponseBody = ProgressResponseBody()
    private var waitNetwork = false

    private val fileUrl by lazy { inputData.getString(KEY_FILE_URL) ?: "" }
    private val fileName by lazy { inputData.getString(KEY_FILE_NAME) ?: "" }
    private val okHttp by lazy { initOkHttp() }
    private val cancelIntent by lazy {
        WorkManager.getInstance(context).createCancelPendingIntent(id)
    }

    init {
        coroutineScope.launch {
            progressResponseBody
                .updateDownloadInfo
                .collect {
                    if (!it.isEmpty && !waitNetwork && !isStopped) {
                        updateProgress(
                            it.bytesRead + downloadedSize,
                            it.contentLength + downloadedSize, it.timeLeft
                        )
                    }
                }
        }
    }

    private val waitingNotification
        get() = notificationsHelper.showProgressNotification(
            ProgressNotificationParams.WaitNetwork("Wait connection"),
            cancelIntent
        )

    private val prepareNotification
        get() = notificationsHelper.showProgressNotification(
            ProgressNotificationParams.Prepare("File downloading"),
            cancelIntent
        )

    private fun progressNotification(progressText: String, progress: Int, timeLeft: Double) =
        notificationsHelper.showProgressNotification(
            ProgressNotificationParams.LoadingInProgress(
                "Download in progress",
                progress,
                progressText,
                downloaderUtils.formatTime(timeLeft)
            ),
            cancelIntent
        )

    private suspend fun updateProgress(bytesRead: Double, contentLength: Double, timeLeft: Double) {
        val progress = PROGRESS_MAX * bytesRead / contentLength.coerceAtLeast(1.0)
        val progressText = if (progress == 0.0) {
            ""
        } else {
            "${downloaderUtils.formatBytesToText(bytesRead)} / ${
                downloaderUtils.formatBytesToText(
                    contentLength
                )
            }"
        }
        log("FileDownloadWorker: progress = $progress progressText = $progressText}")
        setForegroundInfo(progressNotification(progressText, progress.toInt(), timeLeft))
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            log("FileDownloadWorker: Download start")
            setForegroundInfo(prepareNotification)
            val file = File(context.cacheDir, fileName)
            if (file.exists()) {
                file.delete()
                file.createNewFile()
            }
            saveAndExit(file)
        } catch (error: Throwable) {
            log("FileDownloadWorker: Download worker error")
            cleanup()
        }
    }

    private suspend fun saveAndExit(file: File): Result {
        val uri = saveFile(file)
        coroutineScope.cancel()
        progressResponseBody.cancel()
        notificationsHelper.showDownloadFinishedNotification(uri)
        log("FileDownloadWorker: Worker result success $uri")
        return Result.success(workDataOf(KEY_FILE_URI to uri.toString()))
    }

    private suspend fun saveFile(file: File): Uri = withContext(Dispatchers.IO) {
        val request = Request.Builder().apply {
            if (tempFile?.exists() == true) {
                val tempFileSize = tempFile?.length() ?: 0L
                if (tempFileSize > 0) {
                    downloadedSize = tempFile?.length()?.toDouble() ?: 0.0
                    addHeader("Range", "bytes=${tempFileSize}-")
                }
            }
            url(fileUrl)
        }.build()

        makeCall(request, file)

        return@withContext file.toUri()
    }

    private suspend fun makeCall(request: Request, file: File) {
        tempCall?.cancel()
        val call = okHttp.newCall(request)
        tempCall = call
        val run = runCatching {
            call.execute().use { response ->
                if (response.isSuccessful) {
                    val input = BufferedInputStream(response.body?.byteStream())
                    val output = FileOutputStream(file, tempFile?.exists() == true)

                    tempFile = file

                    val dataBuffer = ByteArray(1024)
                    var readBytes: Int
                    while (input.read(dataBuffer)
                            .also { readBytes = it } != -1
                    ) {
                        output.write(dataBuffer, 0, readBytes)
                    }
                    log("FileDownloadWorker: Required file size = ${response.headersContentLength()}")
                    log("FileDownloadWorker: Downloaded file size = ${file.length()}")
                } else {
                    log("FileDownloadWorker: Response is unsuccessful. Error code: ${response.code}")
                    showErrorToast()
                    cleanup()
                }
            }
        }

        run.exceptionOrNull()?.let {
            if (it is IOException && !isStopped) {
                waitInternetConnection()
                saveAndExit(file)
            } else {
                log("File download worker error")
                showErrorToast()
                cleanup()
            }
        }
    }

    private fun cleanup(): Result {
        progressResponseBody.cancel()
        coroutineScope.cancel()
        tempCall?.cancel()
        tempFile?.delete()
        tempFile = null
        return Result.failure()
    }

    private fun initOkHttp() = OkHttpClient()
        .newBuilder()
        .addNetworkInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.HEADERS)
        })
        .addNetworkInterceptor(Interceptor { chain: Interceptor.Chain ->
            val originalResponse: Response = chain.proceed(chain.request())
            progressResponseBody.init(originalResponse.body)
            originalResponse
                .newBuilder()
                .body(progressResponseBody)
                .build()
        }).build()

    private suspend fun showWaitingNotification() {
        setForegroundInfo(
            waitingNotification
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
        while (true) {
            log("File download worker wait internet connection")
            waitNetwork = true
            showWaitingNotification()
            delay(WAIT_CONNECTION_DELAY)
            if (downloaderUtils.hasInternetConnection()) {
                waitNetwork = false
                break
            }
        }
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
        private const val WAIT_CONNECTION_DELAY = 5000L
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
