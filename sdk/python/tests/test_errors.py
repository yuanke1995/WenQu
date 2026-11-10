"""错误映射与客户端构造校验测试：两种错误体形状、鉴权失败与 429。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import (  # noqa: E402
    WenquAPIError,
    WenquAuthError,
    WenquClient,
    WenquError,
    WenquRateLimitError,
)


class ClientConstructionTest(unittest.TestCase):
    def test_empty_base_url_rejected(self) -> None:
        with self.assertRaises(ValueError):
            WenquClient(base_url="", api_key="sk-x")

    def test_empty_api_key_rejected(self) -> None:
        with self.assertRaises(ValueError):
            WenquClient(base_url="http://127.0.0.1:1/ai", api_key="")

    def test_trailing_slash_stripped(self) -> None:
        client = WenquClient(base_url="http://127.0.0.1:1/ai/", api_key="sk-x")
        self.assertEqual(client.base_url, "http://127.0.0.1:1/ai")
        client.close()


class ErrorMappingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def test_invalid_api_key_maps_auth_error(self) -> None:
        client = WenquClient(base_url=self.base_url, api_key="sk-wrong")
        with self.assertRaises(WenquAuthError) as ctx:
            client.chat.completions.create(model="agent-1", messages=[{"role": "user", "content": "hi"}])
        self.assertEqual(ctx.exception.status_code, 401)
        self.assertEqual(ctx.exception.error_type, "authentication_error")
        client.close()

    def test_openai_error_shape(self) -> None:
        client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)
        with self.assertRaises(WenquAPIError) as ctx:
            client.chat.completions.create(model="TRIGGER_ERROR", messages=[])
        err = ctx.exception
        self.assertEqual(err.status_code, 400)
        self.assertEqual(err.message, "智能体不存在")
        self.assertEqual(err.error_type, "invalid_request_error")
        # 异常层次：所有 SDK 错误都是 WenquError 的子类
        self.assertIsInstance(err, WenquError)
        client.close()

    def test_envelope_error_shape(self) -> None:
        client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)
        with self.assertRaises(WenquAPIError) as ctx:
            client.retrieval.search("")
        self.assertEqual(ctx.exception.status_code, 400)
        self.assertEqual(ctx.exception.message, "query 不能为空")
        client.close()

    def test_429_is_rate_limit_error(self) -> None:
        client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)
        with self.assertRaises(WenquRateLimitError):
            client.retrieval.search("TRIGGER_429")
        # 限频异常同时是 API 异常，便于统一兜底
        with self.assertRaises(WenquAPIError):
            client.embeddings.create(input="x", model="TRIGGER_429")
        client.close()

    def test_auth_error_class_hierarchy(self) -> None:
        self.assertTrue(issubclass(WenquAuthError, WenquAPIError))
        self.assertTrue(issubclass(WenquRateLimitError, WenquAPIError))
        self.assertTrue(issubclass(WenquAPIError, WenquError))


if __name__ == "__main__":
    unittest.main()
