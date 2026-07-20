package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.util.HostInstanceRegistry
import com.github.qdreaderexporter.util.ReflectExt.allMethods
import com.github.qdreaderexporter.util.ReflectExt.safeInvoke
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClassOrNull
import com.highcapable.yukihookapi.hook.log.YLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes host "downloaded/cached chapter ids" APIs.
 * Batch loading may call [refreshDownloadedIds] to re-query, then
 * [com.github.qdreaderexporter.export.BatchDownloadedLoader] asks the host to load those chapters.
 */
object ChapterCacheHooker : YukiBaseHooker() {

    /** bookId -> known cached/downloaded chapter ids (may be incomplete). */
    private val downloadedIdsMap = ConcurrentHashMap<String, MutableSet<String>>()

    /** Snapshot for diagnostics / UI. */
    val downloadedIds: Map<String, Set<String>>
        get() = downloadedIdsMap.mapValues { it.value.toSet() }

    fun idsFor(bookId: String): List<String> =
        downloadedIdsMap[bookId]?.sortedWith(compareBy { it.toLongOrNull() ?: Long.MAX_VALUE })
            ?: emptyList()

    fun recordIds(bookId: String?, ids: Collection<String>) {
        if (bookId.isNullOrBlank() || ids.isEmpty()) return
        val set = downloadedIdsMap.getOrPut(bookId) { ConcurrentHashMap.newKeySet() }
        set.addAll(ids.filter { it.isNotBlank() && it != "0" })
        YLog.info("ChapterCacheHooker: book=$bookId downloadedIds=${set.size}")
    }

    override fun onHook() {
        hookGetDownloadedChapterIds()
        hookDownloadChapterListMethods()
        hookBookChapterListCompanionSuspends()
        YLog.info("ChapterCacheHooker: installed")
    }

    private fun hookGetDownloadedChapterIds() {
        val candidates = arrayOf(
            HookTargets.CLS_BOOK_CHAPTER_LIST,
            HookTargets.CLS_BOOK_CHAPTER_LIST_COMPANION,
            "com.qidian.QDReader.bll.manager.BookChapterList",
            HookTargets.CLS_CHAPTER_PROVIDER,
            HookTargets.CLS_READ_BOOK
        )
        var hooked = false
        for (owner in candidates) {
            val clazz = owner.toClassOrNull(appClassLoader) ?: continue
            runCatching {
                clazz.resolve().method {
                    name = HookTargets.M_GET_DOWNLOADED_CHAPTER_IDS
                }.hookAll {
                    after {
                        runCatching {
                            HostInstanceRegistry.rememberExtra(instance)
                            val result = result
                            val ids = normalizeIdSet(result)
                            val bookId = extractBookIdFromArgs(args)
                                ?: ChapterMemoryStore.current?.bookId
                            if (ids.isNotEmpty()) {
                                recordIds(bookId, ids)
                            } else if (result != null) {
                                // Kotlin Flow / deferred — keep instance for refresh attempts
                                YLog.info(
                                    "ChapterCacheHooker: getDownloadedChapterIds returned " +
                                        result.javaClass.name + " (not a direct collection)"
                                )
                                HostInstanceRegistry.rememberExtra(result)
                            }
                        }
                    }
                }
                hooked = true
                YLog.info("ChapterCacheHooker: hooked $owner#${HookTargets.M_GET_DOWNLOADED_CHAPTER_IDS}")
            }.onFailure {
                // expected when method absent
            }
        }
        if (!hooked) {
            YLog.warn(
                "ChapterCacheHooker: getDownloadedChapterIds not found. " +
                    "Batch load needs ids from other host list APIs or prior observation."
            )
        }
    }

    private fun hookDownloadChapterListMethods() {
        val owners = arrayOf(
            HookTargets.CLS_BOOK_CHAPTER_LIST,
            HookTargets.CLS_BOOK_CHAPTER_LIST_COMPANION,
            HookTargets.CLS_CHAPTER_PROVIDER,
            HookTargets.CLS_READ_BOOK,
            "com.qidian.QDReader.ui.view.QDReaderDirectoryViewV2",
            "com.qidian.QDReader.ui.modules.listening.detail.view.ListeningReaderDirectoryView"
        )
        val names = arrayOf(
            "getDownloadChapters",
            "getDownloadedChapterIds",
            "getDownloadChapterList",
            "allDownloadChapterList"
        )
        for (owner in owners) {
            val clazz = owner.toClassOrNull(appClassLoader) ?: continue
            for (methodName in names) {
                runCatching {
                    clazz.resolve().method { name = methodName }.hookAll {
                        after {
                            runCatching {
                                HostInstanceRegistry.rememberExtra(instance)
                                val ids = normalizeIdSet(result)
                                if (ids.isNotEmpty()) {
                                    recordIds(
                                        extractBookIdFromArgs(args)
                                            ?: ChapterMemoryStore.current?.bookId,
                                        ids
                                    )
                                }
                            }
                        }
                    }
                    YLog.info("ChapterCacheHooker: hooked $owner#$methodName")
                }
            }
        }
    }

    /**
     * Kotlin suspend/Flow continuations for getDownloadedChapterIds often land in
     * BookChapterList$Companion$getDownloadedChapterIds$2 / $3 — hook invoke/invokeSuspend.
     */
    private fun hookBookChapterListCompanionSuspends() {
        val classNames = arrayOf(
            "com.qidian.QDReader.component.bll.BookChapterList\$Companion\$getDownloadedChapterIds\$2",
            "com.qidian.QDReader.component.bll.BookChapterList\$Companion\$getDownloadedChapterIds\$3"
        )
        for (className in classNames) {
            val clazz = className.toClassOrNull(appClassLoader) ?: continue
            for (methodName in arrayOf("invoke", "invokeSuspend")) {
                runCatching {
                    clazz.resolve().method { name = methodName }.hookAll {
                        after {
                            runCatching {
                                val ids = normalizeIdSet(result)
                                if (ids.isNotEmpty()) {
                                    recordIds(ChapterMemoryStore.current?.bookId, ids)
                                }
                            }
                        }
                    }
                    YLog.info("ChapterCacheHooker: hooked $className#$methodName")
                }.onFailure {
                    YLog.debug("ChapterCacheHooker: skip $className#$methodName — ${it.message}")
                }
            }
        }
    }

    /**
     * Best-effort active query. Returns number of ids recorded this call.
     * Safe: never touches buy/pay APIs.
     */
    fun refreshDownloadedIds(bookId: String): Int {
        if (bookId.isBlank()) return 0
        val before = downloadedIdsMap[bookId]?.size ?: 0
        val bookLong = bookId.toLongOrNull()
        val owners = listOfNotNull(
            HookTargets.CLS_BOOK_CHAPTER_LIST_COMPANION.toClassOrNull(appClassLoader),
            HookTargets.CLS_BOOK_CHAPTER_LIST.toClassOrNull(appClassLoader),
            HostInstanceRegistry.chapterProvider()?.javaClass,
            HostInstanceRegistry.readBook()?.javaClass
        )
        for (clazz in owners) {
            val methods = clazz.allMethods().filter {
                val n = it.name.lowercase()
                (n.contains("getdownloadedchapterids") || n == "getdownloadchapters") &&
                    !n.contains("buy")
            }
            val instances = buildList {
                // static / companion
                add(null)
                HostInstanceRegistry.candidates().filter { clazz.isInstance(it) }.forEach { add(it) }
                // Companion INSTANCE field
                runCatching {
                    clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull()?.let { add(it) }
                runCatching {
                    clazz.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                }.getOrNull()?.let { add(it) }
            }.distinct()

            for (m in methods) {
                for (inst in instances) {
                    if (inst == null && !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.declaringClass.simpleName != "Companion"
                    ) {
                        // try Companion object instance via outer
                        continue
                    }
                    val args: Array<Any?> = when (m.parameterTypes.size) {
                        0 -> emptyArray()
                        1 -> {
                            val t = m.parameterTypes[0]
                            val v: Any = when {
                                t == java.lang.Long.TYPE || t == java.lang.Long::class.java ->
                                    bookLong ?: continue
                                t == String::class.java -> bookId
                                t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java ->
                                    bookLong?.toInt() ?: continue
                                else -> continue
                            }
                            arrayOf(v)
                        }
                        else -> continue // skip complex signatures (continuations etc.)
                    }
                    val result = m.safeInvoke(inst, *args) ?: continue
                    val ids = normalizeIdSet(result)
                    if (ids.isNotEmpty()) {
                        recordIds(bookId, ids)
                    } else {
                        // Flow: cannot collect safely here; leave to hooks
                        YLog.debug(
                            "refreshDownloadedIds: ${clazz.simpleName}#${m.name} -> ${result.javaClass.simpleName}"
                        )
                    }
                }
            }
        }
        val after = downloadedIdsMap[bookId]?.size ?: 0
        return (after - before).coerceAtLeast(0).let { if (after > 0 && it == 0) after else it }
    }

    private fun extractBookIdFromArgs(args: Array<out Any?>): String? {
        for (a in args) {
            when (a) {
                is Long -> if (a != 0L) return a.toString()
                is Int -> if (a != 0) return a.toString()
                is String -> if (a.isNotBlank() && a.all(Char::isDigit) && a.length in 4..16) return a
                is Number -> if (a.toLong() != 0L) return a.toString()
            }
        }
        return null
    }

    fun normalizeIdSet(result: Any?): Set<String> {
        if (result == null) return emptySet()
        return when (result) {
            is Collection<*> -> result.flatMap { flattenId(it) }.toSet()
            is LongArray -> result.map { it.toString() }.toSet()
            is IntArray -> result.map { it.toString() }.toSet()
            is Array<*> -> result.flatMap { flattenId(it) }.toSet()
            is Map<*, *> -> result.keys.flatMap { flattenId(it) }.toSet().ifEmpty {
                result.values.flatMap { flattenId(it) }.toSet()
            }
            else -> {
                // Try common list fields on wrapper objects
                val fields = result.javaClass.declaredFields
                val collected = mutableSetOf<String>()
                for (f in fields) {
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(result)
                        collected += normalizeIdSet(v)
                    }
                }
                if (collected.isNotEmpty()) return collected
                // Iterable non-Collection
                if (result is Iterable<*>) {
                    return result.flatMap { flattenId(it) }.toSet()
                }
                emptySet()
            }
        }
    }

    private fun flattenId(item: Any?): List<String> {
        if (item == null) return emptyList()
        return when (item) {
            is Number -> listOf(item.toString()).filter { it != "0" }
            is String -> listOf(item).filter { it.isNotBlank() && it != "0" }
            is Collection<*> -> item.flatMap { flattenId(it) }
            else -> {
                // entity with chapterId field
                val id = runCatching {
                    val f = item.javaClass.declaredFields.firstOrNull {
                        it.name.equals("chapterId", true) ||
                            it.name.equals("mChapterId", true) ||
                            it.name.equals("id", true)
                    }
                    f?.isAccessible = true
                    f?.get(item)
                }.getOrNull()
                when (id) {
                    is Number -> listOf(id.toString())
                    is String -> listOf(id)
                    else -> emptyList()
                }
            }
        }
    }
}
