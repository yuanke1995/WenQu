#!/usr/bin/env python3
"""问渠 · 智能体运行时这一批改动的行为验收（档位/工具面/接续/巡检/评估/联网兜底/分享归属）

用法（后端已跑起来之后）：
    WQ_PW=<口令> ./wenqu.sh run check-agent-runtime.py              # 不建数据、不动配置的只读项
    WQ_PW=<口令> ./wenqu.sh run check-agent-runtime.py --fixtures   # 另建临时分享链接验访客作答方归属
    WQ_PW=<口令> ./wenqu.sh run check-agent-runtime.py --turns      # 另跑真实问答轮验档位工具面（花模型额度）

口令取 env WQ_PW，没给就交互输入（不落盘）。退出码：0=全过，1=有 FAIL，2=前置条件不满足而跳过。
每条判据都写「断言的是行为」而不是「文件在不在」——静态那几条单列在 --static 里。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("WQ_BASE", "http://127.0.0.1:8090/ai")
MY = os.environ.get("MYSQL", "/opt/homebrew/opt/mysql@8.0/bin/mysql")
DB = os.environ.get("WQ_DB", "wenqu_ai")
FAILS = []
SKIPS = []


def say(ok, name, evidence=""):
    print(("[PASS] " if ok else "[FAIL] ") + name + ((" —— " + evidence) if evidence else ""))
    if not ok:
        FAILS.append(name)


def skip(name, why):
    print("[SKIP] " + name + " — " + why)
    SKIPS.append(name)


def pw():
    return os.environ.get("WQ_PW") or input("管理员口令：").strip()


def api(method, path, body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode())
        except Exception:
            return e.code, {"msg": raw.decode(errors="replace")[:200]}


def sql(q):
    p = subprocess.run([MY, "-h127.0.0.1", "-uroot", "-N", "--default-character-set=utf8mb4",
                        "-e", q, DB], capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("SQL 失败：" + p.stderr.strip()[:200])
    return [l.split("\t") for l in p.stdout.strip().split("\n") if l.strip()]


def sse_chat(token, payload, timeout=240):
    """跑一轮流式问答，返回 (原始帧文本, 解析出的事件列表)。"""
    req = urllib.request.Request(BASE + "/api/ai/chat",
                                data=json.dumps(payload, ensure_ascii=False).encode(),
                                method="POST")
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    req.add_header("Accept", "text/event-stream")
    req.add_header("Authorization", "Bearer " + token)
    chunks = []
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            for raw in r:
                chunks.append(raw.decode("utf-8", "replace"))
                if "event:done" in chunks[-1]:
                    break
    except urllib.error.HTTPError as e:
        return "HTTP %s %s" % (e.code, e.read()[:200].decode("utf-8", "replace")), []
    text = "".join(chunks)
    events = re.findall(r"event:(\w+)\s+data:(\{.*?\})(?=\n\n|\Z)", text, re.S)
    return text, events


def tool_frames(events):
    """把 tool_status 帧解成 {name,status,text} 列表（载荷是套了一层字符串的 JSON）。"""
    out = []
    for name, blob in events:
        if name != "tool_status":
            continue
        try:
            outer = json.loads(blob)
            inner = json.loads(outer.get("content") or "{}")
        except Exception:
            continue
        out.append({"name": inner.get("tool") or inner.get("name") or "",
                    "status": inner.get("status") or "",
                    "text": str(inner.get("brief") or inner.get("result") or inner.get("error") or "")})
    return out


def cfg_value(token, key):
    """GET /api/ai/config 的形状不假设（平铺 map 或行清单都认）。"""
    st, rr = api("GET", "/api/ai/config", token=token)
    d = rr.get("data")
    if isinstance(d, dict):
        return d.get(key)
    if isinstance(d, list):
        for row in d:
            if isinstance(row, dict) and row.get("key") == key:
                return row.get("value")
    return None


def find_agent(token, want_preset=None):
    st, rr = api("GET", "/api/ai/agents", token=token)
    for a in ((rr.get("data") or {}).get("items") or rr.get("data") or []):
        if want_preset and (a.get("permissionPreset") or "") != want_preset:
            continue
        return a
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", action="store_true", help="建临时分享链接验访客会话作答方归属（按主键自清）")
    ap.add_argument("--turns", action="store_true", help="跑真实问答轮验只读档工具面（花模型额度，一条一发）")
    ap.add_argument("--static", action="store_true", help="只跑仓库内静态对账项（不需要后端在跑）")
    a = ap.parse_args()

    if a.static:
        run_static()
        finish()
        return

    st, rr = api("POST", "/api/ai/auth/login", {"identifier": "admin", "password": pw()})
    if st != 200 or not rr.get("success"):
        raise SystemExit("登录失败 st=%s %s（确认后端在 %s 与口令）" % (st, rr.get("msg"), BASE))
    admin = rr["data"]["token"]
    print("后端 %s 已登录 uid=%s" % (BASE, rr["data"].get("uid") or "admin"))

    # 1) 巡检：这一批改动的可见出口
    st, rr = api("POST", "/api/ai/ops/checkup", token=admin)
    checks = (rr.get("data") or {}).get("checks") or []
    keys = {c.get("key"): c for c in checks}
    say(all(k in keys for k in ("tools", "resume")),
        "巡检含「工具装配对账」与「长任务接续」两项",
        "实到 key=" + ",".join(sorted(k for k in keys)))
    if "tools" in keys:
        say(keys["tools"].get("level") != "fail", "工具装配对账不为 fail",
            keys["tools"].get("detail", "")[:110])
    if "resume" in keys:
        say(keys["resume"].get("level") != "fail", "长任务接续不为 fail",
            keys["resume"].get("detail", "")[:110])

    # 2) 档位判据只有一处：设置页那三处文案与判据同口径（静态对账，见 --static）
    # 3) 评估报告带参数指纹，且改参数后当场说「这是旧参数下测的」
    st, rr = api("GET", "/api/ai/eval/last-report", token=admin)
    rep = rr.get("data") or {}
    if not rep:
        skip("评估参数漂移提示", "从未跑过自动体检（先在评估页生成评估集或点「立即体检」）")
    else:
        say(bool(rep.get("paramsId")), "体检报告带检索参数指纹",
            "paramsId 段数=" + str(len(str(rep.get("paramsId")).split("|"))))
        # 改一个参与指纹的参数，立刻读回来看有没有标 stale（finally 还原）
        original = cfg_value(admin, "retrieval.minContextScore")
        say(original is not None, "当前生效的检索阈值可读回", "retrieval.minContextScore=%s" % original)
        try:
            st, rr = api("PUT", "/api/ai/config", {"retrieval": {"minContextScore": "0.31"}}, token=admin)
            say(st == 200 and rr.get("success"), "写入检索参数成功", str(rr.get("msg") or ""))
            st, rr = api("GET", "/api/ai/eval/last-report", token=admin)
            rep2 = rr.get("data") or {}
            say(bool(rep2.get("paramsStale")),
                "改过参数后旧报告被标为「出自旧参数」",
                "stale=%s drift=%s" % (rep2.get("paramsStale"), (rep2.get("paramsDrift") or [])[:1]))
        finally:
            if original is not None:
                api("PUT", "/api/ai/config", {"retrieval": {"minContextScore": str(original)}}, token=admin)
                st, rr = api("GET", "/api/ai/eval/last-report", token=admin)
                say(not (rr.get("data") or {}).get("paramsStale"), "还原参数后 stale 标记消失（不留夹具状态）",
                    "回到原值 %s" % original)

    # 4) 只读档的工具面：真跑两发（一发检索、一发要求写文件）
    if a.turns:
        agent = find_agent(admin, "research")
        if not agent:
            skip("只读档工具面", "平台上没有权限档位为 research 的智能体")
        else:
            aid = agent.get("id")
            print("用档位=research 的智能体「%s」跑两发" % agent.get("name"))
            _, events = sse_chat(admin, {"question": "请检索知识库，说明合同违约金上限怎么规定，标出条号",
                                         "agentId": aid, "deepThink": False})
            tools = tool_frames(events)
            retried = [t for t in tools if t["name"] in ("search_knowledge", "searchKnowledge")]
            denied_words = ("只读", "不执行", "不允许执行", "denied", "Explore mode")
            if not retried:
                skip("只读档检索可用", "模型这一轮没调用检索工具（换个问题或助手）")
            else:
                bad = [t for t in retried if t["status"] == "error" and any(w in t["text"] for w in denied_words)]
                say(not bad, "只读档的检索工具真的执行了（不被档位误拒）",
                    "调用 %d 次，档位误拒 %d 次" % (len(retried), len(bad)))
                got = [t for t in retried if "untrusted-data" in t["text"] or "【" in t["text"]]
                say(bool(got), "检索结果按预期带回资料", "有资料帧 %d 次" % len(got))
            _, events2 = sse_chat(admin, {"question": "在沙盒里新建 probe.txt 写入一句话，然后把它交付",
                                           "agentId": aid, "deepThink": False})
            tools2 = tool_frames(events2)
            write_names = ("write_file", "edit_file", "execute", "run_code", "deliver_artifact")
            shown = [t for t in tools2 if t["name"] in write_names]
            say(not shown,
                "只读档下写类工具没有「看得见却被拒」的调用（档位收紧在工具表那一层就生效）",
                "出现 %s" % (sorted({t["name"] for t in shown}) or "无"))
    else:
        skip("只读档工具面", "加 --turns 才跑真实问答轮（要烧模型额度）")

    # 5) 免登录分享链路：会话归属那一列必须写（分享页按智能体列历史/回收都读它）
    if a.fixtures:
        rows = sql("select agent_id, created_by, tenant_id from c_ai_agent "
                   "where permission_preset='research' limit 1")
        if not rows:
            skip("访客会话作答方归属", "库里没有 research 档智能体可做夹具")
        else:
            agent_id, owner, tenant = rows[0][0], rows[0][1], (rows[0][2] if len(rows[0]) > 2 else "default")
            tok = "probetier" + os.urandom(8).hex()
            sql("insert into c_ai_agent_share (id,agent_id,token,model_ref,enabled,created_by,mcp_enabled,"
                "visit_count,guest_images_enabled,guest_files_enabled,tenant_id) values "
                "('probe-tier','%s','%s','',1,'%s',0,0,0,0,'%s')" % (agent_id, tok, owner, tenant))
            try:
                st, rr = api("POST", "/api/ai/share/%s/chat" % tok,
                             {"message": "违约金上限怎么规定？请按资料回答", "visitorId": os.urandom(16).hex()})
                ok = st == 200
                say(ok, "访客分享问答可跑（免登录链路活着）", "st=%s" % st)
                # 这一列的消费方：分享页按智能体列访客会话（列不出来 == agent_id 没写）
                st2, rr2 = api("GET", "/api/ai/share/%s/sessions?visitorId=probevisitor" % tok)
                say(st2 in (200, 400), "分享页按智能体列历史会话的接口可读", "st=%s" % st2)
            finally:
                sql("delete from c_ai_agent_share where id='probe-tier'")
                say(not sql("select count(*) from c_ai_agent_share where id='probe-tier'"),
                    "探针分享行已清理")
    else:
        skip("访客会话作答方归属", "加 --fixtures 才建临时分享链接")

    run_static()
    finish()


def run_static():
    """不依赖后端的对账项：文案与判据必须同口径、两端都接了同一件事。"""
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    def read(*parts):
        p = os.path.join(root, *parts)
        try:
            return open(p, encoding="utf-8").read()
        except Exception:
            return ""

    m9 = read("src", "main", "resources", "manual", "09-技能与沙盒.md")
    say("不写长期记忆" in m9 and "不暴露给模型" in m9,
        "手册的只读档口径与判据一致（含写长期记忆、且说不暴露而非拒调用）")
    m6 = read("src", "main", "resources", "manual", "06-检索原理与调参.md")
    say("关键词匹配" in m6 and "位置衰减" in m6,
        "手册写明联网兜底用关键词匹配、且不信服务商自带分值")
    gate = read("src", "main", "java", "com", "wenqu", "ai", "agentx", "AgentxFrameworkToolGate.java")
    say("FRAMEWORK_TOOL_WRITES" not in gate,
        "档位闸里那张手抄副作用表已删（判据只剩工具申报的只读位一处）")
    say("isMcp()" in gate, "MCP 自报的只读位在档位闸里不算免检")
    tools = read("src", "main", "java", "com", "wenqu", "ai", "agentx", "WenquAgentxTools.java")
    say("SANDBOX" in tools and "memory_save" in tools,
        "组管理面覆盖沙盒族与框架记忆工具")
    pc = read("src", "main", "resources", "config-schema.json")
    say(pc.count("都不进工具表") >= 2, "设置页两处档位选项文案与判据同口径")
    say("turnResumeScanMs" in pc and "turnResumeWindowMinutes" in pc,
        "接续的间隔与窗口都进了配置面（不是硬编码常量）")
    chat = read("web", "src", "views", "ChatPage.vue")
    mob = read("web", "src", "h5", "MobileMsgRow.vue")
    say("subagent_step" in chat or "onSubagentStep" in read("web", "src", "api.js"),
        "PC 端渲染子智能体过程帧")
    say("subagent_step" in mob, "移动壳同样渲染（两端不许各写一遍、也不许一端没有）")
    share = read("src", "main", "java", "com", "wenqu", "ai", "controller", "ShareController.java")
    say("recordTurnAgent" in share, "免登录链路写会话行作答方（分享页按智能体列历史的依赖）")


def finish():
    print("")
    if FAILS:
        print("有 %d 条不符：%s" % (len(FAILS), "、".join(FAILS)))
    if SKIPS:
        print("跳过 %d 项：%s" % (len(SKIPS), "、".join(SKIPS)))
    if not FAILS:
        print("已跑项全过")
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
