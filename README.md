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
   - **批量加载当前书已下载章节**：按宿主「已下载 ID」触发宿主本地加载，再捕获解密后明文（**不做 AES/VIP 解密，不购买**）  
   - **尝试合并明文缓存（诊断）**：扫描宿主目录，合并**已是明文**的文本文件  
   - **仅扫描存储路径**：输出扫描日志，不改缓存  
   - **运行时诊断**：版本 / 缓存 / 路径报告  

> **说明：** 批量加载只走宿主自己的本地/已购加载路径。  
> 加密离线章、未购 VIP、从未下载的章 **不会** 被解密或强下。
> 若「已知下载 ID」为 0：请先打开目录/下载管理，让宿主枚举已下载章后再试。

导出路径（按可写顺序尝试，Toast/菜单面板会打印实际路径）：

```text
/sdcard/QDReaderExporter/
/sdcard/Documents/QDReaderExporter/
# 若公共目录不可写，回退到宿主私有目录，例如：
/sdcard/Android/data/com.qidian.QDReader/files/Documents/QDReaderExporter/
```

目录内常见文件：
- `LAST_EXPORT.txt` — 最近一次导出路径
- `MODULE_ALIVE.txt` — 阅读页挂载成功时写入（证明模块在宿主内运行）
- `STATUS.txt` — 最近一次「缓存状态」
- `UI_*.txt` — 菜单结果备份（即使面板异常也有文件）

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

1. push 到 `main` / 打 `v*` tag / 手动 Run workflow  
2. 构建成功后：
   - **Actions → Artifacts**：`QDReaderExporter-debug`
   - **[Releases](https://github.com/zhonggy/yuqd/releases)**：自动上传 APK（latest）

不需要本机 Android SDK。

## 安装与启用

1. 从 [Releases](https://github.com/zhonggy/yuqd/releases) 下载 APK 并安装  
2. **强行停止 LSPosed 管理器**（或重启手机）后再打开  
3. LSPosed → **模块** → 启用 **QDReaderExporter**（也可搜 `Exporter` / `qdreaderexporter`）  
4. 作用域**仅**勾选 `com.qidian.QDReader`  
5. 强行停止「起点读书」后重新打开  
6. 打开任意可读小说阅读页 → 右下角「导出」  
7. 先翻几章再导出（被动缓存：只导出本进程已加载过的章）  

Logcat 过滤：`QDReaderExporter`

### LSPosed 里找不到模块？

| 检查 | 说明 |
|------|------|
| 是否装上了 | 桌面/应用列表应有 **QDReaderExporter** |
| 刷新模块列表 | 强停 LSPosed 管理器，或重启一次 |
| 搜包名 | `com.github.qdreaderexporter` |
| 框架类型 | 需 **Zygisk LSPosed**（不是仅 ROOT） |
| 多用户/分身 | 模块要装在与 LSPosed 同一用户空间 |

APK 内应包含：`assets/xposed_init` + Manifest `xposedmodule=true`。

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
| 无「导出」按钮 | 确认进入的是文本阅读页；看 logcat 是否 hook 到 `QDReaderActivity`；检查是否有 `MODULE_ALIVE.txt` |
| 点按钮无菜单 | 0.3.2+ 使用内容层面板而非 AlertDialog；看 Toast「导出菜单已打开」；检查 `UI_*.txt` |
| 导出提示缓存为空 | 先翻页触发章节加载；确认章节本身可读（非未购 VIP）；点「缓存状态」 |
| 宿主版本警告 | 非 7.9.394 时符号可能失效，需重新逆向 |
| 构建失败依赖找不到 | 检查 Maven Central / xposed.info 仓库网络 |

## 参考

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)  
- 本地参考实现：`D:\github\YukiHookAPI\samples\demo-module`  
