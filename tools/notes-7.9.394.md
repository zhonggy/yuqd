# RE notes — QDReader 7.9.394 (versionCode 1526)

APK: `D:\github\起点读书_7.9.394.apk`  
ASCII copy: `D:\github\_re_work\QDReader_7.9.394.apk`  
Package: `com.qidian.QDReader`

## Confirmed by DEX string scan

### Activities
- `com.qidian.QDReader.ui.activity.QDReaderActivity` (primary reader)
- `com.qidian.QDReader.ui.activity.QDReaderLiteActivity`
- Launchable: `com.qidian.QDReader.ui.activity.SplashActivity`

### Engine
- `com.qidian.QDReader.readerengine.ReadBook`
- `com.qidian.QDReader.readerengine.manager.ChapterProvider`
- `com.qidian.QDReader.readerengine.config.ReadPageConfig`
- Flip callback class name observed:
  - `...view.pageflip.scrollpage.QDNewScrollFlipView$loadChapterData$getChapterContentCallback$1`

### Entities
- `com.qidian.QDReader.repository.entity.ChapterItem`
- `com.qidian.QDReader.repository.entity.ChapterInfo`
- `com.qidian.QDReader.repository.entity.ChapterContentItem`
- `com.qidian.QDReader.repository.entity.ChapterData`
- `com.qidian.QDReader.repository.entity.TxtChapterItem`

### Method name strings (owners provisional)
| Name | Role hypothesis |
|------|-----------------|
| `loadChapterContent` | load entry |
| `loadChapterContentFinishInternal` | **best post-decrypt finish** |
| `loadChapterContentFinish` | finish variant |
| `getChapterContent` | content fetch (cache/net) |
| `getChapterContentOnly` | content-only |
| `downloadVipChapterContent` | VIP path — capture result only, never reimplement |
| `insertChapterInfo` | local meta write |
| `getDownloadedChapterIds` | cached chapter id list |
| `divideChapterContentIntoSentence` | post-content processing |

### Field / log clues
- `mQDBookId`, `bookId`, `chapterId`, `chapterName`, `bookName`, `mBookName`
- logs: `chapterContent:`, `loadChapterContent  isAutoBuy :`, `playTTS getChapterContent onSuccess`

### Crypto
- `AES/CBC/PKCS5Padding`, many `decrypt *` logs  
- **Do not hook native decrypt as primary.** Capture after host finishes decrypt.

### Network (do not use as primary capture)
- `/argus/api/v2/bookcontent/safegetcontent?bookId=%1$s&chapterId=%2$s`
- `/argus/newapi/v1/bookcontent/getcontentbatch`

## Still need jadx (fill HookTargets precisely)

Open APK in jadx and record for each P0:

1. Exact class owning `loadChapterContentFinishInternal`  
2. Full signature (params + return)  
3. Where plaintext string lives on `ChapterContentItem` / result object  
4. `ReadBook` fields for bookId / bookName / current chapter  
5. Whether reader runs in a non-default process  

Search queries in jadx:
- `loadChapterContentFinishInternal`
- `getChapterContent`
- `ChapterContentItem`
- `class ChapterProvider`

## Module mapping

| Module class | Uses |
|--------------|------|
| `HookTargets` | all FQCNs / method names |
| `ChapterContentHooker` | content method candidates + entity constructors |
| `BookMetaHooker` | reader onCreate intent + ReadBook |
| `ChapterCacheHooker` | `getDownloadedChapterIds` (passive) |
| `ReaderActivityHooker` | FAB lifecycle |

## Passive vs active cache

- **v1 shipped:** passive — only chapters loaded in this process appear in export.  
- **v0.3.0 batch (no AES):** observe downloaded IDs → re-invoke host local load APIs → capture post-decrypt plaintext. Never reimplement VIP/AES; `onBuy` is skip signal only.
