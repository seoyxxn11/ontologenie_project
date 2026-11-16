"""72번 SQL 비교기가 실제 저장소 SQL을 사용하고 구문 변화 때 중단하는지 검사한다."""

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/scripts/profile_object_search.py"
SPEC = importlib.util.spec_from_file_location("profile_search", SCRIPT)
profile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profile)


class SqlProfileTests(unittest.TestCase):
    def test_extracts_page_and_count_with_same_filter_and_bound_inputs(self):
        repository = (ROOT / profile.REPOSITORY / "ObjectQueryRepository.java").read_text(
            encoding="utf-8"
        )
        display = (ROOT / profile.REPOSITORY / "ObjectDisplaySql.java").read_text(
            encoding="utf-8"
        )
        queries = profile.queries(repository, display)
        self.assertEqual(set(queries), {"page", "count"})
        for query in queries.values():
            self.assertIn(":visibleTypeIds", query)
            self.assertIn(":maskedTypeIds", query)
            self.assertIn(":pattern", query)
            self.assertNotIn("%1$s", query)
        self.assertIn(":exactPattern", queries["page"])
        self.assertNotIn(":exactPattern", queries["count"])
        self.assertIn("limit 20 offset 0", queries["page"])

    def test_fails_closed_if_sql_composition_is_missing(self):
        with self.assertRaises(ValueError):
            profile.block("", "SELECT")
        with self.assertRaises(ValueError):
            profile.queries("", "")

    def test_preserves_differences_in_masking_sql_between_versions(self):
        repository = (ROOT / profile.REPOSITORY / "ObjectQueryRepository.java").read_text(
            encoding="utf-8"
        )
        after = (ROOT / profile.REPOSITORY / "ObjectDisplaySql.java").read_text(
            encoding="utf-8"
        )
        before = after.replace(
            "bool_and(pd.sensitivity = 'NORMAL')",
            "bool_and(pd.sensitivity = 'RESTRICTED')",
        )
        before_queries = profile.queries(repository, before)
        after_queries = profile.queries(repository, after)
        self.assertNotEqual(before_queries["page"], after_queries["page"])
        self.assertNotEqual(before_queries["count"], after_queries["count"])

    def test_remote_program_compiles(self):
        compile(profile.REMOTE, "remote-profile", "exec")


if __name__ == "__main__":
    unittest.main()
