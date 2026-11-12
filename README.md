# 题库助手 · LSPosed 模块

识别屏幕上的题目，用**悬浮窗**给出参考结果：本地制度题库检索 + 可选联网模型兜底。

## 特性

- **三通道识别**：无障碍读控件文本 → LSPosed Hook 读视图树 → 截图 + 本地 OCR，逐级降级
- **本地题库**：仓库**不含题库数据**，App 内直接导入 `txt / docx / pdf / xlsx`，自动识别为问答库或条文库，多库同时启用
- **可选联网**：任意 OpenAI 兼容接口（OpenAI / DeepSeek / Moonshot / 通义千问），未配置则完全离线
- **判断题核对**：命中题库后按本地条文二次比对，并列展示「一致 / 按条文应为对/错」

## 权限

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮球与答案面板 |
| 无障碍服务 | 读取当前屏幕文字 |
| `MediaProjection` | 前两路取不到文字时截图 + OCR；点击悬浮球时截一张，不录制、不保存、不上传 |
| `INTERNET` | 仅在你自己配置了 LLM 接口时使用 |

截图与 OCR 全部在本机完成（ML Kit 中文离线模型）。API Key 存在本机私有 SharedPreferences（明文，见「已知限制」）。日志在 `Android/data/<包名>/files/logs/`，可能含题干文本，勿随意分享。

## 安装

1. 手机已装 **LSPosed**（Magisk / KernelSU / APatch，或 LSPatch 免 Root）
2. 安装本模块 APK
3. LSPosed 管理器 → 模块 → 勾选本模块 →（精准模式）把目标 App 加入作用域 → 重启目标 App
4. 打开 App → 授予悬浮窗权限 → 开启无障碍服务

## 使用

点击「显示悬浮球」→ 打开题目页面 → 点悬浮球自动识别并弹出面板。面板右上 **↻** 重识别、**✕** 收起；**长按悬浮球**关闭。

## 构建

需要 **JDK 17** + **Android SDK 34**（`platforms;android-34`、`build-tools;34.0.0`）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

签名用你自己的密钥（仓库**不附带** keystore）：

```bash
keytool -genkeypair -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
BT=$ANDROID_SDK/build-tools/34.0.0
$BT/zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
$BT/apksigner sign --ks my.keystore --out app-release.apk app-release-aligned.apk
```

> 覆盖升级必须用**同一 keystore**，否则签名冲突。排错见 [BUILD.md](BUILD.md)。

## 导入题库

仓库**不含题库数据**，在 App 里直接导入，不用改任何文件。

1. App 首页「题库」区 → **选择文件导入题库**
2. 选文件：`.xlsx` / `.docx` / `.pdf` / `.txt`
3. 自动识别类型并入库，Toast 提示「导入成功：问答 N 条 / 条文 N 条」
4. 题库列表里逐条**勾选启用**、取消或删除；「清空所有导入的题库」只清导入项，**内置知识库不受影响**

| 格式 | 解析方式 |
|---|---|
| `.xlsx` | 按列读取，需含**「题目」**和**「答案」**两列（只生成问答库） |
| `.docx` / `.pdf` | 提取全文后自动判定类型 |
| `.txt` | 纯文本，直接判定 |

**类型自动判定**：≥3 条「第X条」或带章节结构 → 条文库（kb）；有 A/B/C/D 选项行或较多「答案：」行 → 问答库（qa）；已配置 LLM 时先让模型看一眼前两页 / 开头 1200 字辅助判断，失败回退本地规则。多份题库可同时启用，答题时合并检索。

**匹配原理**：清洗题干 → 抽取 2/3/4 字 n-gram 与数字实体 → 逐条打分并叠加制度名/章名加成 → 高置信直接返回，否则条文 + LLM 兜底。

### 可选：预置条文库

想把制度文本直接打包进 APK，仍可用脚本生成 `app/src/main/assets/knowledge_base.json`（每行一条正文，可带「N．制度名」「第X章」「第X条」结构行）：

```bash
python3 tools/build_kb.py 你的制度文本.txt knowledge_base.json
```

## 已知限制

- **API Key 明文存储**，且清单 `allowBackup=true`，系统备份可能带走；共享设备勿存长期 Key
- Hook 回传广播为**导出接收器**且未校验来源包，理论上可被其他应用注入文本（欢迎 PR）
- 判断题核对对 OCR 质量敏感（漏识别「不」或数字会给出相反提示），以面板「题目：」一行为准
- 自绘界面 / WebView 常取不到文字，会退化为截图 OCR；Android 14 部分机型重启后需重新授权截图

## 免责声明

本项目仅用于学习 Android / LSPosed 技术及公开制度文本检索，请勿用于违反考试纪律或学校规定的用途，使用后果自负。仓库不含任何题库数据，自行导入内容的版权与合规由使用者负责。
