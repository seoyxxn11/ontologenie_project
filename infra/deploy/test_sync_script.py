"""실제 Docker 대신 가짜 CLI로 배포 셸의 성공·실패·복구 흐름을 실행한다."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys

args = sys.argv[1:]
state = Path(os.environ['TEST_STATE'])
with (state / 'commands').open('a') as handle:
    handle.write(' '.join(args) + '\n')
if args[0] == 'run':
    remaining = args[args.index('python') + 1:]
    remaining[0] = str(Path(os.environ['TEST_HELPER']) / 'configuration.py')
    remaining[remaining.index('--source') + 1] = os.environ['TEST_SOURCE']
    sys.exit(subprocess.run([sys.executable, *remaining]).returncode)
if args[0] == 'compose':
    if 'config' in args and '--quiet' in args and os.environ.get('TEST_FAIL_INITIAL_CONFIG') == '1':
        sys.exit(1)
    if 'config' in args and '--format' in args:
        project = args[args.index('--project-name') + 1]
        services = {'grafana': {'image': 'fake:1'}, 'nginx': {'image': 'fake:1'}} if project == 'axcore' else {}
        print(json.dumps({'name': project, 'services': services}))
    if 'up' in args:
        count = int((state / 'ups').read_text()) if (state / 'ups').exists() else 0
        (state / 'ups').write_text(str(count + 1))
    sys.exit(0)
if args[0] == 'inspect':
    template = args[args.index('--format') + 1]
    if 'Health' in template:
        ups = int((state / 'ups').read_text())
        if args[-1] == 'kafka-console':
            print('true false none')
            sys.exit(0)
        fail = os.environ.get('TEST_FAIL_HEALTH') == '1' and ups == 1
        fail = fail or os.environ.get('TEST_FAIL_ROLLBACK') == '1'
        print('true false unhealthy' if fail else 'true false healthy')
    else:
        print('true')
    sys.exit(0)
if args[0] == 'exec':
    if 'http://kafka-console:8080/' in args and os.environ.get('TEST_FAIL_CONSOLE_HTTP') == '1':
        sys.exit(1)
    sys.exit(0)
sys.exit(2)
'''


@unittest.skipUnless(os.name != "nt" and shutil.which("bash"), "Linux bash 환경에서 실행합니다.")
class ConfigurationShellTests(unittest.TestCase):
    def run_case(self, fail=False, rollback_fail=False, console=False, console_http_fail=False, initial_config_fail=False):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root, source, state, commands = (base / name for name in ("server", "source", "state", "bin"))
            for folder in (root, source, state, commands):
                folder.mkdir()
            for folder in (root, source):
                (folder / "cdc").mkdir()
                (folder / "compose.yaml").write_text("services: {}\n")
                (folder / "cdc/compose.yaml").write_text("services: {}\n")
            (root / ".env").write_text("POSTGRES_DB=fake\n")
            (root / "cdc/.env").write_text("# fake\n")
            (root / ".env").write_text("POSTGRES_DB=fake\nAI_ONTOLOGY_LLM_MODEL=new-model\nBACKEND_TAG=abc1234\nPOSTGRES_PASSWORD=fake-only\n")
            (root / ".deployment-settings-before.json").write_text(json.dumps({
                "main": {"POSTGRES_DB": "fake", "AI_ONTOLOGY_LLM_MODEL": "old-model"}, "cdc": {},
            }))
            name = "cdc/console.yaml" if console else "monitoring/grafana/dashboards/test.json"
            for folder, content in ((root, '{"title":"old"}'), (source, '{"title":"new"}')):
                target = folder / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            docker = commands / "docker"
            docker.write_text(FAKE_DOCKER)
            docker.chmod(0o755)
            sleeper = commands / "sleep"
            sleeper.write_text("#!/bin/sh\nexit 0\n")
            sleeper.chmod(0o755)
            helper = Path(__file__).resolve().parent
            environment = {
                **os.environ, "PATH": f"{commands}:{os.environ['PATH']}",
                "DEPLOY_DIR": str(root), "WORKSPACE": str(base / "workspace"),
                "BUILD_NUMBER": "12", "GIT_SHA": "abc1234",
                "TEST_STATE": str(state), "TEST_SOURCE": str(source), "TEST_HELPER": str(helper),
                "TEST_FAIL_HEALTH": "1" if fail else "0",
                "TEST_FAIL_ROLLBACK": "1" if rollback_fail else "0",
                "TEST_FAIL_CONSOLE_HTTP": "1" if console_http_fail else "0",
                "TEST_FAIL_INITIAL_CONFIG": "1" if initial_config_fail else "0",
            }
            result = subprocess.run(
                ["bash", str(helper / "sync-config.sh")], env=environment,
                capture_output=True, text=True, timeout=30,
            )
            manifest = root / ".managed-config.json"
            transaction = root / "backup/config-abc1234-12"
            return {
                "code": result.returncode, "output": result.stdout + result.stderr,
                "title": json.loads((root / name).read_text())["title"],
                "manifest": manifest.exists(), "ups": int((state / "ups").read_text()) if (state / "ups").exists() else 0,
                "runtime": (root / ".env").read_text(),
                "commands": (state / "commands").read_text(),
                "secret_snapshots": list(transaction.glob("*-old.json")) + list(transaction.glob("*-new.json")),
            }

    def test_success_promotes_and_cleans_rendered_secrets(self):
        result = self.run_case()
        self.assertEqual(result["code"], 0, result["output"])
        self.assertEqual(result["title"], "new")
        self.assertTrue(result["manifest"])
        self.assertEqual(result["ups"], 1)
        self.assertEqual(result["secret_snapshots"], [])

    def test_failed_health_restores_files_restarts_and_rechecks(self):
        result = self.run_case(fail=True)
        self.assertNotEqual(result["code"], 0)
        self.assertEqual(result["title"], "old")
        self.assertFalse(result["manifest"])
        self.assertEqual(result["ups"], 2)
        self.assertIn("이전 설정·서비스 복구 검사 통과", result["output"])
        self.assertEqual(result["secret_snapshots"], [])

    def test_failed_rollback_is_not_reported_as_success(self):
        result = self.run_case(fail=True, rollback_fail=True)
        self.assertNotEqual(result["code"], 0)
        self.assertIn("복구 검사 실패", result["output"])
        self.assertNotIn("복구 검사 통과", result["output"])
        self.assertEqual(result["secret_snapshots"], [])

    def test_console_without_healthcheck_uses_http(self):
        result = self.run_case(console=True)
        self.assertEqual(result["code"], 0, result["output"])
        self.assertIn("http://kafka-console:8080/", result["commands"])
        self.assertEqual(result["ups"], 1)

    def test_console_http_failure_is_not_accepted(self):
        result = self.run_case(console=True, console_http_fail=True)
        self.assertNotEqual(result["code"], 0)
        self.assertEqual(result["title"], "old")
        self.assertEqual(result["ups"], 2)
        self.assertIn("복구 검사 실패", result["output"])

    def test_initial_compose_failure_restores_settings_before_staging(self):
        result = self.run_case(initial_config_fail=True)
        self.assertNotEqual(result["code"], 0)
        self.assertEqual(result["ups"], 0)
        self.assertEqual(result["title"], "old")
        self.assertIn("AI_ONTOLOGY_LLM_MODEL=old-model", result["runtime"])
        self.assertIn("BACKEND_TAG=abc1234", result["runtime"])
        self.assertIn("POSTGRES_PASSWORD=fake-only", result["runtime"])
        self.assertNotIn("configuration.py prepare ", result["commands"])


if __name__ == "__main__":
    unittest.main()
