package com.github.qdreaderexporter.export

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.qdreaderexporter.BuildConfig
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.hook.ChapterCacheHooker
import com.github.qdreaderexporter.hook.HookTargets
import com.github.qdreaderexporter.util.HostInstanceRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeDiagnostics {

    fun buildReport(context: Context): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val hostPi = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    HookTargets.HOST_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(HookTargets.HOST_PACKAGE, 0)
            }
        }.getOrNull()

        val hostVersionName = hostPi?.versionName ?: "?"
        val hostVersionCode = hostPi?.longVersionCode ?: -1L

        return buildString {
            appendLine("=== QDReaderExporter runtime diagnostic ===")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("module: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("buildType: ${BuildConfig.BUILD_TYPE}")
            appendLine("processPkg: $pkg")
            appendLine("sdk: ${Build.VERSION.SDK_INT} / ${Build.VERSION.RELEASE}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("--- host ---")
            appendLine("package: ${HookTargets.HOST_PACKAGE}")
            appendLine("versionName: $hostVersionName (expected ${HookTargets.EXPECTED_VERSION_NAME})")
            appendLine("versionCode: $hostVersionCode (expected ${HookTargets.EXPECTED_VERSION_CODE})")
            appendLine(
                "versionMatch: ${
                    hostVersionName == HookTargets.EXPECTED_VERSION_NAME &&
                        hostVersionCode == HookTargets.EXPECTED_VERSION_CODE
                }"
            )
            appendLine()
            appendLine("--- memory cache ---")
            appendLine(ChapterMemoryStore.statusText())
            appendLine()
            appendLine("--- downloaded ids observed ---")
            if (ChapterCacheHooker.downloadedIds.isEmpty()) {
                appendLine("(none yet — open book / chapter list / download manager)")
            } else {
                ChapterCacheHooker.downloadedIds.forEach { (bookId, ids) ->
                    appendLine("bookId=$bookId count=${ids.size}")
                    appendLine("  sample: ${ids.take(12).joinToString()}")
                }
            }
            appendLine()
            appendLine("--- host engine instances ---")
            append(HostInstanceRegistry.statusText())
            appendLine("batchRunning: ${BatchDownloadedLoader.isRunning()}")
            appendLine()
            appendLine("--- storage roots ---")
            listOfNotNull(
                context.filesDir,
                context.cacheDir,
                context.getDatabasePath("x").parentFile,
                context.getExternalFilesDir(null),
                context.externalCacheDir,
                TxtExporter.exportDir(context)
            ).forEach { f ->
                appendLine("${f.absolutePath} exists=${f.exists()} canRead=${f.canRead()}")
            }
            appendLine()
            appendLine("--- export dirs ---")
            append(TxtExporter.describeWritableDirs(context))
            appendLine()
            appendLine("--- notes ---")
            appendLine("1) Captures plaintext AFTER host decrypt/load only.")
            appendLine("2) Batch load re-invokes host local load APIs for downloaded IDs — no AES reimplementation.")
            appendLine("3) Encrypted offline chapter files are NOT decrypted.")
            appendLine("4) Export path: ${TxtExporter.exportDir(context).absolutePath}")
            appendLine("5) Prefer /sdcard/QDReaderExporter when writable; else host app-private path.")
            appendLine("6) If hooks miss, update HookTargets via jadx (tools/notes-7.9.394.md).")
        }
    }

    fun writeReport(context: Context): File {
        val dir = TxtExporter.exportDir(context)
        val name = "diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
        val file = File(dir, name)
        file.writeText(buildReport(context), Charsets.UTF_8)
        return file
    }
}
