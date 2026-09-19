#!/usr/bin/env python3
"""比对「pom 依赖闭包」与「源码 import」，找出 build.sh 能编、IDE 编不了的依赖缺口。

为什么需要这个脚本
------------------
本工程没有 maven，`build.sh` 是把整个 `~/.m2` **全量**拼进 classpath：

    find ~/.m2 -name "*.jar" > /tmp/wenqu-server-deps.txt

于是任何躺在 `~/.m2` 里的 jar 都能满足编译——哪怕它压根没写进 `pom.xml`。
而 IDEA / Maven 严格按 `pom.xml` 的依赖闭包解析，两边不一致时就出现
「命令行编译通过、IDEA 报 找不到符号」。

本脚本用 `~/.m2` 里现成的 pom 文件**离线**算出 pom 的传递闭包，再逐条核对
源码的第三方 import，把缺口一次列全（而不是补一个撞一个）。

用法
----
    python3 tools/deps-check.py

输出三段：
    缺口 A —— 闭包外但 m2 里有（build.sh 侥幸编过，IDE 编不过）  ← 需要修 pom.xml
    缺口 B —— m2 里也没有（任何方式都编不过）
    正常   —— 由闭包内哪个 artifact 提供
"""
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

MODULE = Path(__file__).resolve().parent.parent
M2 = Path.home() / ".m2" / "repository"
DEPS_TXT = Path("/tmp/wenqu-server-deps.txt")
SRC = MODULE / "src/main/java"
POM = MODULE / "pom.xml"


def tag_of(elem):
    return elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag


def collect_jars():
    """收集 m2 里的 jar（优先用 build.sh 生成的清单，缺失则自行遍历）。"""
    jars = []
    if DEPS_TXT.exists():
        for raw in DEPS_TXT.read_text().splitlines():
            p = Path(raw.strip())
            if p.name.endswith(".jar") and p.exists():
                jars.append(p)
        if jars:
            return jars
    return [p for p in M2.rglob("*.jar")
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]


# ── 1. 建索引：artifactId -> [(groupId, version, jarPath)] ──
jar_index = {}
for jar in collect_jars():
    try:
        version = jar.parent.name
        artifact = jar.parent.parent.name
        group = str(jar.parent.parent.parent.relative_to(M2)).replace("/", ".")
    except ValueError:
        continue
    jar_index.setdefault(artifact, []).append((group, version, jar))


def pom_for(artifact):
    for _group, version, jar in jar_index.get(artifact, []):
        pom = jar.parent / f"{artifact}-{version}.pom"
        if pom.exists():
            return pom
    return None


def read_deps(pom_path):
    """读 pom 根节点下的 <dependencies>（不含 dependencyManagement），过滤 test/provided/optional。"""
    try:
        root = ET.parse(pom_path).getroot()
    except Exception:
        return []
    out = []
    for child in root:
        if tag_of(child) != "dependencies":
            continue
        for dep in child:
            if tag_of(dep) != "dependency":
                continue
            fields = {tag_of(f): (f.text or "").strip() for f in dep}
            artifact = fields.get("artifactId")
            if not artifact:
                continue
            if fields.get("scope") in ("test", "provided", "system"):
                continue
            if fields.get("optional") == "true":
                continue
            out.append(artifact)
    return out


# ── 2. 从 pom.xml 的直接依赖出发算传递闭包 ──
root = ET.parse(POM).getroot()
direct = []
for child in root:
    if tag_of(child) != "dependencies":
        continue
    for dep in child:
        if tag_of(dep) != "dependency":
            continue
        fields = {tag_of(f): (f.text or "").strip() for f in dep}
        if fields.get("artifactId"):
            direct.append(fields["artifactId"])

closure, visited = set(), set()
queue = list(direct)
while queue:
    art = queue.pop()
    if art in visited:
        continue
    visited.add(art)
    closure.add(art)
    pom = pom_for(art)
    if pom is None:
        continue
    for child_art in read_deps(pom):
        if child_art not in visited:
            queue.append(child_art)


# ── 3. 收集源码第三方 import ──
imports = set()
for java_file in SRC.rglob("*.java"):
    for line in java_file.read_text(errors="ignore").splitlines():
        line = line.strip()
        if not line.startswith("import "):
            continue
        q = line[len("import "):].rstrip(";").strip()
        if q.startswith("static "):
            q = q[len("static "):].strip()
        imports.add(q)

third = {q for q in imports if not q.startswith(("java.", "javax.", "com.wisesoft.wenqu"))}


def class_candidates(q):
    """把 import 展开成可能的 class 条目。覆盖两种 Java 写法：
       - 内部类 `A.B.C` 可能落成 `A/B$C.class`
       - 通配符 `a.b.*` 落成目录前缀 `a/b/`"""
    if q.endswith(".*"):
        return [("PKG", q[:-2].replace(".", "/") + "/")]
    parts = q.split(".")
    out = [("CLASS", q.replace(".", "/") + ".class")]
    for i in range(len(parts) - 1, 0, -1):
        outer = ".".join(parts[:i]).replace(".", "/")
        inner = "$".join(parts[i:])
        out.append(("CLASS", outer + "$" + inner + ".class"))
    return out


def jar_entries(jar, cache={}):
    key = str(jar)
    if key not in cache:
        try:
            with zipfile.ZipFile(jar) as zf:
                cache[key] = set(zf.namelist())
        except Exception:
            cache[key] = set()
    return cache[key]


def resolve(imports):
    """在 pom 闭包内解析 import，返回 {import: artifact} 与仍未解析的 import 集合。

    注意：一个 import 会展开成多个候选条目（内部类 x.y.Z、通配符 a.b.*），
    命中后必须把它**全部**候选一起摘掉，否则剩余候选会被误判成"未解析"。
    """
    resolved = {}
    pending = {}                             # class 条目 -> (import, kind)
    for q in imports:
        for kind, entry in class_candidates(q):
            pending.setdefault(entry, (q, kind))
    for art in sorted(closure):
        if not pending:
            break
        for _group, _version, jar in jar_index.get(art, []):
            names = jar_entries(jar)
            if not names:
                continue
            for entry, (q, kind) in list(pending.items()):
                hit = (entry in names) if kind == "CLASS" else \
                    any(n.startswith(entry) and n.endswith(".class") for n in names)
                if not hit:
                    continue
                resolved.setdefault(q, art)
                for other in [e for e, (owner, _k) in pending.items() if owner == q]:
                    pending.pop(other, None)
    return resolved, {q for q, _kind in pending.values()}


provided, unresolved = resolve(third)

# 缺口：闭包里找不到，但 m2 别处能找到（= build.sh 侥幸编过的那些）
resolved_anywhere = {}
remaining = set(unresolved)
all_artifacts = jar_index
for art in sorted(all_artifacts):
    if not remaining:
        break
    if art in closure:
        continue
    for _group, _version, jar in jar_index.get(art, []):
        names = jar_entries(jar)
        for q in list(remaining):
            hit = False
            for kind, entry in class_candidates(q):
                if kind == "CLASS" and entry in names:
                    hit = True
                elif kind == "PKG" and any(
                        n.startswith(entry) and n.endswith(".class") for n in names):
                    hit = True
                if hit:
                    break
            if hit:
                resolved_anywhere[q] = art
                remaining.discard(q)

# ── 4. 报告 ──
if "--print-classpath" in sys.argv:
    # 输出「pom 闭包内实际存在的 jar」classpath，可用于模拟 IDEA 的编译：
    #   python3 tools/deps-check.py --print-classpath > /tmp/closure-jars.txt
    #   javac -encoding UTF-8 -nowarn -parameters \
    #         -cp "$(tr '\n' ':' < /tmp/closure-jars.txt)" -d /tmp/closure-out @srcs.txt
    for art in sorted(closure):
        for _group, _version, jar in jar_index.get(art, []):
            print(jar)
    sys.exit(0)

print(f"pom.xml 直接依赖 {len(direct)} 个；传递闭包 {len(closure)} 个 artifact")
print(f"源码第三方 import {len(third)} 条\n")

gaps = sorted(resolved_anywhere)
absent = sorted(remaining)

print(f"【缺口 A】闭包外、m2 里有 → build.sh 能编、IDE 编不了（{len(gaps)} 条）")
if gaps:
    for q in gaps:
        print(f"  ✗ {q:54s} 提供者: {resolved_anywhere[q]}（未写进 pom.xml）")
else:
    print("  （无）")

print(f"\n【缺口 B】m2 里也没有 → 任何方式都编不过（{len(absent)} 条）")
if absent:
    for q in absent:
        print(f"  ✗✗ {q}")
else:
    print("  （无）")

print(f"\n【正常】{len(provided)} 条 import 由 pom 闭包提供")
by_art = {}
for q, art in provided.items():
    by_art.setdefault(art, []).append(q)
for art in sorted(by_art):
    sample = ", ".join(sorted(by_art[art])[:2])
    print(f"  ✓ {art:40s} {len(by_art[art]):3d} 条  (如 {sample})")

sys.exit(1 if (gaps or absent) else 0)
