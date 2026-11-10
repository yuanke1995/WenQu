"""知识检索测试：包络解析、参数透传与错误映射。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquAPIError, WenquClient, WenquRateLimitError  # noqa: E402


class RetrievalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()
        self.client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)

    def tearDown(self) -> None:
        self.client.close()
        self.server.shutdown()
        self.server.server_close()

    def test_search_success(self) -> None:
        data = self.client.retrieval.search("问渠是什么", knowledge_base_ids=["kb-demo"])
        self.assertIn("chunks", data)
        self.assertEqual(data["total"], 2)
        chunk = data["chunks"][0]
        for key in ("id", "text", "score", "title", "titlePath", "docId", "kbId", "chunkIndex", "images"):
            self.assertIn(key, chunk)
        # warnings 为可选字段，成功路径不保证存在
        self.assertNotIn("warnings", data)

    def test_top_k_and_min_score(self) -> None:
        data = self.client.retrieval.search("问渠", top_k=1)
        self.assertEqual(data["total"], 1)
        data = self.client.retrieval.search("问渠", min_score=0.9)
        self.assertEqual(data["total"], 1)
        self.assertGreaterEqual(data["chunks"][0]["score"], 0.9)

    def test_warnings_passthrough(self) -> None:
        data = self.client.retrieval.search("TRIGGER_WARN")
        self.assertEqual(data["warnings"], ["关键词检索失败，仅向量结果"])

    def test_empty_query_maps_envelope_error(self) -> None:
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.retrieval.search("   ")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertIn("query 不能为空", ctx.exception.message)

    def test_rate_limit_envelope_shape(self) -> None:
        with self.assertRaises(WenquRateLimitError) as ctx:
            self.client.retrieval.search("TRIGGER_429")
        self.assertEqual(ctx.exception.status_code, 429)
        self.assertIn("频繁", ctx.exception.message)


if __name__ == "__main__":
    unittest.main()
