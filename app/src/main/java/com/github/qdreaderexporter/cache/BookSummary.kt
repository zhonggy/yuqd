package com.github.qdreaderexporter.cache

data class BookSummary(
    val bookId: String,
    val bookName: String,
    val chapterCount: Int,
    val totalChars: Int
)
