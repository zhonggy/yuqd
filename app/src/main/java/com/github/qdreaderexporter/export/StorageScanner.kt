package com.github.qdreaderexporter.export

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans host app storage for chapter-like / plaintext-looking files.
 * Does NOT attempt VIP/AES decrypt — encrypted offline chapters are reported only.
 */
object StorageScanner {

    data class Hit(
        val path: String,
        val size: Long,
        val kind: Kind,
        val note: String = ""
    )

    enum class Kind {
        PLAINTEXT_CANDIDATE,
        TEXT_LIKE,
        BINARY_OR_ENCRYPTED,
        DATABASE,
        DIRECTORY_HINT
    }

    data class ScanReport(
        val roots: List<String>,
        val hits: List<Hit>,
        val scannedFiles: Int,
        val elapsedMs: Long
    ) {
        fun toText(): String = buildString {
            appendLine("=== Storage scan ===")
            appendLine("scannedFiles: $scannedFiles")
            appendLine("hits: ${hits.size}")
            appendLine("elapsedMs: $elapsedMs")
            appendLine("roots:")
            roots.forEach { appendLine("  $it") }
            appendLine()
            if (hits.isEmpty()) {
                appendLine("(no interesting files found under scanned roots)")
            } else {
                hits.forEach { h ->
                    appendLine("[${h.kind}] ${h.size}B  ${h.path}")
                    if (h.note.isNotBlank()) appendLine("    ${h.note}")
                }
            }
            appendLine()
            appendLine("Note: encrypted offline chapter files are NOT decrypted.")
        }
    }

    private val interestingName = Regex(
        pattern = """(?i)(chapter|content|book|qidian|qdreader|offline|cache|txt|db)""",
    )

    private val textExt = setOf("txt", "text", "json", "xml", "html", "htm", "log", "csv")
    private val dbExt = setOf("db", "sqlite", "sqlite3")
    private const val MAX_FILES = 4000
    private const val MAX_DEPTH = 6
    private const val MAX_PROBE_BYTES = 4096
    private const val MAX_HITS = 200

    fun scan(context: Context): ScanReport {
        val started = System.currentTimeMillis()
        val roots = collectRoots(context)
        val hits = mutableListOf<Hit>()
        val scanned = AtomicInteger(0)

        for (root in roots) {
            walk(root, depth = 0, scanned = scanned, hits = hits)
            if (scanned.get() >= MAX_FILES || hits.size >= MAX_HITS) break
        }

        return ScanReport(
            roots = roots.map { it.absolutePath },
            hits = hits,
            scannedFiles = scanned.get(),
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    fun writeReport(context: Context, report: ScanReport): File {
        val dir = TxtExporter.exportDir(context)
        val name = "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log"
        val file = File(dir, name)
        file.writeText(report.toText(), Charsets.UTF_8)
        return file
    }

    /**
     * Import plaintext-looking files into memory store when content is readable Chinese/ASCII prose.
     * Returns number of chapters merged.
     */
    fun mergePlaintextCache(
        context: Context,
        bookIdFallback: String?
    ): MergeResult {
        val report = scan(context)
        var merged = 0
        val notes = mutableListOf<String>()
        val bookId = bookIdFallback?.takeIf { it.isNotBlank() } ?: "scanned"
        for (hit in report.hits) {
            if (hit.kind != Kind.PLAINTEXT_CANDIDATE && hit.kind != Kind.TEXT_LIKE) continue
            val file = File(hit.path)
            if (!file.isFile || file.length() > 2_000_000L) continue
            val text = runCatching {
                file.readText(Charset.forName("UTF-8"))
            }.getOrNull() ?: continue
            val cleaned = text.trim()
            if (!looksLikeChapterProse(cleaned)) {
                notes += "skip(not prose): ${file.name}"
                continue
            }
            val chapterId = file.nameWithoutExtension.take(64).ifBlank { file.name }
            val chapterName = file.nameWithoutExtension
            com.github.qdreaderexporter.cache.ChapterMemoryStore.put(
                com.github.qdreaderexporter.cache.ChapterRecord(
                    bookId = bookId,
                    chapterId = "file:$chapterId",
                    chapterName = chapterName,
                    content = cleaned,
                    source = com.github.qdreaderexporter.cache.CaptureSource.CACHE
                )
            )
            merged++
            notes += "merged: ${file.name} (${cleaned.length} chars)"
        }
        return MergeResult(
            scannedHits = report.hits.size,
            mergedChapters = merged,
            notes = notes,
            report = report
        )
    }

    data class MergeResult(
        val scannedHits: Int,
        val mergedChapters: Int,
        val notes: List<String>,
        val report: ScanReport
    ) {
        fun toText(): String = buildString {
            appendLine("=== Merge plaintext cache ===")
            appendLine("scannedHits: $scannedHits")
            appendLine("mergedChapters: $mergedChapters")
            notes.take(80).forEach { appendLine(it) }
            if (notes.size > 80) appendLine("... ${notes.size - 80} more")
            appendLine()
            append(report.toText())
        }
    }

    private fun collectRoots(context: Context): List<File> {
        val list = mutableListOf<File>()
        fun add(f: File?) {
            if (f != null && f.exists()) list += f
        }
        add(context.filesDir)
        add(context.cacheDir)
        add(context.getDatabasePath("x").parentFile)
        add(context.getExternalFilesDir(null))
        add(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS))
        add(context.externalCacheDir)
        // Common sibling dirs under Android/data/<pkg>/
        context.getExternalFilesDir(null)?.parentFile?.let { add(it) }
        // Host internal may not be readable from same process if multi-user, but filesDir works in-process.
        return list.distinctBy { it.absolutePath }
    }

    private fun walk(
        dir: File,
        depth: Int,
        scanned: AtomicInteger,
        hits: MutableList<Hit>
    ) {
        if (depth > MAX_DEPTH || scanned.get() >= MAX_FILES || hits.size >= MAX_HITS) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (scanned.get() >= MAX_FILES || hits.size >= MAX_HITS) return
            if (child.isDirectory) {
                if (interestingName.containsMatchIn(child.name)) {
                    hits += Hit(child.absolutePath, 0, Kind.DIRECTORY_HINT, "dir name match")
                }
                walk(child, depth + 1, scanned, hits)
            } else if (child.isFile) {
                scanned.incrementAndGet()
                classifyFile(child)?.let { hits += it }
            }
        }
    }

    private fun classifyFile(file: File): Hit? {
        val name = file.name
        val ext = file.extension.lowercase(Locale.US)
        val size = file.length()
        if (size <= 0L) return null

        if (ext in dbExt || name.endsWith("-journal") || name.endsWith("-wal")) {
            if (interestingName.containsMatchIn(name) || size > 10_000) {
                return Hit(file.absolutePath, size, Kind.DATABASE)
            }
            return null
        }

        val nameInteresting = interestingName.containsMatchIn(name)
        if (!nameInteresting && ext !in textExt && size > 50_000) {
            // large unknown binary under host storage — ignore unless name matches
            return null
        }
        if (!nameInteresting && ext !in textExt && size < 64) return null

        val probe = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(MAX_PROBE_BYTES)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrNull() ?: return null

        val printable = probe.count { b ->
            val c = b.toInt() and 0xFF
            c == 0x09 || c == 0x0A || c == 0x0D || c in 0x20..0x7E || c >= 0x80
        }
        val ratio = if (probe.isEmpty()) 0f else printable.toFloat() / probe.size
        val hasNull = probe.any { it == 0.toByte() }

        return when {
            ext in textExt && ratio > 0.85f && !hasNull -> {
                val sample = runCatching { String(probe, Charsets.UTF_8) }.getOrDefault("")
                val kind = if (looksLikeChapterProse(sample)) Kind.PLAINTEXT_CANDIDATE else Kind.TEXT_LIKE
                Hit(file.absolutePath, size, kind, "printable=${"%.2f".format(ratio)}")
            }
            nameInteresting && ratio > 0.90f && !hasNull && size < 1_500_000 -> {
                val sample = runCatching { String(probe, Charsets.UTF_8) }.getOrDefault("")
                val kind = if (looksLikeChapterProse(sample)) Kind.PLAINTEXT_CANDIDATE else Kind.TEXT_LIKE
                Hit(file.absolutePath, size, kind, "name+printable")
            }
            nameInteresting && (hasNull || ratio < 0.6f) -> {
                Hit(
                    file.absolutePath,
                    size,
                    Kind.BINARY_OR_ENCRYPTED,
                    "likely encrypted/binary printable=${"%.2f".format(ratio)}"
                )
            }
            else -> null
        }
    }

    fun looksLikeChapterProse(text: String): Boolean {
        if (text.length < 80) return false
        // Reject pure JSON/XML logs
        val t = text.trimStart()
        if (t.startsWith("{") || t.startsWith("[") || t.startsWith("<")) {
            // still allow HTML-ish chapter dumps if long enough Chinese
            if (countCjk(text) < 40) return false
        }
        val cjk = countCjk(text)
        val letters = text.count { it.isLetter() }
        return cjk >= 40 || (letters >= 120 && text.contains('\n'))
    }

    private fun countCjk(text: String): Int =
        text.count { ch -> ch in '一'..'鿿' }
}
