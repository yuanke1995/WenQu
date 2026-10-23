#!/usr/bin/env python3
"""问渠·本地统计 MCP Server（streamable HTTP @ 127.0.0.1:8765/mcp）。

给问渠对话链路提供「查本地 wenqu_ai 库拿真实数据」的工具，出图 demo 的取数端。
只读：拒绝任何非 SELECT/SHOW/DESC/EXPLAIN 语句，结果强制限量。

启动（依赖装进 WorkBuddy 托管 venv，见 README.md）：
  /Users/wangxi/.workbuddy/binaries/python/envs/default/bin/python server.py

在问渠「智能体 → MCP 外部工具」登记（每用户各自登记）：
  名称：本地统计    地址：http://127.0.0.1:8765/mcp    类型：streamable
"""
import json
import os
import re

import pymysql
from mcp.server.fastmcp import FastMCP

DB = dict(
    host=os.environ.get("DB_HOST", "127.0.0.1"),
    port=int(os.environ.get("DB_PORT", "3306")),
    user=os.environ.get("DB_USER", "root"),
    password=os.environ.get("DB_PASSWORD", ""),
    database=os.environ.get("DB_NAME", "wenqu_ai"),
    charset="utf8mb4",
)
MAX_ROWS = 200
mcp = FastMCP("本地统计", host="127.0.0.1", port=8765, streamable_http_path="/mcp")

READ_ONLY = re.compile(r"^\s*(select|show|describe|desc|explain)\b", re.IGNORECASE)
FORBIDDEN = re.compile(
    r"\b(insert|update|delete|drop|alter|create|truncate|replace|grant|revoke"
    r"|load_file|into\s+outfile|into\s+dumpfile|call|set|use|lock)\b", re.IGNORECASE)


def _query(sql: str, max_rows: int = MAX_ROWS) -> dict:
    """执行一条只读 SQL，返回 {columns, rows, row_count, truncated}。"""
    conn = pymysql.connect(**DB, cursorclass=pymysql.cursors.Cursor)
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            rows = cur.fetchmany(max_rows + 1)
            truncated = len(rows) > max_rows
            rows = rows[:max_rows]
            cols = [d[0] for d in cur.description] if cur.description else []
            # date/datetime/decimal 等转成可 JSON 的字符串
            rows = [[str(c) if not isinstance(c, (int, float, str, type(None))) else c
                     for c in r] for r in rows]
            return {"columns": cols, "rows": rows,
                    "row_count": len(rows), "truncated": truncated}
    finally:
        conn.close()


@mcp.tool()
def sql_query(sql: str) -> str:
    """对问渠本地库（wenqu_ai）执行一条只读查询，返回 {columns, rows, row_count}。

    参数 sql 必须是单条 SELECT/SHOW/DESC/EXPLAIN 语句；不带 LIMIT 时自动补 LIMIT 200。
    库里有什么先调 db_overview 摸底；不认识的表用 SHOW COLUMNS 看结构，不要猜列名。
    """
    one = sql.strip().rstrip(";")
    if ";" in one:
        raise ValueError("只允许一条语句（sql 里出现了分号）")
    if not READ_ONLY.match(one):
        raise ValueError("只允许只读查询（SELECT/SHOW/DESC/EXPLAIN）")
    if FORBIDDEN.search(one):
        raise ValueError("语句包含写操作或被禁用的关键字")
    if not re.search(r"\blimit\b", one, re.IGNORECASE):
        one += f" LIMIT {MAX_ROWS}"
    return json.dumps(_query(one), ensure_ascii=False)


@mcp.tool()
def db_overview() -> str:
    """返回问渠本地库概览：核心表的行数与消息时间范围，取数前先看这个。"""
    out = {}
    for t in ("c_ai_message", "c_ai_session", "c_ai_agent", "c_ai_knowledge_base",
              "c_ai_document", "c_ai_artifact", "c_ai_user", "c_ai_qa_feedback"):
        try:
            out[t] = _query(f"SELECT COUNT(*) AS n FROM {t}")["rows"][0][0]
        except Exception as e:  # 表可能不存在
            out[t] = f"不可用: {e}"
    try:
        rng = _query("SELECT MIN(create_time) AS first_msg, MAX(create_time) AS last_msg "
                     "FROM c_ai_message")["rows"][0]
        out["消息时间范围"] = rng
    except Exception:
        pass
    return json.dumps(out, ensure_ascii=False)


if __name__ == "__main__":
    mcp.run(transport="streamable-http")
