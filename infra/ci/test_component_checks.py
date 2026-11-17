"""Execute the real Jenkins shell with fake build tools and real cache/OS locks."""

import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time
import unittest


POLICY = next((p / "Jenkinsfile" for p in Path(__file__).resolve().parents if (p / "Jenkinsfile").is_file()), None)
POLICY_FIXTURE = Path(__file__).with_name("fixtures") / "component_checks.sh"
CACHE_SCRIPT = Path(__file__).with_name("result_cache.py")


def load_check_script(policy, fixture, function="componentCheckScript"):
    # 병합 전 develop의 Jenkinsfile은 infra/ci만 마운트한다. 동일한 검사를 fixture로 실행한다.
    snapshot = fixture.read_text(encoding="utf-8")
    if policy is None:
        return snapshot
    match = re.search(r"def " + function + r"\(\) \{\s*return '''(.*?)'''", policy.read_text(encoding="utf-8"), re.S)
    if match is None or match.group(1).rstrip() + "\n" != snapshot:
        raise ValueError("Jenkinsfile 검사 셸과 infra/ci/fixtures/component_checks.sh를 함께 갱신해야 합니다.")
    return snapshot


SCRIPT = load_check_script(POLICY, POLICY_FIXTURE)
HEAVY_SCRIPT = load_check_script(POLICY, POLICY_FIXTURE.with_name("heavy_task.sh"), "heavyTaskScript")


class CheckPolicyTests(unittest.TestCase):
    def test_isolated_ci_mount_runs_all_checks_with_bundled_policy(self):
        self.assertEqual(load_check_script(None, POLICY_FIXTURE), SCRIPT)

    def test_available_pipeline_must_match_bundled_policy(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "Jenkinsfile"
            policy.write_text("def componentCheckScript() { return '''" + SCRIPT + "'''\n}", encoding="utf-8")
            self.assertEqual(load_check_script(policy, POLICY_FIXTURE), SCRIPT)

    def test_policy_drift_is_an_error_instead_of_testing_stale_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / "Jenkinsfile"
            policy.write_text("def componentCheckScript() { return '''changed'''\n}", encoding="utf-8")
            with self.assertRaises(ValueError):
                load_check_script(policy, POLICY_FIXTURE)


FAKE_TOOL = r'''#!/usr/bin/env python3
import hashlib, json, os, pathlib, subprocess, sys, time
args = sys.argv[1:]
tool = pathlib.Path(sys.argv[0]).name
component = os.environ['CHECK_COMPONENT']
work = pathlib.Path(os.environ['WORKSPACE'])
if tool == 'git':
    if args[0] == 'cat-file': sys.exit(0)
    if args[0] == 'hash-object':
        body = sys.stdin.buffer.read()
        print(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest())
        sys.exit(0)
    if args == ['rev-parse', 'HEAD:ai/tests/fixtures/transform_cases.json']:
        fixture = work / 'ai/tests/fixtures/transform_cases.json'
        if not fixture.is_file(): sys.exit(1)
        body = fixture.read_bytes()
        print(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest())
        sys.exit(0)
    print(os.environ.get('TEST_TREE', 'a' * 40) if args[-1].startswith('HEAD:') and args[-1] != 'HEAD:Jenkinsfile' else 'b' * 40)
    sys.exit(0)
if tool == 'java':
    print(os.environ.get('TEST_RUNTIME', 'fixture-jdk21'), file=sys.stderr)
    sys.exit(0)
if tool == 'docker' and args[:2] == ['image', 'inspect']:
    print('sha256:fixture-runtime')
    sys.exit(0)
if tool == 'docker' and args[:2] == ['rm', '-f']:
    pathlib.Path(os.environ['TEST_REMOVED']).write_text(args[2])
    with open(os.environ['TEST_REMOVED_LOG'], 'a') as stream: stream.write(args[2] + '\n')
    sys.exit(0)
if tool == 'docker' and '/script/result_cache.py' in args:
    if os.environ.get('TEST_CACHE_BLOCK') == '1' and 'restore' in args:
        pathlib.Path(args[args.index('--cidfile') + 1]).write_text('fixture-cache')
        pathlib.Path(os.environ['TEST_CACHE_STARTED']).write_text('started')
        time.sleep(5)
    if os.environ.get('TEST_CACHE_FAIL') == '1': sys.exit(1)
    index = args.index('/script/result_cache.py')
    sys.exit(subprocess.call([sys.executable, os.environ['REAL_CACHE_SCRIPT'], *args[index + 1:],
        '--work', str(work / component), '--cache', str(pathlib.Path(os.environ['JENKINS_HOME']) / 'ci-results')]))
if tool == 'gradlew':
    assert args == ['--no-daemon', '--max-workers=2', 'spotlessCheck', 'test', '--rerun', 'build', 'jacocoTestReport'], args
else:
    assert args[args.index('--cpus') + 1] == '1', args
    assert args[args.index('--memory') + 1] == ('2g' if component == 'frontend' else '1g'), args
    pathlib.Path(args[args.index('--cidfile') + 1]).write_text('fixture-container')
    command = args[-1]
    expected = ('npm ci --no-audit --no-fund && npm run format:check && npm run lint && npm run test:coverage && npm run build'
        if component == 'frontend' else 'pip install -q -r requirements-dev.txt && ruff check . && pytest -q --junitxml=test-results/pytest.xml --cov --cov-report=xml')
    assert command == expected, command
events = pathlib.Path(os.environ['TEST_EVENTS'])
def event(phase):
    with events.open('a') as stream:
        stream.write(json.dumps({'phase': phase, 'time': time.monotonic(), 'workspace': str(work)}) + '\n')
event('start')
time.sleep(float(os.environ.get('TEST_DELAY', '0')))
if os.environ.get('TEST_FAIL') == '1':
    event('failed')
    sys.exit(23)
outputs = {
    'backend': {'build/classes/java/main/Example.class': 'compiled',
        'build/reports/jacoco/test/jacocoTestReport.xml': '<report/>',
        'build/test-results/test/TEST-example.xml': '<testsuite tests="2" failures="0" errors="0"/>'},
    'frontend': {'coverage/lcov.info': 'TN:\nSF:src/a.ts\nend_of_record\n'},
    'ai': {'coverage/coverage.xml': '<coverage/>',
        'test-results/pytest.xml': '<testsuite tests="2" failures="0" errors="0"/>'},
}
for relative, content in outputs[component].items():
    target = work / component / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
event('end')
'''


class ComponentChecksTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("git", "java", "docker"):
            self.executable(self.bin / name, FAKE_TOOL)
        self.events = self.root / "events.jsonl"

    def executable(self, path, content):
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    def environment(self, name, component="backend", **options):
        work = self.root / name
        (work / "infra/ci").mkdir(parents=True)
        (work / "infra/ci/result_cache.py").write_text("fixture")
        (work / component).mkdir()
        if component == "backend":
            self.executable(work / component / "gradlew", FAKE_TOOL)
            fixture = work / "ai/tests/fixtures/transform_cases.json"
            fixture.parent.mkdir(parents=True)
            fixture.write_text('[{"input": "a", "expected": "a"}]')
        return {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                "WORKSPACE": str(work), "JENKINS_HOME": str(self.root / "jenkins"),
                "CHECK_COMPONENT": component, "REAL_CACHE_SCRIPT": str(CACHE_SCRIPT),
                "TEST_EVENTS": str(self.events), "CHECK_POLICY_BODY": SCRIPT,
                "TEST_REMOVED": str(self.root / "removed"),
                "TEST_REMOVED_LOG": str(self.root / "removed-log"), **options}

    def start(self, environment, script=SCRIPT):
        process = subprocess.Popen(
            ["sh", "-c", script], cwd=environment["WORKSPACE"], env=environment,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            start_new_session=True,
        )
        self.addCleanup(self.stop, process)
        return process

    def stop(self, process):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
        process.communicate(timeout=5)

    def finish(self, process, expected=0):
        output, _ = process.communicate(timeout=20)
        self.assertEqual(process.returncode, expected, output)
        return output

    def records(self):
        return [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []

    def test_sequential_jobs_reuse_complete_success_without_repeating_any_checks(self):
        for component in ("backend", "frontend", "ai"):
            with self.subTest(component=component):
                self.finish(self.start(self.environment(component + "-mr", component)))
                output = self.finish(self.start(self.environment(component + "-sonar", component)))
                self.assertIn("Reused successful full check outputs", output)
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 3)

    def test_same_source_concurrent_jobs_run_full_checks_only_once(self):
        first = self.start(self.environment("mr", TEST_DELAY="0.6"))
        second = self.start(self.environment("sonar", TEST_DELAY="0.6"))
        outputs = [self.finish(first), self.finish(second)]
        self.assertEqual(sum("Reused successful full check outputs" in output for output in outputs), 1)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "end"])

    def test_different_sources_both_run_and_backend_tests_do_not_overlap(self):
        first = self.start(self.environment("mr", TEST_DELAY="0.4"))
        second = self.start(self.environment("sonar", TEST_TREE="c" * 40, TEST_DELAY="0.4"))
        self.finish(first)
        self.finish(second)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "end", "start", "end"])

    def test_different_components_share_one_heavy_slot(self):
        first = self.start(self.environment("frontend", "frontend", TEST_DELAY="0.3"))
        second = self.start(self.environment("ai", "ai", TEST_DELAY="0.3"))
        self.finish(first)
        self.finish(second)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "end", "start", "end"])

    def test_build_or_scanner_wrapper_cannot_overlap_component_checks(self):
        environment = self.environment("build", TEST_DELAY="0.4")
        environment["HEAVY_COMMAND"] = 'cd backend && ./gradlew --no-daemon --max-workers=2 spotlessCheck test --rerun build jacocoTestReport'
        first = self.start(environment, HEAVY_SCRIPT)
        second = self.start(self.environment("frontend", "frontend", TEST_DELAY="0.4"))
        self.finish(first)
        self.finish(second)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "end", "start", "end"])

    def test_heavy_wrapper_timeout_releases_slot_and_preserves_failure(self):
        environment = self.environment("build-timeout", TEST_DELAY="5")
        environment["HEAVY_COMMAND"] = 'cd backend && ./gradlew --no-daemon --max-workers=2 spotlessCheck test --rerun build jacocoTestReport'
        self.finish(self.start(environment, HEAVY_SCRIPT.replace('30m sh', '0.3s sh')), expected=124)
        self.finish(self.start(self.environment("retry", "frontend")))

    def test_heavy_wrapper_cancel_stops_children_before_releasing_slot(self):
        environment = self.environment("cancel-build", TEST_DELAY="5")
        environment["HEAVY_COMMAND"] = 'cd backend && ./gradlew --no-daemon --max-workers=2 spotlessCheck test --rerun build jacocoTestReport'
        process = self.start(environment, HEAVY_SCRIPT)
        deadline = time.monotonic() + 5
        while not self.records() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.records())
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.finish(self.start(self.environment("retry", "frontend")))

    def test_slot_timeout_does_not_start_checks_or_publish_success(self):
        import fcntl

        environment = self.environment("waiting")
        lock = Path(environment["JENKINS_HOME"]) / "ci-locks/heavy.lock"
        lock.parent.mkdir(parents=True)
        with lock.open("w") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            output = self.finish(self.start(environment, SCRIPT.replace('flock -w 2100', 'flock -w 0.1')), expected=1)
        self.assertIn("35분 초과", output)
        self.assertEqual(self.records(), [])

    def test_check_timeout_removes_container_without_publishing_and_releases_slot(self):
        environment = self.environment("timed-out", "frontend", TEST_DELAY="5")
        self.finish(self.start(environment, SCRIPT.replace('run_check 30m docker run', 'run_check 0.5s docker run')), expected=124)
        self.assertEqual((self.root / "removed").read_text(), 'fixture-container')
        cache = self.root / "jenkins/ci-results/frontend"
        self.assertFalse(cache.exists() and any(cache.iterdir()))
        self.finish(self.start(self.environment("retry", "frontend")))

    def test_cancelled_container_is_removed_and_slot_is_released(self):
        process = self.start(self.environment("cancelled", "ai", TEST_DELAY="5"))
        deadline = time.monotonic() + 5
        while not self.records() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.records())
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual((self.root / "removed").read_text(), 'fixture-container')
        self.finish(self.start(self.environment("retry", "ai")))

    def test_cache_timeout_cleans_helper_and_falls_back_to_full_checks(self):
        environment = self.environment('cache-timeout', TEST_CACHE_BLOCK='1',
            TEST_CACHE_STARTED=str(self.root / 'cache-started'))
        self.finish(self.start(environment, SCRIPT.replace('run_check 2m docker run', 'run_check 0.3s docker run')))
        self.assertIn('fixture-cache', (self.root / 'removed-log').read_text().splitlines())
        self.assertEqual([item['phase'] for item in self.records()], ['start', 'end'])

    def test_cancel_during_cache_restore_cleans_helper(self):
        started = self.root / 'cache-started'
        environment = self.environment('cache-cancel', TEST_CACHE_BLOCK='1',
            TEST_CACHE_STARTED=str(started))
        process = self.start(environment)
        deadline = time.monotonic() + 5
        while not started.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(started.exists())
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertIn('fixture-cache', (self.root / 'removed-log').read_text().splitlines())
        self.assertEqual(self.records(), [])

    def test_failed_checks_propagate_and_are_not_reused_by_waiting_job(self):
        failed = self.start(self.environment("failed", TEST_DELAY="0.3", TEST_FAIL="1"))
        # Ensure the failed producer owns the real OS lock before starting its waiter.
        deadline = time.monotonic() + 5
        while not self.records() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.records())
        waiting = self.start(self.environment("waiting"))
        self.finish(failed, expected=23)
        self.finish(waiting)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "failed", "start", "end"])

    def test_runtime_change_forces_complete_checks(self):
        self.finish(self.start(self.environment("first")))
        self.finish(self.start(self.environment("changed", TEST_RUNTIME="fixture-jdk22")))
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 2)

    def test_shared_fixture_change_with_same_backend_tree_forces_complete_checks(self):
        self.finish(self.start(self.environment("first")))
        changed = self.environment("changed")
        fixture = Path(changed["WORKSPACE"]) / "ai/tests/fixtures/transform_cases.json"
        fixture.write_text('[{"input": "a", "expected": "changed"}]')
        self.finish(self.start(changed))
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 2)

    def test_shared_fixture_deletion_cannot_reuse_previous_backend_success(self):
        self.finish(self.start(self.environment("first")))
        changed = self.environment("deleted", TEST_FAIL="1")
        (Path(changed["WORKSPACE"]) / "ai/tests/fixtures/transform_cases.json").unlink()
        self.finish(self.start(changed), expected=23)
        self.assertEqual([e["phase"] for e in self.records()], ["start", "end", "start", "failed"])

    def test_changed_execution_policy_forces_complete_checks(self):
        self.finish(self.start(self.environment("first")))
        self.finish(self.start(self.environment("changed", CHECK_POLICY_BODY=SCRIPT + "\n# changed policy")))
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 2)

    def test_corrupt_cached_output_runs_complete_checks_again(self):
        self.finish(self.start(self.environment("first")))
        cached = next((self.root / "jenkins/ci-results/backend").glob("*/outputs/build/classes/java/main/Example.class"))
        cached.write_text("broken")
        self.finish(self.start(self.environment("second")))
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 2)

    def test_unavailable_cache_does_not_skip_tests_or_fail_successful_checks(self):
        self.finish(self.start(self.environment("first", TEST_CACHE_FAIL="1")))
        self.finish(self.start(self.environment("second", TEST_CACHE_FAIL="1")))
        self.assertEqual(sum(e["phase"] == "start" for e in self.records()), 2)

    def test_frontend_and_ai_failure_is_not_converted_to_a_cache_success(self):
        for component in ("frontend", "ai"):
            with self.subTest(component=component):
                failed = self.environment(component + "-failed", component, TEST_FAIL="1")
                self.finish(self.start(failed), expected=23)
                cache_root = self.root / "jenkins/ci-results" / component
                self.assertFalse(cache_root.exists() and any(cache_root.iterdir()))
                self.finish(self.start(self.environment(component + "-retry", component)))

    def test_cancelled_process_group_releases_backend_lock_without_publishing(self):
        cancelled = self.start(self.environment("cancelled", TEST_DELAY="5"))
        deadline = time.monotonic() + 5
        while not self.records() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.records())
        os.killpg(cancelled.pid, signal.SIGTERM)
        cancelled.communicate(timeout=5)
        self.assertNotEqual(cancelled.returncode, 0)
        self.finish(self.start(self.environment("retry")))
        self.assertEqual([e["phase"] for e in self.records()], ["start", "start", "end"])


if __name__ == "__main__":
    unittest.main()
