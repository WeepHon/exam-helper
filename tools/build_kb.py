# -*- coding: utf-8 -*-
"""
把整理好的制度文本解析为结构化知识库 JSON。
结构: categories -> documents -> chapters -> articles

输入要求：纯文本，按顺序包含
  - 分类行：「一、分类名」「二、分类名」…
  - 目录行：「N．制度名」「N. 制度名」
  - 章标题：「第一章 名称」
  - 条款正文：「第X条 …」，或「一、二、…」条目
输出：knowledge_base.json（categories / documents / chapters / articles）
"""
import re, json
import sys, os

# 用法：python3 build_kb.py <预抽取好的文本文件.txt> [输出.json]
# 输出默认写到输入文件同目录下的 knowledge_base.json
if len(sys.argv) < 2:
    print("用法: python3 build_kb.py <预抽取好的文本文件.txt> [输出.json]")
    sys.exit(1)
SRC = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(SRC)), "knowledge_base.json")

lines = open(SRC, encoding="utf-8").read().split("\n")

# ---------- 1. 目录解析 ----------
CAT_NAMES = ["教学管理制度", "学生管理制度", "学生评奖评优制度", "学生资助助学制度", "附录"]
DOC_RE = re.compile(r"^(\d+)[．.．]\s*(.*)")

def find_cat(s):
    for cn in CAT_NAMES:
        if re.match(r"^[一二三四五六七八九十]+、\s*" + re.escape(cn) + r"\s*$", s):
            return cn
    return None

toc_categories = []
cur_cat = None
flat_docs = []
for ln in lines:
    s = ln.strip()
    if not s:
        continue
    cn = find_cat(s)
    if cn:
        cur_cat = {"name": cn, "docs": []}
        toc_categories.append(cur_cat)
        continue
    m = DOC_RE.match(s)
    if m and cur_cat is not None:
        if cur_cat["name"] == "附录":
            continue
        no = int(m.group(1))
        title = re.sub(r"[·\s]", "", m.group(2))
        cur_cat["docs"].append({"no": no, "title": title})
        flat_docs.append(title)

# ---------- 2. 正则 ----------
CHAP_RE = re.compile(r"^第[一二三四五六七八九十百零]+章")
ART_RE  = re.compile(r"^第[一二三四五六七八九十百零]+条")
ITEM_RE = re.compile(r"^([一二三四五六七八九十]+)、")
QA_RE   = re.compile(r"^(\d+)[．.．]\s*\S")
ANSWER_RE = re.compile(r"^答[：:]")
NOISE_NUM = re.compile(r"^\d{1,4}$")
NOISE_HDR = re.compile(r"^\d{4}\s+\d{1,4}$")

body_start = 0
for i, ln in enumerate(lines):
    t = ln.strip()
    if CHAP_RE.match(t) or ART_RE.match(t):
        body_start = i
        break

def join_text(t):
    return re.sub(r"\s+", "", t)

# ---------- 3. 解析正文 ----------
docs = []          # 所有文档（含特殊格式）
cur_doc = None
cur_chap = None
cur_art = None
pending_chapter = None
special = None     # None | 'items' | 'qa'

def doc_has_articles(d):
    return any(ch["articles"] for ch in d["chapters"])

def new_doc():
    global cur_doc, cur_chap, cur_art, special
    cur_doc = {"title": "", "chapters": []}
    cur_chap = None
    cur_art = None
    docs.append(cur_doc)

def new_chap(title):
    global cur_chap, cur_art
    cur_chap = {"title": title, "articles": []}
    cur_doc["chapters"].append(cur_chap)
    cur_art = None

def new_art(no):
    global cur_art
    cur_art = {"no": no, "text": ""}
    cur_chap["articles"].append(cur_art)

new_doc()

def is_noise(s):
    if not s:
        return True
    if NOISE_NUM.match(s):
        return True
    if NOISE_HDR.match(s):
        return True
    if s in ("学生手册", "（2026 汇编）"):
        return True
    if re.match(r"^\.{3,}\d+$", s):
        return True
    return False

for i in range(body_start, len(lines)):
    s = lines[i].strip()
    if is_noise(s):
        continue

    # 问答：答：行
    if ANSWER_RE.match(s) and special == "qa":
        if cur_art is not None:
            cur_art["text"] += s
        continue

    # 中文条目：一、二、…（实验室安全守则 / 教室文明守则）
    m = ITEM_RE.match(s)
    if m:
        if special != "items" or not doc_has_articles(cur_doc):
            # 进入新的"条目"文档（或当前文档为空）
            if doc_has_articles(cur_doc):
                new_doc()
            special = "items"
        if cur_chap is None:
            new_chap("（守则）")
        no = "第" + m.group(1) + "条"
        new_art(no)
        rest = s[len(m.group(0)):].strip()
        if rest:
            cur_art["text"] += rest
        continue

    # 问答：N．问题（医疗保险政策问答）
    m = QA_RE.match(s)
    if m and ("？" in s or "?" in s or "哪些" in s or "如何" in s or "怎么" in s or "什么" in s):
        if special != "qa" or not doc_has_articles(cur_doc):
            if doc_has_articles(cur_doc):
                new_doc()
            special = "qa"
        if cur_chap is None:
            new_chap("（问答）")
        no = "问" + m.group(1)
        new_art(no)
        rest = s[len(m.group(0)):].strip()
        if rest:
            cur_art["text"] += rest
        continue

    # 章节
    m = CHAP_RE.match(s)
    if m:
        title = re.sub(r"\s+", " ", s)
        if title.startswith("第一章"):
            pending_chapter = title
        else:
            new_chap(title)
        continue

    # 条款
    m = ART_RE.match(s)
    if m:
        no = re.match(r"第[一二三四五六七八九十百零]+条", s).group(0)
        special = None   # 回到常规条款模式
        if no == "第一条":
            if doc_has_articles(cur_doc):
                new_doc()
            if pending_chapter is not None:
                new_chap(pending_chapter)
                pending_chapter = None
            if cur_chap is None:
                new_chap("（无章节）")
        else:
            if cur_chap is None and pending_chapter is not None:
                new_chap(pending_chapter)
                pending_chapter = None
            if cur_chap is None:
                new_chap("（无章节）")
        new_art(no)
        rest = s[len(no):].strip()
        if rest:
            cur_art["text"] += rest
        continue

    # 普通正文
    if cur_art is not None:
        cur_art["text"] += s

# 清理空文档/空章 + 拼接正文
def clean(docs):
    out = []
    for d in docs:
        d["chapters"] = [c for c in d["chapters"] if c["articles"]]
        if not d["chapters"]:
            continue
        for c in d["chapters"]:
            for a in c["articles"]:
                a["text"] = join_text(a["text"])
        out.append(d)
    return out

docs = clean(docs)
print("解析到文档数:", len(docs))
for i, d in enumerate(docs):
    n = sum(len(c["articles"]) for c in d["chapters"])
    print(f"  [{i}] {d['chapters'][0]['articles'][0]['no']} {d['chapters'][0]['articles'][0]['text'][:24]} ... 共{n}条")

# ---------- 4. 标题映射 ----------
# 48 个 TOC 标题 + 附录《普通高等学校学生管理规定》
titles = list(flat_docs) + ["普通高等学校学生管理规定（教育部41号令，附录）"]
for idx, d in enumerate(docs):
    if idx < len(titles):
        d["title"] = titles[idx]
    else:
        d["title"] = f"（未命名文档{idx+1}）"

# ---------- 5. 输出 ----------
out = {
    "meta": {
        "name": os.environ.get("KB_NAME", "自建题库"),
        "school": os.environ.get("KB_SCHOOL", ""),
        "version": os.environ.get("KB_VERSION", ""),
        "categories": [c["name"] for c in toc_categories],
        "docCount": len(docs),
        "articleCount": sum(len(a) for d in docs for c in d["chapters"] for a in c["articles"]),
    },
    "categories": toc_categories,
    "documents": docs,
}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print("已写出:", OUT)
