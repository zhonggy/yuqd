package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.util.HostContext
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * Soft version gate: warn when host is not the reverse-engineered build.
 * Hooks still load so exploratory use on nearby builds is possible, but
 * fragile name-based hooks are expected to break.
 */
object HostVersionGate : YukiBaseHooker() {

    @Volatile
    var isExpectedVersion: Boolean = false
        private set

    override fun onHook() {
        runCatching {
            val ctx = appContext ?: return@runCatching
            HostContext.setAppContext(ctx)
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(HookTargets.HOST_PACKAGE, 0)
            val name = info.versionName.orEmpty()
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            isExpectedVersion =
                name == HookTargets.EXPECTED_VERSION_NAME &&
                    code == HookTargets.EXPECTED_VERSION_CODE
            if (isExpectedVersion) {
                YLog.info("Host version OK: $name ($code)")
            } else {
                YLog.warn(
                    "Host version mismatch: $name ($code), expected " +
                        "${HookTargets.EXPECTED_VERSION_NAME} (${HookTargets.EXPECTED_VERSION_CODE}). " +
                        "Hooks may fail — re-run jadx and update HookTargets."
                )
            }
        }.onFailure {
            YLog.error("HostVersionGate failed: ${it.message}", it)
        }
    }
}
