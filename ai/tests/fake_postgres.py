"""원천 PostgreSQL을 흉내 내는 가짜 연결 (드라이버 없이 테스트)."""

from app.sources import postgres_reader as pg

TABLES = {
    "work_order": {
        "kind": "BASE TABLE",
        "comment": "작업지시",
        "columns": [
            ("wo_no", "character varying(20)", "작업지시 번호"),
            ("qty", "integer", None),
        ],
        "pk": ["wo_no"],
        "rows": [("WO-1", 10), ("WO-2", None)],
    },
    "v_open": {
        "kind": "VIEW",
        "comment": None,
        "columns": [("wo_no", "text", None)],
        "pk": [],
        "rows": [],
    },
}


class FakeCursor:
    def __init__(self, conn):
        self.conn, self.result = conn, []

    def execute(self, sql, params=()):
        if self.conn.fail:
            raise self.conn.fail
        if sql == pg.LIST_RESOURCES:
            self.result = [(name, t["kind"]) for name, t in sorted(TABLES.items())]
        elif sql == pg.TABLE_COMMENT:
            self.result = [(TABLES[params[1]]["comment"],)]
        elif sql == pg.COLUMNS:
            self.result = TABLES[params[1]]["columns"]
        elif sql == pg.PRIMARY_KEY:
            self.result = [(c,) for c in TABLES[params[1]]["pk"]]
        elif sql.startswith("SELECT * FROM"):
            self.conn.row_queries.append(sql)
            self.result = TABLES[sql.split(".")[1].split()[0].strip('"')]["rows"]
        else:
            raise AssertionError(sql)

    def fetchall(self):
        return self.result


class FakeConn:
    def __init__(self, writable=0, fail=None):
        self.writable, self.fail, self.closed, self.row_queries = (
            writable,
            fail,
            False,
            [],
        )

    def cursor(self):
        return FakeCursor(self)

    def close(self):
        self.closed = True


class ConnectError(Exception):
    """드라이버의 접속 단계 오류. 실제 psycopg처럼 sqlstate 없이 서버 메시지만 있다."""

    sqlstate = None


def failing_connect(
    message='FATAL:  password authentication failed for user "reader" host "db.internal"',
):
    def connect():
        raise ConnectError(message)

    return connect
