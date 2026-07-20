# QDReaderExporter

LSPosed / Xposed 模块：在 **起点读书**（`com.qidian.QDReader`）阅读页注入悬浮导出菜单，捕获**宿主已解密**的章节正文，导出为 TXT（当前章 + 本进程已缓存章）。

| 项 | 值 |
|----|----|
| 模块包名 | `com.github.qdreaderexporter` |
| 目标宿主 | `com.qidian.QDReader` |
| 钉死版本 | `7.9.394` (`versionCode` **1526**) |
| 技术栈 | Kotlin · YukiHookAPI 1.3.2 · KSP · KavaRef · minSdk 31 |
| 工程路径 | `D:\github\QDReaderExporter` |

## 声明（必读）

本模块**仅**捕获官方客户端已为当前登录用户解密并展示/缓存的正文，用于**个人备份**。

- 不绕过付费、不重实现 VIP/AES 解密、不批量爬取未购章节  
- 请勿传播导出内容  
- 使用后果自负，请遵守当地法律与起点用户协议  

## 功能

1. Hook 阅读页 `QDReaderActivity` / `QDReaderLiteActivity`  
2. 获取当前书籍元信息（bookId / bookName / chapterId / chapterName）  
3. Hook 章节加载完成相关方法，捕获解密后正文  
4. 阅读页右下角可拖动「导出」按钮  
5. 菜单（对齐常见导出模块）：
   - **导出当前阅读书籍 (TXT)**：当前书在本进程已捕获的章节  
   - **导出全部已捕获书籍 (TXT)**：内存中所有书（每书一个 TXT）  
   - **尝试合并明文缓存（诊断）**：扫描宿主目录，合并**已是明文**的文本文件  
   - **仅扫描存储路径**：输出扫描日志，不改缓存  
   - **运行时诊断**：版本 / 缓存 / 路径报告  

> **说明：** 「全部已下载」在 v1.1 语义是 **本进程已捕获的已读/已加载章**。  
> 加密离线章、未购 VIP、从未打开的章 **不会** 被解密或强下。

导出路径（宿主应用外部私有目录，Android 12+ 无需额外存储权限）：

```text
/sdcard/Android/data/com.qidian.QDReader/files/Documents/QDReaderExporter/
```

（实际以设备 `getExternalFilesDir(Documents)` 为准，Toast 会打印绝对路径。）

## 环境要求

- Android 12+ 设备，已安装 **LSPosed**（Zygisk）  
- 起点读书 **7.9.394**（强烈建议与逆向版本一致）  
- Android Studio Ladybug+ / JDK 17  
- 网络可访问 Maven Central 与 `https://api.xposed.info/`  

## 构建

### 本机构建

需要 **完整 JDK 17+**（含 `jlink` 与 `jmods`；JRE / 残缺 JBR 会失败）：

```bash
cd D:\github\QDReaderExporter
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 远程编译

仓库已包含 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

1. 把本工程推到 GitHub（新建仓库后 `git init && git remote add origin ... && git push`）  
2. **Actions** 页手动 **Run workflow**，或 push 到 `main`/`master`  
3. 构建成功后在 Artifacts 下载 **`QDReaderExporter-debug`**

不需要本机 Android SDK；GitHub-hosted runner 自带 JDK 17 + 会自动拉 Android 依赖。

## 安装与启用

1. 安装模块 APK  
2. LSPosed → 启用 **QDReaderExporter**  
3. 作用域**仅**勾选 `com.qidian.QDReader`  
4. 强行停止「起点读书」后重新打开  
5. 打开任意可读小说阅读页 → 右下角「导出」  
6. 先翻几章再导出（v1 为**被动缓存**：只导出本进程已加载过的章）  

Logcat 过滤：`QDReaderExporter`

## 工程结构

```text
app/src/main/java/com/github/qdreaderexporter/
├── application/App.kt              # ModuleApplication
├── hook/
│   ├── HookEntry.kt                # Xposed 入口
│   ├── HookTargets.kt              # 版本化类名/方法名
│   ├── HostVersionGate.kt
│   ├── BookMetaHooker.kt
│   ├── ChapterContentHooker.kt
│   ├── ChapterCacheHooker.kt
│   └── ReaderActivityHooker.kt
├── cache/                          # 进程内章节缓存
├── export/                         # TXT 写出
├── ui/FloatingExportMenu.kt        # 悬浮菜单
└── moduleui/MainActivity.kt        # 模块激活状态页
```

## 版本与逆向

目标符号针对 **7.9.394** 的 DEX 字符串扫描结果，方法 owner 在 `HookTargets` 中为候选列表；若某版更新后失效：

1. 用 jadx 打开对应 APK  
2. 按 `tools/notes-7.9.394.md` 核对 `loadChapterContentFinishInternal` / `getChapterContent` / `ChapterContentItem`  
3. 更新 `HookTargets.kt` 后重编  

## 故障排查

| 现象 | 处理 |
|------|------|
| 模块显示未激活 | 确认 LSPosed 启用 + 作用域 + 重启宿主 |
| 无「导出」按钮 | 确认进入的是文本阅读页；看 logcat 是否 hook 到 `QDReaderActivity` |
| 导出提示缓存为空 | 先翻页触发章节加载；确认章节本身可读（非未购 VIP） |
| 宿主版本警告 | 非 7.9.394 时符号可能失效，需重新逆向 |
| 构建失败依赖找不到 | 检查 Maven Central / xposed.info 仓库网络 |

## 参考

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)  
- 本地参考实现：`D:\github\YukiHookAPI\samples\demo-module`  
