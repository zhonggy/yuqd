package com.github.qdreaderexporter.hook

import com.github.qdreaderexporter.BuildConfig
import com.github.qdreaderexporter.cache.BookSession
import com.github.qdreaderexporter.cache.CaptureSource
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.cache.ChapterRecord
import com.github.qdreaderexporter.util.ReflectExt.asStringValue
import com.github.qdreaderexporter.util.ReflectExt.extractPlainContent
import com.github.qdreaderexporter.util.ReflectExt.readLongLike
import com.github.qdreaderexporter.util.ReflectExt.readStringProperty
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.factory.toClassOrNull
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam

/**
 * Captures plaintext chapter content AFTER host-side decrypt/load finish.
 * Never reimplements VIP/AES crypto; skips empty/onBuy-like payloads.
 */
object ChapterContentHooker : YukiBaseHooker() {

    override fun onHook() {
        var hooked = 0
        for (owner in HookTargets.CONTENT_OWNER_CANDIDATES) {
            val clazz = owner.toClassOrNull(appClassLoader) ?: continue
            for (methodName in HookTargets.CONTENT_METHOD_CANDIDATES) {
                runCatching {
                    clazz.resolve().method {
                        name = methodName
                    }.hookAll {
                        after {
                            runCatching { captureFromHook(methodName, this) }
                                .onFailure { YLog.debug("capture error in $methodName: ${it.message}") }
                        }
                    }
                    hooked++
                    YLog.info("ChapterContentHooker: hooked $owner#$methodName (*)")
                }.onFailure {
                    // method may not exist on this owner — expected
                }
            }
        }

        // Also try constructor of content entity — content often assigned at construct time
        hookContentEntityConstructors()

        if (hooked == 0) {
            YLog.error(
                "ChapterContentHooker: no content methods hooked. " +
                    "Run jadx and update HookTargets.CONTENT_OWNER_CANDIDATES."
            )
        } else {
            YLog.info("ChapterContentHooker: $hooked method-group(s) registered")
        }
    }

    private fun hookContentEntityConstructors() {
        val entities = arrayOf(
            HookTargets.CLS_CHAPTER_CONTENT_ITEM,
            HookTargets.CLS_CHAPTER_DATA,
            HookTargets.CLS_CHAPTER_ITEM,
            HookTargets.CLS_CHAPTER_INFO
        )
        for (name in entities) {
            runCatching {
                val clazz = name.toClass(appClassLoader)
                clazz.declaredConstructors.forEach { ctor ->
                    runCatching {
                        ctor.hook {
                            after {
                                runCatching {
                                    captureEntity(instance, CaptureSource.CONSTRUCTOR)
                                }
                            }
                        }
                    }
                }
                YLog.info("ChapterContentHooker: watching constructors of $name")
            }.onFailure {
                YLog.debug(msg = "skip entity $name: ${it.message}")
            }
        }
    }

    private fun captureFromHook(methodName: String, param: HookParam) {
        val source = when {
            methodName.contains("Finish", ignoreCase = true) -> CaptureSource.FINISH_CALLBACK
            methodName.contains("getChapterContent", ignoreCase = true) -> CaptureSource.CACHE
            else -> CaptureSource.UNKNOWN
        }

        // Prefer result, then args, then this
        val candidates = buildList {
            param.result?.let { add(it) }
            param.args.filterNotNull().let { addAll(it) }
            param.instance.let { add(it) }
        }

        for (candidate in candidates) {
            if (captureEntity(candidate, source)) return
            // Sometimes result is List/array of chapters
            when (candidate) {
                is List<*> -> candidate.filterNotNull().forEach { captureEntity(it, source) }
                is Array<*> -> candidate.filterNotNull().forEach { captureEntity(it, source) }
            }
        }

        // Try extract ids from args even if content is on instance fields
        val bookId = candidates.firstNotNullOfOrNull {
            readLongLike(it, *HookTargets.BOOK_ID_KEYS)
                ?: readStringProperty(it, *HookTargets.BOOK_ID_KEYS)
                ?: it.asStringValue()?.takeIf { s -> s.all(Char::isDigit) && s.length in 4..16 }
        }
        val chapterId = candidates.firstNotNullOfOrNull {
            readLongLike(it, *HookTargets.CHAPTER_ID_KEYS)
                ?: readStringProperty(it, *HookTargets.CHAPTER_ID_KEYS)
        }
        if (bookId != null) {
            ChapterMemoryStore.updateSession { prev ->
                (prev ?: BookSession(bookId = bookId))
                    .copy(bookId = bookId)
                    .withChapter(chapterId.orEmpty())
            }
        }
    }

    private fun captureEntity(entity: Any?, source: CaptureSource): Boolean {
        if (entity == null) return false
        // Skip primitive wrappers / short strings
        if (entity is Number || entity is Boolean) return false

        val content = extractPlainContent(entity) ?: return false
        val bookId = readLongLike(entity, *HookTargets.BOOK_ID_KEYS)
            ?: readStringProperty(entity, *HookTargets.BOOK_ID_KEYS)
            ?: ChapterMemoryStore.current?.bookId
            ?: return false
        val chapterId = readLongLike(entity, *HookTargets.CHAPTER_ID_KEYS)
            ?: readStringProperty(entity, *HookTargets.CHAPTER_ID_KEYS)
            ?: return false
        val chapterName = readStringProperty(entity, *HookTargets.CHAPTER_NAME_KEYS).orEmpty()
        val bookName = readStringProperty(entity, *HookTargets.BOOK_NAME_KEYS)

        val record = ChapterRecord(
            bookId = bookId,
            chapterId = chapterId,
            chapterName = chapterName,
            content = content,
            source = source
        )
        ChapterMemoryStore.put(record)
        if (!bookName.isNullOrBlank()) {
            ChapterMemoryStore.updateSession { it?.withBookName(bookName) }
        }

        if (BuildConfig.DEBUG) {
            val preview = content.take(80).replace("\n", " ")
            YLog.debug(
                "captured chapter book=$bookId ch=$chapterId name=$chapterName " +
                    "len=${content.length} src=$source preview=$preview"
            )
        } else {
            YLog.info("captured chapter book=$bookId ch=$chapterId len=${content.length}")
        }
        return true
    }
}
