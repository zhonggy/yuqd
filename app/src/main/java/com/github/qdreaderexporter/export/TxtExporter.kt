package com.github.qdreaderexporter.export

import android.content.Context
import android.os.Environment
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.cache.ChapterRecord
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
            val extraFiles: List<File> = emptyList()
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    /** Export the currently focused chapter only. */
    fun exportCurrentChapter(context: Context, options: ExportOptions = ExportOptions()): Result {
        val record = ChapterMemoryStore.currentChapter()
            ?: return Result.Failure("当前章节尚未捕获，请先翻页/打开可读章节")
        return writeSingle(context, record, options)
    }

    /** Export all in-memory chapters of the current book. */
    fun exportCurrentBook(context: Context, options: ExportOptions = ExportOptions()): Result {
        val session = ChapterMemoryStore.current
            ?: return Result.Failure("尚未识别当前书籍，请先打开阅读页")
        val list = ChapterMemoryStore.allFor(session.bookId).filter { it.hasContent }
        if (list.isEmpty()) {
            return Result.Failure("当前书籍内存缓存为空。请先在阅读页浏览已下载/可读章节后再导出")
        }
        val bookName = session.bookName.ifBlank { session.bookId }
        return writeMulti(context, bookName, session.bookId, list, options, tag = "book")
    }

    /** Backward-compatible alias. */
    fun exportCurrent(context: Context, options: ExportOptions = ExportOptions()): Result =
        exportCurrentChapter(context, options)

    /** Backward-compatible alias for current-book cached chapters. */
    fun exportCached(context: Context, options: ExportOptions = ExportOptions()): Result =
        exportCurrentBook(context, options)

    /**
     * Export every book currently held in process memory.
     * One TXT per book under /sdcard/Documents/QDReaderExporter/.
     */
    fun exportAllCachedBooks(context: Context, options: ExportOptions = ExportOptions()): Result {
        val summaries = ChapterMemoryStore.summaries()
        if (summaries.isEmpty()) {
            return Result.Failure(
                "内存中没有任何已捕获章节。\n" +
                    "请打开已下载书籍并翻页，或先运行「尝试合并明文缓存」。\n" +
                    "说明：加密离线章不会被解密导出。"
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
            extraFiles = files.drop(1)
        )
    }

    /**
     * Preferred: `/sdcard/Documents/QDReaderExporter`
     * (= Environment.DIRECTORY_DOCUMENTS public dir).
     * Falls back to host app-specific Documents only if public dir is not writable.
     */
    fun exportDir(context: Context): File {
        val publicDocs = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val preferred = File(publicDocs, "QDReaderExporter")
        if (ensureWritableDir(preferred)) return preferred

        // Some devices expose /sdcard differently from getExternalStoragePublicDirectory
        val alt = File("/sdcard/Documents/QDReaderExporter")
        if (alt.absolutePath != preferred.absolutePath && ensureWritableDir(alt)) {
            return alt
        }

        val fallback = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "QDReaderExporter"
        )
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun ensureWritableDir(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory || !dir.canWrite()) return false
        val probe = File(dir, ".qdre_write_probe")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    private fun writeSingle(
        context: Context,
        record: ChapterRecord,
        options: ExportOptions
    ): Result = runCatching {
        val bookName = ChapterMemoryStore.current?.bookName?.ifBlank { null }
            ?: record.bookId
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
        Result.Success(file, 1)
    }.getOrElse { Result.Failure(it.message ?: "export failed") }

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
        Result.Success(file, chapters.size)
    }.getOrElse { Result.Failure(it.message ?: "export failed") }

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
        val dir = exportDir(context)
        val file = File(dir, fileName)
        val bytes = if (options.withBom) {
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(utf8)
        } else {
            body.toByteArray(utf8)
        }
        file.writeBytes(bytes)
        return file
    }
}
