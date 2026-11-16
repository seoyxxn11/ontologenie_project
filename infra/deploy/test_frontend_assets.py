"""배포 교체·롤백·보관 만료 시 실제 파일과 manifest를 검증한다."""

import json
import os
from pathlib import Path
import tempfile
import unittest

from frontend_assets import inside, load_releases, retain


DAY = 86400
A, B, C = ("sha256:" + digit * 64 for digit in "abc")


class FrontendAssetTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.archive = self.base / "archive"
        self.archive.mkdir()

    def snapshot(self, name, files):
        directory = self.base / name
        directory.mkdir()
        for filename, content in files.items():
            target = directory / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        return directory

    def test_new_deployment_keeps_old_js_css_and_dependencies(self):
        old = self.snapshot("old", {"CheckRunsPage-old.js": b"old", "old.css": b"css", "shared.js": b"shared"})
        new = self.snapshot("new", {"CheckRunsPage-new.js": b"new", "new.css": b"new css", "shared.js": b"shared"})
        retain(self.archive, {A: old}, 7, now=0)
        retain(self.archive, {A: old, B: new}, 7, now=DAY)
        for filename in ["CheckRunsPage-old.js", "old.css", "CheckRunsPage-new.js", "new.css", "shared.js"]:
            self.assertTrue((self.archive / "assets" / filename).is_file())

    def test_long_running_release_is_retained_from_replacement_not_build_time(self):
        old = self.snapshot("old", {"old.js": b"old"})
        new = self.snapshot("new", {"new.js": b"new"})
        retain(self.archive, {A: old}, 7, now=0)
        retain(self.archive, {A: old, B: new}, 7, now=30 * DAY)
        self.assertEqual(load_releases(self.archive)[A]["last_seen"], 30 * DAY)
        self.assertTrue((self.archive / "assets/old.js").is_file())

    def test_expiration_keeps_current_files_shared_dependencies_and_unmanaged_files(self):
        old = self.snapshot("old", {"old.js": b"old", "shared.js": b"shared"})
        current = self.snapshot("current", {"current.js": b"current", "shared.js": b"shared"})
        new = self.snapshot("new", {"new.js": b"new"})
        retain(self.archive, {A: old}, 7, now=0)
        retain(self.archive, {A: old, B: current}, 7, now=DAY)
        unmanaged = self.archive / "assets/manual.js"
        unmanaged.write_bytes(b"manual")
        result = retain(self.archive, {B: current, C: new}, 7, now=9 * DAY)
        self.assertFalse((self.archive / "assets/old.js").exists())
        self.assertTrue((self.archive / "assets/shared.js").exists())
        self.assertTrue((self.archive / "assets/current.js").exists())
        self.assertEqual(unmanaged.read_bytes(), b"manual")
        self.assertEqual(result["expired_assets"], 1)
        self.assertEqual(set(load_releases(self.archive)), {B, C})

    def test_exact_retention_boundary_is_not_removed_early(self):
        old = self.snapshot("old", {"old.js": b"old"})
        new = self.snapshot("new", {"new.js": b"new"})
        retain(self.archive, {A: old}, 7, now=0)
        retain(self.archive, {B: new}, 7, now=7 * DAY)
        self.assertTrue((self.archive / "assets/old.js").exists())
        retain(self.archive, {B: new}, 7, now=7 * DAY + 1)
        self.assertFalse((self.archive / "assets/old.js").exists())

    def test_failed_deployment_can_roll_back_while_both_versions_remain_available(self):
        old = self.snapshot("old", {"old.js": b"old"})
        attempted = self.snapshot("attempted", {"attempted.js": b"attempted"})
        retain(self.archive, {A: old, B: attempted}, 7, now=DAY)
        # 배포 실패 후 기존 이미지가 다시 현재 이미지가 되어도 새 탭이 요청한 파일은 남는다.
        retain(self.archive, {A: old}, 7, now=2 * DAY)
        self.assertTrue((self.archive / "assets/old.js").is_file())
        self.assertTrue((self.archive / "assets/attempted.js").is_file())

    def test_conflicting_content_fails_before_overwrite_expiration_or_state_change(self):
        old = self.snapshot("old", {"same.js": b"old", "expired.js": b"keep on error"})
        new = self.snapshot("new", {"same.js": b"different"})
        retain(self.archive, {A: old}, 7, now=0)
        manifest = (self.archive / "releases.json").read_bytes()
        with self.assertRaises(ValueError):
            retain(self.archive, {B: new}, 7, now=30 * DAY)
        self.assertEqual((self.archive / "assets/same.js").read_bytes(), b"old")
        self.assertTrue((self.archive / "assets/expired.js").exists())
        self.assertEqual((self.archive / "releases.json").read_bytes(), manifest)

    def test_corrupt_manifest_or_unsafe_managed_path_aborts_without_deleting_files(self):
        source = self.snapshot("source", {"new.js": b"new"})
        sentinel = self.base / "keep.js"
        sentinel.write_bytes(b"outside")
        for content in ["invalid json", json.dumps({"version": 1, "releases": {A: {"last_seen": 0, "files": ["../../keep.js"]}}})]:
            with self.subTest(content=content):
                (self.archive / "releases.json").write_text(content)
                with self.assertRaises(ValueError):
                    retain(self.archive, {B: source}, 7, now=30 * DAY)
                self.assertEqual(sentinel.read_bytes(), b"outside")

    def test_empty_missing_or_hidden_snapshot_fails_closed(self):
        empty = self.snapshot("empty", {})
        hidden = self.snapshot("hidden", {".env": b"must not publish"})
        for source in [empty, self.base / "missing", hidden]:
            with self.subTest(source=source):
                with self.assertRaises(ValueError):
                    retain(self.archive, {A: source}, 7, now=0)
                self.assertFalse((self.archive / "assets").exists())

    def test_invalid_retention_does_not_publish(self):
        source = self.snapshot("source", {"new.js": b"new"})
        for days in [0, -1, True, "7"]:
            with self.subTest(days=days), self.assertRaises(ValueError):
                retain(self.archive, {A: source}, days, now=0)

    def test_path_traversal_and_noncanonical_paths_are_rejected(self):
        for name in ["../file.js", "nested/../file.js", "/file.js", "nested//file.js", "nested\\file.js", ".hidden", ""]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                inside(self.archive, name)

    @unittest.skipUnless(os.name != "nt", "운영 Linux의 심볼릭 링크를 검증합니다.")
    def test_source_or_archive_symlinks_are_rejected_before_publish_or_delete(self):
        source = self.snapshot("source", {"new.js": b"new"})
        target = self.base / "outside"
        target.mkdir()
        (source / "link").symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            retain(self.archive, {A: source}, 7, now=0)
        (source / "link").unlink()
        (self.archive / "assets").symlink_to(target, target_is_directory=True)
        with self.assertRaises(ValueError):
            retain(self.archive, {A: source}, 7, now=0)
        self.assertEqual(list(target.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
