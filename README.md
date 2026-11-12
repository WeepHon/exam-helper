# 题库助手 · LSPosed 模块

一个 **LSPosed / Xposed 模块**：识别屏幕上的题目，通过**悬浮窗**给出参考结果。

- 三通道识别：无障碍读控件文本 → LSPosed Hook 读视图树 → 屏幕截图 + 本地 OCR；
- 本地题库检索：仓库**不附带题库数据**，用 `tools/build_kb.py` 把自己的制度文本生成 JSON 即可启用，n-gram 打分匹配；
- 可选联网：配置任意 OpenAI 兼容接口（OpenAI / DeepSeek / Moonshot / 通义千问等）补充作答；
- 判断题条文核对：题库命中判断题时，用本地条文再校验一次，把「按条文应为对/错」并列展示（不覆盖题库答案）。

> 本项目面向「规章制度检索 / 校园问答辅助」场景，请遵守所在学校的规定与学术诚信要求，不要用于违规考试。

---

## 一、功能

| 能力 | 说明 |
|------|------|
| 屏幕题目识别 | ① 无障碍直接读控件文本（最准）→ ② LSPosed Hook 读目标 App 视图树 → ③ 屏幕截图 + 本地 OCR，逐级降级 |
| 悬浮窗答案 | 可拖动的悬浮球，点击后弹出答案面板，展示命中条款与来源 |
| 本地题库 | 自建题库（`assets/knowledge_base.json`），关键词 n-gram + 数字实体打分匹配；未导入时只用联网或 OCR 结果 |
| 条文核对 | 判断题命中题库后，与本地条文比对，提示「一致 / 按条文应为对/错」 |
| 联网功能（可选） | OpenAI 兼容接口，本地命中 + 模型生成，未配置时完全离线 |

## 二、识别通道与权限

模块需要以下权限，用途如下（不申请定位、通讯录、短信等任何无关权限）：

| 权限 / 能力 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` 悬浮窗 | 显示悬浮球与答案面板 |
| 无障碍服务 | 读取当前屏幕上的文字（识别题目） |
| `MediaProjection` 屏幕录制 | 前两种方式都取不到文字时，截图 + 本地 OCR；**只在点击悬浮球时截一张，不录制、不保存、不上传** |
| `INTERNET` | 仅在用户自己配置了 LLM 接口时使用 |

此外：

- 截图与 OCR 全部在本机完成（ML Kit 中文识别，离线模型）；
- **API Key 保存在本机私有 SharedPreferences，不会上传到除你配置的接口之外的任何地方**；但它是明文存储（见「已知限制」）；
- 日志写到 `Android/data/<包名>/files/logs/`，其中可能包含识别到的题干文本，请勿随意分享日志。

## 三、安装

1. 手机已安装 **LSPosed**（Magisk / KernelSU / APatch 环境，或 LSPatch 免 Root）。
2. 安装本模块 APK。
3. 打开 LSPosed 管理器 → 模块 → 勾选本模块 →（精准模式）把目标 App 加入作用域 → 重启目标 App。
4. 打开 App → 授予悬浮窗权限 → 开启无障碍服务。

## 四、使用

- 点击「显示悬浮球」；
- 打开题目页面，点击悬浮球 → 自动识别 → 弹出答案面板；
- 面板右上角 **↻** 重新识别、**✕** 收起；**长按悬浮球**关闭。

## 五、构建

需要 **JDK 17** + **Android SDK 34**（`platforms;android-34`、`build-tools;34.0.0`）。

```bash
# 1) 写入 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 构建（未签名的 release APK）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

签名（本仓库**不附带**签名密钥，请用你自己的）：

```bash
keytool -genkeypair -v -keystore my.keystore -alias mykey \
  -keyalg RSA -keysize 2048 -validity 10000

BT=$ANDROID_SDK/build-tools/34.0.0
$BT/zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
$BT/apksigner sign --ks my.keystore --out app-release.apk app-release-aligned.apk
$BT/apksigner verify --print-certs app-release.apk
```

> 升级覆盖安装时必须用**同一个 keystore**，否则系统会报签名冲突。
> 详细的构建 / 调试排错见 [BUILD.md](BUILD.md)。

## 六、自定义题库

替换 `app/src/main/assets/knowledge_base.json` 即可更换题库，JSON 结构：

```json
{
  "meta": { "docCount": 1, "articleCount": 1 },
  "categories": [ { "name": "分类", "docs": [ { "no": 1, "title": "制度名" } ] } ],
  "documents": [
    {
      "title": "制度名",
      "chapters": [
        { "title": "第一章 总则",
          "articles": [ { "no": "第一条", "text": "条款正文…" } ] }
      ]
    }
  ]
}
```

`tools/build_kb.py` 可以把整理好的纯文本（每行一条正文，带「N．制度名」「第X章」「第X条」等结构行）解析成上述 JSON：

```bash
python3 tools/build_kb.py 你的制度文本.txt knowledge_base.json
```

## 七、匹配原理

1. 题干清洗：去题号、标签、标点、空白，统一大小写；
2. 抽取 2/3/4 字 n-gram 与数字实体（如 `6000元`、`4年`），长词权重更高；
3. 逐条计算命中得分，叠加「制度名 / 章名」命中加成；
4. 导入题库命中时先做置信度判定（领先比例、最低分），高置信直接返回，否则走条文 + LLM 兜底；
5. 判断题再做条文核对：编辑距离匹配条文句子，数字差异或否定词差异 → 按条文判「错」，其余判「对」，仅提示。

## 八、已知限制

- **API Key 明文存储**：保存在 SharedPreferences，且清单里 `allowBackup=true`，系统备份可能把 Key 一起带走；共享设备请勿保存长期 Key。
- **Hook 回传广播为导出接收器**，目前未校验来源包，理论上可被其他应用注入文本（已知问题，欢迎 PR）。
- 判断题核对对 OCR 质量敏感：题干里的「不」「数字」若被漏识别，核对可能给出相反的提示；以面板上「题目：」一行为准。
- 无障碍 / Hook 通道在部分 App（自绘界面、WebView）取不到文字，会退化为截图 OCR。
- 没有针对所有机型的截图授权兼容性测试（Android 14 起每次重启需重新授权一次个别机型）。

## 九、免责声明

本项目仅用于学习 Android / LSPosed 技术，以及对公开制度文本的检索查询。请勿用于违反考试纪律或学校规定的用途；使用产生的一切后果由使用者自行承担。

仓库不附带任何题库数据；自行导入的题库版权与合规由使用者负责。
