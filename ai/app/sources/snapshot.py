"""분석 대상으로 고른 표·컬럼만 CSV 폴더(`<root>/<원천>/<표>.csv` + `_schema.json`)로 옮긴다.

원천이 CSV든 DB든 같은 분석 코드를 쓰기 위함이다. 기본키는 행을 구별하려고 늘 함께 옮긴다.
"""

import json
from dataclasses import dataclass, field
from pathlib import Path

from app.sources.base import SourceReader
from app.sources.csv_reader import SCHEMA_FILE
from app.sources.postgres_reader import type_name

# 표 하나에서 읽는 최대 행 수. AI 계정은 쿼리 30초 제한이 있어 큰 표는 기본키 순서로 앞에서 자른다.
MAX_ROWS = 100_000


@dataclass
class SnapshotReport:
    """옮기면서 알게 된 것. resultSummary로 화면(019)에 보인다."""

    # 고른 컬럼 중 원천에서 사라진 것 {표: [컬럼]}
    missing_columns: dict[str, list[str]] = field(default_factory=dict)
    # MAX_ROWS에서 잘린 표 {표: 읽은 행 수}
    sampled_tables: dict[str, int] = field(default_factory=dict)


def write_snapshot(
    reader: SourceReader,
    root: Path,
    columns: dict[str, list[str] | None],
    metadata: dict[str, dict] | None = None,
    name: str | None = None,
    max_rows: int = MAX_ROWS,
    structure_only: dict[str, list] | None = None,
) -> SnapshotReport:
    """columns: {표: 고른 컬럼 또는 None(전체)}, metadata: {표: 등록한 원천 설명·동의어·메모}.

    name은 옮길 폴더 이름(기본 reader.source_id). 원천에 없는 컬럼은 빼고 report에 남긴다.
    structure_only: {표: 이름·자료형만 받은 컬럼}. 값 없이 빈 컬럼으로 넣어 속성 후보만 만든다(416).
    """
    prefix = name or reader.source_id
    folder = Path(root) / prefix
    folder.mkdir(parents=True, exist_ok=True)
    report = SnapshotReport()
    meta = {}
    for table, chosen in columns.items():
        schema = reader.read_schema(table)
        keep = [
            c
            for c in schema.columns
            if chosen is None or c.name in chosen or c.is_primary_key
        ]
        names = [c.name for c in keep]
        missing = sorted(set(chosen or []) - {c.name for c in schema.columns})
        if missing:
            report.missing_columns[f"{prefix}.{table}"] = missing
        if not names:
            # 고른 컬럼이 모두 사라졌고 기본키도 없는 표는 건너뛰고 missingColumns로만 알린다
            continue
        rows = reader.read_rows(table, columns=names, limit=max_rows + 1)
        if len(rows) > max_rows:
            rows = rows.iloc[:max_rows]
            report.sampled_tables[f"{prefix}.{table}"] = max_rows
        valueless = [
            c for c in (structure_only or {}).get(table, []) if c.name not in names
        ]
        for column in valueless:
            rows[column.name] = None
        rows.to_csv(folder / f"{table}.csv", index=False, encoding="utf-8")
        meta[table] = {
            "description": schema.description or "",
            "primary_key": [c.name for c in keep if c.is_primary_key],
            "columns": {
                **{
                    c.name: {"type": c.declared_type or "", "comment": c.comment or ""}
                    for c in keep
                },
                **{
                    c.name: {
                        "type": type_name(c.data_type),
                        "comment": c.comment or "",
                        "values_read": False,
                        "nullable": c.nullable,
                    }
                    for c in valueless
                },
            },
        }
        add_metadata(meta[table], (metadata or {}).get(table))
    (folder / SCHEMA_FILE).write_text(
        json.dumps({"tables": meta}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return report


def add_metadata(table_meta: dict, metadata: dict | None) -> None:
    """등록한 설명은 원천 주석이 없을 때만 채우고(주석은 객체·속성 이름으로 쓰인다), 동의어·메모는 덧붙인다.

    형식은 docs/api-internal.md 3절. 모르는 키와 고르지 않은 컬럼은 무시한다.
    """
    if not isinstance(metadata, dict):
        return
    if not table_meta["description"]:
        table_meta["description"] = _text(metadata.get("description"))
    note = _note(metadata)
    if note:
        table_meta["note"] = note
    columns = metadata.get("columns")
    for name, column in (columns if isinstance(columns, dict) else {}).items():
        target = table_meta["columns"].get(name)
        if target is None or not isinstance(column, dict):
            continue
        if not target["comment"]:
            target["comment"] = _text(column.get("description"))
        note = _note(column)
        if note:  # 속성 이름은 주석의 괄호 앞부분이라 괄호로 붙인다
            target["comment"] = f"{target['comment']} ({note})".strip()


def _note(metadata: dict) -> str:
    synonyms = metadata.get("synonyms")
    if isinstance(synonyms, str):
        synonyms = [synonyms]
    parts = []
    names = [_text(s) for s in synonyms or [] if _text(s)]
    if names:
        parts.append(f"동의어: {', '.join(names)}")
    if _text(metadata.get("memo")):
        parts.append(f"메모: {_text(metadata.get('memo'))}")
    return "; ".join(parts)


def _text(value) -> str:
    return value.strip() if isinstance(value, str) else ""
