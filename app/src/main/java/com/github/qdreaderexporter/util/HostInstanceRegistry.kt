package com.github.qdreaderexporter.util

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Weak references to live host engine objects observed via hooks.
 * Used only to re-invoke the host's own chapter-load APIs (no crypto).
 */
object HostInstanceRegistry {

    @Volatile
    private var chapterProvider: WeakReference<Any>? = null

    @Volatile
    private var readBook: WeakReference<Any>? = null

    @Volatile
    private var flipView: WeakReference<Any>? = null

    private val extras = CopyOnWriteArrayList<WeakReference<Any>>()

    fun rememberChapterProvider(instance: Any?) {
        if (instance == null) return
        chapterProvider = WeakReference(instance)
        rememberExtra(instance)
    }

    fun rememberReadBook(instance: Any?) {
        if (instance == null) return
        readBook = WeakReference(instance)
        rememberExtra(instance)
    }

    fun rememberFlipView(instance: Any?) {
        if (instance == null) return
        flipView = WeakReference(instance)
        rememberExtra(instance)
    }

    fun rememberExtra(instance: Any?) {
        if (instance == null) return
        // de-dupe by identity
        if (extras.any { it.get() === instance }) return
        extras += WeakReference(instance)
        // soft bound
        while (extras.size > 24) {
            extras.removeAt(0)
        }
    }

    fun chapterProvider(): Any? = chapterProvider?.get()

    fun readBook(): Any? = readBook?.get()

    fun flipView(): Any? = flipView?.get()

    fun candidates(): List<Any> {
        val list = LinkedHashSet<Any>()
        chapterProvider()?.let { list += it }
        readBook()?.let { list += it }
        flipView()?.let { list += it }
        HostContext.activity()?.let { list += it }
        extras.mapNotNull { it.get() }.forEach { list += it }
        return list.toList()
    }

    fun statusText(): String = buildString {
        appendLine("chapterProvider: ${chapterProvider()?.javaClass?.name ?: "-"}")
        appendLine("readBook: ${readBook()?.javaClass?.name ?: "-"}")
        appendLine("flipView: ${flipView()?.javaClass?.name ?: "-"}")
        appendLine("activity: ${HostContext.activity()?.javaClass?.name ?: "-"}")
        appendLine("extraInstances: ${extras.count { it.get() != null }}")
    }
}
