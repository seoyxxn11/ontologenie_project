import sys
import types

import pandas as pd
import pytest

from app.sources.postgres_reader import (
    PostgresSourceReader,
    SourceError,
    psycopg_connector,
)
from tests.fake_postgres import FakeConn, failing_connect


def test_reader_reads_structure_and_rows_as_text():
    reader = PostgresSourceReader("mes", FakeConn)
    assert reader.list_tables() == ["v_open", "work_order"]
    schema = reader.read_schema("work_order")
    assert schema.primary_key == ["wo_no"]
    assert schema.column("wo_no").declared_type == "varchar(20)"  # CSV 원천과 같은 이름
    assert schema.column("qty").declared_type == "int"
    rows = reader.read_rows("work_order")
    qty = rows.set_index("wo_no")["qty"]
    assert qty["WO-1"] == "10" and pd.isna(qty["WO-2"])
    with pytest.raises(KeyError):
        reader.read_rows("secret_table")  # 읽기 권한 목록에 없는 표


@pytest.mark.parametrize(
    "message, code",
    [
        (
            'FATAL:  password authentication failed for user "reader" host "db.internal"',
            "SOURCE_AUTH_FAILED",
        ),
        (  # SSL 없이 붙으면 원천 서버가 거절한다
            'pg_hba.conf rejects connection for host "db.internal", user "reader", no encryption',
            "SOURCE_AUTH_FAILED",
        ),
        ('connection to server at "db.internal" failed: timeout', "SOURCE_UNREACHABLE"),
    ],
)
def test_errors_carry_codes_not_connection_details(message, code):
    with pytest.raises(SourceError) as caught:
        PostgresSourceReader("mes", failing_connect(message))
    assert caught.value.code == code
    assert "db.internal" not in str(caught.value)
    assert "reader" not in str(caught.value)


def test_connection_fields_from_spring_reach_driver(monkeypatch):
    seen = {}
    monkeypatch.setitem(
        sys.modules,
        "psycopg",
        types.SimpleNamespace(connect=lambda **kw: seen.update(kw)),
    )
    connection = {
        "host": "source-erp",
        "port": 5432,
        "database": "erp",
        "username": "platform_ai",
        "password": "pw",
        "sslmode": "require",
    }
    psycopg_connector(connection)()
    assert seen["dbname"] == "erp" and seen["user"] == "platform_ai"
    assert seen["sslmode"] == "require"
    assert "default_transaction_read_only=on" in seen["options"]
    psycopg_connector({"host": "localhost"})()
    assert seen["sslmode"] == "prefer"  # 값이 없으면 SSL을 먼저 시도한다
