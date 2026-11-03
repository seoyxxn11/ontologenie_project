import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import pandas as pd


@dataclass
class ColumnProfile:
    name: str
    dtype: str
    null_count: int
    null_ratio: float
    distinct_count: int
    sample_values: list
    # 값이 모두 채워져 있고 중복이 없다는 관찰일 뿐, PK로 확정한 것이 아니다.
    is_key_candidate: bool


@dataclass
class TableProfile:
    table_name: str
    row_count: int
    duplicate_row_count: int
    columns: list[ColumnProfile]


def profile_table(
    table_name: str, df: pd.DataFrame, sample_size: int = 3
) -> TableProfile:
    row_count = len(df)
    columns = []
    for column in df.columns:
        series = df[column]
        null_count = int(series.isna().sum())
        distinct_count = int(series.nunique(dropna=True))
        columns.append(
            ColumnProfile(
                name=str(column),
                dtype=str(series.dtype),
                null_count=null_count,
                null_ratio=null_count / row_count if row_count else 0.0,
                distinct_count=distinct_count,
                sample_values=series.dropna()
                .drop_duplicates()
                .head(sample_size)
                .tolist(),
                is_key_candidate=row_count > 0
                and null_count == 0
                and distinct_count == row_count,
            )
        )
    return TableProfile(
        table_name=table_name,
        row_count=row_count,
        duplicate_row_count=int(df.duplicated().sum()),
        columns=columns,
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="CSV 파일의 컬럼별 프로파일을 JSON으로 출력한다."
    )
    parser.add_argument("csv_path", type=Path)
    args = parser.parse_args()

    df = pd.read_csv(args.csv_path)
    profile = profile_table(args.csv_path.stem, df)
    print(json.dumps(asdict(profile), ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
