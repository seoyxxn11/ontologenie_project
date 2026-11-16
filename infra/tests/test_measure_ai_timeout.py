"""시간 지표를 다른 요청·재시작과 섞어 성능 결과로 오인하지 않는지 검사한다."""

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "measure_ai_timeout.py"
SPEC = importlib.util.spec_from_file_location("measure_ai_timeout", SCRIPT)
measurement = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(measurement)
ROUTE = "/api/v1/analyses"


def snapshot(count=0, seconds=0, started="2026-11-16T00:00:00Z"):
    return {
        "backend_started_at": started,
        "requests": [{"uri": ROUTE, "status": "202", "count": count, "sum": seconds}],
    }


class MeasurementTests(unittest.TestCase):
    def test_successful_post_is_separate_from_get_and_failure(self):
        text = '\n'.join([
            'http_server_requests_seconds_count{method="POST",uri="/api/v1/analyses",status="202"} 3',
            'http_server_requests_seconds_sum{method="POST",uri="/api/v1/analyses",status="202"} 0.9',
            'http_server_requests_seconds_count{method="POST",uri="/api/v1/analyses",status="500"} 1',
            'http_server_requests_seconds_sum{method="POST",uri="/api/v1/analyses",status="500"} 2.0',
            'http_server_requests_seconds_count{method="GET",uri="/api/v1/analyses",status="200"} 99',
            'http_server_requests_seconds_count{method="POST",uri="/api/v1/auth/login",status="200"} 55',
        ])
        metrics = measurement.parse_metrics(text)
        self.assertEqual(len(metrics), 2)
        successful = next(item for item in metrics if item["status"] == "202")
        self.assertEqual(successful["count"], 3)
        self.assertAlmostEqual(successful["sum"], 0.9)

    def test_concurrent_request_is_not_reported_as_one_sample(self):
        with self.assertRaises(ValueError):
            measurement.compare(snapshot(), snapshot(2, 0.4), ROUTE, 1)

    def test_restart_is_rejected_even_when_counters_increased(self):
        with self.assertRaises(ValueError):
            measurement.compare(snapshot(), snapshot(1, 0.2, started="new-process"), ROUTE, 1)

    def test_counter_reset_is_rejected(self):
        with self.assertRaises(ValueError):
            measurement.compare(snapshot(5, 1.0), snapshot(6, 0.1), ROUTE, 1)

    def test_first_request_can_create_a_new_metric(self):
        before = {"backend_started_at": "same", "requests": []}
        after = snapshot(1, 0.125, started="same")
        result = measurement.compare(before, after, ROUTE, 1)
        self.assertEqual(result["backend_mean_seconds"], 0.125)

    def test_nonfinite_timing_is_rejected(self):
        with self.assertRaises(ValueError):
            measurement.parse_metrics('http_server_requests_seconds_sum{method="POST",uri="/api/v1/analyses",status="202"} NaN')

    def test_missing_duration_is_not_reported_as_zero_latency(self):
        with self.assertRaises(ValueError):
            measurement.parse_metrics('http_server_requests_seconds_count{method="POST",uri="/api/v1/analyses",status="202"} 1')

    def test_observer_program_compiles(self):
        compile(measurement.REMOTE, "remote-observer", "exec")


if __name__ == "__main__":
    unittest.main()
