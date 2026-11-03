"""FN-AIP-01 정형 데이터 분석 중 코드가 하는 부분.

컬럼 통계·값 모양·키 후보를 계산하고, 두 컬럼의 값이 이어지는지(값 포함 비율)와
표기가 다르면 어떤 변환 단계로 같아지는지 찾는다. LLM은 쓰지 않는다(크레딧 0).

    .\\.venv\\Scripts\\python.exe -m app.analyzers.structured data\\sample\\v1
"""

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path

import pandas as pd

from app.analyzers.table_profiler import profile_table
from app.rules.transforms import (
    TransformError,
    TransformOp,
    TransformStep,
    apply_steps,
    describe_steps,
)
from app.sources.csv_reader import CsvSourceReader

# 이 비율 이상 값이 이어지면 연결 후보로 낸다. 낮은 쪽(오타·결측)은 정합성 검사가 다룬다.
MIN_COVERAGE = 0.6
# 값이 이 비율 이상 서로 다르면 키 후보로 본다. 중복이 조금 있어도(원천 오류) 후보에서 빼지 않기 위함.
MIN_KEY_UNIQUE_RATIO = 0.95
# 이어지는 값이 이보다 적으면 우연일 수 있어 후보로 내지 않는다. 예: 라인 코드 L1·L2
MIN_MATCHED = 3
SKIP_TABLES = {"change_log"}  # 변경 이력은 원천 데이터가 아니라 동기화용 기록이다.
EXAMPLE_COUNT = 3
# 개인정보 컬럼(공통 기준 AI-C-08). 값을 가리고 연결 비교에도 쓰지 않는다.
PERSONAL_COMMENT_HINTS = ("가명", "성명", "전화", "휴대", "주소", "이메일", "주민")
PERSONAL_COLUMN_NAMES = {
    "emp_name",
    "worker_name",
    "person_name",
    "phone",
    "mobile",
    "email",
    "address",
}
MASK = "***"


@dataclass
class ColumnAnalysis:
    ref: str
    declared_type: str | None
    comment: str | None
    is_declared_key: bool
    row_count: int
    null_count: int
    distinct_count: int
    duplicate_count: int  # 값이 있는 행 중 다른 행과 값이 겹치는 행 수
    top_pattern: (
        str | None
    )  # 값 모양. 숫자=0, 영문=A, 한글=가. 예: OD-2608-012 → AA-0000-000
    top_pattern_ratio: float
    sample_values: list[str]
    is_key_candidate: bool
    is_personal: bool


@dataclass
class LinkCandidate:
    from_ref: str
    to_ref: str
    rule_type: str  # same_value · transform
    source_transforms: list[dict]
    target_transforms: list[dict]
    description: str
    from_distinct: int
    matched_distinct: int
    coverage: float
    from_null_count: int
    cardinality: str
    examples: list[dict] = field(default_factory=list)
    unmatched_examples: list[str] = field(default_factory=list)
    # 변환 뒤 같은 도착 값으로 합쳐진 서로 다른 출발 값. 예: 2610021과 OD-2610-021 → OD-2610-021
    merged_examples: list[list[str]] = field(default_factory=list)


@dataclass
class TableKey:
    columns: list[str]
    source: str  # declared: 표 정의의 기본키 · observed: 값을 보고 찾은 후보
    duplicate_rows: int  # 이 키로 봤을 때 겹치는 행 수. 0이 아니면 원천 중복 사례


@dataclass
class StructuredAnalysis:
    tables: dict[str, list[ColumnAnalysis]]
    table_keys: dict[str, list[TableKey]]
    links: list[LinkCandidate]


def value_pattern(value: str) -> str:
    out = []
    for ch in value:
        if ch.isdigit():
            out.append("0")
        elif "a" <= ch.lower() <= "z":
            out.append("A")
        elif "가" <= ch <= "힣":
            out.append("가")
        else:
            out.append(ch)
    return "".join(out)


def analyze_sources(root: Path) -> StructuredAnalysis:
    """root 아래 폴더 하나를 원천 하나로 보고(erp, mes, maint …) 모든 표를 분석한다."""
    frames: dict[str, pd.DataFrame] = {}
    tables: dict[str, list[ColumnAnalysis]] = {}
    table_keys: dict[str, list[TableKey]] = {}
    for folder in sorted(p for p in Path(root).iterdir() if p.is_dir()):
        reader = CsvSourceReader(folder.name, folder)
        for table in reader.list_tables():
            if table in SKIP_TABLES:
                continue
            schema = reader.read_schema(table)
            df = reader.read_rows(table)
            key = f"{folder.name}.{table}"
            frames[key] = df
            tables[key] = _analyze_table(key, schema, df)
            table_keys[key] = _table_keys(schema, df, tables[key])
    return StructuredAnalysis(tables, table_keys, _find_links(frames, tables))


def _analyze_table(key, schema, df) -> list[ColumnAnalysis]:
    profile = profile_table(key, df)
    result = []
    for meta, prof in zip(schema.columns, profile.columns):
        values = df[meta.name].dropna()
        duplicate_count = int(values.duplicated(keep=False).sum())
        patterns = Counter(value_pattern(v) for v in values)
        top, top_count = patterns.most_common(1)[0] if patterns else (None, 0)
        unique_ratio = (
            prof.distinct_count / profile.row_count if profile.row_count else 0
        )
        personal = is_personal(meta.name, meta.comment)
        result.append(
            ColumnAnalysis(
                ref=f"{key}.{meta.name}",
                declared_type=meta.declared_type,
                comment=meta.comment,
                is_declared_key=meta.is_primary_key,
                row_count=profile.row_count,
                null_count=prof.null_count,
                distinct_count=prof.distinct_count,
                duplicate_count=duplicate_count,
                top_pattern=top,
                top_pattern_ratio=round(top_count / len(values), 3)
                if len(values)
                else 0.0,
                sample_values=[
                    MASK if personal else str(v) for v in prof.sample_values
                ],
                is_key_candidate=prof.null_count == 0
                and profile.row_count > 1
                and unique_ratio >= MIN_KEY_UNIQUE_RATIO,
                is_personal=personal,
            )
        )
    return result


def is_personal(name: str, comment: str | None) -> bool:
    return name.lower() in PERSONAL_COLUMN_NAMES or any(
        hint in (comment or "") for hint in PERSONAL_COMMENT_HINTS
    )


def _table_keys(schema, df, columns: list[ColumnAnalysis]) -> list[TableKey]:
    """표 정의에 기본키가 있으면 그것을(여러 컬럼 가능), 없을 때만 값으로 찾은 코드형 한 컬럼 키 후보를 낸다.
    이름·금액·시각은 값이 우연히 안 겹칠 수 있어 키로 추측하지 않는다."""
    if schema.primary_key:
        return [
            TableKey(
                schema.primary_key,
                "declared",
                int(df.duplicated(subset=schema.primary_key).sum()),
            )
        ]
    return [
        TableKey([name], "observed", int(df[name].duplicated().sum()))
        for c in columns
        if c.is_key_candidate and _is_code_like(c)
        for name in [c.ref.rsplit(".", 1)[1]]
    ]


def _is_link_target(
    column: ColumnAnalysis, table_columns: list[ColumnAnalysis]
) -> bool:
    """연결의 도착이 될 수 있는 컬럼. 표 정의에 한 컬럼 기본키가 있으면 그 키만, 기본키가 없을 때만 값으로 찾은 키 후보.
    작은 표에서 우연히 값이 안 겹치는 컬럼(재고의 품번, 금형의 제품 코드 등)으로 잘못 잇는 것을 막는다."""
    declared = [c for c in table_columns if c.is_declared_key]
    if declared:
        return len(declared) == 1 and column is declared[0] and _is_code_like(column)
    return column.is_key_candidate and _is_code_like(column)


def _is_code_like(column: ColumnAnalysis) -> bool:
    """식별자로 쓰일 만한 문자형 컬럼만 비교한다. 수량·금액·날짜끼리 숫자가 우연히 겹치는 것을 막는다."""
    if column.is_personal:
        return False
    if column.declared_type:
        return column.declared_type.startswith(("varchar", "char"))
    return column.top_pattern is not None and set(column.top_pattern) != {"0"}


def _find_links(frames, tables) -> list[LinkCandidate]:
    columns = {c.ref: c for cols in tables.values() for c in cols}
    targets = [
        c
        for c in columns.values()
        if _is_link_target(c, tables[c.ref.rsplit(".", 1)[0]])
    ]
    links = []
    for source in columns.values():
        if not _is_code_like(source) or source.distinct_count == 0:
            continue
        source_table = source.ref.rsplit(".", 1)[0]
        source_values = _values(frames, source.ref)
        for target in targets:
            if target.ref.rsplit(".", 1)[0] == source_table:
                continue
            link = _best_link(
                source, target, source_values, _values(frames, target.ref)
            )
            if link:
                links.append(link)
    return _drop_reverse_duplicates(_prefer_direct_links(links))


def _prefer_direct_links(links: list[LinkCandidate]) -> list[LinkCandidate]:
    """값 그대로 거의 다 이어지는 연결이 있는 컬럼은 변환으로 만든 다른 연결을 버린다.
    예: 실적의 작업자 번호는 MES 작업자로 이어지므로, 직원 번호로 다시 잇지 않는다."""
    direct = {
        l.from_ref for l in links if l.rule_type == "same_value" and l.coverage >= 0.9
    }
    return [l for l in links if l.rule_type == "same_value" or l.from_ref not in direct]


def _values(frames, ref: str) -> list[str]:
    table, column = ref.rsplit(".", 1)
    return frames[table][column].dropna().astype(str).tolist()


def _best_link(source, target, source_values, target_values) -> LinkCandidate | None:
    distinct_source = sorted(set(source_values))
    best = None
    for rule_type, src_steps, tgt_steps in _transform_options(
        distinct_source, sorted(set(target_values))
    ):
        target_map = _transform_targets(tgt_steps, target_values)
        if target_map is None:
            continue
        matched, unmatched = [], []
        for value in distinct_source:
            try:
                converted = apply_steps(src_steps, value)
            except TransformError:
                converted = None
            (matched if converted in target_map else unmatched).append(
                (value, target_map.get(converted))
            )
        coverage = len(matched) / len(distinct_source)
        better = (
            best is None
            or coverage > best[0] + 1e-9
            or (
                abs(coverage - best[0]) < 1e-9
                and len(src_steps) + len(tgt_steps) < len(best[2]) + len(best[3])
            )
        )
        if better:
            best = (coverage, rule_type, src_steps, tgt_steps, matched, unmatched)
    if best is None or best[0] < MIN_COVERAGE or len(best[4]) < MIN_MATCHED:
        return None
    coverage, rule_type, src_steps, tgt_steps, matched, unmatched = best
    merged = defaultdict(list)
    for value, target_value in matched:
        merged[target_value].append(value)
    description = (
        "값을 그대로 비교"
        if rule_type == "same_value"
        else (f"출발: {describe_steps(src_steps)} / 도착: {describe_steps(tgt_steps)}")
    )
    return LinkCandidate(
        from_ref=source.ref,
        to_ref=target.ref,
        rule_type=rule_type,
        source_transforms=[_step_dict(s) for s in src_steps],
        target_transforms=[_step_dict(s) for s in tgt_steps],
        description=description,
        from_distinct=len(distinct_source),
        matched_distinct=len(matched),
        coverage=round(coverage, 3),
        from_null_count=source.null_count,
        cardinality="ONE_TO_ONE"
        if source.is_key_candidate and source.duplicate_count == 0
        else "MANY_TO_ONE",
        examples=[{"from": f, "to": t} for f, t in matched[:EXAMPLE_COUNT]],
        unmatched_examples=[f for f, _ in unmatched[:EXAMPLE_COUNT]],
        merged_examples=[v for v in merged.values() if len(v) > 1][:EXAMPLE_COUNT],
    )


def _transform_targets(steps, target_values) -> dict | None:
    """도착 값에 변환을 적용한 사전(변환 값 → 원래 값). 서로 다른 값이 같아지면 쓸 수 없는 변환이다."""
    mapping = {}
    for value in set(target_values):
        try:
            converted = apply_steps(steps, value)
        except TransformError:
            return None
        if converted in mapping:
            return None
        mapping[converted] = value
    return mapping


_PREFIX_DIGITS = re.compile(r"(\D*)(\d+)")


def _shape(values: list[str]) -> tuple[str, int] | None:
    """값이 '공통 접두어 + 같은 자릿수 숫자'인지. 예: GP0031·GP0007 → ('GP', 4)"""
    parts = [_PREFIX_DIGITS.fullmatch(v) for v in values]
    if not values or any(p is None for p in parts):
        return None
    prefixes = {p.group(1) for p in parts}
    widths = Counter(len(p.group(2)) for p in parts)
    if len(prefixes) != 1:
        return None
    width, count = widths.most_common(1)[0]
    return (prefixes.pop(), width) if count / len(values) >= 0.9 else None


def _common_prefix(values: list[str]) -> str | None:
    parts = [_PREFIX_DIGITS.fullmatch(v) for v in values]
    if not values or any(p is None for p in parts):
        return None
    prefixes = {p.group(1) for p in parts}
    return prefixes.pop() if len(prefixes) == 1 else None


def _transform_options(source_values, target_values):
    """시도해 볼 변환 조합. 모두 FN-MAP-05 허용 목록 안의 단계로만 만든다."""
    step = TransformStep
    yield "same_value", [], []
    clean = [step(op=TransformOp.TRIM), step(op=TransformOp.UPPER)]
    yield "transform", clean, clean
    if _digits_comparison_makes_sense(source_values, target_values):
        digits = [step(op=TransformOp.EXTRACT_NUMBER)]
        yield "transform", digits, digits
    target_shape = _shape(target_values)
    source_prefix = _common_prefix(source_values)
    if target_shape and source_prefix is not None:
        prefix, width = target_shape
        steps = (
            [step(op=TransformOp.STRIP_PREFIX, arg=source_prefix)]
            if source_prefix
            else []
        )
        steps.append(step(op=TransformOp.PAD_ZEROS, arg=width))
        if prefix:
            steps.append(step(op=TransformOp.ADD_PREFIX, arg=prefix))
        yield "transform", steps, []


def _digits_comparison_makes_sense(source_values, target_values) -> bool:
    """숫자만 비교는 출발 값이 대부분 숫자뿐이거나, 일부가 이미 도착 값과 똑같을 때만 시도한다.
    CU0001(고객)과 FA-0001(자산)처럼 번호만 우연히 같은 경우를 막는다."""
    if not source_values or not any(ch.isdigit() for v in target_values for ch in v):
        return False
    digits_only = sum(v.isdigit() for v in source_values) / len(source_values)
    return digits_only >= 0.5 or bool(set(source_values) & set(target_values))


def _step_dict(s: TransformStep) -> dict:
    return {"op": s.op.value, "arg": s.arg}


def _drop_reverse_duplicates(links: list[LinkCandidate]) -> list[LinkCandidate]:
    """A→B와 B→A가 둘 다 나오면 더 많이 이어지는 쪽 하나만 남긴다."""
    by_pair = {}
    for link in links:
        pair = frozenset((link.from_ref, link.to_ref))
        kept = by_pair.get(pair)
        if kept is None or link.coverage > kept.coverage:
            by_pair[pair] = link
    return sorted(by_pair.values(), key=lambda l: (l.from_ref, -l.coverage))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="정형 데이터 분석 (코드 부분, LLM 호출 없음)"
    )
    parser.add_argument("root", type=Path, help="원천 폴더 (예: data/sample/v1)")
    parser.add_argument("--json", type=Path, help="전체 결과를 저장할 JSON 파일")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    analysis = analyze_sources(args.root)
    print(f"표 {len(analysis.tables)}개, 연결 후보 {len(analysis.links)}개")
    for table, keys in analysis.table_keys.items():
        shown = ", ".join(
            f"{'+'.join(k.columns)}({'정의' if k.source == 'declared' else '값'}"
            + (f", 중복 {k.duplicate_rows}행" if k.duplicate_rows else "")
            + ")"
            for k in keys
        )
        print(f"  키 | {table}: {shown or '없음'}")
    for link in analysis.links:
        print(
            f"- {link.from_ref} → {link.to_ref} | {link.description} | "
            f"{link.matched_distinct}/{link.from_distinct} 이어짐 ({link.coverage:.0%}), 빈 값 {link.from_null_count}"
            + (
                f", 안 이어지는 값 예: {link.unmatched_examples}"
                if link.unmatched_examples
                else ""
            )
            + (
                f", 하나로 합쳐진 값 예: {link.merged_examples}"
                if link.merged_examples
                else ""
            )
        )
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(
            json.dumps(asdict(analysis), ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
