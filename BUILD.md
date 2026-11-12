# 构建与开发说明（BUILD.md）

> 本工程是一个 **LSPosed 模块**：识别屏幕上的题目，通过悬浮窗给出答案。
> 能力：本地知识库匹配（仓库不附带数据，需自建）+ 三通道屏幕识别（无障碍 / Hook / OCR）+ 可选联网 LLM 补充。

---

## 1. 环境要求

| 项目 | 版本要求 |
|------|----------|
| JDK | **17**（工程已锁定 Java 17） |
| Android SDK | `platforms;android-34` + `build-tools;34.0.0` |
| Gradle | 8.2（wrapper 已配置，首次会自动下载） |
| 网络 | 需要下载依赖；`settings.gradle.kts` 已配置阿里云镜像加速 |

> 推荐直接使用 **Android Studio**（自带 JDK 17 与 SDK 管理）。

---

## 2. 构建

### 方式 A：Android Studio（推荐）

1. `File → Open` 选择本工程根目录（`LSPosed-ExamHelper/`）。
2. 等待 Gradle Sync 完成（首次会下载依赖）。
3. 菜单 `Build → Build APK(s)`，或右侧 Gradle 面板双击 `app → Tasks → build → assembleRelease`。
4. 产物在 `app/build/outputs/apk/release/`。

### 方式 B：命令行

```bash
# 1) 配置 SDK 路径（本机路径，示例为 Linux）
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# 2) 构建
./gradlew assembleRelease
# 若无 gradlew，可先执行：gradle wrapper --gradle-version 8.2
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`（**未签名**）。

---

## 3. 签名（必须）

`assembleRelease` 默认产出 **unsigned** APK，无法直接安装。仓库不附带签名密钥，请先生成自己的：

```bash
keytool -genkeypair -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
```

然后签名：

```bash
BT=/path/to/android-sdk/build-tools/34.0.0
cd app/build/outputs/apk/release/

# 对齐
$BT/zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk

# 签名
$BT/apksigner sign \
  --ks /path/to/my.keystore \
  --ks-pass pass:你的口令 \
  --out app-release.apk app-release-aligned.apk

# 验证
$BT/apksigner verify --print-certs app-release.apk
```

**签名凭据（务必自己保存，用于后续升级覆盖安装）：**

- keystore、别名、口令都由你自己生成与保管，**不要提交到仓库**（`.gitignore` 已忽略 `*.keystore` / `*.jks`）。

> ⚠️ 升级模块时必须用**同一个 keystore** 签名，否则会因签名不一致而无法覆盖安装。

---

## 4. 安装与启用

1. 安装签名后的 APK。
2. 手机安装并激活 **LSPosed**（需 Root，或使用 LSPatch 免 Root 方案）。
3. LSPosed → 模块 → 勾选本模块（`com.examhelper`）→ 重启。
4. 打开本模块 App，授予 **悬浮窗** 与 **无障碍** 权限。
5. 屏幕出现悬浮球 → 在题目页面点击悬浮球即可识别并弹出答案面板。

---

## 5. 项目结构

```
LSPosed-ExamHelper/
├── settings.gradle.kts            # 仓库配置（含阿里云镜像）
├── build.gradle.kts               # 根构建：AGP 8.2.2 / Kotlin 1.9.22
├── gradle.properties
├── gradle/wrapper/                # Gradle 8.2
├── local.properties               # sdk.dir（本机路径，不入库）
├── tools/build_kb.py              # 纯文本 → 题库 JSON 解析脚本
├── README.md
├── BUILD.md                       # 本文件
└── app/
    ├── build.gradle.kts           # applicationId=com.examhelper, minSdk26, targetSdk34
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── xposed_init        # LSPosed 入口类声明
        │   └── knowledge_base.json # 内置知识库（仓库内为空模板）
        ├── kotlin/com/examhelper/
        │   ├── XposedEntry.kt     # LSPosed 入口
        │   ├── App.kt             # Application
        │   ├── MainActivity.kt    # 设置/引导页
        │   ├── core/
        │   │   ├── Models.kt          # 数据类
        │   │   ├── KnowledgeBase.kt   # 题库加载单例
        │   │   ├── QuestionMatcher.kt # 本地匹配（n-gram 打分）
        │   │   ├── AnswerEngine.kt    # 匹配 + 联网兜底
        │   │   └── Config.kt          # 配置（LLM API 等）
        │   ├── float/
        │   │   └── FloatingBallService.kt # 悬浮球 + 答案面板 + 识别调度
        │   ├── capture/
        │   │   ├── ScreenCaptureService.kt    # MediaProjection 截图
        │   │   └── CapturePermissionActivity.kt
        │   ├── ocr/
        │   │   └── OcrEngine.kt     # ML Kit 中文 OCR
        │   ├── search/
        │   │   └── LlmClient.kt     # OpenAI 兼容接口调用
        │   ├── a11y/
        │   │   └── AnswerAccessibilityService.kt # 无障碍读文本
        │   └── hook/
        │       └── ViewTextHook.kt  # LSPosed Hook 目标 App 读视图文本
        └── res/...                # 布局、字符串、无障碍配置等
```

---

## 6. 核心逻辑职责（改代码先看这里）

| 文件 | 职责 | 优化切入点 |
|------|------|-----------|
| `QuestionMatcher.kt` | 把题干与题库条款做 n-gram 关键词打分，返回命中条款 | 换分词（HanLP/jieba4j）、加权重、向量检索 |
| `AnswerEngine.kt` | 串联「本地匹配 → 联网 LLM」 | 命中阈值、提示词、回退策略 |
| `LlmClient.kt` | 调用 OpenAI 兼容 API | 加 DeepSeek/通义默认端点、鉴权、超时 |
| `FloatingBallService.kt` | 悬浮球 + 答案面板 + 三通道识别调度 | UI 样式、拖动交互、识别通道优先级 |
| `KnowledgeBase.kt` | 从 assets 加载题库 JSON | 数据结构变更 |
| `OcrEngine.kt` | ML Kit 中文 OCR | 换更准的模型 |

### 题库替换

题库即 `app/src/main/assets/knowledge_base.json`，结构：

```json
{
  "meta": { "docCount": 0, "articleCount": 0 },
  "categories": [ { "name": "…", "docs": [ { "no": 1, "title": "…" } ] } ],
  "documents": [
    { "title": "…", "chapters": [ { "title": "…", "articles": [ { "no": "第X条", "text": "…" } ] } ] }
  ]
}
```

用 `tools/build_kb.py` 可从整理好的纯文本重新生成（`python3 build_kb.py <文本.txt> [输出.json]`）。

---

## 7. 常见问题

| 问题 | 解决 |
|------|------|
| 报错 `sdk.dir` 未找到 | 新建 `local.properties`，写入 `sdk.dir=你的SDK路径` |
| 依赖下载慢 | 已配阿里云镜像；仍慢可换其他国内镜像 |
| 报 `Unsupported class file major version` | JDK 版本不对，请使用 **JDK 17** |
| 安装提示签名冲突 | 升级时未使用同一个 keystore 签名 |
| 悬浮球点不动/不识别 | 检查悬浮窗 + 无障碍权限是否已授予；部分系统需在省电白名单放行 |

---

## 8. 合规提醒

本模块为「屏幕搜题 + 悬浮窗」学习工具，请仅用于制度检索与学习，勿用于违规考试场景，遵守学校规定与学术诚信要求。
