package com.github.qdreaderexporter.export

object FileNameSanitizer {
    private val illegal = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun sanitize(raw: String, maxLen: Int = 80): String {
        val cleaned = raw
            .replace(illegal, "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        if (cleaned.isEmpty()) return "untitled"
        return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen).trim()
    }
}
