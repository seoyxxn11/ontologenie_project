"""원천 데이터 정책 세 사본이 같은지 본다(S15P31C106-395·405).

기준은 백엔드 application.yml의 axcore.source-data-policy(금지·제한)다. 원천 계정 허용 목록(infra/source-db/sql/allowlist.sql의
axcore_policy)은 글자까지 같아야 원천 권한과 플랫폼 판단이 어긋나지 않고, AI 사본(ai/app/source_policy.py)은 같은 항목을 표·컬럼으로 나눠 둔다.
MR 검사(Jenkins Infra check)가 이 파일과 원본들만 읽기 전용으로 붙여 돌린다. AI 사본이 없는 예전 커밋은 그 비교만 건너뛴다.
실행: python -m unittest discover -s infra/tests -p 'test_source_allowlist.py' -v
"""

import ast
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
APPLICATION_YML = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
ALLOWLIST_SQL = ROOT / "infra" / "source-db" / "sql" / "allowlist.sql"
AI_POLICY = ROOT / "ai" / "app" / "source_policy.py"

NAME = r"[a-z_][a-z0-9_$]*"


def yaml_plain(line):
    """YAML 한 줄에서 주석을 뗀다. #는 줄 처음이거나 앞이 공백이고 따옴표 밖일 때만 주석이다."""
    quote = None
    for i, char in enumerate(line):
        if quote:
            if char == quote:
                quote = None
        elif char in ("'", '"') and (i == 0 or line[i - 1] in " \t[,-:"):
            quote = char
        elif char == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i].rstrip()
    return line.rstrip()


def yaml_scalar(value):
    """따옴표로 감싼 값은 따옴표를 뗀다."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def backend_policy(text):
    """application.yml에서 source-data-policy의 forbidden·restricted 목록을 읽는다. 없으면 None.

    줄 끝 주석과 따옴표는 YAML처럼 뗀다. 흐름 목록(forbidden: [a, b])도 읽는다.
    """
    lines = [yaml_plain(line) for line in text.splitlines()]
    start = next((i for i, line in enumerate(lines) if line.strip() == "source-data-policy:"), None)
    if start is None:
        return None
    base = len(lines[start]) - len(lines[start].lstrip())
    policy = {}
    grade = None
    for line in lines[start + 1:]:
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if indent <= base:
            break
        stripped = line.strip()
        key, colon, rest = stripped.partition(":")
        if colon and key in ("forbidden", "restricted"):
            grade = key.upper()
            rest = rest.strip()
            if rest.startswith("[") and rest.endswith("]"):
                for item in rest[1:-1].split(","):
                    if item.strip():
                        policy[yaml_scalar(item)] = grade
                grade = None
        elif stripped.startswith("-") and grade:
            policy[yaml_scalar(stripped[1:])] = grade
        elif not stripped.startswith("-"):
            grade = None
    return policy


def sql_without_comments(text):
    """SQL의 -- 줄 주석과 /* */ 주석을 지운다. 이 파일의 문자열 값에는 주석 표시가 없다."""
    return re.sub(r"--[^\n]*", "", re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL))


def allowlist_policy(text):
    pattern = rf"\('({NAME}\.{NAME}\.(?:{NAME}|\*))',\s*'(FORBIDDEN|RESTRICTED)'\)"
    return dict(re.findall(pattern, sql_without_comments(text)))


def allowlist_tables(text):
    """axcore_allow에 넣는 (스키마, 표) 목록."""
    block = sql_without_comments(text).split("insert into axcore_allow", 1)[1].split(";", 1)[0]
    return set(re.findall(rf"\('({NAME})',\s*'({NAME})',", block))


def ai_policy(text):
    """ai/app/source_policy.py의 세 frozenset을 백엔드와 같은 {항목: 등급} 모양으로 읽는다. 가져오지(import) 않고 구문만 읽는다."""
    values = {}
    for node in ast.parse(text).body:
        if (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and isinstance(node.value, ast.Call)
            and getattr(node.value.func, "id", None) == "frozenset"
        ):
            values[node.targets[0].id] = ast.literal_eval(node.value.args[0])
    policy = {f"{table}.*": "FORBIDDEN" for table in values["FORBIDDEN_TABLES"]}
    policy.update({column: "FORBIDDEN" for column in values["FORBIDDEN_COLUMNS"]})
    policy.update({column: "RESTRICTED" for column in values["RESTRICTED_COLUMNS"]})
    return policy


class SourceAllowlistPolicyTest(unittest.TestCase):
    def backend(self):
        backend = backend_policy(APPLICATION_YML.read_text(encoding="utf-8"))
        self.assertIsNotNone(backend, "application.yml에 axcore.source-data-policy가 없다")
        self.assertTrue(backend, "application.yml의 axcore.source-data-policy가 비어 있다")
        return backend

    def test_allowlist_policy_matches_backend_policy(self):
        self.assertEqual(self.backend(), allowlist_policy(ALLOWLIST_SQL.read_text(encoding="utf-8")))

    def test_ai_copy_matches_backend_policy(self):
        if not AI_POLICY.exists():
            self.skipTest("AI 사본이 없는 커밋(406 이전)")
        self.assertEqual(self.backend(), ai_policy(AI_POLICY.read_text(encoding="utf-8")))

    def test_restricted_entries_are_columns(self):
        # 민감은 컬럼 단위로만 판단한다. 표 전체(.*)는 백엔드가 기동을 거절한다(405).
        restricted = [entry for entry, grade in self.backend().items() if grade == "RESTRICTED"]
        self.assertFalse([entry for entry in restricted if entry.endswith(".*")])

    def test_allowlist_policy_is_not_empty(self):
        policy = allowlist_policy(ALLOWLIST_SQL.read_text(encoding="utf-8"))
        self.assertIn("erp.attendance.*", policy)
        self.assertEqual("RESTRICTED", policy["mes.worker.skill_level"])

    def test_change_log_tables_are_forbidden_and_not_granted(self):
        # 결정 D3(405): change_log는 근태 키(사번|근무일)를 담아 표 전체를 금지한다.
        text = ALLOWLIST_SQL.read_text(encoding="utf-8")
        policy = allowlist_policy(text)
        self.assertEqual("FORBIDDEN", policy["erp.change_log.*"])
        self.assertEqual("FORBIDDEN", policy["mes.change_log.*"])
        granted = allowlist_tables(text)
        self.assertIn(("erp", "item"), granted)
        whole = {tuple(entry.split(".")[:2]) for entry in policy if entry.endswith(".*")}
        self.assertFalse(whole & granted, "표 전체 금지인 표가 허용 목록에 있다")

    def test_parser_reads_comments_quotes_and_flow_lists_like_yaml(self):
        text = (
            "axcore:\n"
            "  source-data-policy:  # 정책\n"
            "    forbidden:\n"
            "      - erp.change_log.*  # D3\n"
            "      - 'erp.employee.emp_name'\n"
            '      - "mes.worker.name" # 이름\n'
            "    restricted: [erp.vendor.contact_name, 'mes.worker.skill_level']  # 민감\n"
            "  source-load:\n"
        )
        self.assertEqual(
            {
                "erp.change_log.*": "FORBIDDEN",
                "erp.employee.emp_name": "FORBIDDEN",
                "mes.worker.name": "FORBIDDEN",
                "erp.vendor.contact_name": "RESTRICTED",
                "mes.worker.skill_level": "RESTRICTED",
            },
            backend_policy(text),
        )
        self.assertEqual("a#b", yaml_scalar(yaml_plain("- a#b")[1:]))
        self.assertEqual("x # y", yaml_scalar(yaml_plain("- 'x # y'  # 주석")[1:]))

    def test_parser_reads_backend_block(self):
        text = (
            "axcore:\n"
            "  source-data-policy:\n"
            "    forbidden:\n"
            "      - erp.attendance.*\n"
            "    restricted:\n"
            "      - erp.vendor.contact_name\n"
            "    allow-empty: false\n"
            "  source-load:\n"
            "    max-rows: 1\n"
        )
        self.assertEqual(
            {"erp.attendance.*": "FORBIDDEN", "erp.vendor.contact_name": "RESTRICTED"},
            backend_policy(text),
        )

    def test_parser_reads_allowlist_tables_past_comments(self):
        text = (
            "insert into axcore_allow (schema_name, table_name, ro_columns, ai_columns, cdc) values\n"
            "    ('erp', 'item', null, null, true),\n"
            "    -- 주석 안의 ; 와 ('erp', 'ghost', null, null, true) 는 세지 않는다\n"
            "    ('mes', 'worker2', '{id,shift}', '{id}', true);\n"
            "insert into axcore_excluded (schema_name, table_name, reason) values\n"
            "    ('erp', 'gl_account', '회계');\n"
        )
        self.assertEqual({("erp", "item"), ("mes", "worker2")}, allowlist_tables(text))

    def test_parser_reads_ai_copy(self):
        text = (
            'FORBIDDEN_TABLES = frozenset({"erp.attendance"})\n'
            'FORBIDDEN_COLUMNS = frozenset({"mes.worker.name"})\n'
            "RESTRICTED_COLUMNS = frozenset(\n"
            '    {"erp.vendor.contact_name"}\n'
            ")\n"
        )
        self.assertEqual(
            {
                "erp.attendance.*": "FORBIDDEN",
                "mes.worker.name": "FORBIDDEN",
                "erp.vendor.contact_name": "RESTRICTED",
            },
            ai_policy(text),
        )


if __name__ == "__main__":
    unittest.main()
