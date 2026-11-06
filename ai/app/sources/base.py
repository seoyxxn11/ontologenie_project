from typing import Protocol

import pandas as pd

from app.models.source import TableSchema


class UnknownTableError(KeyError):
    pass


class SourceReader(Protocol):
    source_id: str

    def list_tables(self) -> list[str]: ...

    def read_schema(self, table: str) -> TableSchema: ...

    def read_rows(
        self, table: str, columns: list[str] | None = None, limit: int | None = None
    ) -> pd.DataFrame:
        """columns가 있으면 그 컬럼만, limit이 있으면 앞에서 그 행 수까지 읽는다.

        PostgreSQL은 기본키가 있으면 기본키 순서, CSV는 파일에 적힌 순서다.
        """
        ...
