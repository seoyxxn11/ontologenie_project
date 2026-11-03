"""분석 대상으로 고른 표·컬럼만 CSV 폴더(`<root>/<원천>/<표>.csv` + `_schema.json`)로 옮긴다.

분석기·제안기가 CSV 폴더를 읽도록 만들어져 있어서, 원천(CSV든 DB든)에서 고른 것만 임시 폴더에 옮겨
같은 코드로 분석한다. API 명세 018: FastAPI는 고른 컬럼만 읽는다. 기본키는 행을 구별하려고 늘 함께 옮긴다.
임시 폴더에는 원천 값이 들어 있으므로 분석이 끝나면 지운다(analysis.py).
"""

import json
from pathlib import Path

from app.sources.base import SourceReader
from app.sources.csv_reader import SCHEMA_FILE


def write_snapshot(
    reader: SourceReader,
    root: Path,
    columns: dict[str, list[str] | None],
    metadata: dict[str, dict] | None = None,
    name: str | None = None,
) -> None:
    """columns: {표 이름: 고른 컬럼 목록 또는 None(전체)}. 읽을 수 없는 표는 UnknownTableError.

    metadata: {표 이름: 플랫폼에 등록한 원천 설명·동의어·메모(source_resource.source_metadata)}.
    name: 옮길 폴더 이름. 없으면 reader.source_id. 후보의 resourceKey가 이 이름으로 시작한다.
    """
    folder = Path(root) / (name or reader.source_id)
    folder.mkdir(parents=True, exist_ok=True)
    meta = {}
    for table, chosen in columns.items():
        schema = reader.read_schema(table)
        keep = [
            c
            for c in schema.columns
            if chosen is None or c.name in chosen or c.is_primary_key
        ]
        names = [c.name for c in keep]
        reader.read_rows(table)[names].to_csv(
            folder / f"{table}.csv", index=False, encoding="utf-8"
        )
        meta[table] = {
            "description": schema.description or "",
            "primary_key": [c.name for c in keep if c.is_primary_key],
            "columns": {
                c.name: {"type": c.declared_type or "", "comment": c.comment or ""}
                for c in keep
            },
        }
        add_metadata(meta[table], (metadata or {}).get(table))
    (folder / SCHEMA_FILE).write_text(
        json.dumps({"tables": meta}, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def add_metadata(table_meta: dict, metadata: dict | None) -> None:
    """등록한 설명은 원천 주석이 없을 때만 채우고, 동의어·메모는 참고로 덧붙인다.

    형식(docs/api-internal.md 3절): {"description", "synonyms": [...], "memo",
    "columns": {"<컬럼>": {"description", "synonyms", "memo"}}}. 모르는 키와 고르지 않은 컬럼은 무시한다.
    원천 주석을 덮어쓰지 않는 이유: 표 주석은 객체 이름, 컬럼 주석은 속성 이름으로 쓰인다.
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
