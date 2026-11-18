"""Jenkinsfile의 실제 셸을 실행해 구형 MR과 잘못된 bind source를 검증한다."""

import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


PIPELINE = Path(__file__).resolve().parents[2] / "Jenkinsfile"
FAKE_CLI = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
if Path(sys.argv[0]).name == 'git':
    if args[0] == 'cat-file':
        path = args[-1]
        tracked = (
            'TRACKED_POLICY' if 'test_source_allowlist' in path
            else 'TRACKED_AI_POLICY' if 'source_policy.py' in path
            else 'TRACKED_MOUNTS' if 'test_jenkins_mounts' in path
            else 'TRACKED_CI'
        )
        sys.exit(0 if os.environ.get(tracked) == '1' else 1)
    if args[0] in ('rev-parse', 'hash-object'):
        print('a' * 40)
        sys.exit(0)
    sys.exit(2)
with open(os.environ['DOCKER_CALLS'], 'a') as handle:
    handle.write(json.dumps(args) + '\n')
if args[:2] == ['rm', '-f']:
    sys.exit(0)
if args[0] == 'run' and os.environ.get('BLOCK_INFRA') == '1':
    import time
    Path(args[args.index('--cidfile') + 1]).write_text('infra-fixture-container')
    Path(os.environ['INFRA_STARTED']).write_text('started')
    time.sleep(30)
if args[:2] == ['image', 'inspect']:
    print('sha256:test-image')
    sys.exit(0)
for index, argument in enumerate(args[:-1]):
    if argument not in ('-v', '--mount'):
        continue
    value = args[index + 1]
    source = value.split(':')[0] if argument == '-v' else dict(
        item.split('=', 1) for item in value.split(',') if '=' in item
    )['src']
    path = Path(source)
    if os.environ.get('DAEMON_ROOT'):
        try:
            path = Path(os.environ['DAEMON_ROOT']) / path.relative_to(os.environ['WORKSPACE'])
        except ValueError:
            pass
    if not path.exists():
        if argument == '-v':
            path.mkdir(parents=True)
        else:
            sys.exit(125)
sys.exit(0)
'''


@unittest.skipUnless(os.name != "nt" and shutil.which("bash"), "Linux bash 환경에서 실행합니다.")
class JenkinsMountTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.workspace = self.base / "workspace"
        for folder in ("infra/deploy", "infra/scripts", "backend", "bin"):
            (self.workspace / folder).mkdir(parents=True)
        for name in ("infra/deploy/sync-config.sh", "infra/scripts/pg-backup.sh"):
            (self.workspace / name).write_text("#!/bin/bash\n")
        for name in ("git", "docker"):
            executable = self.workspace / "bin" / name
            executable.write_text(FAKE_CLI)
            executable.chmod(0o755)
        java = self.workspace / "bin/java"
        java.write_text("#!/bin/sh\necho test-java >&2\n")
        java.chmod(0o755)
        gradle = self.workspace / "backend/gradlew"
        gradle.write_text("#!/bin/sh\necho full-check >> ../checks-ran\n")
        gradle.chmod(0o755)
        (self.workspace / "Jenkinsfile").write_text(PIPELINE.read_text())
        self.calls = self.base / "docker-calls"
        self.environment = {
            **os.environ,
            "PATH": f"{self.workspace / 'bin'}:{os.environ['PATH']}",
            "WORKSPACE": str(self.workspace),
            "JENKINS_HOME": str(self.base / "jenkins"),
            "TRACKED_CI": "0",
            "DOCKER_CALLS": str(self.calls),
            "CHECK_COMPONENT": "backend",
        }
        self.source = PIPELINE.read_text()

    def shell(self, marker):
        section = self.source.split(marker, 1)[1]
        return section.split("'''", 2)[1]

    def run_shell(self, marker):
        self.environment["CHECK_POLICY_BODY"] = self.shell("def componentCheckScript(")
        return subprocess.run(
            ["bash", "-e", "-c", self.shell(marker)],
            cwd=self.workspace, env=self.environment,
            capture_output=True, text=True, timeout=15,
        )

    def add_ci(self):
        folder = self.workspace / "infra/ci"
        folder.mkdir()
        for name in ("result_cache.py", "test_result_cache.py"):
            (folder / name).write_text("# fixture\n")
        self.environment["TRACKED_CI"] = "1"

    POLICY_FILES = (
        "infra/tests/test_source_allowlist.py",
        "infra/source-db/sql/allowlist.sql",
        "backend/src/main/resources/application.yml",
    )
    AI_POLICY = "ai/app/source_policy.py"

    def add_policy(self, ai=True):
        for name in self.POLICY_FILES + ((self.AI_POLICY,) if ai else ()):
            path = self.workspace / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# fixture\n")
        self.environment["TRACKED_POLICY"] = "1"
        self.environment["TRACKED_AI_POLICY"] = "1" if ai else "0"

    def policy_mounts(self, call):
        mounts = [call[i + 1] for i, argument in enumerate(call) if argument == "--mount"]
        self.assertTrue(all(mount.endswith(",readonly") for mount in mounts))
        return [next(item for item in mount.split(",") if item.startswith("dst=")) for mount in mounts]

    def docker_calls(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_infra_timeout_removes_its_container(self):
        self.environment['BLOCK_INFRA'] = '1'
        self.environment['INFRA_STARTED'] = str(self.base / 'infra-started')
        self.source = self.source.replace('5m docker run', '0.3s docker run')
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
        calls = self.docker_calls()
        self.assertEqual(calls[-1], ['rm', '-f', 'infra-fixture-container'])
        cidfile = Path(calls[0][calls[0].index('--cidfile') + 1])
        self.assertFalse(cidfile.parent.exists())

    def test_infra_cancel_removes_its_container(self):
        self.environment['BLOCK_INFRA'] = '1'
        started = self.base / 'infra-started'
        self.environment['INFRA_STARTED'] = str(started)
        process = subprocess.Popen(
            ['bash', '-e', '-c', self.shell("stage('Infra check')")],
            cwd=self.workspace, env=self.environment, start_new_session=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            deadline = time.monotonic() + 5
            while not started.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(started.exists())
            os.killpg(process.pid, signal.SIGTERM)
            process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertEqual(self.docker_calls()[-1], ['rm', '-f', 'infra-fixture-container'])
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)

    def test_old_mr_does_not_create_ci_directory(self):
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.workspace / "infra/ci").exists())
        self.assertEqual(len(self.docker_calls()), 1)
        self.assertIn("캐시 도입 전", result.stdout)

    def test_untracked_leftover_directory_is_not_treated_as_ci_support(self):
        self.add_ci()
        self.environment["TRACKED_CI"] = "0"
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_supported_commit_runs_both_infra_suites(self):
        self.add_ci()
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.docker_calls()), 2)

    def test_missing_tracked_test_is_an_error(self):
        self.add_ci()
        (self.workspace / "infra/ci/test_result_cache.py").unlink()
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_policy_comparison_mounts_only_its_files_read_only(self):
        self.add_ci()
        self.add_policy()
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.docker_calls()
        self.assertEqual(len(calls), 3)
        policy = calls[-1]
        options = policy[: policy.index("python:3.11-slim")]
        self.assertIn("none", options)
        self.assertNotIn("-v", options)
        self.assertEqual(
            [f"dst=/repo/{name}" for name in self.POLICY_FILES + (self.AI_POLICY,)],
            self.policy_mounts(policy),
        )
        self.assertIn("test_source_allowlist.py", policy)

    def test_commit_without_ai_copy_compares_without_it(self):
        self.add_policy(ai=False)
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [f"dst=/repo/{name}" for name in self.POLICY_FILES],
            self.policy_mounts(self.docker_calls()[-1]),
        )

    def test_commit_without_policy_test_skips_comparison(self):
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("395 이전 코드", result.stdout)

    def test_missing_policy_file_is_an_error(self):
        self.add_policy()
        (self.workspace / "infra/source-db/sql/allowlist.sql").unlink()
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_linked_policy_file_is_not_mounted(self):
        # MR이 정책 파일을 작업 폴더 밖 파일로 가는 링크로 바꾸면 붙이지 않고 멈춘다.
        self.add_policy()
        outside = self.base / "secret"
        outside.write_text("secret\n")
        target = self.workspace / "backend/src/main/resources/application.yml"
        target.unlink()
        target.symlink_to(outside)
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def add_mount_tests(self):
        folder = self.workspace / "infra/tests"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "test_jenkins_mounts.py").write_text("# fixture\n")
        self.environment["TRACKED_MOUNTS"] = "1"

    def test_mount_tests_run_with_pipeline_and_test_file_only(self):
        self.add_mount_tests()
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.docker_calls()
        self.assertEqual(len(calls), 2)
        self.assertEqual(
            ["dst=/repo/Jenkinsfile", "dst=/repo/infra/tests/test_jenkins_mounts.py"],
            self.policy_mounts(calls[-1]),
        )
        self.assertIn("test_jenkins_mounts.py", calls[-1])
        self.assertIn("none", calls[-1][: calls[-1].index("python:3.11-slim")])

    def test_linked_pipeline_is_not_mounted(self):
        self.add_mount_tests()
        outside = self.base / "pipeline"
        outside.write_text("# outside\n")
        pipeline = self.workspace / "Jenkinsfile"
        pipeline.unlink()
        pipeline.symlink_to(outside)
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_linked_policy_directory_is_not_mounted(self):
        self.add_policy()
        outside = self.base / "elsewhere"
        outside.mkdir()
        (outside / "allowlist.sql").write_text("# fixture\n")
        sql = self.workspace / "infra/source-db/sql"
        (sql / "allowlist.sql").unlink()
        sql.rmdir()
        sql.symlink_to(outside)
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_old_mr_cache_publish_and_restore_never_invoke_docker(self):
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.workspace / "checks-ran").exists())
        self.assertFalse((self.workspace / "infra/ci").exists())
        self.assertEqual(self.docker_calls(), [])

    def test_missing_host_source_is_not_created_by_infra_or_cache(self):
        self.add_ci()
        daemon = self.base / "daemon-workspace"
        (daemon / "infra/deploy").mkdir(parents=True)
        self.environment["DAEMON_ROOT"] = str(daemon)
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.workspace / "checks-ran").exists())
        self.assertFalse((daemon / "infra/ci").exists())

    def test_supported_cache_uses_existing_sources(self):
        self.add_ci()
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.docker_calls()
        self.assertEqual(len(calls), 1)
        self.assertNotIn("-v", calls[0])
        self.assertEqual(calls[0].count("--mount"), 3)


class ShellTestsRequiredTests(unittest.TestCase):
    """CI(Infra check)는 AXCORE_REQUIRE_SHELL_TESTS=1로 돌린다. 그때 bash가 없어 위 시험이 모두 건너뛰어지면 통과가 아니라 실패다."""

    def test_shell_tests_can_run_where_required(self):
        if os.environ.get("AXCORE_REQUIRE_SHELL_TESTS") != "1":
            self.skipTest("로컬 실행")
        self.assertTrue(os.name != "nt" and shutil.which("bash"), "Jenkins 셸 시험을 돌릴 bash가 없습니다")


if __name__ == "__main__":
    unittest.main()
