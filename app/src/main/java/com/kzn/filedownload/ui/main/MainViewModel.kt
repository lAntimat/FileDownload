package com.kzn.filedownload.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import com.kzn.filedownload.ui.main.downloader.FileDownloadWorker
import kotlin.time.ExperimentalTime

@ExperimentalTime
class MainViewModel : ViewModel() {

    fun startWorker(context: Context) {
        FileDownloadWorker.startWork(context, "https://builds.hb.bizmrg.com/icqAlpha/10013249/icq_10013249.apk")
    }
}