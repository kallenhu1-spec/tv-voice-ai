#!/usr/bin/env python3
# 第 2 轮：修正核验方法 + 换成旗舰模型公平对比 + 检查“推荐的 UP主到底拍没拍过这个城市”。
import json, os, sys, time, urllib.request, urllib.parse, http.cookiejar, re, statistics
KEY = os.environ.get("SF_KEY", "") or sys.exit("缺少密钥")
H = {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}

def get_models():
    req = urllib.request.Request("https://api.siliconflow.cn/v1/models?type=text&sub_type=chat", headers=H)
    return [m["id"] for m in json.load(urllib.request.urlopen(req, timeout=20))["data"]]
ids = get_models()
def pick(pats):
    for p in pats:
        for i in ids:
            if re.search(p, i, re.I) and not re.search(r"(Air|mini|Flash-Lite|Distill|-\d+B-A3B)", i, re.I): return i
    return None
MODELS = [m for m in ["deepseek-ai/DeepSeek-V4.1-Flash", "deepseek-ai/DeepSeek-V3.2",
          pick([r"^Qwen/Qwen3\.\d.*(397B|480B|Max|235B)", r"^Qwen/Qwen3\.\d.*\d{3}B"]),
          pick([r"^moonshotai/Kimi-K2"]), pick([r"^zai-org/GLM-5", r"^zai-org/GLM-4\.[5-9]"])] if m and m in ids]
print("参加对比的模型：", MODELS)

CITY = [("成都", "我下个月去成都旅游，推荐5个B站上做成都美食探店的UP主"),
        ("上海", "推荐5个B站上专门探上海小馆子、本帮菜的UP主"),
        ("长沙", "去长沙玩，推荐5个B站上做长沙美食的UP主"),
        ("西安", "去西安玩，推荐5个B站上做西安美食的UP主"),
        ("广州", "去广州喝早茶，推荐5个B站上做广州美食的UP主")]
OTHER = ["推荐5个适合5岁小孩学英语的B站UP主", "推荐5个B站上讲财经、宏观经济科普的UP主",
         "推荐5个B站上讲艾尔登法环攻略的UP主", "推荐5个B站上教做家常菜的UP主"]
FAKE = ["上海弄堂小馆研究所阿强", "成都苍蝇馆子扫街王老五", "广州早茶一盅两件陈伯"]
SYS_REC = "你是电视上的语音助手，帮用户找B站UP主。只输出UP主在B站的名字，每行一个，不要编号、不要解释。不确定的不要写。"
SYS_WHO = "你是电视上的语音助手。用一句话说这个B站UP主主要做什么内容。如果你不知道或不确定这个UP主，就只回答：不知道。"

def chat(model, sys_p, user):
    base = {"model": model, "temperature": 0, "max_tokens": 400, "stream": False,
            "messages": [{"role": "system", "content": sys_p}, {"role": "user", "content": user}]}
    for extra in ({"enable_thinking": False}, {}):   # 关掉“思考”，公平比较；不支持就去掉这个参数重试
        t0 = time.time()
        try:
            req = urllib.request.Request("https://api.siliconflow.cn/v1/chat/completions", data=json.dumps({**base, **extra}).encode(), headers=H)
            j = json.load(urllib.request.urlopen(req, timeout=90))
            txt = re.sub(r"(?s)<think>.*?</think>", "", j["choices"][0]["message"].get("content") or "").strip()
            return txt, round(time.time() - t0, 1)
        except urllib.error.HTTPError as e:
            if e.code == 400 and extra: continue
            return "ERROR HTTP %s" % e.code, round(time.time() - t0, 1)
        except Exception as e:
            return "ERROR " + str(e)[:80], round(time.time() - t0, 1)

cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
try: op.open(urllib.request.Request("https://www.bilibili.com/", headers={"User-Agent": UA}), timeout=15).read(2000)
except Exception: pass
norm = lambda s: re.sub(r"<[^>]+>|[\s_\-·•]", "", s or "").lower()
def bapi(params):
    req = urllib.request.Request("https://api.bilibili.com/x/web-interface/search/type?" + urllib.parse.urlencode(params),
                                 headers={"User-Agent": UA, "Referer": "https://search.bilibili.com/"})
    time.sleep(1.5)
    j = json.load(op.open(req, timeout=15))
    if j.get("code") != 0: raise Exception("code %s" % j.get("code"))
    return (j.get("data") or {}).get("result") or []

ucache = {}
def check_user(name):
    """在 B站 用户搜索里找“名字相同或几乎相同、粉丝最多”的那个账号；粉丝 ≥1万 才算真实有效的 UP主（名字相同的小号、仿冒号很多）。"""
    if name in ucache: return ucache[name]
    try: res = bapi({"search_type": "bili_user", "keyword": name})
    except Exception as e: ucache[name] = {"status": "未验证", "err": str(e)}; return ucache[name]
    n = norm(name)
    cands = [u for u in res[:20] if norm(u.get("uname")) == n or (len(n) >= 3 and (norm(u.get("uname")).startswith(n) or n.startswith(norm(u.get("uname")))))]
    top = max(res[:20], key=lambda u: u.get("fans") or 0) if res else None
    if not cands:
        r = {"status": "不存在", "top": top and top.get("uname"), "top_fans": top and top.get("fans")}
    else:
        best = max(cands, key=lambda u: u.get("fans") or 0)
        r = {"uname": best.get("uname"), "mid": best.get("mid"), "fans": best.get("fans") or 0}
        r["status"] = "真实" if r["fans"] >= 10000 else "只有小号"
    ucache[name] = r; return r

def covers_city(uname, mid, city):
    """搜“UP主名 + 城市”，看结果里有没有这个 UP主本人发的视频。"""
    try: res = bapi({"search_type": "video", "keyword": f"{uname} {city}"})
    except Exception: return None
    return any(v.get("mid") == mid for v in res[:20])

out = {"models": MODELS, "rec": [], "who": []}
for m in MODELS:
    print("\n==== 模型", m)
    for city, q in CITY + [(None, x) for x in OTHER]:
        txt, sec = chat(m, SYS_REC, q)
        names = [re.sub(r"^[\d\.\-、\)\s*•]+", "", l).strip(" 「」“”\"'") for l in txt.splitlines()] if not txt.startswith("ERROR") else []
        names = [x for x in names if x and len(x) <= 30][:6]
        checks = []
        for x in names:
            c = dict(check_user(x)); c["name"] = x
            if city and c["status"] == "真实": c["city"] = covers_city(c["uname"], c["mid"], city)
            checks.append(c)
        real = sum(c["status"] == "真实" for c in checks)
        cityok = sum(c.get("city") is True for c in checks)
        print(f"[{sec}s] {q[:16]}… 推荐{len(names)}  真实{real}" + (f"  拍过{city}{cityok}" if city else "") + ("  " + txt[:40] if txt.startswith("ERROR") else ""))
        out["rec"].append({"model": m, "city": city, "q": q, "raw": txt, "sec": sec, "checks": checks})
    for f in FAKE:
        txt, sec = chat(m, SYS_WHO, "B站UP主「%s」" % f)
        print(f"[{sec}s] 编造的「{f}」→ {txt[:50]}")
        out["who"].append({"model": m, "name": f, "answer": txt, "sec": sec})
json.dump(out, open("kbtest-result.json", "w"), ensure_ascii=False, indent=1)

L = ["# 第 2 轮：大模型认不认识 B站 UP主", "", "核验：B站用户搜索里名字匹配、粉丝最多的账号，粉丝 ≥1万 才算真实；城市题再搜“UP主名+城市”，看他本人有没有发过相关视频。", ""]
for m in MODELS:
    rs = [r for r in out["rec"] if r["model"] == m]
    cs = [c for r in rs for c in r["checks"]]; ver = [c for c in cs if c["status"] != "未验证"]
    real = [c for c in ver if c["status"] == "真实"]
    cc = [c for r in rs if r["city"] for c in r["checks"] if c["status"] == "真实"]
    cyes = [c for c in cc if c.get("city") is True]
    err = sum(r["raw"].startswith("ERROR") for r in rs)
    uniq = len({c["uname"] for r in rs if r["city"] for c in r["checks"] if c["status"] == "真实"})
    fk = [w for w in out["who"] if w["model"] == m]
    fake_ok = sum(("不知道" in w["answer"]) for w in fk)
    L += [f"## {m}",
          f"- 出错/超时 {err} 题；推荐 {len(cs)} 个，真实有效 {len(real)}（{len(real)/max(1,len(ver))*100:.0f}%），只有小号或不存在 {len(ver)-len(real)}",
          f"- 城市题：真实 UP主里真的拍过该城市的 {len(cyes)}/{len(cc)}（{len(cyes)/max(1,len(cc))*100:.0f}%）；5 个城市一共只推荐了 {uniq} 个不同的 UP主",
          (f"- 真实 UP主粉丝中位数 {int(statistics.median([c['fans'] for c in real])):,}" if real else "- 无"),
          f"- 3 个编造的名字，诚实回答“不知道”的 {fake_ok}/3", ""]
open("kbtest-report.md", "w").write("\n".join(L)); print("\n".join(L))
print("✅ 测完了，报告在 state/kbtest-report.md")
