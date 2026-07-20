package com.github.qdreaderexporter.hook

/**
 * Version-pinned symbols for QDReader 7.9.394 (versionCode 1526).
 *
 * Method owners marked provisional were confirmed by DEX string scan;
 * refine signatures with jadx when a hook fails (see tools/notes-7.9.394.md).
 */
object HookTargets {

    const val HOST_PACKAGE = "com.qidian.QDReader"
    const val EXPECTED_VERSION_NAME = "7.9.394"
    const val EXPECTED_VERSION_CODE = 1526L

    // Reader UI
    const val CLS_READER_ACTIVITY =
        "com.qidian.QDReader.ui.activity.QDReaderActivity"
    const val CLS_READER_LITE_ACTIVITY =
        "com.qidian.QDReader.ui.activity.QDReaderLiteActivity"
    const val CLS_BASE_IMMERSE_READER_ACTIVITY =
        "com.qidian.QDReader.ui.activity.BaseImmerseReaderActivity"

    /** All known reader activity FQCNs (direct hooks). */
    val READER_ACTIVITY_CANDIDATES = arrayOf(
        CLS_READER_ACTIVITY,
        CLS_READER_LITE_ACTIVITY,
        CLS_BASE_IMMERSE_READER_ACTIVITY
    )

    // Engine / providers
    const val CLS_READ_BOOK =
        "com.qidian.QDReader.readerengine.ReadBook"
    const val CLS_CHAPTER_PROVIDER =
        "com.qidian.QDReader.readerengine.manager.ChapterProvider"

    // Entities
    const val CLS_CHAPTER_ITEM =
        "com.qidian.QDReader.repository.entity.ChapterItem"
    const val CLS_CHAPTER_INFO =
        "com.qidian.QDReader.repository.entity.ChapterInfo"
    const val CLS_CHAPTER_CONTENT_ITEM =
        "com.qidian.QDReader.repository.entity.ChapterContentItem"
    const val CLS_CHAPTER_DATA =
        "com.qidian.QDReader.repository.entity.ChapterData"

    // Method names (owners may be ChapterProvider / ReadBook / flip view)
    const val M_LOAD_CHAPTER_CONTENT = "loadChapterContent"
    const val M_LOAD_CHAPTER_CONTENT_FINISH = "loadChapterContentFinish"
    const val M_LOAD_CHAPTER_CONTENT_FINISH_INTERNAL = "loadChapterContentFinishInternal"
    const val M_GET_CHAPTER_CONTENT = "getChapterContent"
    const val M_GET_CHAPTER_CONTENT_ONLY = "getChapterContentOnly"
    const val M_INSERT_CHAPTER_INFO = "insertChapterInfo"
    const val M_GET_DOWNLOADED_CHAPTER_IDS = "getDownloadedChapterIds"

    const val CLS_BOOK_CHAPTER_LIST =
        "com.qidian.QDReader.component.bll.BookChapterList"
    const val CLS_BOOK_CHAPTER_LIST_COMPANION =
        "com.qidian.QDReader.component.bll.BookChapterList\$Companion"

    // Field / property name candidates
    val BOOK_ID_KEYS = arrayOf(
        "mQDBookId", "qdBookId", "bookId", "mBookId", "QDBookId"
    )
    val BOOK_NAME_KEYS = arrayOf(
        "mBookName", "bookName", "qdBookName", "mQDBookName", "QDBookName"
    )
    val CHAPTER_ID_KEYS = arrayOf(
        "mChapterId", "chapterId", "mCurChapterId", "curChapterId", "ChapterId"
    )
    val CHAPTER_NAME_KEYS = arrayOf(
        "chapterName", "mChapterName", "chapterTitle", "title", "ChapterName"
    )

    /** Classes we attempt name-based method hooks on (first existing wins per name). */
    val CONTENT_OWNER_CANDIDATES = arrayOf(
        CLS_CHAPTER_PROVIDER,
        CLS_READ_BOOK,
        "com.qidian.QDReader.readerengine.manager.a",
        "com.qidian.QDReader.readerengine.manager.b",
        "com.qidian.QDReader.readerengine.controller.a",
        "com.qidian.QDReader.readerengine.view.pageflip.scrollpage.QDNewScrollFlipView"
    )

    val CONTENT_METHOD_CANDIDATES = arrayOf(
        M_LOAD_CHAPTER_CONTENT_FINISH_INTERNAL,
        M_LOAD_CHAPTER_CONTENT_FINISH,
        M_GET_CHAPTER_CONTENT,
        M_GET_CHAPTER_CONTENT_ONLY,
        M_LOAD_CHAPTER_CONTENT,
        "loadChapterContentWithId",
        "loadChapterData",
        "switchChapter",
        "preloadChapter",
        "reloadChapterContent"
    )
}
