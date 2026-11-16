"""71번의 성공률·p95 계산, 동시 요청 제한과 실패 시 중단을 검증한다."""

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "measure_api_performance.py"
SPEC = importlib.util.spec_from_file_location("performance", SCRIPT)
performance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(performance)


def sample(ok=True, elapsed_ms=10):
    return {"ok": ok, "elapsed_ms": elapsed_ms, "http_status": 200 if ok else 503}


class StatisticsTests(unittest.TestCase):
    def test_nearest_rank_p95_with_twenty_samples(self):
        self.assertEqual(performance.p95(list(range(20, 0, -1))), 19)

    def test_no_success_is_not_reported_as_zero_ms(self):
        result = performance.summarize([sample(False)], 20)
        self.assertIsNone(result["success_mean_ms"])
        self.assertIsNone(result["success_p95_ms"])
        self.assertFalse(result["complete"])
        self.assertEqual(result["failures"], 1)

    def test_failed_fast_response_does_not_improve_success_latency(self):
        result = performance.summarize([sample(True, 100), sample(False, 1)], 2)
        self.assertEqual(result["success_mean_ms"], 100)
        self.assertFalse(result["complete"])
        self.assertEqual(result["nfr_result"], "UNDEFINED_TARGET")

    def test_invalid_elapsed_time_is_rejected(self):
        for value in [float("nan"), float("inf"), -1]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                performance.p95([value])


class ResponseTests(unittest.TestCase):
    def test_json_error_and_empty_demo_data_are_not_success(self):
        for body in [b'{"error":{}}', b'{"data":[]}', b'<html>login</html>']:
            with self.subTest(body=body), self.assertRaises(ValueError):
                performance.validate_body(body, "list")

    def test_only_fixed_get_paths_can_be_measured(self):
        session = performance.Session()
        for path in ["https://example.com", "/api/v1/auth/logout", "/api/v1/actions"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                session.measure(path, "list")

    def test_redirect_is_not_followed_with_credentials(self):
        self.assertIsNone(
            performance.NoRedirect().redirect_request(
                None, None, 302, "", {}, "https://example.com"
            )
        )

    def test_successful_response_body_is_not_saved_in_sample(self):
        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *_):
                pass

            def read(self, _):
                return b'{"data":[{"private_value":"must-not-be-saved"}]}'

        class Opener:
            def open(self, *_args, **_kwargs):
                return Response()

        session = performance.Session()
        _, path, shape = performance.TARGETS[0]
        with patch.object(performance, "build_opener", return_value=Opener()):
            result = session.measure(path, shape)
        self.assertTrue(result["ok"])
        self.assertEqual(result["rows"], 1)
        self.assertNotIn("must-not-be-saved", json.dumps(result))


class ConcurrencyTests(unittest.TestCase):
    def test_five_requests_actually_overlap_and_never_exceed_five(self):
        lock = threading.Lock()
        active, maximum = 0, 0
        peers = threading.Barrier(5)

        def measure(path, shape):
            nonlocal active, maximum
            with lock:
                active += 1
                maximum = max(maximum, active)
            peers.wait(timeout=5)
            with lock:
                active -= 1
            return sample()

        result = performance.run_case(measure, "/unused", "list", 5)
        self.assertEqual(maximum, 5)
        self.assertTrue(result["complete"])
        self.assertEqual(len(result["samples"]), 20)

    def test_failure_stops_after_current_wave(self):
        result = performance.run_case(lambda *_: sample(False), "/unused", "list", 5)
        self.assertEqual(result["attempts"], 5)
        self.assertFalse(result["complete"])

    def test_expired_budget_sends_no_requests(self):
        def unexpected(*_):
            self.fail("Expired budget should not issue a request")

        result = performance.run_case(unexpected, "/unused", "list", 5, time.monotonic() - 1)
        self.assertEqual(result["attempts"], 0)


class ProfileTests(unittest.TestCase):
    def test_failed_preflight_prevents_load_and_logs_out(self):
        class FakeSession:
            calls = 0
            closed = False

            def measure(self, *_):
                self.calls += 1
                return sample(False)

            def logout(self):
                self.closed = True
                return True

        session = FakeSession()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            report = performance.run_profile(session, output, lambda _: None)
            saved = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(session.calls, 1)
        self.assertTrue(session.closed)
        self.assertEqual(report["status"], "ABORTED_PREFLIGHT")
        self.assertEqual(saved["status"], "ABORTED_PREFLIGHT")
        self.assertEqual(saved["cases"], [])

    def test_complete_profile_has_280_samples_and_separate_warmup(self):
        class FakeSession:
            def measure(self, *_):
                return sample()

            def logout(self):
                return True

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            report = performance.run_profile(FakeSession(), output, lambda _: None)
            self.assertNotIn(b"\r\n", output.read_bytes())
        self.assertEqual(report["status"], "COMPLETED")
        self.assertEqual(len(report["probes"]), 7)
        self.assertEqual(len(report["cases"]), 14)
        self.assertEqual(sum(case["attempts"] for case in report["cases"]), 280)
        self.assertTrue(report["logout_succeeded"])

    def test_unexpected_exception_is_not_left_running_and_logs_out(self):
        class FakeSession:
            def measure(self, *_):
                raise RuntimeError("secret response must not be copied")

            def logout(self):
                return True

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            with self.assertRaises(RuntimeError):
                performance.run_profile(FakeSession(), output, lambda _: None)
            saved = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(saved["status"], "ABORTED_EXCEPTION")
        self.assertEqual(saved["error_type"], "RuntimeError")
        self.assertTrue(saved["logout_succeeded"])
        self.assertNotIn("secret response", json.dumps(saved))


if __name__ == "__main__":
    unittest.main()
