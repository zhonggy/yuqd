package com.github.qdreaderexporter.hook

import android.app.Activity
import android.os.Bundle
import com.github.qdreaderexporter.ui.FloatingExportMenu
import com.github.qdreaderexporter.util.HostContext
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * Injects floating export menu into reader activities (content overlay, no overlay permission).
 */
object ReaderActivityHooker : YukiBaseHooker() {

    override fun onHook() {
        hookLifecycle(HookTargets.CLS_READER_ACTIVITY)
        hookLifecycle(HookTargets.CLS_READER_LITE_ACTIVITY)
    }

    private fun hookLifecycle(className: String) {
        runCatching {
            val clazz = className.toClass(appClassLoader)
            clazz.resolve().apply {
                firstMethod {
                    name = "onCreate"
                    parameters(Bundle::class)
                }.hook {
                    after {
                        runCatching {
                            val activity = instance<Activity>()
                            HostContext.setActivity(activity)
                            HostContext.setAppContext(activity.applicationContext)
                            activity.runOnUiThread {
                                FloatingExportMenu.attach(activity)
                            }
                        }.onFailure {
                            YLog.error("FAB attach failed: ${it.message}", it)
                        }
                    }
                }

                firstMethod {
                    name = "onResume"
                    emptyParameters()
                }.hook {
                    after {
                        runCatching {
                            val activity = instance<Activity>()
                            HostContext.setActivity(activity)
                            activity.runOnUiThread {
                                FloatingExportMenu.attach(activity)
                            }
                        }
                    }
                }

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
            YLog.info("ReaderActivityHooker: hooked $className lifecycle")
        }.onFailure {
            YLog.warn("ReaderActivityHooker: skip $className — ${it.message}")
        }
    }
}
