package com.github.qdreaderexporter.cache

data class BookSession(
    val bookId: String,
    val bookName: String = "",
    val currentChapterId: String = "",
    val currentChapterName: String = ""
) {
    fun withChapter(chapterId: String, chapterName: String = currentChapterName): BookSession =
        copy(
            currentChapterId = chapterId.ifBlank { currentChapterId },
            currentChapterName = chapterName.ifBlank { currentChapterName }
        )

    fun withBookName(name: String): BookSession =
        if (name.isBlank()) this else copy(bookName = name)
}
