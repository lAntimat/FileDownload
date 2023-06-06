package com.kzn.filedownload

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.kzn.filedownload.ui.main.MainFragment
import kotlin.time.ExperimentalTime

class MainActivity : AppCompatActivity() {

    @OptIn(ExperimentalTime::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MainFragment.newInstance())
                .commitNow()
        }
    }
}