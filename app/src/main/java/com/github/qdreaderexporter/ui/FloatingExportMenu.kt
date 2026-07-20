package com.github.qdreaderexporter.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.export.BatchDownloadedLoader
import com.github.qdreaderexporter.export.RuntimeDiagnostics
import com.github.qdreaderexporter.export.StorageScanner
import com.github.qdreaderexporter.export.TxtExporter
import com.github.qdreaderexporter.hook.ChapterCacheHooker
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Content-view overlay FAB + export dialog (aligned with common exporter menus).
 * No SYSTEM_ALERT_WINDOW required.
 */
object FloatingExportMenu {

    private const val TAG = "qdre_fab_overlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastActivity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        lastActivity = WeakReference(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            Toast.makeText(activity.applicationContext, "QDRE: content=null", Toast.LENGTH_SHORT).show()
            return
        }
        if (content.findViewWithTag<View>(TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val size = (58 * density).toInt()
        val margin = (18 * density).toInt()

        val fab = TextView(activity).apply {
            tag = TAG
            text = "导出"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF6D00"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 12 * density
            isClickable = true
            isFocusable = true
            // Keep above host chrome
            translationZ = 100f
            setOnClickListener { showMenu(activity) }
            enableDrag(this)
        }

        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(margin, margin, margin, margin * 4)
        }
        // android.R.id.content is usually FrameLayout; if not, wrap safely
        try {
            content.addView(fab, lp)
        } catch (_: Throwable) {
            val wrap = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val overlay = FrameLayout(activity)
            overlay.tag = TAG + "_wrap"
            content.addView(overlay, wrap)
            overlay.addView(fab, lp)
        }
        Toast.makeText(activity.applicationContext, "QDReaderExporter 导出按钮已显示", Toast.LENGTH_SHORT).show()
    }

    fun detach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
        content.findViewWithTag<View>(TAG + "_wrap")?.let { content.removeView(it) }
    }

    private fun showMenu(activity: Activity) {
        val currentCount = ChapterMemoryStore.count()
        val allCount = ChapterMemoryStore.totalChapterCount()
        val bookCount = ChapterMemoryStore.bookIds().size
        val bookId = ChapterMemoryStore.current?.bookId
        val dlCount = bookId?.let { ChapterCacheHooker.idsFor(it).size } ?: 0
        val batchLabel = if (BatchDownloadedLoader.isRunning()) {
            "批量加载已下载章节（进行中…点此无效）"
        } else {
            "批量加载当前书已下载章节  ·  已知ID $dlCount"
        }
        val items = arrayOf(
            "导出当前阅读书籍 (TXT)  ·  $currentCount 章",
            "导出全部已捕获书籍 (TXT)  ·  ${bookCount}书 / ${allCount}章",
            batchLabel,
            "尝试合并明文缓存（诊断）",
            "仅扫描存储路径",
            "运行时诊断",
            "查看缓存状态",
            "清空内存缓存"
        )
        AlertDialog.Builder(activity)
            .setTitle("起点读书导出")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportCurrentBook(activity)
                    1 -> exportAllBooks(activity)
                    2 -> batchLoadDownloaded(activity)
                    3 -> mergePlaintext(activity)
                    4 -> scanStorage(activity)
                    5 -> runDiagnostic(activity)
                    6 -> showStatus(activity)
                    7 -> {
                        ChapterMemoryStore.clear()
                        toast(activity, "已清空全部内存缓存")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun batchLoadDownloaded(activity: Activity) {
        if (BatchDownloadedLoader.isRunning()) {
            toast(activity, "批量加载进行中…")
            return
        }
        val bookId = ChapterMemoryStore.current?.bookId
        if (bookId.isNullOrBlank()) {
            toast(activity, "尚未识别当前书籍，请先打开阅读页")
            return
        }
        val known = ChapterCacheHooker.idsFor(bookId).size
        AlertDialog.Builder(activity)
            .setTitle("批量加载已下载章节")
            .setMessage(
                "将让宿主按「已下载章节 ID」自行加载本地/已购缓存，" +
                    "模块只捕获解密后的明文。\n\n" +
                    "当前书: ${ChapterMemoryStore.current?.bookName?.ifBlank { bookId } ?: bookId}\n" +
                    "已知下载 ID: $known\n" +
                    "已捕获: ${ChapterMemoryStore.count(bookId)} 章\n\n" +
                    "不会做 AES/VIP 解密，不会购买未购章节。\n" +
                    "若 ID 为 0：请先打开目录或下载管理再试。"
            )
            .setPositiveButton("开始") { _, _ ->
                toast(activity, "开始批量加载…请保持阅读页在前台")
                io.execute {
                    val result = BatchDownloadedLoader.runForCurrentBook(
                        maxChapters = 300,
                        perChapterWaitMs = 500L
                    ) { p ->
                        if (p.attempted % 10 == 0 || p.attempted == 1) {
                            mainHandler.post {
                                toast(
                                    activity,
                                    "批量 ${p.attempted}/${p.totalIds - p.skippedAlready}  " +
                                        "已捕获 ${p.capturedNow} 章"
                                )
                            }
                        }
                    }
                    val reportFile = runCatching {
                        val dir = TxtExporter.exportDir(activity.applicationContext)
                        val f = java.io.File(
                            dir,
                            "batch_${System.currentTimeMillis()}.log"
                        )
                        f.writeText(result.toText())
                        f
                    }.getOrNull()
                    mainHandler.post {
                        AlertDialog.Builder(activity)
                            .setTitle("批量加载完成")
                            .setMessage(
                                "下载 ID: ${result.totalIds}\n" +
                                    "尝试加载: ${result.attempted}\n" +
                                    "新捕获: ${result.newlyCaptured}\n" +
                                    "当前共捕获: ${result.totalCaptured}\n" +
                                    "跳过已有: ${result.skippedAlready}\n" +
                                    "宿主 onBuy 信号: ${result.buySignals}\n" +
                                    "API 命中: ${result.invokeHits}\n" +
                                    "日志: ${reportFile?.absolutePath ?: "(write failed)"}\n\n" +
                                    "可继续点「导出当前阅读书籍」。"
                            )
                            .setPositiveButton("导出当前书") { _, _ ->
                                exportCurrentBook(activity)
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCurrentBook(activity: Activity) {
        val count = ChapterMemoryStore.count()
        val total = ChapterMemoryStore.totalChapterCount()
        if (count == 0 && total == 0) {
            AlertDialog.Builder(activity)
                .setTitle("无法导出")
                .setMessage(
                    "内存中还没有捕获到任何章节正文。\n\n" +
                        "请先：\n" +
                        "1. 在阅读页翻 2～3 页（让宿主解密加载）\n" +
                        "2. 打开「查看缓存状态」确认章节数 > 0\n" +
                        "3. 再点导出\n\n" +
                        "也可先跑「运行时诊断」，日志会写到导出目录。"
                )
                .setPositiveButton("查看缓存状态") { _, _ -> showStatus(activity) }
                .setNegativeButton("运行时诊断") { _, _ -> runDiagnostic(activity) }
                .setNeutralButton("关闭", null)
                .show()
            return
        }
        toast(activity, "正在导出当前书籍…")
        io.execute {
            val result = TxtExporter.exportCurrentBook(activity.applicationContext)
            mainHandler.post {
                showExportResult(activity, result, title = "导出当前书")
            }
        }
    }

    private fun exportAllBooks(activity: Activity) {
        val books = ChapterMemoryStore.bookIds().size
        val chapters = ChapterMemoryStore.totalChapterCount()
        if (chapters == 0) {
            AlertDialog.Builder(activity)
                .setTitle("无法导出")
                .setMessage(
                    "内存中无已捕获章节。\n" +
                        "请打开已下载书籍并翻页；加密离线章不会被解密导出。"
                )
                .setPositiveButton("确定", null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("导出全部已捕获书籍")
            .setMessage(
                "将导出本进程内存中已捕获的 $books 本书 / $chapters 章。\n" +
                    "每本书一个 TXT。\n" +
                    "不含未打开、未解密、未购 VIP 章节。"
            )
            .setPositiveButton("导出") { _, _ ->
                toast(activity, "正在导出…")
                io.execute {
                    val result = TxtExporter.exportAllCachedBooks(activity.applicationContext)
                    mainHandler.post {
                        showExportResult(activity, result, title = "导出全部书籍")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportResult(
        activity: Activity,
        result: TxtExporter.Result,
        title: String
    ) {
        if (activity.isFinishing) return
        when (result) {
            is TxtExporter.Result.Success -> {
                val file = result.file
                val exists = file.exists()
                val size = if (exists) file.length() else 0L
                val msg =
                    "已导出 ${result.bookCount} 本 / ${result.chapterCount} 章\n\n" +
                        "文件:\n${file.absolutePath}\n\n" +
                        "大小: ${size} 字节\n" +
                        "存在: $exists\n" +
                        "目录:\n${result.dir.absolutePath}\n\n" +
                        "请用文件管理器打开上述路径。\n" +
                        "若在 /Android/data/... 下，需 ROOT 或 adb 才能直接看。"
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .show()
                toast(activity, "已导出 → ${file.name}")
            }
            is TxtExporter.Result.Failure -> {
                AlertDialog.Builder(activity)
                    .setTitle("$title 失败")
                    .setMessage(result.message)
                    .setPositiveButton("查看缓存") { _, _ -> showStatus(activity) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun mergePlaintext(activity: Activity) {
        toast(activity, "正在扫描并尝试合并明文缓存…")
        io.execute {
            val result = runCatching {
                StorageScanner.mergePlaintextCache(
                    activity.applicationContext,
                    ChapterMemoryStore.current?.bookId
                )
            }.getOrElse {
                mainHandler.post { toast(activity, "合并失败: ${it.message}") }
                return@execute
            }
            val reportFile = runCatching {
                val dir = TxtExporter.exportDir(activity.applicationContext)
                val f = java.io.File(
                    dir,
                    "merge_${System.currentTimeMillis()}.log"
                )
                f.writeText(result.toText())
                f
            }.getOrNull()
            mainHandler.post {
                val path = reportFile?.absolutePath ?: "(report write failed)"
                AlertDialog.Builder(activity)
                    .setTitle("合并明文缓存")
                    .setMessage(
                        "扫描命中 ${result.scannedHits} 项\n" +
                            "合并章节 ${result.mergedChapters}\n" +
                            "日志: $path\n\n" +
                            "说明：加密章节文件不会被解密合并。"
                    )
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun scanStorage(activity: Activity) {
        toast(activity, "正在扫描存储路径…")
        io.execute {
            val report = runCatching {
                StorageScanner.scan(activity.applicationContext)
            }.getOrElse {
                mainHandler.post { toast(activity, "扫描失败: ${it.message}") }
                return@execute
            }
            val file = runCatching {
                StorageScanner.writeReport(activity.applicationContext, report)
            }.getOrNull()
            mainHandler.post {
                AlertDialog.Builder(activity)
                    .setTitle("存储扫描")
                    .setMessage(
                        "扫描文件 ${report.scannedFiles}\n" +
                            "命中 ${report.hits.size}\n" +
                            "耗时 ${report.elapsedMs}ms\n" +
                            "日志: ${file?.absolutePath ?: "(write failed)"}"
                    )
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun runDiagnostic(activity: Activity) {
        toast(activity, "正在生成运行时诊断…")
        io.execute {
            val file = runCatching {
                RuntimeDiagnostics.writeReport(activity.applicationContext)
            }.getOrElse {
                mainHandler.post { toast(activity, "诊断失败: ${it.message}") }
                return@execute
            }
            val preview = runCatching {
                file.readText().take(1200)
            }.getOrDefault(file.absolutePath)
            mainHandler.post {
                AlertDialog.Builder(activity)
                    .setTitle("运行时诊断")
                    .setMessage("$preview\n\n完整日志:\n${file.absolutePath}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun showStatus(activity: Activity) {
        val bookId = ChapterMemoryStore.current?.bookId
        val dl = if (bookId.isNullOrBlank()) {
            "(no book)"
        } else {
            val ids = ChapterCacheHooker.idsFor(bookId)
            "downloadedIds: ${ids.size}\n  sample: ${ids.take(12).joinToString()}"
        }
        val dirs = runCatching {
            TxtExporter.describeWritableDirs(activity.applicationContext)
        }.getOrDefault("(dir probe failed)")
        AlertDialog.Builder(activity)
            .setTitle("缓存状态")
            .setMessage(
                ChapterMemoryStore.statusText() +
                    "\n\n--- downloaded ---\n" + dl +
                    "\nbatchRunning=${BatchDownloadedLoader.isRunning()}" +
                    "\n\n--- export dirs ---\n" + dirs
            )
            .setPositiveButton("确定", null)
            .show()
    }

    private fun toast(activity: Activity, msg: String) {
        if (activity.isFinishing) return
        Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    private fun enableDrag(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val touchSlop = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, view.resources.displayMetrics
        )

        view.setOnTouchListener { v, event ->
            val parent = v.parent as? ViewGroup ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val nx = (startX + dx).coerceIn(0f, (parent.width - v.width).toFloat())
                        val ny = (startY + dy).coerceIn(0f, (parent.height - v.height).toFloat())
                        v.x = nx
                        v.y = ny
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }
}
