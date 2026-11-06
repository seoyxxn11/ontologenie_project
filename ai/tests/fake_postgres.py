"""원천 PostgreSQL을 흉내 내는 가짜 연결 (드라이버 없이 테스트)."""

import re

from app.sources import postgres_reader as pg

ROW_QUERY = re.compile(
    r'SELECT (?P<cols>.+?) FROM "?\w+"?\."?(?P<table>\w+)"?(?: ORDER BY (?P<order>.+?))?(?: LIMIT (?P<limit>\d+))?$'
)

TABLES = {
    "work_order": {
        "kind": "BASE TABLE",
        "comment": "작업지시",
        "columns": [
            ("wo_no", "character varying(20)", "작업지시 번호"),
            ("qty", "integer", None),
        ],
        "pk": ["wo_no"],
        # 기본키 순서와 다르게 넣어 ORDER BY가 실제로 정렬하는지 본다
        "rows": [("WO-2", None), ("WO-1", 10)],
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
        elif m := ROW_QUERY.match(sql):
            self.conn.row_queries.append(sql)
            table = TABLES[m["table"]]
            names = [c[0] for c in table["columns"]]
            picked = [names.index(c.strip('"')) for c in m["cols"].split(", ")]
            source = table["rows"]
            if m["order"]:
                keys = [names.index(c.strip('"')) for c in m["order"].split(", ")]
                source = sorted(source, key=lambda row: [row[i] for i in keys])
            rows = [tuple(row[i] for i in picked) for row in source]
            self.result = rows[: int(m["limit"])] if m["limit"] else rows
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
