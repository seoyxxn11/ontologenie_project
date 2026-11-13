"""원천 계정 허용 목록(infra/source-db/sql/allowlist.sql)의 정책이 백엔드 정책과 같은지 본다(S15P31C106-395).

백엔드 application.yml의 axcore.source-data-policy(금지·제한)와 allowlist.sql의 axcore_policy가 글자까지 같아야
원천 권한과 플랫폼 판단이 어긋나지 않는다. 실행: python -m unittest discover -s infra/tests -p 'test_source_allowlist.py' -v
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
APPLICATION_YML = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
ALLOWLIST_SQL = ROOT / "infra" / "source-db" / "sql" / "allowlist.sql"


def backend_policy(text):
    """application.yml에서 source-data-policy의 forbidden·restricted 목록을 읽는다. 없으면 None."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == "source-data-policy:"), None)
    if start is None:
        return None
    base = len(lines[start]) - len(lines[start].lstrip())
    policy = {}
    grade = None
    for line in lines[start + 1:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent <= base:
            break
        stripped = line.strip()
        if stripped in ("forbidden:", "restricted:"):
            grade = stripped[:-1].upper()
        elif stripped.startswith("- ") and grade:
            policy[stripped[2:].strip()] = grade
    return policy


def allowlist_policy(text):
    return {entry: grade for entry, grade in re.findall(r"\('([a-z_]+\.[a-z_]+\.(?:[a-z_]+|\*))', '(FORBIDDEN|RESTRICTED)'\)", text)}


class SourceAllowlistPolicyTest(unittest.TestCase):
    def test_allowlist_policy_matches_backend_policy(self):
        backend = backend_policy(APPLICATION_YML.read_text(encoding="utf-8"))
        if backend is None:
            self.skipTest("application.yml에 axcore.source-data-policy가 아직 없다")
        self.assertEqual(backend, allowlist_policy(ALLOWLIST_SQL.read_text(encoding="utf-8")))

    def test_allowlist_policy_is_not_empty(self):
        policy = allowlist_policy(ALLOWLIST_SQL.read_text(encoding="utf-8"))
        self.assertIn("erp.attendance.*", policy)
        self.assertEqual("RESTRICTED", policy["mes.worker.skill_level"])

    def test_parser_reads_backend_block(self):
        text = (
            "axcore:\n"
            "  source-data-policy:\n"
            "    forbidden:\n"
            "      - erp.attendance.*\n"
            "    restricted:\n"
            "      - erp.vendor.contact_name\n"
            "  source-load:\n"
            "    max-rows: 1\n"
        )
        self.assertEqual(
            {"erp.attendance.*": "FORBIDDEN", "erp.vendor.contact_name": "RESTRICTED"},
            backend_policy(text),
        )


if __name__ == "__main__":
    unittest.main()
