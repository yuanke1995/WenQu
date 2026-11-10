"""知识库管理测试：列表/详情/文档列表/建库/上传与错误映射。"""

import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquAPIError, WenquAuthError, WenquClient  # noqa: E402


class KnowledgeBaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()
        self.client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)

    def tearDown(self) -> None:
        self.client.close()
        self.server.shutdown()
        self.server.server_close()

    def test_list(self) -> None:
        data = self.client.kbs.list()
        self.assertEqual(data["total"], 1)
        kb = data["knowledgeBases"][0]
        for key in ("id", "name", "description", "embeddingRef", "docCount", "isDefault",
                    "createTime", "updateTime"):
            self.assertIn(key, kb)

    def test_get(self) -> None:
        kb = self.client.kbs.get("kb-demo")
        self.assertEqual(kb["id"], "kb-demo")

    def test_get_forbidden_maps_auth_error(self) -> None:
        with self.assertRaises(WenquAuthError) as ctx:
            self.client.kbs.get("kb-403")
        self.assertEqual(ctx.exception.status_code, 403)
        self.assertIn("不存在或不可见", ctx.exception.message)

    def test_get_not_found(self) -> None:
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.kbs.get("kb-404")
        self.assertEqual(ctx.exception.status_code, 404)

    def test_list_documents(self) -> None:
        data = self.client.kbs.list_documents("kb-demo")
        self.assertEqual(data["total"], 1)
        doc = data["documents"][0]
        self.assertEqual(doc["status"], 2)
        self.assertEqual(doc["statusText"], "解析中")
        self.assertIn("parseProgress", doc)

    def test_create(self) -> None:
        kb = self.client.kbs.create(
            name="新库", embedding_ref="dashscope/text-embedding-v3", description="由 SDK 创建"
        )
        self.assertEqual(kb["id"], "kb-new")
        self.assertEqual(kb["name"], "新库")
        self.assertEqual(kb["embeddingRef"], "dashscope/text-embedding-v3")

    def test_create_missing_required(self) -> None:
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.kbs.create(name="缺向量模型", embedding_ref="")
        self.assertEqual(ctx.exception.status_code, 400)

    def test_upload_document_by_path(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".md")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write("# 测试文档\n")
            doc = self.client.kbs.upload_document("kb-demo", path)
        finally:
            os.unlink(path)
        self.assertEqual(doc["id"], "doc-uploaded")
        self.assertEqual(doc["status"], 2)

    def test_upload_document_by_file_object_with_description(self) -> None:
        fh = io.BytesIO("内容".encode("utf-8"))
        doc = self.client.kbs.upload_document("kb-demo", fh, description="带描述上传")
        self.assertEqual(doc["description"], "已带描述")


if __name__ == "__main__":
    unittest.main()
