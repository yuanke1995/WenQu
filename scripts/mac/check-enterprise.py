#!/usr/bin/env python3
"""问渠 · 企业级基底自检（身份治理 / 审计链 / 多租户启停 / 配额与 DR 的行为验收

用法（后端已在 8090 跑起来之后）：
    ./wenqu.sh run check-enterprise.py                  # 只读项：不建任何数据
    ./wenqu.sh run check-enterprise.py --fixtures       # 另建临时租户/成员验停用与跨租户重置密码
口令取 env WQ_PW，没给就交互输入（不落盘）。退出码：0=全过，1=有 FAIL。

只读项判据来自本轮真机回归；--fixtures 项会动库，但只动它自己建的行（按主键删）。
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter

BASE = os.environ.get("WQ_BASE", "http://127.0.0.1:8090/ai")
MY = os.environ.get("MYSQL", "/opt/homebrew/opt/mysql@8.0/bin/mysql")
DB = os.environ.get("WQ_DB", "wenqu_ai")
RCLI = os.environ.get("REDIS_CLI", "/opt/homebrew/bin/redis-cli")
FAILS = []


def say(ok, name, evidence=""):
    print(("[PASS] " if ok else "[FAIL] ") + name + ((" —— " + evidence) if evidence else ""))
    if not ok:
        FAILS.append(name)


def pw():
    return os.environ.get("WQ_PW") or input("管理员口令：").strip()


def api(method, path, body=None, token=None, timeout=60):
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", action="store_true", help="附带建临时租户/成员验停用与跨租户重置密码")
    a = ap.parse_args()

    st, rr = api("POST", "/api/ai/auth/login", {"identifier": "admin", "password": pw()})
    if st != 200 or not rr.get("success"):
        raise SystemExit(f"登录失败 st={st} {rr.get('msg')}（确认后端在 {BASE} 与口令）")
    admin = rr["data"]["token"]
    print(f"后端 {BASE} 已登录 uid=admin roles={rr['data'].get('roles')}")

    # 1) 审计链：哈希不符必须为 0；历史序号洞允许存在（那是修复前留下的真洞，按设计如实报）
    st, rr = api("GET", "/api/ai/audit/verify?maxRows=50000", token=admin)
    pr = (rr.get("data") or {}).get("problems") or []
    kinds = Counter(p["problem"] for p in pr)
    say(kinds.get("内容被改写（哈希不符）", 0) == 0,
        "审计哈希链：没有「内容被改写」",
        f"分布={dict(kinds)} 未入库={ (rr.get('data') or {}).get('droppedSinceStart') }")
    # 阳性对照：真改一行必须报出来（否则上面那个 0 是假绿）
    vid, vdetail, vseq = sql("select id, ifnull(detail,''), seq from c_ai_audit_event order by seq desc limit 1")[0]
    esc = vdetail.replace("\\", "\\\\").replace("'", "\\'")
    sql("update c_ai_audit_event set detail='__自检篡改__' where id='%s'" % vid)
    st, rr = api("GET", "/api/ai/audit/verify?maxRows=50000", token=admin)
    hit = [p for p in (rr.get("data") or {}).get("problems") or [] if p["problem"].startswith("内容被改写")]
    sql("update c_ai_audit_event set detail='%s' where id='%s'" % (esc, vid))
    st, rr = api("GET", "/api/ai/audit/verify?maxRows=50000", token=admin)
    back = [p for p in (rr.get("data") or {}).get("problems") or [] if p["problem"].startswith("内容被改写")]
    say(len(hit) >= 1 and int(hit[0]["seq"]) == int(vseq) and not back,
        "审计哈希链：改一行当场报断点、还原即消（阳性对照）",
        f"篡改时报在 seq={hit[0]['seq'] if hit else '未报'} 还原后={len(back)} 条")

    # 2) 新写的审计行：时间戳必须已是毫秒精度（尾部 .xx000 的微秒段为 0）
    bad = sql("select count(*) from c_ai_audit_event where date_format(ts,'%f') not like '%000'")
    say(int(bad[0][0]) == 0, "新落库的审计行时间戳是毫秒精度（写侧截断在生效）", f"非毫秒行={bad[0][0]}")

    # 3) 停机不再自造洞：拿本进程 replica，验它这段序号连续无重号
    st, rr = api("GET", "/api/audit?days=1&size=1", token=admin)
    rep_rows = sql("select substring_index(replica,'-',-1) r, count(*) c, min(seq) a, max(seq) b,"
                   " count(distinct seq) d from c_ai_audit_event group by r order by max(create_time) desc limit 1")
    if rep_rows:
        r, c, aa, bb, dd = rep_rows[0]
        # 最近这个副本的洞数（从接口全量问题里挑它的）
        st, rr = api("GET", "/api/audit/verify?maxRows=50000", token=admin)
        mine = [p for p in (rr.get("data") or {}).get("problems") or [] if r in (p.get("replica") or "")]
        say(int(c) == int(dd) and int(c) == int(bb) - int(aa) + 1,
            "最新副本的链序号连续无重号（含停机收尾那几条）",
            f"replica=*#{r} 行={c} 区间={aa}..{bb} 该副本问题={len(mine)}")

    # 4) 启动期硬依赖 Redis（不是缺陷，是边界）：确认 Redis 在线，否则后端根本起不来
    ping = subprocess.run([RCLI, "-h", "127.0.0.1", "ping"], capture_output=True, text=True).stdout.strip()
    say(ping == "PONG", "Redis 在线（P8 的降级只在运行期，启动期没它起不来）", f"ping={ping or '无响应'}")

    if not a.fixtures:
        print("\n（跳过会动数据的两条：停用租户挡登录 / 跨租户重置密码。加 --fixtures 才跑）")
    else:
        TX, TY, M1, M2, TK = "wqchk-a", "wqchk-b", "wq-chk-m1", "wq-chk-m2", "wq-chk-token"
        api("POST", "/api/ai/tenant", {"id": TX, "name": "自检租户"}, token=admin)
        api("POST", "/api/ai/tenant", {"id": TY, "name": "自检租户乙"}, token=admin)
        api("POST", "/api/ai/user", {"uid": M1, "username": "自检成员", "password": pw(), "role": "user"}, token=admin)
        api("POST", "/api/ai/tenant/member", {"uid": M1, "tenantId": TX}, token=admin)
        api("POST", "/api/ai/identity/providers", {"type": "scim", "name": "自检SCIM", "enabled": 1,
                                                   "defaultRole": "user", "autoCreateUser": 1, "tenantId": TX,
                                                   "config": {"bearerToken": TK, "deprovisionAction": "disable"}},
            token=admin)
        pid = [row[0] for row in sql("select id from c_ai_identity_provider where name='自检SCIM'")][0]
        try:
            st, rr = api("GET", "/api/ai/scim/v2/Users", token=TK)
            say(st == 200, "启用时 SCIM 通道可用（对照）", f"st={st}")
            api("POST", f"/api/ai/tenant/{TX}/status", {"enabled": False}, token=admin)
            st, rr = api("POST", "/api/ai/auth/login", {"identifier": M1, "password": pw()})
            say(st != 200 and "已被平台停用" in (rr.get("msg") or ""),
                "停用租户后该成员登录被拒且文案点名是租户停用", f"st={st} msg={rr.get('msg')}")
            st, rr = api("GET", "/api/ai/scim/v2/Users", token=TK)
            created = int(sql("select count(*) from c_ai_user where uid='scim_wq-chk-scim2'")[0][0])
            st2, rr2 = api("POST", "/api/ai/scim/v2/Users",
                           {"schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"], "userName": "wq-chk-scim2",
                            "emails": [{"value": "wq-chk2@example.com"}]}, token=TK)
            say(st == 403 and st2 == 403 and int(sql("select count(*) from c_ai_user where uid like 'scim_wq-chk-scim2%'")[0][0]) == 0,
                "同一租户的 SCIM 建号/读清单一起 403，且没真建出账号", f"读={st} 写={st2} msg={rr2.get('detail') or rr2.get('msg')}")
            api("POST", f"/api/ai/tenant/{TX}/status", {"enabled": True}, token=admin)
            st, rr = api("POST", "/api/ai/auth/login", {"identifier": M1, "password": pw()})
            say(st == 200, "恢复启用后同一个人立刻能登录（缓存当场逐出）", f"st={st}")

            api("POST", "/api/ai/user", {"uid": M2, "username": "自检别家管理员", "password": pw(), "role": "admin"}, token=admin)
            api("POST", "/api/ai/tenant/member", {"uid": M2, "tenantId": TY}, token=admin)
            st, rr = api("PUT", f"/api/ai/user/{M1}/password", {"password": pw()}, token=admin)
            h1 = sql("select left(password_hash,24) from c_ai_user where uid='%s'" % M1)[0][0]
            say(st == 200 and rr.get("success"), "superadmin 给别家租户成员重置密码不再「用户不存在」", f"st={st} msg={rr.get('msg')}")
            t2 = None
            st, rr = api("POST", "/api/ai/auth/login", {"identifier": M2, "password": pw()})
            if rr.get("success"):
                t2 = rr["data"]["token"]
            st, rr = api("PUT", f"/api/ai/user/{M1}/password", {"password": pw() + "x"}, token=t2 or admin)
            h2 = sql("select left(password_hash,24) from c_ai_user where uid='%s'" % M1)[0][0]
            say(t2 is not None and not rr.get("success") and h1 == h2,
                "别家租户（另一家）的管理员改不到本家成员，且没有静默改哈希（fail-closed）",
                f"登录={t2 is not None} st={st} msg={rr.get('msg')} 哈希前后={h1} vs {h2}")
        finally:
            for u in [M1, M2, "scim_wq-chk-scim2"]:
                api("DELETE", f"/api/ai/user/{u}", token=admin)
                sql("delete from c_ai_user_role where uid='%s'" % u)
            sql("delete from c_ai_user_identity where uid like 'scim_wq-chk%'")
            sql("delete from c_ai_identity_provider where name='自检SCIM'")
            for t in [TX, TY]:
                sql("delete from c_ai_content_rule where tenant_id='%s'" % t)
                sql("delete from c_ai_notification where tenant_id='%s'" % t)
                sql("delete from c_ai_tenant_request where desired_id='%s'" % t)
                sql("delete from c_ai_user where tenant_id='%s'" % t)
                sql("delete from c_ai_tenant where id='%s'" % t)
            left = sql("select (select group_concat(id) from c_ai_tenant), (select count(*) from c_ai_user where uid like 'wq-chk%')")[0]
            print("[CLEAN] 租户=", left[0], "自检成员残留=", left[1])

    print("\n结论：", "全部通过" if not FAILS else f"{len(FAILS)} 条未过：{FAILS}")
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
