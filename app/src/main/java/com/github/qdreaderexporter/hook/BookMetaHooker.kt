package com.github.qdreaderexporter.hook

import android.app.Activity
import android.os.Bundle
import com.github.qdreaderexporter.cache.BookSession
import com.github.qdreaderexporter.cache.ChapterMemoryStore
import com.github.qdreaderexporter.util.HostContext
import com.github.qdreaderexporter.util.HostInstanceRegistry
import com.github.qdreaderexporter.util.ReflectExt.readLongLike
import com.github.qdreaderexporter.util.ReflectExt.readStringProperty
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog

private fun Bundle.readExtraAsString(key: String): String? {
    if (!containsKey(key)) return null
    getString(key)?.let { return it }
    if (containsKey(key)) {
        // long / int extras are common for bookId / chapterId
        runCatching { getLong(key) }.getOrNull()?.takeIf { it != 0L }?.let { return it.toString() }
        runCatching { getInt(key) }.getOrNull()?.takeIf { it != 0 }?.let { return it.toString() }
    }
    return null
}

/**
 * Captures book / chapter meta from reader activity intent and ReadBook instance.
 */
object BookMetaHooker : YukiBaseHooker() {

    override fun onHook() {
        hookReaderActivity(HookTargets.CLS_READER_ACTIVITY)
        hookReaderActivity(HookTargets.CLS_READER_LITE_ACTIVITY)
        hookReadBook()
    }

    private fun hookReaderActivity(className: String) {
        runCatching {
            className.toClass(appClassLoader).resolve().apply {
                firstMethod {
                    name = "onCreate"
                    parameters(Bundle::class)
                }.hook {
                    after {
                        runCatching {
                            val activity = instance<Activity>()
                            HostContext.setActivity(activity)
                            HostContext.setAppContext(activity.applicationContext)
                            extractFromIntent(activity)
                            extractFromInstance(activity)
                        }.onFailure {
                            YLog.debug("BookMeta onCreate parse failed: ${it.message}")
                        }
                    }
                }
            }
            YLog.info("BookMetaHooker: hooked $className.onCreate")
        }.onFailure {
            YLog.warn("BookMetaHooker: skip $className — ${it.message}")
        }
    }

    private fun hookReadBook() {
        runCatching {
            val clazz = HookTargets.CLS_READ_BOOK.toClass(appClassLoader)
            // Hook constructors to observe new ReadBook sessions
            clazz.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.hook {
                        after {
                            runCatching {
                                HostInstanceRegistry.rememberReadBook(instance)
                                extractFromInstance(instance)
                            }
                        }
                    }
                }
            }
            YLog.info("BookMetaHooker: hooked ReadBook constructors (${clazz.declaredConstructors.size})")
        }.onFailure {
            YLog.warn("BookMetaHooker: ReadBook hook failed — ${it.message}")
        }
    }

    private fun extractFromIntent(activity: Activity) {
        val extras = activity.intent?.extras ?: return
        val bookId = listOf("bookId", "QDBookId", "qdBookId", "mQDBookId", "bookid")
            .firstNotNullOfOrNull { key ->
                extras.readExtraAsString(key)?.takeIf { it.isNotBlank() && it != "0" }
            }
        val bookName = listOf("bookName", "QDBookName", "qdBookName", "bookname")
            .firstNotNullOfOrNull { key ->
                extras.getString(key)?.takeIf { it.isNotBlank() }
            }
        val chapterId = listOf("chapterId", "ChapterId", "mChapterId", "curChapterId")
            .firstNotNullOfOrNull { key ->
                extras.readExtraAsString(key)?.takeIf { it.isNotBlank() && it != "0" }
            }
        val chapterName = listOf("chapterName", "ChapterName", "chapterTitle")
            .firstNotNullOfOrNull { key ->
                extras.getString(key)?.takeIf { it.isNotBlank() }
            }
        if (bookId != null) {
            ChapterMemoryStore.updateSession { prev ->
                (prev ?: BookSession(bookId = bookId))
                    .copy(bookId = bookId)
                    .withBookName(bookName.orEmpty())
                    .withChapter(chapterId.orEmpty(), chapterName.orEmpty())
            }
            YLog.info("BookMeta from intent: bookId=$bookId name=$bookName chapter=$chapterId")
        }
    }

    private fun extractFromInstance(target: Any?) {
        if (target == null) return
        val bookId = readLongLike(target, *HookTargets.BOOK_ID_KEYS)
            ?: readStringProperty(target, *HookTargets.BOOK_ID_KEYS)
        val bookName = readStringProperty(target, *HookTargets.BOOK_NAME_KEYS)
        val chapterId = readLongLike(target, *HookTargets.CHAPTER_ID_KEYS)
            ?: readStringProperty(target, *HookTargets.CHAPTER_ID_KEYS)
        val chapterName = readStringProperty(target, *HookTargets.CHAPTER_NAME_KEYS)
        if (bookId.isNullOrBlank() && bookName.isNullOrBlank()) return
        ChapterMemoryStore.updateSession { prev ->
            val id = bookId ?: prev?.bookId.orEmpty()
            if (id.isBlank()) return@updateSession prev
            (prev ?: BookSession(bookId = id))
                .copy(bookId = id)
                .withBookName(bookName.orEmpty())
                .withChapter(chapterId.orEmpty(), chapterName.orEmpty())
        }
    }
}
