"""工作流对外触发测试。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquAPIError, WenquClient  # noqa: E402


class WorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url = mock_server.start_mock_server()
        self.client = WenquClient(base_url=self.base_url, api_key=mock_server.API_KEY)

    def tearDown(self) -> None:
        self.client.close()
        self.server.shutdown()
        self.server.server_close()

    def test_run(self) -> None:
        run = self.client.workflows.run("wf-demo", inputs={"question": "你好"})
        self.assertEqual(run["status"], "success")
        self.assertEqual(run["outputs"], {"answer": "工作流输出"})
        self.assertEqual(run["inputs"], {"question": "你好"})
        self.assertIn("nodeTraces", run)
        self.assertIn("durationMs", run)

    def test_run_without_inputs(self) -> None:
        run = self.client.workflows.run("wf-demo")
        self.assertEqual(run["inputs"], {})

    def test_get_run(self) -> None:
        run = self.client.workflows.get_run("wf-demo", "run-123")
        self.assertEqual(run["id"], "run-123")
        self.assertEqual(run["status"], "success")

    def test_run_not_found(self) -> None:
        with self.assertRaises(WenquAPIError) as ctx:
            self.client.workflows.run("wf-404")
        self.assertEqual(ctx.exception.status_code, 404)
        self.assertIn("不存在或未发布", ctx.exception.message)


if __name__ == "__main__":
    unittest.main()
