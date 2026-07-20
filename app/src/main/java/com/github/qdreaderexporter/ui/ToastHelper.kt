package com.github.qdreaderexporter.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object ToastHelper {
    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String, long: Boolean = true) {
        handler.post {
            Toast.makeText(
                context.applicationContext,
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }
}
