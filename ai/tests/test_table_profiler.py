import pandas as pd

from app.analyzers.table_profiler import profile_table
from app.sources.csv_reader import CsvSourceReader


def _column(profile, name):
    return next(column for column in profile.columns if column.name == name)


def test_profile_counts_nulls_duplicates_and_key_candidates():
    df = pd.DataFrame(
        {
            "wo_id": ["WO-1", "WO-2", "WO-3", "WO-4"],
            "sales_order_no": ["SO-1", None, "SO-2", "SO-2"],
            "plan_qty": [100, 200, 300, 300],
        }
    )

    profile = profile_table("mes_work_order", df)

    assert profile.table_name == "mes_work_order"
    assert profile.row_count == 4
    assert profile.duplicate_row_count == 0

    wo_id = _column(profile, "wo_id")
    assert wo_id.is_key_candidate is True
    assert wo_id.null_count == 0

    sales_order_no = _column(profile, "sales_order_no")
    assert sales_order_no.null_count == 1
    assert sales_order_no.null_ratio == 0.25
    assert sales_order_no.distinct_count == 2
    assert sales_order_no.is_key_candidate is False

    assert _column(profile, "plan_qty").is_key_candidate is False


def test_profile_counts_fully_duplicated_rows():
    df = pd.DataFrame({"a": [1, 1, 2], "b": ["x", "x", "y"]})

    profile = profile_table("t", df)

    assert profile.duplicate_row_count == 1


def test_profile_limits_sample_values_to_distinct_non_null():
    df = pd.DataFrame({"status": ["RUN", None, "RUN", "STOP", "IDLE", "DOWN"]})

    column = _column(profile_table("t", df, sample_size=3), "status")

    assert column.sample_values == ["RUN", "STOP", "IDLE"]


def test_profile_empty_table_has_no_key_candidates():
    df = pd.DataFrame({"id": pd.Series([], dtype="int64")})

    profile = profile_table("empty", df)

    assert profile.row_count == 0
    column = _column(profile, "id")
    assert column.null_ratio == 0.0
    assert column.is_key_candidate is False


def test_profiler_sees_planted_duplicate_and_missing_value(dev_factory):
    reader = CsvSourceReader("mes", dev_factory / "mes")

    profile = profile_table("jobs", reader.read_rows("jobs"))
    job_no = next(column for column in profile.columns if column.name == "job_no")
    order_code = next(
        column for column in profile.columns if column.name == "order_code"
    )

    assert profile.row_count == 6
    assert job_no.distinct_count == 5
    assert job_no.is_key_candidate is False
    assert order_code.null_count == 1
