package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClassOrNull
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * Optional: observe host "downloaded/cached chapter ids" APIs.
 * v1 does NOT force-load those chapters; it only logs / records ids for diagnostics.
 * Active load can be added after jadx confirms a disk-cache-only path.
 */
object ChapterCacheHooker : YukiBaseHooker() {

    /** bookId -> known cached chapter ids (may be incomplete). */
    val downloadedIds: MutableMap<String, Set<String>> = LinkedHashMap()

    override fun onHook() {
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
                            val result = result ?: return@runCatching
                            val ids = normalizeIdSet(result)
                            if (ids.isEmpty()) return@runCatching
                            val bookId = ChapterMemoryStore.current?.bookId
                            if (!bookId.isNullOrBlank()) {
                                downloadedIds[bookId] = ids
                            }
                            YLog.info(
                                "downloaded/cached chapter ids observed: count=${ids.size} book=$bookId"
                            )
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
                    "Export will use passive memory cache only."
            )
        }
    }

    private fun normalizeIdSet(result: Any): Set<String> {
        return when (result) {
            is Collection<*> -> result.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.toSet()
            is LongArray -> result.map { it.toString() }.toSet()
            is IntArray -> result.map { it.toString() }.toSet()
            is Array<*> -> result.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.toSet()
            else -> emptySet()
        }
    }
}
