"""v1 run 原语（A2）：7 端点裸 JSON、SSE id/seq、Last-Event-ID 自动续传、resume_expired 降级。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mock_server  # noqa: E402
from wenqu import WenquAuthError, WenquClient  # noqa: E402


class RunsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server, cls.base_url = mock_server.start_mock_server()
        cls.client = WenquClient(cls.base_url, mock_server.API_KEY, timeout=5)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.client.close()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self) -> None:
        mock_server.SEEN_LAST_EVENT_IDS.clear()

    # ---------- 7 个端点 ----------

    def test_create_returns_bare_object(self) -> None:
        d = self.client.runs.create("agent-1", "只回复两个字：收到", idempotency_key="idem-1")
        self.assertEqual(d["runId"], "run-new")
        self.assertEqual(d["status"], "running")
        self.assertTrue(d.get("idempotent"))
        self.assertNotIn("success", d)  # v1 不走平台包络

    def test_create_requires_question(self) -> None:
        with self.assertRaises(ValueError):
            self.client.runs.create("agent-1", "")

    def test_get_and_artifacts(self) -> None:
        d = self.client.runs.get("run-basic")
        self.assertEqual(d["runId"], "run-basic")
        lst = self.client.runs.artifacts("run-basic")
        self.assertEqual(lst["count"], 1)
        self.assertEqual(lst["artifacts"][0]["expiresInSeconds"], 3600)
        self.assertIn("sig=", lst["artifacts"][0]["downloadUrl"])

    def test_approve(self) -> None:
        r = self.client.runs.approve("run-basic", "card-1", "revise",
                                     plan="改成两步", answers=["A"])
        self.assertTrue(r["accepted"])
        self.assertEqual(r["decision"], "revise")
        self.assertEqual(r["kind"], "tool_approval")

    def test_steer_and_stop(self) -> None:
        s = self.client.runs.steer("run-basic", "补充一句")
        self.assertTrue(s["accepted"])
        self.assertEqual(s["messageId"], "m-1")
        t = self.client.runs.stop("run-basic")
        self.assertTrue(t["stopped"])
        with self.assertRaises(ValueError):
            self.client.runs.steer("run-basic", "")

    # ---------- 事件流 ----------

    def test_iter_events_parses_id_and_flat_data(self) -> None:
        events = list(self.client.runs.iter_events("run-basic"))
        self.assertEqual([e.id for e in events], [1, 2, 3])
        self.assertEqual([e.event for e in events],
                         ["run.started", "token", "run.done"])
        self.assertEqual(events[1].data["delta"], "收到")
        self.assertEqual(events[0].data["runId"], "run-basic")

    def test_stream_basic(self) -> None:
        seen, tokens = [], []
        summary = self.client.runs.stream(
            "run-basic", on_event=seen.append, on_token=tokens.append,
            reconnect_delay=0)
        self.assertEqual(tokens, ["收到"])
        self.assertEqual(summary["status"], "succeeded")
        self.assertEqual(summary["lastEventId"], 3)
        self.assertEqual(summary["reconnects"], 0)
        self.assertEqual(len(seen), 3)

    def test_disconnect_resumes_with_last_event_id(self) -> None:
        seen = []
        summary = self.client.runs.stream(
            "run-disconnect", on_event=seen.append, reconnect_delay=0)
        self.assertEqual(mock_server.SEEN_LAST_EVENT_IDS, ["", "2"])
        self.assertEqual([e.id for e in seen], [1, 2, 3])
        self.assertEqual(summary["status"], "succeeded")
        self.assertEqual(summary["reconnects"], 1)

    def test_start_at_last_event_id(self) -> None:
        events = list(self.client.runs.iter_events(
            "run-basic", last_event_id=2, reconnect_delay=0))
        self.assertEqual(mock_server.SEEN_LAST_EVENT_IDS, ["2"])
        self.assertEqual([e.id for e in events], [3])

    def test_seq_dedup(self) -> None:
        events = list(self.client.runs.iter_events("run-dup", reconnect_delay=0))
        self.assertEqual([e.id for e in events], [1, 2, 3])

    def test_resume_expired_falls_back_to_get(self) -> None:
        fallback, errors = [], []
        summary = self.client.runs.stream(
            "run-expired",
            on_event=lambda e: errors.append(e) if e.event == "run.error" else None,
            on_fallback=fallback.append, reconnect_delay=0)
        self.assertEqual(summary["status"], "resume_expired")
        self.assertEqual(summary["detail"]["status"], "succeeded")
        self.assertEqual(fallback[0]["runId"], "run-expired")
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].data["code"], "resume_expired")

    def test_run_error_stable_code(self) -> None:
        summary = self.client.runs.stream("run-error", reconnect_delay=0)
        self.assertEqual(summary["status"], "failed")
        self.assertEqual(summary["error"]["code"], "rate_limited")
        self.assertEqual(summary["error"]["requestId"], "rid-e")

    def test_stopped_terminal(self) -> None:
        summary = self.client.runs.stream("run-stopped", reconnect_delay=0)
        self.assertEqual(summary["status"], "stopped")

    def test_403_does_not_reconnect(self) -> None:
        with self.assertRaises(WenquAuthError):
            list(self.client.runs.iter_events("run-403", reconnect_delay=0))
        self.assertEqual(len(mock_server.SEEN_LAST_EVENT_IDS), 1)


if __name__ == "__main__":
    unittest.main()
