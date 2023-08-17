package com.kzn.filedownload.ui.main.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import com.kzn.filedownload.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


class DownloaderUtils(
    private val context: Context,
    private val cm: ConnectivityManager
) {

    suspend fun awaitNetworkAvailable() {
        if (!hasInternetConnection()) {
            waitNetworkAvailable()
        }
    }

    private suspend fun waitNetworkAvailable() = suspendCancellableCoroutine<Unit> {
        var callback: NetworkCallback? = null
        callback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                it.resume(Unit)
                callback?.let {callback ->
                    cm.unregisterNetworkCallback(callback)
                }
            }

            override fun onLost(network: Network) {

            }
        }
        cm.registerDefaultNetworkCallback(callback)

        it.invokeOnCancellation { cm.unregisterNetworkCallback(callback) }
    }

    @ExperimentalTime
    fun formatTime(millis: Double): String {
        val minutes = Duration.milliseconds(millis).inWholeMinutes
        val seconds = Duration.milliseconds(millis).inWholeSeconds
        val hours = Duration.milliseconds(millis).inWholeHours
        val time = when {
            seconds < SECONDS_MAX -> {
                "$seconds ${
                    context.resources.getQuantityString(
                        R.plurals.left_seconds,
                        seconds.toInt(),
                        seconds
                    )
                } left"
            }

            minutes < MINUTES_MAX -> {
                "$minutes ${
                    context.resources.getQuantityString(
                        R.plurals.left_minutes,
                        minutes.toInt(),
                        minutes
                    )
                } left"
            }

            hours in 1..HOURS_MAX -> {
                "$hours ${
                    context.resources.getQuantityString(
                        R.plurals.left_hours,
                        hours.toInt(),
                        hours
                    )
                } left"
            }

            else -> ""
        }

        return if (seconds > 0) "$time" else ""
    }

    @SuppressWarnings("ImplicitDefaultLocale", "MagicNumber")
    fun formatBytesToText(b: Double): String {
        var bytes = b
        if (-1000 < bytes && bytes < 1000) {
            return "$bytes B"
        }
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current())
    }

    suspend fun hasInternetConnection(): Boolean = withContext(Dispatchers.Main) {
        return@withContext cm.activeNetwork != null && cm.getNetworkCapabilities(cm.activeNetwork) != null
    }

    companion object {
        private const val SECONDS_MAX = 60
        private const val MINUTES_MAX = 60
        private const val HOURS_MAX = 100

        fun fileNameFromUrl(url: String): String {
            return url.substringAfterLast("/")
        }
    }

}