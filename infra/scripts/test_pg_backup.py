"""Run the real backup shell against a fake Docker CLI and disposable files."""

import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).with_name("pg-backup.sh").read_text(encoding="utf-8")
FAKE_DOCKER = r'''#!/usr/bin/env python3
import os, pathlib, sys, time
args = sys.argv[1:]
if 'printenv' in args:
    time.sleep(float(os.environ.get('TEST_ENV_DELAY', '0')))
    print(os.environ.get('TEST_DB', 'axcore') if args[-1] == 'POSTGRES_DB' else os.environ.get('TEST_USER', 'axcore'))
elif 'pg_dump' in args:
    assert args[:11] == ['exec', 'postgres', 'flock', '-w', '120', '/tmp/axcore-pg-backup.lock',
                         'timeout', '-k', '30s', '15m', 'pg_dump'], args
    events = pathlib.Path(os.environ['TEST_EVENTS'])
    with events.open('a') as handle: handle.write('start\n')
    if os.environ.get('TEST_EMPTY') != '1': print('PGDMP fixture', flush=True)
    time.sleep(float(os.environ.get('TEST_DELAY', '0')))
    if os.environ.get('TEST_DUMP_FAIL') == '1': sys.exit(23)
    with events.open('a') as handle: handle.write('end\n')
elif 'pg_restore' in args:
    assert args == ['exec', '-i', 'postgres', 'timeout', '-k', '5s', '60s', 'pg_restore', '--list'], args
    assert sys.stdin.read().startswith('PGDMP')
    time.sleep(float(os.environ.get('TEST_RESTORE_DELAY', '0')))
    if os.environ.get('TEST_INVALID_ARCHIVE') == '1': sys.exit(24)
else:
    sys.exit('unexpected Docker operation: ' + repr(args))
'''


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(FAKE_DOCKER, encoding="utf-8")
        docker.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "TEST_EVENTS": str(self.root / "events"),
        }
        # Only redirect the fixed deployment path; keep production commands/traps intact.
        self.script = SCRIPT.replace("cd /home/ubuntu/axcore", f"cd '{self.root}'")

    def start(self, **values):
        process = subprocess.Popen(
            ["bash", "-c", self.script], env={**self.environment, **values},
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
        output, _ = process.communicate(timeout=10)
        self.assertEqual(process.returncode, expected, output)
        return output

    def wait_for_dump(self):
        events = self.root / "events"
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if events.exists() and 'start' in events.read_text():
                return
            time.sleep(0.01)
        self.fail("pg_dump did not start")

    def assert_no_partial(self):
        self.assertEqual(list((self.root / "backup").glob("*.partial")), [])

    def test_validated_dump_is_private_and_atomically_published(self):
        process = self.start(TEST_DELAY="0.3")
        self.wait_for_dump()
        backup = self.root / "backup"
        self.assertEqual(list(backup.glob("*.dump")), [])
        self.assertEqual(len(list(backup.glob("*.partial"))), 1)
        self.assertIn('backup OK', self.finish(process))
        dump, = backup.glob("*.dump")
        self.assertTrue(dump.read_text().startswith('PGDMP'))
        self.assertEqual(stat.S_IMODE(dump.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(backup.stat().st_mode), 0o700)
        self.assert_no_partial()

    def test_dump_failure_keeps_previous_backup_and_does_not_run_retention(self):
        backup = self.root / "backup"
        backup.mkdir()
        old = backup / "axcore_old.dump"
        old.write_text('previous')
        os.utime(old, (0, 0))
        self.finish(self.start(TEST_DUMP_FAIL="1"), expected=23)
        self.assertEqual(old.read_text(), 'previous')
        self.assertEqual(list(backup.glob("*.dump")), [old])
        self.assert_no_partial()

    def test_invalid_archive_is_not_published(self):
        self.finish(self.start(TEST_INVALID_ARCHIVE="1"), expected=24)
        self.assertEqual(list((self.root / "backup").glob("*.dump")), [])
        self.assert_no_partial()

    def test_empty_dump_is_not_published(self):
        self.finish(self.start(TEST_EMPTY="1"), expected=1)
        self.assertEqual(list((self.root / "backup").glob("*.dump")), [])
        self.assert_no_partial()

    def test_concurrent_backups_are_serialized_and_have_unique_names(self):
        first = self.start(TEST_DELAY="0.3")
        self.wait_for_dump()
        second = self.start(TEST_DELAY="0.3")
        self.finish(first)
        self.finish(second)
        self.assertEqual((self.root / "events").read_text().splitlines(), ['start', 'end', 'start', 'end'])
        self.assertEqual(len(list((self.root / "backup").glob("*.dump"))), 2)
        self.assert_no_partial()

    def test_cancel_removes_partial_and_releases_backup_lock(self):
        process = self.start(TEST_DELAY="5")
        self.wait_for_dump()
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assert_no_partial()
        self.finish(self.start())

    def test_parent_cancel_terminates_client_and_cleans_partial(self):
        process = self.start(TEST_DELAY="5")
        self.wait_for_dump()
        os.kill(process.pid, signal.SIGTERM)
        self.finish(process, expected=143)
        self.assert_no_partial()
        self.finish(self.start())

    def test_dump_timeout_is_failure_without_a_completed_dump(self):
        self.script = self.script.replace('run_backup_command 18m', 'run_backup_command 0.3s')
        self.finish(self.start(TEST_DELAY="5"), expected=124)
        self.assertEqual(list((self.root / "backup").glob("*.dump")), [])
        self.assert_no_partial()

    def test_validation_timeout_does_not_publish_or_expire_previous_dump(self):
        backup = self.root / 'backup'
        backup.mkdir()
        previous = backup / 'axcore_old.dump'
        previous.write_text('previous')
        os.utime(previous, (0, 0))
        self.script = self.script.replace('run_backup_command 75s', 'run_backup_command 0.3s')
        self.finish(self.start(TEST_RESTORE_DELAY='5'), expected=124)
        self.assertEqual(list(backup.glob('*.dump')), [previous])
        self.assert_no_partial()

    def test_lock_wait_is_bounded_and_does_not_start_a_dump(self):
        import fcntl

        backup = self.root / "backup"
        backup.mkdir()
        self.script = self.script.replace('flock -w 120 ', 'flock -w 0.1 ')
        with (backup / '.pg-backup.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.finish(self.start(), expected=1)
        self.assertFalse((self.root / "events").exists())

    def test_success_retains_other_files_and_only_expires_old_database_dumps(self):
        backup = self.root / "backup"
        backup.mkdir()
        for name in ('axcore_old.dump', 'other_old.dump', 'config-old.json'):
            path = backup / name
            path.write_text('old')
            os.utime(path, (0, 0))
        self.finish(self.start())
        self.assertFalse((backup / 'axcore_old.dump').exists())
        self.assertTrue((backup / 'other_old.dump').exists())
        self.assertTrue((backup / 'config-old.json').exists())

    def test_retention_and_kept_count_exclude_nested_configuration_backups(self):
        backup = self.root / 'backup'
        nested = backup / 'config-old'
        nested.mkdir(parents=True)
        preserved = nested / 'axcore_old.dump'
        preserved.write_text('configuration transaction backup')
        os.utime(preserved, (0, 0))
        (backup / 'unrelated.txt').write_text('keep')
        self.assertIn('kept 1 dumps', self.finish(self.start()))
        self.assertTrue(preserved.exists())

    def test_invalid_database_name_stops_before_dump(self):
        self.finish(self.start(TEST_DB='axcore;bad'), expected=1)
        self.assertFalse((self.root / "events").exists())

    def test_invalid_database_user_stops_before_dump(self):
        self.finish(self.start(TEST_USER='axcore;bad'), expected=1)
        self.assertFalse((self.root / 'events').exists())

    def test_metadata_timeout_stops_before_creating_partial_dump(self):
        self.script = self.script.replace('timeout -k 5s 15s docker exec', 'timeout -k 5s 0.3s docker exec')
        self.finish(self.start(TEST_ENV_DELAY='5'), expected=124)
        self.assertFalse((self.root / 'events').exists())
        self.assert_no_partial()


if __name__ == "__main__":
    unittest.main()
