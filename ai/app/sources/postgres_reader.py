"""AI 분석용 PostgreSQL 원천 읽기. CsvSourceReader와 같은 메서드를 가진다.

API 공통 규칙: 원천 연결 검사·표본은 배치가 하고, AI 분석만 FastAPI가 AI 계정으로 원천을 읽는다.

드라이버(psycopg)는 psycopg_connector 안에서만 불러온다. 자동 테스트는 가짜 연결로 한다.
세션을 읽기 전용으로 열고 SELECT 권한이 있는 표·뷰만 보여 준다. 오류에는 접속 정보를 넣지 않는다.
"""

from collections.abc import Callable
from typing import Any

import pandas as pd

from app.models.source import ColumnSchema, TableSchema
from app.sources.base import UnknownTableError

LIST_RESOURCES = """
SELECT table_name, table_type FROM information_schema.tables
WHERE table_schema = %s AND table_type IN ('BASE TABLE', 'VIEW')
  AND has_table_privilege(format('%%I.%%I', table_schema, table_name), 'SELECT')
ORDER BY table_name
"""
TABLE_COMMENT = "SELECT obj_description(format('%%I.%%I', %s::text, %s::text)::regclass, 'pg_class')"
# 제약 조건 정보(information_schema)는 표 주인에게만 보여 조회 전용 계정은 pg_catalog로 읽는다
COLUMNS = """
SELECT a.attname, format_type(a.atttypid, a.atttypmod), col_description(a.attrelid, a.attnum)
FROM pg_attribute a
WHERE a.attrelid = format('%%I.%%I', %s::text, %s::text)::regclass
  AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY a.attnum
"""
PRIMARY_KEY = """
SELECT a.attname
FROM pg_index i
JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
WHERE i.indrelid = format('%%I.%%I', %s::text, %s::text)::regclass AND i.indisprimary
ORDER BY array_position(i.indkey::int2[], a.attnum)
"""
# CSV 원천(_schema.json)과 같은 자료형 이름으로 맞춰 분석 결과가 원천 종류에 따라 달라지지 않게 한다
TYPE_NAMES = {
    "character varying": "varchar",
    "character": "char",
    "integer": "int",
    "timestamp without time zone": "timestamp",
    "timestamp with time zone": "timestamptz",
    "time without time zone": "time",
}


def type_name(pg_type: str) -> str:
    """format_type 결과 → CSV 원천과 같은 이름. 예: character varying(20) → varchar(20)."""
    base, paren, rest = pg_type.partition("(")
    return TYPE_NAMES.get(base, base) + paren + rest


# 드라이버 메시지에는 호스트·계정이 들어 있어 밖으로 내보내지 않는다.
# 화면(019)에 그대로 보이므로 명세의 다른 실패 문구와 같은 말투로 쓴다
ERROR_MESSAGES = {
    "SOURCE_AUTH_FAILED": "원천 계정 인증에 실패했습니다",
    "SOURCE_PERMISSION_DENIED": "원천을 읽을 권한이 없습니다",
    "SOURCE_UNREACHABLE": "원천에 접속하지 못했습니다",
    "SOURCE_DRIVER_MISSING": "PostgreSQL 드라이버가 설치되지 않았습니다",
    "SOURCE_UNSUPPORTED": "이 원천 종류는 아직 읽을 수 없습니다",
}


class SourceError(Exception):
    """접속 정보·원천 값을 담지 않는 원천 오류."""

    def __init__(self, code: str):
        super().__init__(ERROR_MESSAGES[code])
        self.code = code


# 접속 단계 오류에는 sqlstate가 없어 서버 메시지로만 가린다. 메시지는 분류에만 쓰고 밖으로 내보내지 않는다
AUTH_MESSAGES = ("authentication failed", "pg_hba.conf")


def error_code(error: Exception) -> str:
    """PostgreSQL 오류 상태값(sqlstate)으로 원인만 구분한다. 28xxx 인증, 42501 권한."""
    if isinstance(error, SourceError):
        return error.code
    state = getattr(error, "sqlstate", None) or ""
    if state.startswith("28") or any(m in str(error) for m in AUTH_MESSAGES):
        return "SOURCE_AUTH_FAILED"
    if state == "42501":
        return "SOURCE_PERMISSION_DENIED"
    return "SOURCE_UNREACHABLE"


def psycopg_connector(
    connection: dict, timeout_sec: int = 10, statement_timeout_ms: int = 30000
) -> Callable[[], Any]:
    """Spring이 넘긴 접속 정보로 읽기 전용 세션을 여는 함수. 드라이버는 이때만 불러온다."""

    def connect():
        try:
            import psycopg
        except ImportError as error:
            raise SourceError("SOURCE_DRIVER_MISSING") from error
        return psycopg.connect(
            host=connection.get("host"),
            port=connection.get("port", 5432),
            dbname=connection.get("database"),
            user=connection.get("username"),
            password=connection.get("password"),
            # 원천 AI 계정은 SSL만 받는다
            sslmode=connection.get("sslmode") or "prefer",
            connect_timeout=timeout_sec,
            options=(
                "-c default_transaction_read_only=on"
                f" -c statement_timeout={statement_timeout_ms}"
            ),
        )

    return connect


def _quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


class PostgresSourceReader:
    """스키마 하나를 원천 하나로 본다. CsvSourceReader와 같은 창구(SourceReader)."""

    def __init__(
        self,
        source_id: str,
        connect: Callable[[], Any],
        schema: str = "public",
    ):
        self.source_id = source_id
        self.schema = schema
        try:
            self._conn = connect()
        except Exception as error:  # noqa: BLE001 - 드라이버별 예외를 코드로만 바꿔 접속 정보가 새지 않게 한다
            raise SourceError(error_code(error)) from None
        self._kinds: dict[str, str] | None = None

    def close(self) -> None:
        self._conn.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _query(self, sql: str, params: tuple = ()) -> list[tuple]:
        try:
            cursor = self._conn.cursor()
            cursor.execute(sql, params)
            return list(cursor.fetchall())
        except Exception as error:  # noqa: BLE001 - 위와 같은 이유
            raise SourceError(error_code(error)) from None

    def resource_kinds(self) -> dict[str, str]:
        """읽을 수 있는 표·뷰 → TABLE · VIEW."""
        if self._kinds is None:
            self._kinds = {
                name: "VIEW" if kind == "VIEW" else "TABLE"
                for name, kind in self._query(LIST_RESOURCES, (self.schema,))
            }
        return self._kinds

    def list_tables(self) -> list[str]:
        return sorted(self.resource_kinds())

    def _check(self, table: str) -> None:
        if table not in self.resource_kinds():
            raise UnknownTableError(
                f"{self.source_id}에서 {table} 표를 읽을 수 없습니다."
            )

    def read_schema(self, table: str) -> TableSchema:
        self._check(table)
        comment = self._query(TABLE_COMMENT, (self.schema, table))
        keys = {r[0] for r in self._query(PRIMARY_KEY, (self.schema, table))}
        columns = [
            ColumnSchema(
                name=name,
                declared_type=type_name(dtype),
                comment=col_comment,
                is_primary_key=name in keys,
            )
            for name, dtype, col_comment in self._query(COLUMNS, (self.schema, table))
        ]
        return TableSchema(
            source_id=self.source_id,
            name=table,
            description=comment[0][0] if comment else None,
            columns=columns,
        )

    def read_rows(self, table: str) -> pd.DataFrame:
        """CsvSourceReader처럼 모든 값을 문자열로, 빈 값은 결측으로 돌려준다."""
        self._check(table)
        names = [c.name for c in self.read_schema(table).columns]
        sql = f"SELECT * FROM {_quote(self.schema)}.{_quote(table)}"
        rows = self._query(sql)
        frame = pd.DataFrame(
            [[None if v is None else str(v) for v in row] for row in rows],
            columns=names,
            dtype=object,
        )
        return frame.where(frame.notna(), float("nan"))
