from app.sources.csv_reader import CsvSourceReader
from app.sources.postgres_reader import PostgresSourceReader
from app.sources.snapshot import write_snapshot
from tests.fake_postgres import FakeConn


def test_snapshot_matches_csv_reader_format(tmp_path):
    write_snapshot(
        PostgresSourceReader("mes", FakeConn), tmp_path, {"work_order": ["qty"]}
    )
    csv = CsvSourceReader("mes", tmp_path / "mes")
    assert csv.list_tables() == ["work_order"]  # 고른 표만
    assert csv.read_schema("work_order").primary_key == ["wo_no"]
    assert list(csv.read_rows("work_order").columns) == ["wo_no", "qty"]


def test_registered_metadata_adds_to_source_comments(tmp_path):
    metadata = {
        "work_order": {
            "description": "덮어쓰지 않음",
            "synonyms": ["WO", "작지"],
            "memo": "현장에서는 작지라고 부름",
            "columns": {
                "wo_no": {"description": "덮어쓰지 않음", "synonyms": "지시번호"},
                "qty": {"description": "지시 수량"},
                "not_chosen": {"description": "무시"},
            },
            "unknown": 1,
        }
    }
    write_snapshot(
        PostgresSourceReader("mes", FakeConn),
        tmp_path,
        {"work_order": ["qty"]},
        metadata,
    )
    schema = CsvSourceReader("mes", tmp_path / "mes").read_schema("work_order")
    # 원천 주석이 있으면 그대로 두고, 없을 때만 등록한 설명으로 채운다
    assert schema.description == "작업지시"
    assert schema.note == "동의어: WO, 작지; 메모: 현장에서는 작지라고 부름"
    assert schema.column("wo_no").comment == "작업지시 번호 (동의어: 지시번호)"
    assert schema.column("qty").comment == "지시 수량"


def test_snapshot_without_metadata_has_no_note(tmp_path):
    write_snapshot(
        PostgresSourceReader("mes", FakeConn), tmp_path, {"work_order": None}, {}
    )
    schema = CsvSourceReader("mes", tmp_path / "mes").read_schema("work_order")
    assert schema.note is None and schema.column("qty").comment == ""


def test_snapshot_folder_can_be_named_by_resource_key_prefix(tmp_path):
    # 원천 코드(MES_MAIN)로 연 연결도 resourceKey 앞부분(mes) 폴더로 옮겨야 후보 키가 맞는다
    reader = PostgresSourceReader("MES_MAIN", FakeConn, schema="mes")
    write_snapshot(reader, tmp_path, {"work_order": None}, name="mes")
    assert (tmp_path / "mes" / "work_order.csv").is_file()
    assert not (tmp_path / "MES_MAIN").exists()


def test_only_chosen_columns_and_key_are_selected_from_source(tmp_path):
    conn = FakeConn()
    write_snapshot(
        PostgresSourceReader("mes", lambda: conn), tmp_path, {"work_order": []}
    )

    # 고르지 않은 컬럼(qty)은 원천에서 읽지도 않는다
    assert conn.row_queries
    assert all('"qty"' not in q and "*" not in q for q in conn.row_queries)
    assert list(
        CsvSourceReader("mes", tmp_path / "mes").read_rows("work_order").columns
    ) == ["wo_no"]


def test_missing_chosen_column_is_reported_and_left_out(tmp_path):
    report = write_snapshot(
        PostgresSourceReader("mes", FakeConn),
        tmp_path,
        {"work_order": ["qty", "removed_col"]},
    )

    assert report.missing_columns == {"mes.work_order": ["removed_col"]}
    assert list(
        CsvSourceReader("mes", tmp_path / "mes").read_rows("work_order").columns
    ) == [
        "wo_no",
        "qty",
    ]


def test_large_table_is_cut_in_key_order_and_reported(tmp_path):
    conn = FakeConn()
    report = write_snapshot(
        PostgresSourceReader("mes", lambda: conn),
        tmp_path,
        {"work_order": None},
        max_rows=1,
    )

    assert report.sampled_tables == {"mes.work_order": 1}
    assert any('ORDER BY "wo_no" LIMIT 2' in q for q in conn.row_queries)
    rows = CsvSourceReader("mes", tmp_path / "mes").read_rows("work_order")
    assert rows["wo_no"].tolist() == ["WO-1"]


def test_view_without_key_is_cut_without_sorting(tmp_path):
    # 기본키가 없으면 정렬 없이 자른다 (큰 뷰 전체 정렬·정렬 못 하는 타입 방지)
    conn = FakeConn()
    write_snapshot(
        PostgresSourceReader("mes", lambda: conn), tmp_path, {"v_open": None}
    )

    assert conn.row_queries
    assert all("ORDER BY" not in q and "LIMIT" in q for q in conn.row_queries)


def test_table_left_with_no_columns_is_skipped_not_failed(tmp_path):
    # 고른 컬럼이 모두 사라졌고 기본키도 없으면 분석을 멈추지 않고 알리기만 한다
    report = write_snapshot(
        PostgresSourceReader("mes", FakeConn), tmp_path, {"v_open": ["gone"]}
    )

    assert report.missing_columns == {"mes.v_open": ["gone"]}
    assert not (tmp_path / "mes" / "v_open.csv").exists()
