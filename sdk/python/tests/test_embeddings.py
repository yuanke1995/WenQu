"""Embeddings 与可用向量模型清单测试。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquAPIError, WenquClient  # noqa: E402


class EmbeddingsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()
        self.client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)

    def tearDown(self) -> None:
        self.client.close()
        self.server.shutdown()
        self.server.server_close()

    def test_create_single_text(self) -> None:
        resp = self.client.embeddings.create(input="你好", model="dashscope/text-embedding-v3")
        self.assertEqual(resp["object"], "list")
        self.assertEqual(len(resp["data"]), 1)
        self.assertEqual(resp["data"][0]["object"], "embedding")
        self.assertIsInstance(resp["data"][0]["embedding"], list)
        self.assertEqual(resp["model"], "dashscope/text-embedding-v3")
        self.assertIn("usage", resp)

    def test_create_batch(self) -> None:
        resp = self.client.embeddings.create(
            input=["文本一", "文本二", "文本三"], model="dashscope/text-embedding-v3"
        )
        self.assertEqual(len(resp["data"]), 3)
        self.assertEqual([d["index"] for d in resp["data"]], [0, 1, 2])

    def test_dimensions_echo(self) -> None:
        resp = self.client.embeddings.create(
            input="你好", model="dashscope/text-embedding-v3", dimensions=512
        )
        self.assertEqual(resp["dimension"], 512)

    def test_base64_encoding_format(self) -> None:
        resp = self.client.embeddings.create(
            input="你好", model="dashscope/text-embedding-v3", encoding_format="base64"
        )
        self.assertIsInstance(resp["data"][0]["embedding"], str)

    def test_missing_model_maps_openai_error(self) -> None:
        # 绕过类型约束模拟漏传 model
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.embeddings.create(input="你好", model="")
        err = ctx.exception
        self.assertEqual(err.status_code, 400)
        self.assertIn("model 必填", err.message)
        self.assertEqual(err.error_type, "invalid_request_error")

    def test_batch_over_limit(self) -> None:
        texts = ["文本" for _ in range(257)]
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.embeddings.create(input=texts, model="dashscope/text-embedding-v3")
        self.assertIn("256", ctx.exception.message)

    def test_rate_limit_openai_shape(self) -> None:
        from wenqu import WenquRateLimitError

        with self.assertRaises(WenquRateLimitError) as ctx:
            self.client.embeddings.create(input="你好", model="TRIGGER_429")
        self.assertEqual(ctx.exception.status_code, 429)

    def test_list_embedding_models(self) -> None:
        resp = self.client.models.list_embeddings()
        self.assertEqual(resp["object"], "list")
        refs = []
        for group in resp["data"]:
            for m in group.get("models", []):
                refs.append(m["ref"])
        self.assertIn("dashscope/text-embedding-v3", refs)


if __name__ == "__main__":
    unittest.main()
