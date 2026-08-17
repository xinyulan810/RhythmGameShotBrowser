# ShotBrowser 音游截图库

一个安卓端的音游结算截图管理应用：自动扫描系统截图，本地 OCR + 可选大模型（VLM）识别歌名/成绩，按游戏分组浏览、搜索、标记 FC/AP，支持分享与删除。

## 功能

- **自动扫描**：识别 vivo 格式截图 `Screenshot_yyyyMMdd_HHmmss_包名.jpg`，按游戏自动分组（包名→游戏名映射可自定义）
- **OCR 识别**：ML Kit 中文识别，后台增量处理，支持全文搜索（歌名/成绩/游戏名）
- **AI 识别（可选）**：接入 OpenAI 兼容接口（如阿里云百炼），结构化提取歌名、难度、分数、ACC、评级、COMBO 等字段，支持并发与批量（Batch API 半价）
- **FC / AP 标记**：识别到 Full Combo / Full Recall / All Perfect / Pure Memory 时，缩略图显示 FC / AP 角标
- **大图查看**：沉浸式全屏翻页，点按屏幕呼出 OCR 信息与分享按钮，长按可分享 / 复制识别文本 / 删除
- **游戏管理**：隐藏不玩的游戏、合并别名、批量重映射

## 构建

```bash
# 需要 Android SDK (compileSdk 34) 与 JDK 17
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装 APK，授予读取图片权限
2. 打开应用 → 点「扫描截图」
3. （可选）在「AI设置」填写 API Key 与模型，启用 AI 识别

### 权限

- 读取媒体图片（扫描截图）
- 网络（ML Kit OCR 模型下载 / AI 识别）

## AI 配置说明

- 兼容任意 OpenAI Chat Completions 接口，在「AI设置」中配置 Base URL / API Key / 模型
- API Key 仅保存在本机应用私有配置中，不上传
- 截图内容仅发送到你配置的接口用于识别

## 技术栈

Kotlin · Jetpack (AppCompat / RecyclerView / ViewPager2 / ViewBinding) · Material 3 · Coil · ML Kit · SQLite · OkHttp

## 目录结构

```
app/src/main/java/com/rhythm/shots/
├── MainActivity.kt          # 主界面：游戏分组 + 缩略图
├── ViewerActivity.kt        # 大图查看（沉浸式 + OCR 覆盖层）
├── SearchActivity.kt        # OCR 全文搜索
├── GameMapActivity.kt       # 包名→游戏名映射
├── GameManageActivity.kt    # 隐藏/合并游戏
├── SettingsActivity.kt      # AI 配置
├── data/
│   ├── ShotScanner.kt       # 截图扫描
│   ├── OcrEngine.kt         # ML Kit OCR
│   ├── VlmEngine.kt         # VLM 识别（含 Batch API）
│   ├── ShotDb.kt            # SQLite 索引
│   └── ShotBadge.kt         # FC/AP 判定
└── tools/                   # 开发辅助脚本（DB 检查 / vivo 自动安装）
```

## 说明

- 本项目为个人工具，仅用于本地管理自己的音游截图
- 不包含任何第三方截图数据；识别结果（OCR/AI 文本）只存于本机数据库
