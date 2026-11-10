"""Chat Completions 与 Responses API 的非流式/流式行为测试。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquClient  # noqa: E402


class ChatTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()
        self.client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)

    def tearDown(self) -> None:
        self.client.close()
        self.server.shutdown()
        self.server.server_close()


class ChatCompletionsTest(ChatTestBase):
    def test_create_non_stream(self) -> None:
        resp = self.client.chat.completions.create(
            model="agent-1", messages=[{"role": "user", "content": "你好"}]
        )
        self.assertEqual(resp["object"], "chat.completion")
        self.assertEqual(resp["choices"][0]["message"]["content"], "你好，世界")
        self.assertEqual(resp["usage"]["total_tokens"], 9)

    def test_create_stream_yields_dict_chunks(self) -> None:
        chunks = list(self.client.chat.completions.create(
            model="agent-1", messages=[{"role": "user", "content": "你好"}], stream=True
        ))
        # [DONE] 不产出；两个 chunk + usage 尾帧
        self.assertEqual(len(chunks), 2)
        self.assertTrue(all(c["object"] == "chat.completion.chunk" for c in chunks))
        text = "".join(c["choices"][0]["delta"].get("content", "") for c in chunks)
        self.assertEqual(text, "你好，世界")
        self.assertEqual(chunks[-1]["choices"][0]["finish_reason"], "stop")
        self.assertEqual(chunks[-1]["usage"]["total_tokens"], 9)

    def test_create_stream_returns_iterator(self) -> None:
        it = self.client.chat.completions.create(
            model="agent-1", messages=[{"role": "user", "content": "你好"}], stream=True
        )
        first = next(iter(it))
        self.assertEqual(first["choices"][0]["delta"]["content"], "你好")


class ResponsesTest(ChatTestBase):
    def test_create_non_stream(self) -> None:
        resp = self.client.responses.create(model="agent-1", input="你好")
        self.assertEqual(resp["object"], "response")
        self.assertEqual(resp["status"], "completed")

    def test_create_stream_events(self) -> None:
        events = list(self.client.responses.create(model="agent-1", input="你好", stream=True))
        types = [e["type"] for e in events]
        self.assertEqual(types, [
            "response.created",
            "response.output_text.delta",
            "response.output_text.delta",
            "response.completed",
        ])
        deltas = [e["delta"] for e in events if "delta" in e]
        self.assertEqual("".join(deltas), "你好，世界")

    def test_instructions_passed(self) -> None:
        # instructions 可选透传，mock 不校验但请求应成功
        resp = self.client.responses.create(model="agent-1", input="你好", instructions="简短回答")
        self.assertEqual(resp["id"], "resp-mock")


if __name__ == "__main__":
    unittest.main()
