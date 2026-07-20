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
import com.github.qdreaderexporter.export.RuntimeDiagnostics
import com.github.qdreaderexporter.export.StorageScanner
import com.github.qdreaderexporter.export.TxtExporter
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
        val items = arrayOf(
            "导出当前阅读书籍 (TXT)  ·  $currentCount 章",
            "导出全部已捕获书籍 (TXT)  ·  ${bookCount}书 / ${allCount}章",
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
                    2 -> mergePlaintext(activity)
                    3 -> scanStorage(activity)
                    4 -> runDiagnostic(activity)
                    5 -> showStatus(activity)
                    6 -> {
                        ChapterMemoryStore.clear()
                        toast(activity, "已清空全部内存缓存")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCurrentBook(activity: Activity) {
        val count = ChapterMemoryStore.count()
        if (count == 0) {
            toast(activity, "当前书籍缓存为空，请先翻页加载已下载/可读章节")
            return
        }
        toast(activity, "正在导出当前书籍…")
        io.execute {
            val result = TxtExporter.exportCurrentBook(activity.applicationContext)
            mainHandler.post {
                when (result) {
                    is TxtExporter.Result.Success ->
                        toast(
                            activity,
                            "已导出 ${result.chapterCount} 章\n${result.file.absolutePath}"
                        )
                    is TxtExporter.Result.Failure -> toast(activity, result.message)
                }
            }
        }
    }

    private fun exportAllBooks(activity: Activity) {
        val books = ChapterMemoryStore.bookIds().size
        val chapters = ChapterMemoryStore.totalChapterCount()
        if (chapters == 0) {
            toast(
                activity,
                "内存中无已捕获章节。\n请打开已下载书籍并翻页；加密离线章不会被解密导出。"
            )
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
                        when (result) {
                            is TxtExporter.Result.Success -> {
                                val dir = result.file.parentFile?.absolutePath
                                    ?: result.file.absolutePath
                                toast(
                                    activity,
                                    "已导出 ${result.bookCount} 本 / ${result.chapterCount} 章\n$dir"
                                )
                            }
                            is TxtExporter.Result.Failure -> toast(activity, result.message)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        AlertDialog.Builder(activity)
            .setTitle("缓存状态")
            .setMessage(ChapterMemoryStore.statusText())
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
