package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.BuildConfig
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = BuildConfig.DEBUG
        debugLog {
            tag = "QDReaderExporter"
        }
    }

    override fun onHook() = encase {
        loadApp(name = HookTargets.HOST_PACKAGE) {
            loadHooker(HostVersionGate)
            loadHooker(BookMetaHooker)
            loadHooker(ChapterContentHooker)
            loadHooker(ChapterCacheHooker)
            loadHooker(ReaderActivityHooker)
        }
    }
}
