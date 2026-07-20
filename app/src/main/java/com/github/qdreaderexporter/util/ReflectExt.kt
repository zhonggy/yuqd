package com.github.qdreaderexporter.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ReflectExt {

    fun Class<*>.allFields(): List<Field> {
        val list = mutableListOf<Field>()
        var c: Class<*>? = this
        while (c != null && c != Any::class.java) {
            list += c.declaredFields
            c = c.superclass
        }
        return list
    }

    fun Class<*>.allMethods(): List<Method> {
        val list = mutableListOf<Method>()
        var c: Class<*>? = this
        while (c != null && c != Any::class.java) {
            list += c.declaredMethods
            c = c.superclass
        }
        return list
    }

    fun Field.safeGet(instance: Any?): Any? = runCatching {
        isAccessible = true
        get(instance)
    }.getOrNull()

    fun Method.safeInvoke(instance: Any?, vararg args: Any?): Any? = runCatching {
        isAccessible = true
        invoke(instance, *args)
    }.getOrNull()

    fun Any?.asStringValue(): String? {
        return when (this) {
            null -> null
            is String -> this
            is CharSequence -> this.toString()
            is Number -> this.toString()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /**
     * Try common id/name field patterns on an entity instance.
     */
    fun readStringProperty(target: Any?, vararg candidates: String): String? {
        if (target == null) return null
        val clazz = target.javaClass
        for (name in candidates) {
            // field
            clazz.allFields().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { f ->
                f.safeGet(target).asStringValue()?.let { return it }
            }
            // getter getXxx / isXxx
            val getterNames = listOf(
                "get${name.replaceFirstChar { it.uppercase() }}",
                name,
                "get${name.uppercase()}"
            )
            for (g in getterNames) {
                clazz.allMethods()
                    .firstOrNull {
                        it.name == g &&
                            it.parameterTypes.isEmpty() &&
                            !Modifier.isStatic(it.modifiers)
                    }?.let { m ->
                        m.safeInvoke(target).asStringValue()?.let { return it }
                    }
            }
        }
        return null
    }

    fun readLongLike(target: Any?, vararg candidates: String): String? {
        if (target == null) return null
        val clazz = target.javaClass
        for (name in candidates) {
            clazz.allFields().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { f ->
                val v = f.safeGet(target)
                when (v) {
                    is Number -> if (v.toLong() != 0L) return v.toString()
                    is String -> if (v.isNotBlank()) return v
                }
            }
            val getter = "get${name.replaceFirstChar { it.uppercase() }}"
            clazz.allMethods()
                .firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
                ?.safeInvoke(target)
                ?.let { v ->
                    when (v) {
                        is Number -> if (v.toLong() != 0L) return v.toString()
                        is String -> if (v.isNotBlank()) return v
                    }
                }
        }
        return null
    }

    /**
     * Best-effort plain text extraction from chapter content entities.
     */
    fun extractPlainContent(target: Any?): String? {
        if (target == null) return null
        if (target is String) return target.takeIf { it.isNotBlank() && looksLikeProse(it) }
        if (target is CharSequence) {
            val s = target.toString()
            return s.takeIf { it.isNotBlank() && looksLikeProse(it) }
        }
        val contentKeys = arrayOf(
            "content", "chapterContent", "contentStr", "plainText",
            "text", "body", "chapterText", "mContent", "contentText"
        )
        // Prefer explicit content fields
        for (key in contentKeys) {
            readStringProperty(target, key)?.let { raw ->
                val plain = stripSimpleHtml(raw)
                if (looksLikeProse(plain)) return plain
            }
        }
        // Scan all string fields for longest prose-like text
        var best: String? = null
        for (f in target.javaClass.allFields()) {
            val v = f.safeGet(target) ?: continue
            val s = when (v) {
                is String -> v
                is CharSequence -> v.toString()
                else -> continue
            }
            val plain = stripSimpleHtml(s)
            if (!looksLikeProse(plain)) continue
            if (best == null || plain.length > best.length) best = plain
        }
        return best
    }

    fun stripSimpleHtml(input: String): String {
        return input
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun looksLikeProse(text: String): Boolean {
        if (text.length < 20) return false
        // Reject obvious ciphertext / base64-ish blobs
        val alnum = text.count { it.isLetterOrDigit() }
        val ratio = alnum.toDouble() / text.length
        if (ratio > 0.95 && text.length > 200 && !text.any { it in '一'..'鿿' }) {
            return false
        }
        // Prefer Chinese novel text or reasonably long paragraphs
        val hasCjk = text.any { it in '一'..'鿿' }
        return hasCjk || text.length > 80
    }
}
