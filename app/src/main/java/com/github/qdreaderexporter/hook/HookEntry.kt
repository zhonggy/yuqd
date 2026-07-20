package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.BuildConfig
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        // Keep logs on until FAB/export is verified on device.
        isDebug = true
        debugLog {
            tag = "QDReaderExporter"
            isEnable = true
        }
    }

    override fun onHook() = encase {
        loadApp(name = HookTargets.HOST_PACKAGE) {
            YLog.info(
                "QDReaderExporter module loaded into host " +
                    "(module ${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE})"
            )
            loadHooker(HostVersionGate)
            loadHooker(BookMetaHooker)
            loadHooker(ChapterContentHooker)
            loadHooker(ChapterCacheHooker)
            loadHooker(ReaderActivityHooker)
        }
    }
}
