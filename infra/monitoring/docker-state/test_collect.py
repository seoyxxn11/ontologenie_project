"""Docker 실행 없이 수집 실패·중지 컨테이너·지표 교체를 검사한다."""

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect


def container(**overrides):
    row = dict(id="abc123", name="/backend", project="axcore", service="backend",
               status="exited", restarts=3, oneoff="False", nano_cpus=0,
               cpu_quota=0, cpu_period=0, cpuset="")
    row.update(overrides)
    return row


class CollectorTests(unittest.TestCase):
    def test_cpu_caps_and_unlimited(self):
        self.assertEqual(collect.cpu_limit(container()), 0)
        self.assertEqual(collect.cpu_limit(container(nano_cpus=1_500_000_000)), 1.5)
        self.assertEqual(collect.cpu_limit(container(cpu_quota=50000, cpu_period=100000)), 0.5)
        self.assertEqual(collect.cpu_limit(container(cpu_quota=50000)), 0.5)
        self.assertEqual(collect.cpu_limit(container(cpuset="0-2,2,4")), 4)
        self.assertEqual(collect.cpu_limit(container(nano_cpus=2_000_000_000, cpuset="0")), 1)
        with self.assertRaises(ValueError):
            collect.cpu_limit(container(cpuset="3-1"))

    def test_host_network_bytes(self):
        output = collect.render_network(
            "Inter-| Receive | Transmit\n eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0\n", 100)
        self.assertIn('receive_bytes_total{device="eth0"} 100', output)
        self.assertIn('transmit_bytes_total{device="eth0"} 200', output)
        self.assertIn("collection_timestamp_seconds 100.000", output)
        for content in ("", "eth0: 100 200", "eth0: -1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0"):
            with self.subTest(content=content), self.assertRaises(ValueError):
                collect.render_network(content, 100)

    def test_stopped_container_retains_state_and_restart_count(self):
        output = collect.render([container()], "axcore", 100)
        self.assertIn('state="exited"} 1', output)
        self.assertIn('state="running"} 0', output)
        self.assertIn('container_id="abc123"} 3', output)
        self.assertIn("# TYPE axcore_docker_container_restart_count gauge", output)

    def test_recreated_container_reports_new_identity_and_zero(self):
        output = collect.render([container(id="new123", restarts=0)], "axcore", 100)
        self.assertIn('container_id="new123"} 0', output)
        self.assertNotIn("abc123", output)

    def test_foreign_project_and_oneoff_are_excluded(self):
        output = collect.render([
            container(), container(project="other", name="/foreign"),
            container(oneoff="True", name="/oneoff"),
        ], "axcore", 100)
        self.assertNotIn("foreign", output)
        self.assertNotIn("oneoff", output)

    def test_label_escaping_and_invalid_snapshot(self):
        self.assertEqual(collect.label('a"b\\c\nd'), 'a\\"b\\\\c\\nd')
        for rows in ([], [container(status="unknown")], [container(restarts=-1)]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                collect.render(rows, "axcore", 100)

    def test_inspection_failure_removes_previous_successful_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "docker-state.prom"
            output.write_text(collect.render([container()], "axcore", 100), encoding="utf-8")
            with patch("sys.argv", ["collect.py", "--project", "axcore", "--output", str(output)]), \
                    patch.object(collect, "collect", side_effect=subprocess.TimeoutExpired("docker", 10)), \
                    redirect_stderr(io.StringIO()):
                self.assertEqual(collect.main(), 1)
            self.assertIn("collection_success 0", output.read_text(encoding="utf-8"))
            self.assertNotIn("container_state", output.read_text(encoding="utf-8"))
            self.assertEqual(list(Path(directory).glob("*.tmp")), [])

    def test_success_replaces_existing_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "docker-state.prom"
            output.write_text("old", encoding="utf-8")
            with patch("sys.argv", ["collect.py", "--project", "axcore", "--output", str(output)]), \
                    patch.object(collect, "collect", return_value=[container()]), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(collect.main(), 0)
            self.assertIn("collection_success 1", output.read_text(encoding="utf-8"))
            self.assertNotIn("old", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
