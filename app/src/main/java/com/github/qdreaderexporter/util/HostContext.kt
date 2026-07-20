package com.github.qdreaderexporter.util

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

object HostContext {
    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun setAppContext(context: Context) {
        appContextRef = context.applicationContext
    }

    fun appContext(): Context? = appContextRef

    fun setActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }

    fun activity(): Activity? = activityRef?.get()
}
