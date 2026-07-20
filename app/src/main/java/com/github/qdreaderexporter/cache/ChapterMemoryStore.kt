package com.github.qdreaderexporter.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local chapter cache. Only holds content already rendered/loaded by the host.
 */
object ChapterMemoryStore {

    private const val MAX_CHAPTERS_PER_BOOK = 500

    private val books =
        ConcurrentHashMap<String, ConcurrentHashMap<String, ChapterRecord>>()
    private val sessionRef = AtomicReference<BookSession?>(null)

    var current: BookSession?
        get() = sessionRef.get()
        set(value) = sessionRef.set(value)

    fun updateSession(transform: (BookSession?) -> BookSession?) {
        while (true) {
            val prev = sessionRef.get()
            val next = transform(prev) ?: return
            if (sessionRef.compareAndSet(prev, next)) return
        }
    }

    fun put(record: ChapterRecord) {
        if (!record.hasContent || record.bookId.isBlank() || record.chapterId.isBlank()) return
        val map = books.getOrPut(record.bookId) { ConcurrentHashMap() }
        val existing = map[record.chapterId]
        // Prefer longer content (more complete capture)
        if (existing != null && existing.content.length >= record.content.length) return
        map[record.chapterId] = record
        // Soft cap
        if (map.size > MAX_CHAPTERS_PER_BOOK) {
            val oldest = map.values.minByOrNull { it.capturedAt } ?: return
            map.remove(oldest.chapterId)
        }
        updateSession { session ->
            val base = session ?: BookSession(bookId = record.bookId)
            base.copy(
                bookId = record.bookId.ifBlank { base.bookId },
                currentChapterId = record.chapterId,
                currentChapterName = record.chapterName.ifBlank { base.currentChapterName }
            )
        }
    }

    fun get(bookId: String, chapterId: String): ChapterRecord? =
        books[bookId]?.get(chapterId)

    fun currentChapter(): ChapterRecord? {
        val session = current ?: return null
        if (session.bookId.isBlank() || session.currentChapterId.isBlank()) return null
        return get(session.bookId, session.currentChapterId)
    }

    fun allFor(bookId: String): List<ChapterRecord> {
        val map = books[bookId] ?: return emptyList()
        return map.values.sortedWith(
            compareBy<ChapterRecord> { it.indexHint ?: Int.MAX_VALUE }
                .thenBy { it.chapterId }
        )
    }

    fun count(bookId: String? = current?.bookId): Int {
        val id = bookId ?: return 0
        return books[id]?.size ?: 0
    }

    fun totalChapterCount(): Int = books.values.sumOf { it.size }

    fun bookIds(): List<String> = books.keys.sorted()

    fun bookNameOf(bookId: String): String {
        val session = current
        if (session?.bookId == bookId && session.bookName.isNotBlank()) return session.bookName
        return bookId
    }

    fun summaries(): List<BookSummary> {
        val session = current
        return books.map { (bookId, map) ->
            val name = if (session?.bookId == bookId && session.bookName.isNotBlank()) {
                session.bookName
            } else {
                bookId
            }
            BookSummary(
                bookId = bookId,
                bookName = name,
                chapterCount = map.size,
                totalChars = map.values.sumOf { it.content.length }
            )
        }.sortedByDescending { it.chapterCount }
    }

    fun allChapters(): List<ChapterRecord> =
        books.values.flatMap { it.values }.sortedWith(
            compareBy<ChapterRecord> { it.bookId }
                .thenBy { it.indexHint ?: Int.MAX_VALUE }
                .thenBy { it.chapterId }
        )

    fun clear(bookId: String? = null) {
        if (bookId == null) {
            books.clear()
        } else {
            books.remove(bookId)
        }
    }

    fun statusText(): String {
        val session = current
        val summaryLines = summaries().joinToString("\n") {
            "  · ${it.bookName} (${it.bookId}): ${it.chapterCount}章 / ${it.totalChars}字"
        }.ifBlank { "  (empty)" }
        return buildString {
            appendLine("current bookId: ${session?.bookId ?: "-"}")
            appendLine("current bookName: ${session?.bookName?.ifBlank { "-" } ?: "-"}")
            appendLine("current chapterId: ${session?.currentChapterId?.ifBlank { "-" } ?: "-"}")
            appendLine("current chapterName: ${session?.currentChapterName?.ifBlank { "-" } ?: "-"}")
            appendLine("current cached chapters: ${count(session?.bookId)}")
            appendLine("books in memory: ${books.size}")
            appendLine("total chapters: ${totalChapterCount()}")
            appendLine("books:")
            append(summaryLines)
        }
    }
}
