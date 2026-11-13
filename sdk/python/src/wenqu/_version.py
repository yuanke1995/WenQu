"""Python SDK 版本单一真源。

每次请求以 ``X-Wenqu-Sdk-Version`` 上报；发布时须与 pyproject.toml、
JS SDK 的 package.json / version.ts 同步（CI 会核对）。
单独成模块是为了让 client.py 在包初始化早期就能读到版本，不产生循环导入。
"""

__version__ = "0.1.0"
