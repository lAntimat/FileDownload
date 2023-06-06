package com.kzn.filedownload.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import com.kzn.filedownload.ui.main.downloader.FileDownloadWorker
import kotlin.time.ExperimentalTime

@ExperimentalTime
class MainViewModel : ViewModel() {

    fun startWorker(context: Context) {
        FileDownloadWorker.startWork(context, "https://freetestdata.com/wp-content/uploads/2022/02/Free_Test_Data_1MB_MP4.mp4")
    }
}