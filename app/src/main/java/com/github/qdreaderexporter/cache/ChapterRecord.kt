package com.github.qdreaderexporter.cache

enum class CaptureSource {
    CURRENT,
    CACHE,
    FINISH_CALLBACK,
    CONSTRUCTOR,
    UNKNOWN
}

data class ChapterRecord(
    val bookId: String,
    val chapterId: String,
    val chapterName: String,
    val indexHint: Int? = null,
    val content: String,
    val source: CaptureSource = CaptureSource.UNKNOWN,
    val capturedAt: Long = System.currentTimeMillis()
) {
    val hasContent: Boolean
        get() = content.isNotBlank()
}
