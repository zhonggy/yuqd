package com.github.qdreaderexporter.export

import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.hook.ChapterCacheHooker
import com.github.qdreaderexporter.util.HostContext
import com.github.qdreaderexporter.util.HostInstanceRegistry
import com.github.qdreaderexporter.util.ReflectExt.allMethods
import com.github.qdreaderexporter.util.ReflectExt.safeInvoke
import com.highcapable.yukihookapi.hook.log.YLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Triggers the host's own local/cache chapter load APIs for already-downloaded chapter IDs.
 *
 * Hard rules:
 * - Never reimplements VIP/AES crypto
 * - Never invokes methods whose names suggest purchase/buy/pay
 * - Only uses chapter IDs already observed as downloaded/cached by the host
 * - Captured plaintext still comes solely from existing post-decrypt hooks
 */
object BatchDownloadedLoader {

    data class Progress(
        val bookId: String,
        val totalIds: Int,
        val attempted: Int,
        val capturedBefore: Int,
        val capturedNow: Int,
        val skippedAlready: Int,
        val buySignals: Int,
        val invokeHits: Int,
        val message: String
    )

    data class Result(
        val bookId: String,
        val bookName: String,
        val totalIds: Int,
        val attempted: Int,
        val newlyCaptured: Int,
        val totalCaptured: Int,
        val skippedAlready: Int,
        val buySignals: Int,
        val invokeHits: Int,
        val notes: List<String>
    ) {
        fun toText(): String = buildString {
            appendLine("=== Batch load downloaded chapters ===")
            appendLine("bookId: $bookId")
            appendLine("bookName: $bookName")
            appendLine("downloadedIds: $totalIds")
            appendLine("attempted: $attempted")
            appendLine("skippedAlready: $skippedAlready")
            appendLine("newlyCaptured: $newlyCaptured")
            appendLine("totalCaptured: $totalCaptured")
            appendLine("buySignals: $buySignals")
            appendLine("invokeHits: $invokeHits")
            notes.forEach { appendLine(it) }
            appendLine()
            appendLine("NOTE: Only host-decrypted plaintext is kept. Encrypted offline files are not decrypted.")
        }
    }

    private val running = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private val buySignals = AtomicInteger(0)

    fun isRunning(): Boolean = running.get()

    fun cancel() {
        cancelFlag.set(true)
    }

    fun noteBuySignal(reason: String) {
        buySignals.incrementAndGet()
        YLog.warn("BatchDownloadedLoader: buy/skip signal — $reason")
    }

    /**
     * Blocking; call from background thread.
     */
    fun runForCurrentBook(
        maxChapters: Int = 300,
        perChapterWaitMs: Long = 450L,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        if (!running.compareAndSet(false, true)) {
            return Result(
                bookId = ChapterMemoryStore.current?.bookId.orEmpty(),
                bookName = ChapterMemoryStore.current?.bookName.orEmpty(),
                totalIds = 0,
                attempted = 0,
                newlyCaptured = 0,
                totalCaptured = ChapterMemoryStore.count(),
                skippedAlready = 0,
                buySignals = 0,
                invokeHits = 0,
                notes = listOf("already running")
            )
        }
        cancelFlag.set(false)
        buySignals.set(0)
        val notes = mutableListOf<String>()
        try {
            val session = ChapterMemoryStore.current
            val bookId = session?.bookId?.takeIf { it.isNotBlank() }
                ?: return emptyResult(notes + "no current book session — open a reader first")
            val bookName = session.bookName.ifBlank { bookId }

            // Best-effort refresh of downloaded ids via host APIs
            val refreshed = ChapterCacheHooker.refreshDownloadedIds(bookId)
            if (refreshed > 0) notes += "refreshed downloaded ids via host API: $refreshed"

            val ids = ChapterCacheHooker.idsFor(bookId)
            if (ids.isEmpty()) {
                return Result(
                    bookId = bookId,
                    bookName = bookName,
                    totalIds = 0,
                    attempted = 0,
                    newlyCaptured = 0,
                    totalCaptured = ChapterMemoryStore.count(bookId),
                    skippedAlready = 0,
                    buySignals = buySignals.get(),
                    invokeHits = 0,
                    notes = notes + listOf(
                        "no downloaded chapter ids observed yet",
                        "open chapter list / download manager once, or flip a few chapters, then retry"
                    )
                )
            }

            val already = ChapterMemoryStore.allFor(bookId).map { it.chapterId }.toSet()
            val pending = ids.filterNot { it in already }.take(maxChapters)
            val skippedAlready = ids.size - pending.size
            val capturedBefore = ChapterMemoryStore.count(bookId)
            notes += "instances: ${HostInstanceRegistry.candidates().joinToString { it.javaClass.simpleName }}"
            notes += HostInstanceRegistry.statusText().trim().lines()

            var attempted = 0
            var invokeHits = 0
            for (chapterId in pending) {
                if (cancelFlag.get()) {
                    notes += "cancelled by user"
                    break
                }
                if (HostContext.activity()?.isFinishing == true) {
                    notes += "reader activity finishing — stop"
                    break
                }
                // Skip if captured meanwhile
                if (ChapterMemoryStore.get(bookId, chapterId) != null) continue

                val hit = tryInvokeHostLoad(bookId, chapterId, notes)
                if (hit) invokeHits++
                attempted++

                // Wait for host async decrypt/load → existing content hooks fill store
                val deadline = System.currentTimeMillis() + perChapterWaitMs
                while (System.currentTimeMillis() < deadline) {
                    if (ChapterMemoryStore.get(bookId, chapterId) != null) break
                    if (cancelFlag.get()) break
                    Thread.sleep(40L)
                }

                onProgress?.invoke(
                    Progress(
                        bookId = bookId,
                        totalIds = ids.size,
                        attempted = attempted,
                        capturedBefore = capturedBefore,
                        capturedNow = ChapterMemoryStore.count(bookId),
                        skippedAlready = skippedAlready,
                        buySignals = buySignals.get(),
                        invokeHits = invokeHits,
                        message = "chapter $chapterId"
                    )
                )
            }

            val totalCaptured = ChapterMemoryStore.count(bookId)
            val newly = (totalCaptured - capturedBefore).coerceAtLeast(0)
            notes += "done: attempted=$attempted newly=$newly total=$totalCaptured"
            return Result(
                bookId = bookId,
                bookName = bookName,
                totalIds = ids.size,
                attempted = attempted,
                newlyCaptured = newly,
                totalCaptured = totalCaptured,
                skippedAlready = skippedAlready,
                buySignals = buySignals.get(),
                invokeHits = invokeHits,
                notes = notes
            )
        } catch (t: Throwable) {
            YLog.error("BatchDownloadedLoader failed: ${t.message}", t)
            return emptyResult(notes + "error: ${t.message}")
        } finally {
            running.set(false)
            cancelFlag.set(false)
        }
    }

    private fun emptyResult(notes: List<String>): Result {
        val bookId = ChapterMemoryStore.current?.bookId.orEmpty()
        return Result(
            bookId = bookId,
            bookName = ChapterMemoryStore.current?.bookName.orEmpty(),
            totalIds = 0,
            attempted = 0,
            newlyCaptured = 0,
            totalCaptured = ChapterMemoryStore.count(bookId.ifBlank { null }),
            skippedAlready = 0,
            buySignals = buySignals.get(),
            invokeHits = 0,
            notes = notes
        )
    }

    private fun tryInvokeHostLoad(bookId: String, chapterId: String, notes: MutableList<String>): Boolean {
        val targets = HostInstanceRegistry.candidates()
        if (targets.isEmpty()) {
            if (notes.none { it.startsWith("no host instances") }) {
                notes += "no host instances yet — open/flip a chapter once so engine objects are observed"
            }
            return false
        }
        var any = false
        for (target in targets) {
            if (invokeLoadOn(target, bookId, chapterId)) {
                any = true
            }
        }
        return any
    }

    private fun invokeLoadOn(target: Any, bookId: String, chapterId: String): Boolean {
        val chapterLong = chapterId.toLongOrNull()
        val bookLong = bookId.toLongOrNull()
        val methods = target.javaClass.allMethods().filter { m ->
            val n = m.name
            if (isForbiddenMethod(n)) return@filter false
            isLoadLikeMethod(n) && m.parameterTypes.size <= 4
        }.sortedBy { scoreMethod(it.name, it.parameterTypes.size) }

        var hit = false
        for (m in methods.take(12)) {
            val argsList = buildArgCandidates(m.parameterTypes, bookLong, chapterLong, bookId, chapterId)
            for (args in argsList) {
                val r = m.safeInvoke(target, *args)
                // void/null still counts as attempted invoke
                hit = true
                YLog.info(
                    "BatchDownloadedLoader: invoke ${target.javaClass.simpleName}#${m.name}" +
                        "(${m.parameterTypes.joinToString { it.simpleName }}) ch=$chapterId result=${r?.javaClass?.simpleName ?: "null/void"}"
                )
                // Prefer first successful signature shape per target
                if (r != null || m.returnType == Void.TYPE) return true
            }
        }
        return hit
    }

    private fun isForbiddenMethod(name: String): Boolean {
        val n = name.lowercase()
        return listOf(
            "buy", "purchase", "pay", "vip", "order", "recharge",
            "comment", "share", "tts", "audio", "ad", "login"
        ).any { n.contains(it) }
    }

    private fun isLoadLikeMethod(name: String): Boolean {
        val n = name.lowercase()
        return listOf(
            "loadchaptercontentwithid",
            "loadchaptercontent",
            "getchaptercontentonly",
            "getchaptercontent",
            "loadchapterdata",
            "switchchapter",
            "preloadchapter",
            "reloadchaptercontent",
            "openchapter",
            "gotochapter",
            "jumpchapter",
            "loadchapter"
        ).any { n == it || n.startsWith(it) }
    }

    private fun scoreMethod(name: String, paramCount: Int): Int {
        val n = name.lowercase()
        var score = 100
        when {
            n.contains("withid") -> score -= 40
            n == "switchchapter" -> score -= 35
            n == "getchaptercontentonly" -> score -= 30
            n == "loadchaptercontent" -> score -= 25
            n == "getchaptercontent" -> score -= 20
            n == "preloadchapter" -> score -= 15
            n == "loadchapterdata" -> score -= 10
        }
        // Prefer fewer params
        score += paramCount
        // Prefer non-network sounding
        if (n.contains("net") || n.contains("download")) score += 20
        return score
    }

    private fun buildArgCandidates(
        types: Array<Class<*>>,
        bookLong: Long?,
        chapterLong: Long?,
        bookId: String,
        chapterId: String
    ): List<Array<Any?>> {
        if (types.isEmpty()) return listOf(emptyArray())

        fun coerce(t: Class<*>, preferChapter: Boolean): Any? {
            return when {
                t == java.lang.Long.TYPE || t == java.lang.Long::class.java -> {
                    if (preferChapter) chapterLong else bookLong
                }
                t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java -> {
                    val v = if (preferChapter) chapterLong else bookLong
                    v?.toInt()
                }
                t == String::class.java -> if (preferChapter) chapterId else bookId
                t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java -> false
                t == java.lang.Float.TYPE || t == java.lang.Float::class.java -> 0f
                t == java.lang.Double.TYPE || t == java.lang.Double::class.java -> 0.0
                else -> null // callbacks / complex — pass null
            }
        }

        // Common shapes:
        // (chapterId)
        // (chapterId, boolean)
        // (bookId, chapterId)
        // (bookId, chapterId, boolean/callback)
        return when (types.size) {
            1 -> listOfNotNull(
                arrayOf(coerce(types[0], preferChapter = true)),
                arrayOf(coerce(types[0], preferChapter = false))
            ).filter { args -> args.all { it != null || !types[args.indexOf(it)].isPrimitive } }
                .ifEmpty {
                    // allow null for non-primitive only
                    if (!types[0].isPrimitive) listOf(arrayOf(null)) else emptyList()
                }
            2 -> listOf(
                arrayOf(coerce(types[0], true), coerce(types[1], false)),
                arrayOf(coerce(types[0], false), coerce(types[1], true)),
                arrayOf(coerce(types[0], true), false),
                arrayOf(coerce(types[0], true), null)
            ).map { args ->
                Array(types.size) { i ->
                    val a = args.getOrNull(i)
                    if (a == null && types[i].isPrimitive) {
                        // invalid primitive null — mark by using sentinel skip later
                        a
                    } else a
                }
            }.filter { args ->
                types.indices.all { i -> args[i] != null || !types[i].isPrimitive }
            }
            else -> {
                val args = Array<Any?>(types.size) { i ->
                    // first long/string ~ book, second ~ chapter, rest false/null
                    when {
                        i == 0 -> coerce(types[i], preferChapter = false) ?: coerce(types[i], true)
                        i == 1 -> coerce(types[i], preferChapter = true) ?: coerce(types[i], false)
                        types[i] == java.lang.Boolean.TYPE || types[i] == java.lang.Boolean::class.java -> false
                        else -> null
                    }
                }
                if (types.indices.all { i -> args[i] != null || !types[i].isPrimitive }) {
                    listOf(args)
                } else emptyList()
            }
        }
    }
}
