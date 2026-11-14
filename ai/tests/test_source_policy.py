"""AI 쪽 원천 정책 사본(app/source_policy.py)이 백엔드 정책과 같은지, 정답·개인정보 판단이 정책을 따르는지 본다."""

import json
from pathlib import Path

import pytest

from app import source_policy
from app.analyzers.structured import is_personal
from app.dataset import gold
from app.llm.privacy import is_person_column

AI_ROOT = Path(__file__).resolve().parents[1]
APPLICATION_YML = (
    AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "application.yml"
)
GOLD_DIR = AI_ROOT / "data" / "gold-v1.0"


def _yaml_plain(line: str) -> str:
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


def _yaml_scalar(value: str) -> str:
    """따옴표로 감싼 값은 따옴표를 뗀다."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _backend_policy(text: str) -> dict[str, set[str]] | None:
    """application.yml의 axcore.source-data-policy에서 forbidden·restricted 목록을 읽는다. 없으면 None.

    줄 끝 주석과 따옴표는 YAML처럼 뗀다. 흐름 목록(forbidden: [a, b])도 읽는다.
    """
    lines = [_yaml_plain(line) for line in text.splitlines()]
    start = next(
        (i for i, line in enumerate(lines) if line.strip() == "source-data-policy:"),
        None,
    )
    if start is None:
        return None
    base = len(lines[start]) - len(lines[start].lstrip())
    policy: dict[str, set[str]] = {"forbidden": set(), "restricted": set()}
    grade = None
    for line in lines[start + 1 :]:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= base:
            break
        stripped = line.strip()
        key, colon, rest = stripped.partition(":")
        if colon and key in ("forbidden", "restricted"):
            grade = key
            rest = rest.strip()
            if rest.startswith("[") and rest.endswith("]"):
                policy[grade].update(
                    _yaml_scalar(item) for item in rest[1:-1].split(",") if item.strip()
                )
                grade = None
        elif stripped.startswith("-") and grade:
            policy[grade].add(_yaml_scalar(stripped[1:]))
        elif not stripped.startswith("-"):
            grade = None
    return policy


def test_backend_policy_parser_reads_comments_quotes_and_flow_lists():
    text = (
        "axcore:\n"
        "  source-data-policy:  # 정책\n"
        "    forbidden:\n"
        "      - erp.change_log.*  # D3\n"
        "      - 'erp.employee.emp_name'\n"
        '    restricted: [erp.vendor.contact_name, "mes.worker.skill_level"]\n'
        "  source-load:\n"
    )
    assert _backend_policy(text) == {
        "forbidden": {"erp.change_log.*", "erp.employee.emp_name"},
        "restricted": {"erp.vendor.contact_name", "mes.worker.skill_level"},
    }


def test_copy_matches_backend_policy():
    if not APPLICATION_YML.exists():
        pytest.skip("백엔드 설정이 없는 환경(ai/만 있는 CI)")
    policy = _backend_policy(APPLICATION_YML.read_text(encoding="utf-8"))
    if policy is None:
        pytest.skip("백엔드에 원천 데이터 정책이 아직 없음")
    forbidden = {f"{t}.*" for t in source_policy.FORBIDDEN_TABLES}
    assert policy["forbidden"] == forbidden | source_policy.FORBIDDEN_COLUMNS
    assert policy["restricted"] == set(source_policy.RESTRICTED_COLUMNS)


def test_forbidden_and_restricted_lookup():
    assert source_policy.is_forbidden("erp.attendance")
    assert source_policy.is_forbidden("erp.fixed_asset.book_value")
    assert source_policy.is_forbidden("mes.worker.name")
    assert not source_policy.is_forbidden("erp.employee.emp_no")
    assert source_policy.is_restricted("mes.worker.skill_level")
    assert not source_policy.is_restricted("mes.worker.shift")


def test_gold_has_no_forbidden_tables_or_columns():
    onto = gold.ontology()
    assert not any(
        source_policy.is_forbidden(t)
        for o in onto["object_types"]
        for t in o["source_tables"]
    )
    assert not any(
        source_policy.is_forbidden(p["source_column"]) for p in onto["properties"]
    )
    for link in onto["link_types"]:
        via = link["via"]
        ends = [via["from"], via["to"]]
        columns = [c for end in ends for c in ([end] if isinstance(end, str) else end)]
        assert not any(source_policy.is_forbidden(c) for c in columns), link["code"]


def test_gold_personal_columns_are_policy_restricted_columns():
    personal = {
        p["source_column"] for p in gold.ontology()["properties"] if p["is_personal"]
    }
    assert personal == set(source_policy.RESTRICTED_COLUMNS)


def test_committed_gold_files_skip_forbidden_data():
    # 정답 파일 전체에서 금지 표·컬럼 이름이 나오지 않아야 한다(메모 정답의 문장 위치는 원천 컬럼 이름을 쓰지 않음)
    words = [
        *source_policy.FORBIDDEN_TABLES,
        *source_policy.FORBIDDEN_COLUMNS,
        "R-ASSET",
    ]
    for path in GOLD_DIR.glob("*.json"):
        text = path.read_text(encoding="utf-8")
        assert not [w for w in words if w in text], path.name
    checks = json.loads(
        (GOLD_DIR / "integrity_checks.json").read_text(encoding="utf-8")
    )
    assert all(c["check_id"] not in gold.FORBIDDEN_CHECKS for c in checks)


def test_forbidden_columns_are_masked_even_without_comment():
    assert is_personal("name", None, "mes.worker.name")
    assert is_personal("position", "", "erp.employee.position")
    assert not is_personal("name", None, "mes.equipment.name")
    assert is_personal("contact_name", None)
    assert is_person_column("contact_name")
