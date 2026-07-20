package com.github.qdreaderexporter.export

import android.content.Context
import android.os.Environment
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.cache.ChapterRecord
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TxtExporter {

    private val utf8: Charset = Charsets.UTF_8
    private val timeFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    sealed class Result {
        data class Success(
            val file: File,
            val chapterCount: Int,
            val bookCount: Int = 1,
            val extraFiles: List<File> = emptyList(),
            val dir: File = file.parentFile ?: file
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    /** Export the currently focused chapter only. */
    fun exportCurrentChapter(context: Context, options: ExportOptions = ExportOptions()): Result {
        val record = ChapterMemoryStore.currentChapter()
            ?: return Result.Failure(
                "当前章节尚未捕获。\n" +
                    "请在阅读页先翻 2～3 页，打开「查看缓存状态」确认有章节后再导出。"
            )
        return writeSingle(context, record, options)
    }

    /** Export all in-memory chapters of the current book. */
    fun exportCurrentBook(context: Context, options: ExportOptions = ExportOptions()): Result {
        val session = ChapterMemoryStore.current
        val bookId = session?.bookId?.takeIf { it.isNotBlank() }
            ?: ChapterMemoryStore.bookIds().firstOrNull()
            ?: return Result.Failure(
                "尚未识别当前书籍。\n" +
                    "请打开阅读页并翻页，再看「缓存状态」。"
            )
        val list = ChapterMemoryStore.allFor(bookId).filter { it.hasContent }
        if (list.isEmpty()) {
            // Fallback: any captured book content
            val any = ChapterMemoryStore.allChapters().filter { it.hasContent }
            if (any.isEmpty()) {
                return Result.Failure(
                    "内存缓存为空，无法写出 TXT。\n\n" +
                        "请先：\n" +
                        "1. 打开已下载/可阅读章节并翻页\n" +
                        "2. 点「查看缓存状态」确认章节数 > 0\n" +
                        "3. 再导出\n\n" +
                        "说明：未解密的离线章不会出现在缓存中。"
                )
            }
            val grouped = any.groupBy { it.bookId }
            val firstBook = grouped.entries.first()
            val name = ChapterMemoryStore.bookNameOf(firstBook.key)
            return writeMulti(context, name, firstBook.key, firstBook.value, options, tag = "book")
        }
        val bookName = session?.bookName?.ifBlank { null }
            ?: ChapterMemoryStore.bookNameOf(bookId)
        return writeMulti(context, bookName, bookId, list, options, tag = "book")
    }

    fun exportCurrent(context: Context, options: ExportOptions = ExportOptions()): Result =
        exportCurrentChapter(context, options)

    fun exportCached(context: Context, options: ExportOptions = ExportOptions()): Result =
        exportCurrentBook(context, options)

    fun exportAllCachedBooks(context: Context, options: ExportOptions = ExportOptions()): Result {
        val summaries = ChapterMemoryStore.summaries()
        if (summaries.isEmpty()) {
            return Result.Failure(
                "内存中没有任何已捕获章节。\n" +
                    "请打开已下载书籍并翻页后再导出。\n" +
                    "加密离线章不会被解密导出。"
            )
        }
        val files = mutableListOf<File>()
        var totalChapters = 0
        for (summary in summaries) {
            val list = ChapterMemoryStore.allFor(summary.bookId).filter { it.hasContent }
            if (list.isEmpty()) continue
            val name = summary.bookName.ifBlank { summary.bookId }
            when (val r = writeMulti(context, name, summary.bookId, list, options, tag = "all")) {
                is Result.Success -> {
                    files += r.file
                    totalChapters += r.chapterCount
                }
                is Result.Failure -> return r
            }
        }
        if (files.isEmpty()) {
            return Result.Failure("没有可写出的章节内容")
        }
        return Result.Success(
            file = files.first(),
            chapterCount = totalChapters,
            bookCount = files.size,
            extraFiles = files.drop(1),
            dir = files.first().parentFile ?: files.first()
        )
    }

    /**
     * Candidate export directories, tried in order until one is writable.
     * Note: code runs as host UID (com.qidian.QDReader), so module permissions do not apply.
     */
    fun candidateDirs(context: Context): List<File> {
        val list = mutableListOf<File>()
        fun add(f: File?) {
            if (f != null) list += f
        }
        // Easy-to-find public locations first
        add(File("/sdcard/QDReaderExporter"))
        add(File("/storage/emulated/0/QDReaderExporter"))
        add(File(Environment.getExternalStorageDirectory(), "QDReaderExporter"))
        add(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "QDReaderExporter"
            )
        )
        add(File("/sdcard/Documents/QDReaderExporter"))
        add(File("/storage/emulated/0/Documents/QDReaderExporter"))
        // Host app-specific (always more likely to work)
        add(File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QDReaderExporter"))
        add(File(context.getExternalFilesDir(null), "QDReaderExporter"))
        add(File(context.filesDir, "QDReaderExporter"))
        add(File(context.cacheDir, "QDReaderExporter"))
        return list.distinctBy { it.absolutePath }
    }

    fun exportDir(context: Context): File {
        for (dir in candidateDirs(context)) {
            if (ensureWritableDir(dir)) {
                YLog.info("TxtExporter: using export dir ${dir.absolutePath}")
                return dir
            }
        }
        // Last resort — may still fail on write
        val fallback = File(context.filesDir, "QDReaderExporter")
        fallback.mkdirs()
        YLog.warn("TxtExporter: fallback to ${fallback.absolutePath}")
        return fallback
    }

    fun describeWritableDirs(context: Context): String = buildString {
        for (dir in candidateDirs(context)) {
            val ok = ensureWritableDir(dir)
            appendLine("${if (ok) "OK" else "NO"} ${dir.absolutePath}")
        }
    }

    private fun ensureWritableDir(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory) return false
        val probe = File(dir, ".qdre_write_probe_${System.currentTimeMillis()}")
        probe.writeText("ok")
        val ok = probe.exists() && probe.length() > 0
        probe.delete()
        ok
    }.getOrDefault(false)

    private fun writeSingle(
        context: Context,
        record: ChapterRecord,
        options: ExportOptions
    ): Result = runCatching {
        val bookName = ChapterMemoryStore.current?.bookName?.ifBlank { null }
            ?: ChapterMemoryStore.bookNameOf(record.bookId)
            .ifBlank { record.bookId }
        val body = buildString {
            appendLine("《$bookName》")
            appendLine(record.chapterName.ifBlank { "chapter-${record.chapterId}" })
            appendLine()
            appendLine(record.content.trim())
            if (options.includeFooter) {
                appendLine()
                appendLine("-- exported by QDReaderExporter / personal backup of already-readable content --")
            }
        }
        val name = buildFileName(
            bookName,
            record.chapterName.ifBlank { record.chapterId }
        )
        val file = writeText(context, name, body, options)
        Result.Success(file, 1, dir = file.parentFile ?: file)
    }.getOrElse { Result.Failure("写出失败: ${it.javaClass.simpleName}: ${it.message}") }

    private fun writeMulti(
        context: Context,
        bookName: String,
        bookId: String,
        chapters: List<ChapterRecord>,
        options: ExportOptions,
        tag: String
    ): Result = runCatching {
        val body = buildString {
            appendLine("《$bookName》")
            appendLine("bookId: $bookId")
            appendLine("exportedAt: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}")
            appendLine("chapters: ${chapters.size}")
            appendLine()
            chapters.forEachIndexed { index, chapter ->
                appendLine("============================================================")
                appendLine("#${index + 1} ${chapter.chapterName.ifBlank { chapter.chapterId }}  (id=${chapter.chapterId})")
                appendLine("============================================================")
                appendLine()
                appendLine(chapter.content.trim())
                appendLine()
            }
            if (options.includeFooter) {
                appendLine("-- exported by QDReaderExporter / personal backup of already-readable content --")
            }
        }
        val name = buildFileName(bookName, tag)
        val file = writeText(context, name, body, options)
        Result.Success(file, chapters.size, dir = file.parentFile ?: file)
    }.getOrElse { Result.Failure("写出失败: ${it.javaClass.simpleName}: ${it.message}") }

    private fun buildFileName(bookName: String, chapterPart: String): String {
        val b = FileNameSanitizer.sanitize(bookName)
        val c = FileNameSanitizer.sanitize(chapterPart)
        return "${b}_${c}_${timeFmt.format(Date())}.txt"
    }

    private fun writeText(
        context: Context,
        fileName: String,
        body: String,
        options: ExportOptions
    ): File {
        val bytes = if (options.withBom) {
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(utf8)
        } else {
            body.toByteArray(utf8)
        }
        val errors = mutableListOf<String>()
        for (dir in candidateDirs(context)) {
            val written = runCatching {
                if (!ensureWritableDir(dir)) error("not writable")
                val file = File(dir, fileName)
                file.outputStream().use { it.write(bytes); it.flush() }
                if (!file.exists() || file.length() <= 0L) {
                    error("file missing or empty after write (${file.length()})")
                }
                // sidecar pointer so user can always find last export
                runCatching {
                    File(dir, "LAST_EXPORT.txt").writeText(
                        "file=${file.absolutePath}\n" +
                            "size=${file.length()}\n" +
                            "time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
                    )
                }
                YLog.info("TxtExporter: wrote ${file.absolutePath} size=${file.length()}")
                file
            }.getOrElse {
                errors += "${dir.absolutePath}: ${it.message}"
                null
            }
            if (written != null) return written
        }
        error("all export paths failed:\n" + errors.joinToString("\n"))
    }
}
