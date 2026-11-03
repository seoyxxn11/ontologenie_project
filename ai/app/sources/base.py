from typing import Protocol

import pandas as pd

from app.models.source import TableSchema


class UnknownTableError(KeyError):
    pass


class SourceReader(Protocol):
    source_id: str

    def list_tables(self) -> list[str]: ...

    def read_schema(self, table: str) -> TableSchema: ...

    def read_rows(self, table: str) -> pd.DataFrame: ...
