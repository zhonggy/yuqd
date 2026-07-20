package com.github.qdreaderexporter.hook

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.github.qdreaderexporter.ui.FloatingExportMenu
import com.github.qdreaderexporter.util.HostContext
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Injects floating export menu into reader activities.
 *
 * Strategy:
 * 1) Hook known reader Activity classes directly
 * 2) Fallback: hook android.app.Activity lifecycle and match class name
 * 3) Delay-attach FAB (content view may not be ready in onCreate)
 */
object ReaderActivityHooker : YukiBaseHooker() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loggedActivities = AtomicBoolean(false)

    private val readerNameHints = arrayOf(
        "QDReaderActivity",
        "QDReaderLiteActivity",
        "BaseImmerseReaderActivity",
        "ReaderActivity"
    )

    override fun onHook() {
        for (cls in HookTargets.READER_ACTIVITY_CANDIDATES) {
            hookNamedClass(cls)
        }
        hookActivityFallback()
        YLog.info("ReaderActivityHooker: installed named + Activity fallback hooks")
    }

    private fun hookNamedClass(className: String) {
        runCatching {
            val clazz = className.toClass(appClassLoader)
            clazz.resolve().optional(silent = true).apply {
                runCatching {
                    firstMethod {
                        name = "onCreate"
                        parameters(Bundle::class)
                    }.hook {
                        after { scheduleAttach(instance<Activity>(), "onCreate/$className") }
                    }
                }
                runCatching {
                    firstMethod {
                        name = "onResume"
                        emptyParameters()
                    }.hook {
                        after { scheduleAttach(instance<Activity>(), "onResume/$className") }
                    }
                }
                runCatching {
                    firstMethod {
                        name = "onDestroy"
                        emptyParameters()
                    }.hook {
                        before {
                            runCatching {
                                val activity = instance<Activity>()
                                FloatingExportMenu.detach(activity)
                                if (HostContext.activity() === activity) {
                                    HostContext.setActivity(null)
                                }
                            }
                        }
                    }
                }
            }
            YLog.info("ReaderActivityHooker: hooked $className")
        }.onFailure {
            YLog.warn("ReaderActivityHooker: skip $className — ${it.message}")
        }
    }

    private fun hookActivityFallback() {
        runCatching {
            Activity::class.resolve().optional(silent = true).apply {
                firstMethod {
                    name = "onResume"
                    emptyParameters()
                }.hook {
                    after {
                        val activity = instance<Activity>()
                        val name = activity.javaClass.name
                        // One-shot dump of activity class to help diagnose wrong page
                        if (loggedActivities.compareAndSet(false, true)) {
                            YLog.info("ReaderActivityHooker: first Activity.onResume -> $name")
                        }
                        if (isReaderActivity(name)) {
                            scheduleAttach(activity, "fallback/onResume/$name")
                        }
                    }
                }
                firstMethod {
                    name = "onCreate"
                    parameters(Bundle::class)
                }.hook {
                    after {
                        val activity = instance<Activity>()
                        val name = activity.javaClass.name
                        if (isReaderActivity(name)) {
                            scheduleAttach(activity, "fallback/onCreate/$name")
                        }
                    }
                }
            }
            YLog.info("ReaderActivityHooker: Activity fallback hooked")
        }.onFailure {
            YLog.error("ReaderActivityHooker: Activity fallback failed: ${it.message}", it)
        }
    }

    private fun isReaderActivity(className: String): Boolean {
        if (HookTargets.READER_ACTIVITY_CANDIDATES.any { it == className }) return true
        // Match simple names / package fragments without false-positive on "ReadPageSettingActivity"
        val simple = className.substringAfterLast('.')
        return readerNameHints.any { hint ->
            simple == hint || simple.endsWith(hint)
        } || className.contains(".ui.activity.QDReader")
    }

    private fun scheduleAttach(activity: Activity, reason: String) {
        HostContext.setActivity(activity)
        HostContext.setAppContext(activity.applicationContext)
        YLog.info("ReaderActivityHooker: schedule FAB attach ($reason)")
        // Content may not be ready immediately; try several times.
        val delays = longArrayOf(0L, 300L, 800L, 1600L, 3000L)
        for (delay in delays) {
            mainHandler.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                runCatching {
                    FloatingExportMenu.attach(activity)
                }.onFailure {
                    YLog.error("FAB attach failed ($reason, +${delay}ms): ${it.message}", it)
                }
            }, delay)
        }
    }
}
