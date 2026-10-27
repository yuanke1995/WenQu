#!/usr/bin/env python3
"""问渠 WenQu · 按租户备份与恢复（Phase 4 刀三 · DR）

为什么按租户而不是整库：多租户部署里「客户 X 要恢复上周三的状态」是真实请求，
而整库回滚会把别家客户一起倒回去。租户的隔离是行级归属（全表 tenant_id），
盘上目录却按成员/uuid 分片，所以一次可用的备份必须同时含：
  1) 该租户名下每张表的行（mysqldump --where）
  2) 该租户成员在盘上的目录（记忆 / 沙盒工作区 / 产物 / 附件 / 外溢原文 / 自装技能）
  3) 一份清单（manifest.json）：行数、字节数、恢复后要核对什么

**目录映射不在这个脚本里重算**：盘上路径由应用的 `GET /api/ai/tenant/{id}/inventory` 报出来
（路径规则只活在各服务里，脚本抄一份必然漂移）。因此备份时要能访问后端。

用法：
  ./tenant-dr.py list
  ./tenant-dr.py inventory --tenant acme
  ./tenant-dr.py backup   --tenant acme --out /backup/wenqu
  ./tenant-dr.py backup   --all        --out /backup/wenqu
  ./tenant-dr.py restore  --from /backup/wenqu/20261028T010000-acme --into-db wenqu_dr_test
  ./tenant-dr.py verify   --from /backup/wenqu/20261028T010000-acme

连接参数默认取环境变量（与容器一致）：DB_HOST DB_PORT DB_USER DB_PASSWORD DB_NAME
后端参数：WENQU_BASE（默认 http://127.0.0.1:8080/ai/api/ai）
         WENQU_ADMIN / WENQU_ADMIN_PASSWORD（脚本自己登录拿令牌）
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

TABLE_NAME_RE = re.compile(r"^[A-Za-z0-9_]+$")


def die(msg, code=2):
    print("错误：" + msg, file=sys.stderr)
    sys.exit(code)


def env(name, default=None):
    v = os.environ.get(name)
    return v if v not in (None, "") else default


# ---------------------------------------------------------------- 后端调用

class Backend:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.user = user
        self.password = password
        self.token = None

    def _req(self, path, body=None, method=None):
        req = urllib.request.Request(
            self.base + path,
            data=None if body is None else json.dumps(body).encode(),
            method=method or ("POST" if body is not None else "GET"))
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            die("后端 %s 返回 %s：%s" % (path, e.code, e.read().decode()[:200]))
        except Exception as e:
            die("连不上后端 %s（备份需要应用在线，目录清单由它报）：%s" % (self.base, e))

    def login(self):
        if not self.user or not self.password:
            die("要访问后端就得给登录凭据：--login 用户 加环境变量 WENQU_ADMIN_PASSWORD，或 --token 直接给令牌")
        r = self._req("/auth/login", {"identifier": self.user, "password": self.password})
        if not r.get("success"):
            die("登录失败：" + str(r.get("msg")))
        self.token = r["data"]["token"]

    def tenants(self):
        r = self._req("/tenant")
        if not r.get("success"):
            die("租户清单读取失败：" + str(r.get("msg")))
        return r["data"] or []

    def inventory(self, tenant):
        r = self._req("/tenant/%s/inventory" % tenant)
        if not r.get("success"):
            die("租户落点清单读取失败：" + str(r.get("msg")))
        return r["data"] or {}


# ---------------------------------------------------------------- MySQL

class Db:
    def __init__(self, args):
        self.host = args.db_host or env("DB_HOST", "127.0.0.1")
        self.port = str(args.db_port or env("DB_PORT", "3306"))
        self.user = args.db_user or env("DB_USER", "root")
        self.password = args.db_password or env("DB_PASSWORD", "")
        self.name = args.db_name or env("DB_NAME", "wenqu_ai")

    def _env(self):
        e = dict(os.environ)
        if self.password:
            e["MYSQL_PWD"] = self.password   # 不把口令放进进程参数（ps 能看见）
        return e

    def base_args(self):
        return ["--host=" + self.host, "--port=" + self.port, "--user=" + self.user]

    def query(self, sql):
        p = subprocess.run(["mysql"] + self.base_args() + ["-N", "-B", "-e", sql],
                           capture_output=True, text=True, env=self._env())
        if p.returncode:
            die("SQL 失败：%s" % p.stderr.strip()[:300])
        return p.stdout.strip()

    def run_script(self, path, into_db):
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            p = subprocess.run(["mysql"] + self.base_args() + [into_db],
                               stdin=f, capture_output=True, text=True, env=self._env())
        if p.returncode:
            die("导入 %s 失败：%s" % (path, p.stderr.strip()[:300]))

    def table_counts(self, db, table, tenant=None):
        if not TABLE_NAME_RE.match(table):
            die("非法表名（不该出现）：" + table)
        where = (" where tenant_id='%s'" % tenant.replace("'", "''")) if tenant else ""
        out = self.query("select count(*) from `%s`.`%s`%s" % (db, table, where))
        return int(out or "0")


# ---------------------------------------------------------------- 子命令

def cmd_list(be, args):
    rows = be.tenants()
    print("%-14s %-6s %-6s %s" % ("租户", "状态", "成员", "显示名"))
    for t in rows:
        print("%-14s %-6s %-6s %s" % (t.get("id"), "启用" if t.get("status", 1) == 1 else "停用",
                                      t.get("members", 0), t.get("name") or ""))
    if len(rows) <= 1:
        print("\n（只有一个租户：这就是单租户部署，备份按整库走 --all 更省事）")


def print_inventory(inv, show_missing=True):
    print("租户 %s：成员 %s 人，本租户行 %s 条，盘上 %s 个目录（%s 个不存在），字节 %s"
          % (inv.get("tenantId"), len(inv.get("members") or []), inv.get("rows"),
             len(inv.get("files") or []), inv.get("filesMissing"),
             format_bytes(inv.get("filesBytes"))))
    print("\n表与行数：")
    for t in inv.get("tables") or []:
        print("  %-34s %s" % (t.get("table"), t.get("rows", t.get("error"))))
    if show_missing:
        print("\n盘上目录（只列存在的）：")
        for f in inv.get("files") or []:
            if f.get("exists"):
                print("  %-28s %-10s %s" % (f.get("kind"), format_bytes(f.get("bytes")), f.get("path")))
        miss = [f for f in (inv.get("files") or []) if not f.get("exists")]
        if miss:
            print("  （另有 %d 个目录不存在——该成员可能从没写过文件，也可能路径配错，现场判断）" % len(miss))
    print("\nRedis 键形态（向量索引要按库重建，限流桶是临时计数）：")
    for k in inv.get("redisKeyPatterns") or []:
        print("  " + k)


def format_bytes(n):
    n = float(n or 0)
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if n < 1024 or unit == "TB":
            return ("%.1f%s" % (n, unit)) if unit != "B" else "%dB" % n
        n /= 1024.0


def cmd_inventory(be, args):
    print_inventory(be.inventory(args.tenant))


def mysqldump_tenant(db, out_dir, table, tenant):
    where = "tenant_id='%s'" % tenant.replace("'", "''")
    path = out_dir / (table + ".sql")
    with open(path, "w", encoding="utf-8") as f:
        env_ = dict(os.environ)
        if db.password:
            env_["MYSQL_PWD"] = db.password
        cmd = (["mysqldump"] + db.base_args() + ["--no-tablespaces", "--skip-add-locks",
               "--complete-insert", "--no-create-info", "--default-character-set=utf8mb4",
               "--where", where, db.name, table])
        p = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, text=True, env=env_)
    if p.returncode:
        die("mysqldump %s 失败：%s" % (table, p.stderr.strip()[:300]))
    return path


def cmd_backup(be, db, args):
    if not args.out:
        die("--out 要给目录")
    all_mode = bool(args.all)
    if all_mode and args.tenant:
        die("--all 与 --tenant 二选一")
    tenant = args.tenant
    stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
    name = ("all" if all_mode else tenant)
    bundle = Path(args.out) / ("%s-%s" % (stamp, name))
    (bundle / "db").mkdir(parents=True, exist_ok=True)
    (bundle / "structure").mkdir(parents=True, exist_ok=True)
    (bundle / "files").mkdir(parents=True, exist_ok=True)

    # 结构单独导一份：库里可能有退役设计留下的列（实体已不映射），
    # 只按 schema.sql 建表会让 --complete-insert 的 INSERT 找不到列
    # 结构单独放 structure/ 目录：它和逐表数据文件同放一个目录会被恢复时的通配一起吞掉，
    # 那份 DDL 带着 DROP TABLE，跑在数据之后等于把刚灌进去的行抹平（真机踩过）
    with open(bundle / "structure" / "ddl.sql", "w", encoding="utf-8") as f:
        env0 = dict(os.environ)
        if db.password:
            env0["MYSQL_PWD"] = db.password
        p0 = subprocess.run(["mysqldump"] + db.base_args() + ["--no-data", "--no-tablespaces",
                            "--skip-add-locks", "--routines", "--default-character-set=utf8mb4",
                            db.name], stdout=f, stderr=subprocess.PIPE, text=True, env=env0)
        if p0.returncode:
            die("结构导出失败：" + p0.stderr.strip()[:300])

    inv = None if all_mode else be.inventory(tenant)
    tables = []
    if all_mode:
        tables = [r for r in db.query(
            "select table_name from information_schema.tables where table_schema='%s' "
            "and table_type='BASE TABLE' order by table_name" % db.name).split("\n") if r.strip()]
        with open(bundle / "db" / "full.sql", "w", encoding="utf-8") as f:
            env_ = dict(os.environ)
            if db.password:
                env_["MYSQL_PWD"] = db.password
            p = subprocess.run(["mysqldump"] + db.base_args() + ["--no-tablespaces", "--routines",
                               "--events", "--single-transaction", "--default-character-set=utf8mb4",
                               "--databases", db.name], stdout=f, stderr=subprocess.PIPE, text=True, env=env_)
            if p.returncode:
                die("整库导出失败：" + p.stderr.strip()[:300])
    else:
        for t in inv.get("tables") or []:
            if t.get("error"):
                print("  跳过读不动的表 %s：%s" % (t.get("table"), t.get("error")))
                continue
            tables.append(t)
            mysqldump_tenant(db, bundle / "db", t["table"], tenant)
        # 租户注册行本身（恢复时要先有它，成员才有个归属）
        with open(bundle / "db" / "c_ai_tenant.sql", "w", encoding="utf-8") as f:
            env_ = dict(os.environ)
            if db.password:
                env_["MYSQL_PWD"] = db.password
            p = subprocess.run(["mysqldump"] + db.base_args() + ["--no-tablespaces", "--skip-add-locks",
                               "--complete-insert", "--no-create-info", "--default-character-set=utf8mb4",
                               "--where", "id='%s'" % tenant, db.name, "c_ai_tenant"],
                               stdout=f, stderr=subprocess.PIPE, text=True, env=env_)
            if p.returncode:
                die("租户注册行导出失败：" + p.stderr.strip()[:300])

    existing = [f for f in (inv or {}).get("files") or [] if f.get("exists")]
    tar_path = bundle / "files" / ("tenant-%s.tar.gz" % tenant if tenant else "all.tar.gz")
    if existing:
        roots = {}
        for f in existing:
            p = Path(f["path"])
            if not p.exists():
                continue
            roots[str(p)] = f
        with tarfile.open(tar_path, "w:gz") as tar:
            for path in sorted(roots):
                # arcname = 绝对路径去掉开头的 /：解出来的结构自描述，
                # 既能解到临时目录看内容，也能 --files-in-place 原样落回本机
                tar.add(path, arcname=path.lstrip("/"))
    else:
        tar_path = None
        if all_mode:
            print("提示：--all 只导数据库。整平台的盘上文件请备份卷（docker-compose 的 user-data / app-data / mysql-data），"
                  "按租户恢复用 --tenant <id>。")

    manifest = {
        "kind": "wenqu-tenant-backup",
        "version": 1,
        "createdAt": datetime.now().isoformat(timespec="seconds"),
        "tenantId": tenant,
        "allTenants": all_mode,
        "sourceDb": {"host": db.host, "port": db.port, "name": db.name},
        "inventory": inv,
        "structure": str(bundle / "structure" / "ddl.sql"),
        "tables": [{"table": t["table"], "rows": t.get("rows")} for t in tables] if not all_mode
                   else [{"full": str(bundle / "db" / "full.sql")}],
        "filesTar": str(tar_path) if tar_path else None,
        "fileDirs": [{"path": f["path"], "kind": f["kind"], "uid": f.get("uid"),
                      "bytes": f["bytes"], "files": f["files"]} for f in existing],
    }
    (bundle / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("已写出备份包：", bundle)
    if inv:
        print_inventory(inv, show_missing=False)
    print("\n下一步核对：./tenant-dr.py verify --from %s" % bundle)


def read_manifest(path):
    m = Path(path) / "manifest.json"
    if not m.is_file():
        die("目录里没有 manifest.json：" + str(path))
    return json.loads(m.read_text(encoding="utf-8"))


def cmd_verify(db, args):
    man = read_manifest(args.from_dir)
    inv = man.get("inventory") or {}
    if not inv:
        print("整库备份没有逐租户清单，verify 只适用按租户包")
        return
    db_name = args.into_db or db.name
    bad = []
    for t in man.get("tables") or []:
        if t.get("rows") is None:
            continue
        want = int(t.get("rows") or 0)
        got = db.table_counts(db_name, t["table"], man["tenantId"])
        flag = "OK " if got == want else "差异"
        if got != want:
            bad.append(t["table"])
        print("  %s %-34s 备份 %s 现值 %s" % (flag, t["table"], want, got))
    print("\n核对结果：", "全部一致" if not bad else "不一致的表：" + ", ".join(bad))
    if bad:
        sys.exit(1)


def cmd_restore(db, args):
    man = read_manifest(args.from_dir)
    tenant = man.get("tenantId")
    into = args.into_db or db.name
    if not TABLE_NAME_RE.match(into):
        die("非法库名：" + into)
    fresh = into != db.name
    if args.skip_db and args.skip_files:
        die("--skip-db 与 --skip-files 不能同时给，那样等于什么都不做")

    print("恢复租户 %s ← %s" % (tenant, args.from_dir))
    if fresh:
        print("目标库 %s 与来源库不同：按新库建库并先装 schema.sql" % into)
        if not args.yes:
            die("要往一个新库里灌数据，加 --yes 确认（避免误写回原库）")
        db.query("create database if not exists `%s` default charset utf8mb4 collate utf8mb4_0900_ai_ci" % into)
        ddl = Path(args.from_dir) / "structure" / "ddl.sql"
        if ddl.is_file() and not args.schema:
            print("  用备份包自带的结构（%s）建表——库里可能有 schema.sql 没有的列，只按 schema.sql 建会灌不进去" % ddl.name)
            db.run_script(str(ddl), into)
        else:
            schema = args.schema or _find_schema()
            if not schema:
                die("找不到 schema.sql（用 --schema 指路径）：新库没有表结构就没法灌行")
            db.run_script(schema, into)
    else:
        print("目标就是来源库 %s：恢复会把该租户的行插回原表（同 id 冲突会报错，不会静默覆盖）" % into)
        if not args.skip_db and not args.yes:
            die("往来源库里恢复是破坏性动作，加 --yes 确认（只解文件用 --skip-db，不需要它）")

    if not args.skip_db:
        reg = Path(args.from_dir) / "db" / "c_ai_tenant.sql"
        if reg.is_file():
            db.run_script(str(reg), into)
        for sql in sorted((Path(args.from_dir) / "db").glob("*.sql")):
            if sql.name == "c_ai_tenant.sql":
                continue
            db.run_script(str(sql), into)
        print("  数据行已导入")
        # 租户注册行在 c_ai_tenant（平台全局表）里，若目标平台已有同名租户则跳过插入
        cmd_verify(db, argparse.Namespace(from_dir=args.from_dir, into_db=into))

    tar = man.get("filesTar")
    if tar and not args.skip_files:
        with tarfile.open(tar, "r:gz") as tf:
            for m in tf.getmembers():
                if m.name.startswith("/") or ".." in Path(m.name).parts:
                    die("备份包里有越界路径（不该出现）：" + m.name)
            if args.files_in_place:
                if not args.yes:
                    die("--files-in-place 会覆盖本机原路径下的同名文件，加 --yes 确认")
                root = Path("/")
            else:
                root = Path(args.files_to or env("WENQU_DR_FILES_TO", "."))
                root.mkdir(parents=True, exist_ok=True)
            tf.extractall(root)
        print("  盘上目录已解到", "本机原路径" if args.files_in_place else root,
              "（同名文件被覆盖；不同机恢复请把 --files-to 指到挂载点后再手工挪）")
    elif tar is None:
        print("  备份包里没有盘上文件（该租户当时没有目录）")
    print("\n恢复完成。注意：知识库向量索引不在备份里，按「知识库 → 重新索引」重建；"
          "限流与配额计数是临时值，重启后按台账重算。")


def _find_schema():
    here = Path(__file__).resolve()
    for cand in [here.parents[2] / "src/main/resources/schema.sql", Path("src/main/resources/schema.sql")]:
        if cand.is_file():
            return str(cand)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db-host"), ap.add_argument("--db-port"), ap.add_argument("--db-user")
    ap.add_argument("--db-password"), ap.add_argument("--db-name")
    ap.add_argument("--base-url", default=env("WENQU_BASE", "http://127.0.0.1:8080/ai/api/ai"))
    ap.add_argument("--login", default=env("WENQU_ADMIN"))
    ap.add_argument("--token", default=env("WENQU_TOKEN"))
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("list", help="列出租户与成员数")
    p = sub.add_parser("inventory", help="打印某租户的落点清单")
    p.add_argument("--tenant", required=True)
    p = sub.add_parser("backup", help="按租户（或整库）备份")
    p.add_argument("--tenant"); p.add_argument("--all", action="store_true")
    p.add_argument("--out", required=True)
    p = sub.add_parser("restore", help="从备份包恢复")
    p.add_argument("--from", dest="from_dir", required=True)
    p.add_argument("--into-db", help="恢复到另一个库（新库会自动建表）")
    p.add_argument("--files-to", help="盘上目录解到哪个根（默认当前目录；结构按绝对路径还原）")
    p.add_argument("--files-in-place", action="store_true", help="直接解回备份时记录的绝对路径（同机灾难恢复）")
    p.add_argument("--schema", help="schema.sql 路径（建新库时用）")
    p.add_argument("--skip-db", action="store_true"); p.add_argument("--skip-files", action="store_true")
    p.add_argument("--yes", action="store_true")
    p = sub.add_parser("verify", help="核对备份包行数与现库")
    p.add_argument("--from", dest="from_dir", required=True)
    p.add_argument("--into-db")

    args = ap.parse_args()
    db = Db(args)
    be = None
    if args.cmd in ("list", "inventory", "backup"):
        be = Backend(args.base_url, args.login, env("WENQU_ADMIN_PASSWORD"), )
        if args.token:
            be.token = args.token
        else:
            be.login()
    if args.cmd == "list":
        cmd_list(be, args)
    elif args.cmd == "inventory":
        cmd_inventory(be, args)
    elif args.cmd == "backup":
        cmd_backup(be, db, args)
    elif args.cmd == "restore":
        cmd_restore(db, args)
    elif args.cmd == "verify":
        cmd_verify(db, args)


if __name__ == "__main__":
    main()
