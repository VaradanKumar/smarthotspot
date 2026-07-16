package com.varadan.hotspot

import androidx.compose.runtime.mutableStateListOf
import android.os.Handler
import android.os.Looper

object LogManager {
    val logs = mutableStateListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addLog(message: String) {
        // Ensure UI updates happen on the main thread
        mainHandler.post {
            if (logs.size > 50) logs.removeAt(0)
            logs.add(message)
        }
    }
}