import pytest

from app.dataset.build import generate
from app.sources.base import UnknownTableError
from app.sources.csv_reader import CsvSourceReader


def test_lists_tables_in_name_order(dev_factory):
    assert CsvSourceReader("erp", dev_factory / "erp").list_tables() == [
        "assets",
        "employees",
        "sales_orders",
    ]


def test_schema_merges_types_comments_and_primary_key(dev_factory):
    schema = CsvSourceReader("mes", dev_factory / "mes").read_schema("jobs")

    assert schema.source_id == "mes"
    assert schema.description == "작업지시"
    assert [column.name for column in schema.columns] == [
        "job_no",
        "order_code",
        "machine_code",
        "operator_code",
        "status",
        "planned_qty",
    ]
    assert schema.primary_key == ["job_no"]
    assert schema.column("order_code").comment == "주문 참조 번호"
    assert schema.column("planned_qty").declared_type == "int"


def test_schema_without_schema_file_uses_csv_header_only(tmp_path):
    (tmp_path / "t.csv").write_text("a,b\n1,2\n", encoding="utf-8")

    schema = CsvSourceReader("x", tmp_path).read_schema("t")

    assert [column.name for column in schema.columns] == ["a", "b"]
    assert schema.primary_key == []
    assert schema.column("a").comment is None


def test_schema_file_with_unknown_column_is_rejected(tmp_path):
    (tmp_path / "t.csv").write_text("a\n1\n", encoding="utf-8")
    (tmp_path / "_schema.json").write_text(
        '{"tables": {"t": {"columns": {"zzz": {}}}}}', encoding="utf-8"
    )

    with pytest.raises(ValueError):
        CsvSourceReader("x", tmp_path).read_schema("t")


def test_rows_are_strings_and_blank_cells_are_missing(dev_factory):
    rows = CsvSourceReader("mes", dev_factory / "mes").read_rows("jobs")

    assert rows.loc[0, "operator_code"] == "1007"
    assert rows["order_code"].isna().sum() == 1


def test_unknown_table_and_missing_folder(dev_factory, tmp_path):
    with pytest.raises(UnknownTableError):
        CsvSourceReader("erp", dev_factory / "erp").read_rows("nope")
    with pytest.raises(FileNotFoundError):
        CsvSourceReader("x", tmp_path / "missing")


def test_reads_every_table_of_generated_dataset(tmp_path):
    # test_dataset.py에서 옮겨 옴. 데이터셋 원천 폴더를 분석기가 읽는 형식인지 확인한다
    generate("t2", root=tmp_path)
    for source in ("erp", "mes"):
        reader = CsvSourceReader(source, tmp_path / "data-t2" / source)
        for table in reader.list_tables():
            reader.read_schema(table)
