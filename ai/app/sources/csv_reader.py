import json
from pathlib import Path

import pandas as pd

from app.models.source import ColumnSchema, TableSchema
from app.sources.base import UnknownTableError

SCHEMA_FILE = "_schema.json"


class CsvSourceReader:
    """폴더 하나를 원천 하나로 보고, `<표 이름>.csv` 파일을 표로 읽는다.

    같은 폴더의 `_schema.json`이 있으면 컬럼 타입·주석·기본키를 덧붙인다. 형식:
    {"tables": {"<표>": {"description": "...", "note": "...", "primary_key": ["..."],
                         "columns": {"<컬럼>": {"type": "...", "comment": "..."}}}}}
    """

    def __init__(self, source_id: str, root: Path):
        self.source_id = source_id
        self.root = Path(root)
        if not self.root.is_dir():
            raise FileNotFoundError(f"원천 폴더가 없습니다: {self.root}")
        schema_path = self.root / SCHEMA_FILE
        self._meta = (
            json.loads(schema_path.read_text(encoding="utf-8")).get("tables", {})
            if schema_path.exists()
            else {}
        )

    def list_tables(self) -> list[str]:
        return sorted(path.stem for path in self.root.glob("*.csv"))

    def read_schema(self, table: str) -> TableSchema:
        header = pd.read_csv(
            self._csv_path(table), nrows=0, encoding="utf-8-sig"
        ).columns
        meta = self._meta.get(table, {})
        column_meta = meta.get("columns", {})
        primary_key = set(meta.get("primary_key", []))
        unknown = (set(column_meta) | primary_key) - set(header)
        if unknown:
            raise ValueError(
                f"{SCHEMA_FILE}의 {table}에 CSV에 없는 컬럼이 있습니다: {sorted(unknown)}"
            )
        columns = [
            ColumnSchema(
                name=name,
                declared_type=column_meta.get(name, {}).get("type"),
                comment=column_meta.get(name, {}).get("comment"),
                is_primary_key=name in primary_key,
            )
            for name in header
        ]
        return TableSchema(
            source_id=self.source_id,
            name=table,
            description=meta.get("description"),
            note=meta.get("note"),
            columns=columns,
        )

    def read_rows(self, table: str) -> pd.DataFrame:
        # 식별자의 앞자리 0이 사라지지 않도록 모든 값을 문자열로 읽는다. 빈 칸은 결측(NaN)이 된다.
        return pd.read_csv(self._csv_path(table), dtype=str, encoding="utf-8-sig")

    def _csv_path(self, table: str) -> Path:
        path = self.root / f"{table}.csv"
        if not path.exists():
            raise UnknownTableError(f"{self.source_id}에 {table} 표가 없습니다.")
        return path
