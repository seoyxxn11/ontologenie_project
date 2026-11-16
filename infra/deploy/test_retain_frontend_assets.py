"""실제 배포 셸을 가짜 Docker CLI로 실행해 보관 순서와 실패 경계를 확인한다."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).parent
FAKE_DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

args = sys.argv[1:]
root = Path(os.environ['TEST_ROOT'])
with (root / 'calls').open('a') as handle:
    handle.write(json.dumps(args) + '\n')
if args[0] == 'ps':
    if os.environ.get('FAIL_DAEMON') == '1': sys.exit(1)
    if os.environ.get('FIRST_DEPLOY') != '1': print('a' * 12)
elif args[0] == 'inspect': print('sha256:' + 'a' * 64)
elif args[:2] == ['image', 'inspect']: print('sha256:' + 'b' * 64)
elif args[0] == 'create': print('b' * 12)
elif args[0] == 'cp':
    if os.environ.get('FAIL_COPY') == '1': sys.exit(1)
    source = root / ('old' if args[1].startswith('a' * 12) else 'new')
    shutil.copytree(source, args[2], dirs_exist_ok=True)
elif args[0] == 'run':
    assert args[args.index('--network') + 1] == 'none'
    remaining = args[args.index('python') + 1:]
    remaining[0] = str(root / 'workspace/infra/deploy/frontend_assets.py')
    remaining = [value.replace('/archive', str(root / 'server/frontend-assets')) for value in remaining]
    sys.exit(subprocess.run([sys.executable, *remaining]).returncode)
elif args[0] != 'rm': sys.exit(2)
'''


@unittest.skipUnless(os.name != "nt" and shutil.which("bash"), "Linux bash 환경에서 실행합니다.")
class RetainFrontendShellTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.archive = self.root / "server/frontend-assets"
        self.archive.mkdir(parents=True)
        self.helper = self.root / "workspace/infra/deploy"
        self.helper.mkdir(parents=True)
        for name in ["retain-frontend-assets.sh", "frontend_assets.py"]:
            shutil.copyfile(HELPER / name, self.helper / name)
        for folder in ["old", "new", "bin"]:
            (self.root / folder).mkdir()
        (self.root / "old/old.js").write_text("old")
        (self.root / "old/shared.css").write_text("shared")
        (self.root / "new/new.js").write_text("new")
        (self.root / "new/shared.css").write_text("shared")
        docker = self.root / "bin/docker"
        docker.write_text(FAKE_DOCKER)
        docker.chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "TEST_ROOT": str(self.root),
            "WORKSPACE": str(self.root / "workspace"),
            "DEPLOY_DIR": str(self.root / "server"),
            "NEXT_FRONTEND_IMAGE": "axcore-frontend:abcdef12",
        }

    def run_shell(self, **environment):
        result = subprocess.run(
            ["bash", str(self.helper / "retain-frontend-assets.sh")],
            env={**self.env, **environment}, capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(list(self.archive.glob(".incoming.*")), [], result.stderr)
        return result

    def calls(self):
        file = self.root / "calls"
        return [json.loads(line) for line in file.read_text().splitlines()] if file.exists() else []

    def test_collects_current_and_next_without_running_or_changing_services(self):
        result = self.run_shell()
        self.assertEqual(result.returncode, 0, result.stderr)
        for filename in ["old.js", "new.js", "shared.css"]:
            self.assertTrue((self.archive / "assets" / filename).is_file())
        calls = self.calls()
        self.assertTrue(any(call[:2] == ["rm", "b" * 12] for call in calls))
        self.assertFalse(any(call[0] in ["start", "stop", "restart", "compose", "exec"] for call in calls))
        self.assertEqual(sum(call[0] == "create" for call in calls), 1)

    def test_first_deployment_without_current_container_is_supported(self):
        result = self.run_shell(FIRST_DEPLOY="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.archive / "assets/new.js").is_file())
        self.assertFalse((self.archive / "assets/old.js").exists())

    def test_copy_failure_stops_before_publishing(self):
        result = self.run_shell(FAIL_COPY="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.archive / "releases.json").exists())
        self.assertFalse(any(call[0] == "run" for call in self.calls()))

    def test_publish_failure_stops_and_removes_only_created_container(self):
        (self.root / "new/shared.css").write_text("conflicting content")
        result = self.run_shell()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.archive / "releases.json").exists())
        removals = [call for call in self.calls() if call[0] == "rm"]
        self.assertEqual(removals, [["rm", "b" * 12]])

    def test_daemon_failure_is_not_treated_as_first_deployment(self):
        result = self.run_shell(FAIL_DAEMON="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call[0] for call in self.calls()], ["ps"])

    def test_invalid_retention_is_rejected_before_docker(self):
        for days in ["0", "-1", "7; echo invalid"]:
            with self.subTest(days=days):
                result = self.run_shell(FRONTEND_ASSET_RETENTION_DAYS=days)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
