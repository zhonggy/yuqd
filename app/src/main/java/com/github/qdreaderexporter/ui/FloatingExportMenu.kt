package com.github.qdreaderexporter.ui

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.export.BatchDownloadedLoader
import com.github.qdreaderexporter.export.RuntimeDiagnostics
import com.github.qdreaderexporter.export.StorageScanner
import com.github.qdreaderexporter.export.TxtExporter
import com.github.qdreaderexporter.hook.ChapterCacheHooker
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Content-view overlay FAB + custom in-layout panels.
 * Avoids host AlertDialog theme crashes in immersive reader activities.
 */
object FloatingExportMenu {

    private const val TAG_FAB = "qdre_fab_overlay"
    private const val TAG_PANEL = "qdre_panel_overlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastActivity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        lastActivity = WeakReference(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            toast(activity, "QDRE: content=null")
            return
        }
        if (content.findViewWithTag<View>(TAG_FAB) != null) return

        val density = activity.resources.displayMetrics.density
        val size = (58 * density).toInt()
        val margin = (18 * density).toInt()

        val fab = TextView(activity).apply {
            tag = TAG_FAB
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
            elevation = 24 * density
            translationZ = 200f
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            enableDragAndClick(this) {
                YLog.info("FloatingExportMenu: FAB clicked")
                toast(activity, "打开导出菜单…")
                showMenu(activity)
            }
        }

        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(margin, margin, margin, margin * 4)
        }
        try {
            content.addView(fab, lp)
        } catch (t: Throwable) {
            YLog.error("FAB addView failed: ${t.message}", t)
            val overlay = FrameLayout(activity).apply { tag = TAG_FAB + "_wrap" }
            content.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            overlay.addView(fab, lp)
        }

        // Always leave a breadcrumb file so user can verify module is alive
        io.execute {
            runCatching {
                val dir = TxtExporter.exportDir(activity.applicationContext)
                File(dir, "MODULE_ALIVE.txt").writeText(
                    buildString {
                        appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                        appendLine("activity=${activity.javaClass.name}")
                        appendLine("chapters=${ChapterMemoryStore.totalChapterCount()}")
                        appendLine("bookId=${ChapterMemoryStore.current?.bookId ?: "-"}")
                        appendLine("exportDir=${dir.absolutePath}")
                    }
                )
            }
        }
        toast(activity, "QDReaderExporter 导出按钮已显示")
        YLog.info("FloatingExportMenu: FAB attached on ${activity.javaClass.name}")
    }

    fun detach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG_FAB)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        content.findViewWithTag<View>(TAG_FAB + "_wrap")?.let { content.removeView(it) }
        content.findViewWithTag<View>(TAG_PANEL)?.let { content.removeView(it) }
    }

    private fun showMenu(activity: Activity) {
        val currentCount = ChapterMemoryStore.count()
        val allCount = ChapterMemoryStore.totalChapterCount()
        val bookCount = ChapterMemoryStore.bookIds().size
        val bookId = ChapterMemoryStore.current?.bookId
        val dlCount = bookId?.let { ChapterCacheHooker.idsFor(it).size } ?: 0
        val items = listOf(
            MenuItem("导出当前阅读书籍 (TXT)  ·  $currentCount 章") { exportCurrentBook(activity) },
            MenuItem("导出全部已捕获书籍 (TXT)  ·  ${bookCount}书 / ${allCount}章") { exportAllBooks(activity) },
            MenuItem(
                if (BatchDownloadedLoader.isRunning()) "批量加载已下载章节（进行中…）"
                else "批量加载当前书已下载章节  ·  已知ID $dlCount"
            ) { batchLoadDownloaded(activity) },
            MenuItem("尝试合并明文缓存（诊断）") { mergePlaintext(activity) },
            MenuItem("仅扫描存储路径") { scanStorage(activity) },
            MenuItem("运行时诊断") { runDiagnostic(activity) },
            MenuItem("查看缓存状态") { showStatus(activity) },
            MenuItem("清空内存缓存") {
                ChapterMemoryStore.clear()
                showMessage(activity, "已清空", "已清空全部内存缓存")
            }
        )
        showListPanel(activity, "起点读书导出", items)
    }

    private data class MenuItem(val label: String, val action: () -> Unit)

    private fun showListPanel(activity: Activity, title: String, items: List<MenuItem>) {
        runOnUi(activity) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                toast(activity, "QDRE: content=null, cannot show menu")
                return@runOnUi
            }
            content.findViewWithTag<View>(TAG_PANEL)?.let { content.removeView(it) }

            val density = activity.resources.displayMetrics.density
            val root = FrameLayout(activity).apply {
                tag = TAG_PANEL
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
                setOnClickListener { content.removeView(this) }
                elevation = 300 * density
                translationZ = 300f
            }

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                    (16 * density).toInt(),
                    (10 * density).toInt()
                )
                isClickable = true
            }

            card.addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(Color.BLACK)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, (10 * density).toInt())
                }
            )
            card.addView(
                TextView(activity).apply {
                    text = "当前捕获 ${ChapterMemoryStore.totalChapterCount()} 章 · 点空白处关闭"
                    setTextColor(Color.DKGRAY)
                    textSize = 12f
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
            )

            items.forEachIndexed { index, item ->
                card.addView(
                    TextView(activity).apply {
                        text = item.label
                        setTextColor(Color.parseColor("#E65100"))
                        textSize = 15f
                        setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            content.removeView(root)
                            runCatching { item.action() }
                                .onFailure {
                                    YLog.error("menu action failed: ${it.message}", it)
                                    showMessage(activity, "操作失败", it.message ?: it.toString())
                                }
                        }
                        if (index < items.lastIndex) {
                            // separator-ish via bottom padding already
                        }
                    }
                )
                if (index < items.lastIndex) {
                    card.addView(
                        View(activity).apply {
                            setBackgroundColor(0x22000000)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                (1 * density).toInt()
                            )
                        }
                    )
                }
            }

            card.addView(
                TextView(activity).apply {
                    text = "关闭"
                    gravity = Gravity.CENTER
                    setTextColor(Color.GRAY)
                    textSize = 14f
                    setPadding(0, (14 * density).toInt(), 0, (6 * density).toInt())
                    setOnClickListener { content.removeView(root) }
                }
            )

            val scroll = ScrollView(activity).apply {
                addView(card)
                isClickable = true
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                val m = (20 * density).toInt()
                setMargins(m, m * 2, m, m * 2)
            }
            root.addView(scroll, lp)
            content.addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            toast(activity, "导出菜单已打开")
        }
    }

    private fun showMessage(
        activity: Activity,
        title: String,
        message: String,
        actions: List<Pair<String, () -> Unit>> = listOf("确定" to {})
    ) {
        runOnUi(activity) {
            // Always toast first so user gets feedback even if panel fails
            toast(activity, "$title: ${message.lineSequence().firstOrNull().orEmpty()}")
            // Always write a result file
            io.execute {
                runCatching {
                    val dir = TxtExporter.exportDir(activity.applicationContext)
                    File(dir, "UI_${System.currentTimeMillis()}.txt").writeText(
                        "title=$title\n\n$message\n"
                    )
                }
            }

            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content == null) {
                toast(activity, message)
                return@runOnUi
            }
            content.findViewWithTag<View>(TAG_PANEL)?.let { content.removeView(it) }

            val density = activity.resources.displayMetrics.density
            val root = FrameLayout(activity).apply {
                tag = TAG_PANEL
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
                setOnClickListener { content.removeView(this) }
                elevation = 320 * density
                translationZ = 320f
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                    (16 * density).toInt(),
                    (10 * density).toInt()
                )
                isClickable = true
            }
            card.addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(Color.BLACK)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
            )
            val body = TextView(activity).apply {
                text = message
                setTextColor(Color.DKGRAY)
                textSize = 13f
                setTextIsSelectable(true)
            }
            val bodyScroll = ScrollView(activity).apply {
                addView(body)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                )
            }
            card.addView(bodyScroll)

            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, (12 * density).toInt(), 0, 0)
            }
            actions.forEach { (label, action) ->
                btnRow.addView(
                    TextView(activity).apply {
                        text = label
                        setTextColor(Color.parseColor("#E65100"))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                        setOnClickListener {
                            content.removeView(root)
                            runCatching { action() }
                        }
                    }
                )
            }
            card.addView(btnRow)

            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                val m = (18 * density).toInt()
                setMargins(m, m, m, m)
            }
            root.addView(card, lp)
            content.addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun batchLoadDownloaded(activity: Activity) {
        if (BatchDownloadedLoader.isRunning()) {
            showMessage(activity, "批量加载", "批量加载进行中…")
            return
        }
        val bookId = ChapterMemoryStore.current?.bookId
        if (bookId.isNullOrBlank()) {
            showMessage(activity, "批量加载", "尚未识别当前书籍，请先打开阅读页并翻页")
            return
        }
        val known = ChapterCacheHooker.idsFor(bookId).size
        showMessage(
            activity,
            "批量加载已下载章节",
            "当前书: ${ChapterMemoryStore.current?.bookName?.ifBlank { bookId } ?: bookId}\n" +
                "已知下载 ID: $known\n" +
                "已捕获: ${ChapterMemoryStore.count(bookId)} 章\n\n" +
                "将触发宿主本地加载，只捕获解密后明文。\n" +
                "不做 AES/VIP 解密，不购买。\n" +
                "若 ID=0：先开目录/下载管理。",
            actions = listOf(
                "取消" to {},
                "开始" to {
                    toast(activity, "开始批量加载…")
                    io.execute {
                        val result = BatchDownloadedLoader.runForCurrentBook(
                            maxChapters = 300,
                            perChapterWaitMs = 500L
                        ) { p ->
                            if (p.attempted % 10 == 0 || p.attempted == 1) {
                                mainHandler.post {
                                    toast(
                                        activity,
                                        "批量 ${p.attempted}  已捕获 ${p.capturedNow} 章"
                                    )
                                }
                            }
                        }
                        val reportFile = runCatching {
                            val dir = TxtExporter.exportDir(activity.applicationContext)
                            val f = File(dir, "batch_${System.currentTimeMillis()}.log")
                            f.writeText(result.toText())
                            f
                        }.getOrNull()
                        mainHandler.post {
                            showMessage(
                                activity,
                                "批量加载完成",
                                "下载 ID: ${result.totalIds}\n" +
                                    "尝试加载: ${result.attempted}\n" +
                                    "新捕获: ${result.newlyCaptured}\n" +
                                    "当前共捕获: ${result.totalCaptured}\n" +
                                    "跳过已有: ${result.skippedAlready}\n" +
                                    "onBuy: ${result.buySignals}\n" +
                                    "API命中: ${result.invokeHits}\n" +
                                    "日志: ${reportFile?.absolutePath ?: "(write failed)"}",
                                actions = listOf(
                                    "关闭" to {},
                                    "导出当前书" to { exportCurrentBook(activity) }
                                )
                            )
                        }
                    }
                }
            )
        )
    }

    private fun exportCurrentBook(activity: Activity) {
        val count = ChapterMemoryStore.count()
        val total = ChapterMemoryStore.totalChapterCount()
        if (count == 0 && total == 0) {
            showMessage(
                activity,
                "无法导出",
                "内存中还没有捕获到任何章节正文。\n\n" +
                    "请先在阅读页翻 2～3 页，再看「缓存状态」。\n" +
                    "也可先跑「运行时诊断」。",
                actions = listOf(
                    "关闭" to {},
                    "缓存状态" to { showStatus(activity) },
                    "运行诊断" to { runDiagnostic(activity) }
                )
            )
            return
        }
        toast(activity, "正在导出当前书籍…")
        io.execute {
            val result = TxtExporter.exportCurrentBook(activity.applicationContext)
            mainHandler.post { showExportResult(activity, result, "导出当前书") }
        }
    }

    private fun exportAllBooks(activity: Activity) {
        val books = ChapterMemoryStore.bookIds().size
        val chapters = ChapterMemoryStore.totalChapterCount()
        if (chapters == 0) {
            showMessage(
                activity,
                "无法导出",
                "内存中无已捕获章节。请打开已下载书籍并翻页。"
            )
            return
        }
        showMessage(
            activity,
            "导出全部已捕获书籍",
            "将导出 $books 本 / $chapters 章（每书一个 TXT）。",
            actions = listOf(
                "取消" to {},
                "导出" to {
                    toast(activity, "正在导出…")
                    io.execute {
                        val result = TxtExporter.exportAllCachedBooks(activity.applicationContext)
                        mainHandler.post { showExportResult(activity, result, "导出全部书籍") }
                    }
                }
            )
        )
    }

    private fun showExportResult(activity: Activity, result: TxtExporter.Result, title: String) {
        when (result) {
            is TxtExporter.Result.Success -> {
                val file = result.file
                val exists = file.exists()
                val size = if (exists) file.length() else 0L
                showMessage(
                    activity,
                    title,
                    "已导出 ${result.bookCount} 本 / ${result.chapterCount} 章\n\n" +
                        "文件:\n${file.absolutePath}\n\n" +
                        "大小: ${size} 字节\n存在: $exists\n" +
                        "目录:\n${result.dir.absolutePath}\n\n" +
                        "请用文件管理器打开。\n" +
                        "优先看 /sdcard/QDReaderExporter/"
                )
            }
            is TxtExporter.Result.Failure -> {
                showMessage(
                    activity,
                    "$title 失败",
                    result.message,
                    actions = listOf(
                        "关闭" to {},
                        "缓存状态" to { showStatus(activity) }
                    )
                )
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
                mainHandler.post { showMessage(activity, "合并失败", it.message ?: "$it") }
                return@execute
            }
            val reportFile = runCatching {
                val dir = TxtExporter.exportDir(activity.applicationContext)
                val f = File(dir, "merge_${System.currentTimeMillis()}.log")
                f.writeText(result.toText())
                f
            }.getOrNull()
            mainHandler.post {
                showMessage(
                    activity,
                    "合并明文缓存",
                    "扫描命中 ${result.scannedHits} 项\n" +
                        "合并章节 ${result.mergedChapters}\n" +
                        "日志: ${reportFile?.absolutePath ?: "(write failed)"}\n\n" +
                        "加密章节文件不会被解密合并。"
                )
            }
        }
    }

    private fun scanStorage(activity: Activity) {
        toast(activity, "正在扫描存储路径…")
        io.execute {
            val report = runCatching {
                StorageScanner.scan(activity.applicationContext)
            }.getOrElse {
                mainHandler.post { showMessage(activity, "扫描失败", it.message ?: "$it") }
                return@execute
            }
            val file = runCatching {
                StorageScanner.writeReport(activity.applicationContext, report)
            }.getOrNull()
            mainHandler.post {
                showMessage(
                    activity,
                    "存储扫描",
                    "扫描文件 ${report.scannedFiles}\n" +
                        "命中 ${report.hits.size}\n" +
                        "耗时 ${report.elapsedMs}ms\n" +
                        "日志: ${file?.absolutePath ?: "(write failed)"}"
                )
            }
        }
    }

    private fun runDiagnostic(activity: Activity) {
        toast(activity, "正在生成运行时诊断…")
        io.execute {
            val file = runCatching {
                RuntimeDiagnostics.writeReport(activity.applicationContext)
            }.getOrElse {
                mainHandler.post { showMessage(activity, "诊断失败", it.message ?: "$it") }
                return@execute
            }
            val preview = runCatching { file.readText().take(1500) }
                .getOrDefault(file.absolutePath)
            mainHandler.post {
                showMessage(
                    activity,
                    "运行时诊断",
                    "$preview\n\n完整日志:\n${file.absolutePath}"
                )
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
        val text =
            ChapterMemoryStore.statusText() +
                "\n\n--- downloaded ---\n$dl" +
                "\nbatchRunning=${BatchDownloadedLoader.isRunning()}" +
                "\n\n--- export dirs ---\n$dirs"
        // Persist status even if UI fails
        io.execute {
            runCatching {
                val dir = TxtExporter.exportDir(activity.applicationContext)
                File(dir, "STATUS.txt").writeText(text)
            }
        }
        showMessage(activity, "缓存状态", text)
    }

    private fun toast(activity: Activity, msg: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runOnUi(activity: Activity, block: () -> Unit) {
        val r = Runnable {
            if (!activity.isFinishing && !activity.isDestroyed) {
                runCatching(block).onFailure {
                    YLog.error("UI panel failed: ${it.message}", it)
                    toast(activity, "UI错误: ${it.message}")
                }
                Unit
            } else {
                toast(activity, "Activity 已结束，无法显示界面")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r)
    }

    /**
     * Drag without eating clicks. Click only fires when movement stays under touch slop.
     */
    private fun enableDragAndClick(view: View, onClick: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val touchSlop = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, view.resources.displayMetrics
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
                    true
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
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        onClick()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }
}
