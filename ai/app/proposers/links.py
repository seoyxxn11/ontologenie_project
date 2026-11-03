"""FN-AIP-04 관계 유형·연결 규칙 후보. 코드 규칙으로만 만든다(method=rule, LLM 미사용).

정형 분석기(structured.py)는 평가 기준선으로 고정해 두고, 그 비교 함수를 가져와 범위를 넓혔다.
- 숫자형(int) 컬럼도 식별자로 비교한다.
- 도착은 기본키 → 다른 유일 키 순서. 같은 출발 컬럼이 기본키로 이어지면 다른 유일 키 후보는 버린다.
- 같은 표 안의 다른 컬럼(자기 참조)도 본다.
- 두 컬럼 조합(복합키), 구분 컬럼 값에 따라 대상이 바뀌는 관계(조건부)를 찾는다.
- 확정하지 않고 보류(HELD)로 내는 경우: 값 종류가 3개 미만, 서로 다른 값 기준으로는 덜 이어지고 행 기준으로만 이어짐,
  원래 값이 하나도 같지 않은데 숫자만 남겨서 같아짐.
- (10-28, 독립 데이터 B에서 드러난 문제를 고침)
  - 숫자 묶음 비교: 'pl354' = 'PL-0354', '26-20' = 'SO26-0020'처럼 표기가 달라도 숫자 묶음이 같으면 잇는다.
  - 순번 키: 1, 2, 3…처럼 이어지는 정수 키는 아무 작은 숫자 컬럼과 겹치므로, 컬럼 이름에 근거가 없으면 보류한다.
- (10-28, 기업 예시 구조에 맞춘 A에서 드러난 문제를 고침)
  - 다른 유일 키: 값 모양이 제각각이어도(부서 이름 '품질팀'·'프레스1팀', 도면 번호) 숫자만 있는 값이 아니면 도착 후보로 본다.
  - 종류 관계: 출발 컬럼 이름에 도착 표 이름과 컬럼 이름이 다 들어 있고(routing.equipment_kind → equipment.kind)
    값이 모두 들어가면, 도착이 키가 아니어도 잇는다(N:M).
  - 거쳐 가는 관계: 출발 표에 없는 컬럼을 이미 찾은 관계의 도착 표에서 가져와 복합키로 잇는다
    (공정 실적 작업지시 번호 → 작업지시 품목 코드 + 공정 순번 → 라우팅). 연결 규칙(ERD link_mapping_condition)은
    두 객체의 속성만 비교하므로 이 관계는 규칙 없이 관계 유형만 보류로 낸다.
"""

import re
from dataclasses import dataclass, field
from functools import cache
from pathlib import Path

import pandas as pd

from app.analyzers.structured import (
    ColumnAnalysis,
    StructuredAnalysis,
    _best_link,
    analyze_sources,
    value_pattern,
)
from app.models.candidate import (
    Candidate,
    Evidence,
    LinkRuleCondition,
    LinkRulePayload,
    LinkTypePayload,
    RuleType,
    SourceFilter,
)
from app.proposers import confidence as score
from app.proposers.objects import TIME_TYPES, to_code
from app.rules.transforms import TransformStep
from app.sources.csv_reader import CsvSourceReader

MIN_ROWS_FOR_SMALL = 20  # 값 종류가 적어도 이만큼 행이 모두 이어지면 보류 후보로 낸다
ROW_COVERAGE = 0.9
CATEGORY_MAX = 5  # 조건 컬럼으로 볼 값 종류 상한
GROUP_COVERAGE = 0.8  # 숫자 묶음 비교로 이을 때 필요한 서로 다른 값 기준 비율
# 이름 비교에서 뺄 흔한 꼬리말
GENERIC_NAMES = {"no", "id", "cd", "code", "seq", "num", "key", "ref", "nbr"}


@dataclass
class Column:
    table: str  # 원천.표
    name: str
    analysis: ColumnAnalysis
    values: pd.Series  # 빈 값 제외, 문자열

    @property
    def ref(self) -> str:
        return f"{self.table}.{self.name}"

    @property
    def declared(self) -> str:
        return (self.analysis.declared_type or "").lower()

    @property
    def unique(self) -> bool:
        return len(self.values) > 0 and self.values.is_unique


@dataclass
class Relation:
    source: list[Column]
    target: list[Column]
    rule_type: str = "same_value"
    source_steps: list[dict] = field(default_factory=list)
    target_steps: list[dict] = field(default_factory=list)
    target_key: str = "primary"
    condition: tuple[Column, str] | None = None
    distinct_coverage: float = 0.0
    row_coverage: float = 0.0
    matched: list[tuple[str, str]] = field(default_factory=list)
    unmatched: list[str] = field(default_factory=list)
    hold: str | None = None
    via: tuple[Column, Column] | None = (
        None  # (찾을 컬럼, 가져올 컬럼): 첫 출발 컬럼에만 적용
    )


class Tables:
    def __init__(self, root: Path, analysis: StructuredAnalysis):
        self.columns: dict[str, list[Column]] = {}
        self.pk: dict[str, list[str]] = {}
        self.frames: dict[str, pd.DataFrame] = {}
        for folder in sorted(p for p in Path(root).iterdir() if p.is_dir()):
            reader = CsvSourceReader(folder.name, folder)
            for name in reader.list_tables():
                key = f"{folder.name}.{name}"
                if key not in analysis.tables:
                    continue
                schema = reader.read_schema(name)
                df = reader.read_rows(name)
                self.frames[key] = df
                self.pk[key] = schema.primary_key
                by_name = {c.ref.rsplit(".", 1)[1]: c for c in analysis.tables[key]}
                self.columns[key] = [
                    Column(
                        key, c.name, by_name[c.name], df[c.name].dropna().astype(str)
                    )
                    for c in schema.columns
                ]

    def all(self):
        return [c for cols in self.columns.values() for c in cols]


def is_identifier_like(col: Column) -> bool:
    """식별자로 비교해 볼 컬럼: 문자형이거나 정수형. 시각·소수·개인정보는 뺀다."""
    if col.analysis.is_personal or len(col.values) == 0:
        return False
    d = col.declared
    if d.startswith((*TIME_TYPES, "numeric", "decimal", "float", "double")):
        return False
    if d.startswith(("varchar", "char", "text")):
        return True
    if d.startswith(("int", "bigint", "smallint")):
        return True
    return not d and col.analysis.top_pattern is not None


def target_keys(tables: Tables) -> list[tuple[Column, str]]:
    """(도착 컬럼, primary·alternate)."""
    out = []
    for table, cols in tables.columns.items():
        pk = tables.pk[table]
        for col in cols:
            if not is_identifier_like(col):
                continue
            if pk == [col.name]:
                out.append((col, "primary"))
            elif (
                col.name not in pk
                and col.analysis.null_count == 0
                and col.unique
                and len(col.values) >= 3
                and not col.declared.startswith(("int", "bigint", "smallint"))
                and not col.values.str.fullmatch(r"[\d.\-]+").any()
            ):
                out.append((col, "alternate"))
            elif not pk and col.analysis.is_key_candidate:
                out.append((col, "primary"))
    return out


def name_evidence(source: Column, target: Column) -> bool:
    """출발 컬럼 이름이 도착 표·키 이름과 이어지는지. 예: wo_id → work_order.wo_id, order_ref → sales_order."""
    if source.name.lower() == target.name.lower():
        return True
    words = set(source.name.lower().split("_")) - GENERIC_NAMES
    table = target.table.split(".")[1].lower()
    target_words = set(table.split("_")) | set(target.name.lower().split("_"))
    return bool(words & (target_words - GENERIC_NAMES))


def is_sequence_key(col: Column) -> bool:
    """1, 2, 3…이나 1001, 1002…처럼 촘촘히 이어지는 정수 키. 작은 숫자 컬럼이면 무엇이든 들어간다."""
    values = col.values
    if len(values) < 3 or not values.str.fullmatch(r"\d+").all():
        return False
    numbers = values.astype(int)
    return numbers.is_unique and numbers.max() - numbers.min() + 1 <= 1.5 * len(numbers)


def _check_sequence_key(rel: Relation, source: Column, target: Column) -> None:
    if (
        rel.hold is None
        and is_sequence_key(target)
        and source.values.str.fullmatch(r"\d+").all()
        and not name_evidence(source, target)
    ):
        rel.hold = "도착이 1, 2, 3…처럼 이어지는 순번 키라 숫자가 우연히 겹칠 수 있고, 컬럼 이름에도 근거가 없음"


@cache
def _groups(value: str) -> tuple[int, ...]:
    """값 속 숫자 묶음. 'SO26-0020' → (26, 20), 'pl354' → (354,)"""
    return tuple(int(x) for x in re.findall(r"\d+", value))


def _letters(value: str) -> str:
    return re.sub(r"[^A-Za-z]", "", value).upper()


def _group_index(target: Column) -> dict | None:
    """숫자 묶음 → 도착 값. 묶음이 같은 값이 둘 이상이면 하나로 정할 수 없어 None."""
    if not hasattr(target, "_index"):
        index = {}
        for value in set(target.values):
            groups = _groups(value)
            if groups in index:
                index = None
                break
            if groups:
                index[groups] = value
        target._index = index
    return target._index


def _number_groups(source: Column, target: Column, key: str) -> Relation | None:
    """표기가 달라도 숫자 묶음이 같으면 잇는다. 한 묶음이면 허용 변환으로, 여러 묶음이면 대응표로 표현한다."""
    distinct = set(source.values)
    if len(distinct) < 3 or distinct & set(target.values):
        return None
    if all(v.isdigit() for v in distinct) or target.values.str.fullmatch(r"\d+").all():
        # 숫자뿐인 값은 위의 비교가 이미 본다. 숫자뿐인 키로 가면 'INJ-07' → 7처럼 아무 번호나 이어진다
        return None
    index = _group_index(target)
    if not index:
        return None
    hit = {v: index[_groups(v)] for v in distinct if _groups(v) in index}
    coverage = len(hit) / len(distinct)
    if coverage < GROUP_COVERAGE:
        return None
    pairs = sorted(hit.items())
    # 글자가 서로 다른 번호끼리(GR2610-001 ↔ SO2610-001)는 날짜·순번만 우연히 같은 다른 문서다.
    # 글자가 같거나(pl354 ↔ PL-0354) 출발에 글자가 없을 때(26-20 ↔ SO26-0020)만 잇는다
    agree = sum(not _letters(v) or _letters(v) == _letters(t) for v, t in pairs)
    if agree < 0.9 * len(pairs):
        return None
    single = all(len(_groups(v)) == 1 for v in hit)
    if single:
        steps = [
            {"op": "EXTRACT_NUMBER", "arg": None},
            {"op": "TO_INTEGER", "arg": None},
        ]
        target_steps = steps
    else:
        steps = [{"op": "MAP_VALUE", "arg": dict(pairs)}]
        target_steps = []
    rows = _row_coverage(
        source.values,
        target.values,
        [TransformStep.model_validate(x) for x in steps],
        target_steps,
    )
    example = f"{pairs[0][0]} = {pairs[0][1]}"
    hold = None
    if not single:
        hold = f"숫자 묶음만 같아({example}) 대응표로 이었다. 새 값은 연결되지 않으니 변환 함수 추가 검토 필요"
    elif not _letters(pairs[0][0]):
        hold = f"출발 값에 글자가 없고 숫자만 같음({example}). 업무 의미 확인 필요"
    return Relation(
        [source],
        [target],
        "transform",
        steps,
        target_steps,
        key,
        distinct_coverage=round(coverage, 3),
        row_coverage=rows,
        matched=pairs[:3],
        unmatched=sorted(distinct - set(hit))[:3],
        hold=hold,
    )


def _single(source: Column, target: Column, key: str) -> Relation | None:
    rel = _compare(source, target, key)
    if rel is not None:
        _check_sequence_key(rel, source, target)
    return rel


def _compare(source: Column, target: Column, key: str) -> Relation | None:
    link = _best_link(
        source.analysis, target.analysis, source.values.tolist(), target.values.tolist()
    )
    targets = set(target.values)
    if link is not None:
        steps = [TransformStep.model_validate(s) for s in link.source_transforms]
        rows = _row_coverage(
            source.values, target.values, steps, link.target_transforms
        )
        rel = Relation(
            [source],
            [target],
            link.rule_type,
            link.source_transforms,
            link.target_transforms,
            key,
            distinct_coverage=link.coverage,
            row_coverage=rows,
            matched=[(e["from"], e["to"]) for e in link.examples],
            unmatched=link.unmatched_examples,
        )
        digits = any(s["op"] == "EXTRACT_NUMBER" for s in link.source_transforms)
        numeric_source = source.values.str.fullmatch(r"\d+").all()
        no_raw_overlap = not (set(source.values) & targets)
        if digits and no_raw_overlap:
            rel.hold = "원래 값이 하나도 같지 않고 숫자만 남겼을 때 같아짐. 업무 의미 확인 필요"
        elif link.rule_type == "transform" and numeric_source and no_raw_overlap:
            rel.hold = "숫자뿐인 값에 0 채우기·접두어를 붙여야 같아짐. 번호가 우연히 같을 수 있어 업무 의미 확인 필요"
        return rel
    # 분석기 기준(서로 다른 값 3개 이상·포함률 0.6)에 못 미치는 경우
    distinct = set(source.values)
    hit = distinct & targets
    rows = source.values.isin(targets).mean()
    same_shape = {value_pattern(v) for v in distinct} <= {
        value_pattern(v) for v in targets
    }
    if hit and rows >= 0.8 and len(source.values) >= 10 and same_shape:
        why = (
            f"값 종류가 {len(distinct)}개뿐"
            if len(distinct) < 3
            else f"서로 다른 값 기준 {len(hit)}/{len(distinct)}만 이어지고 행 기준 {rows:.0%}"
        )
        return Relation(
            [source],
            [target],
            target_key=key,
            distinct_coverage=round(len(hit) / len(distinct), 3),
            row_coverage=round(float(rows), 3),
            matched=[(v, v) for v in sorted(hit)[:3]],
            unmatched=sorted(distinct - targets)[:3],
            hold=f"{why}이라 우연일 수 있음",
        )
    return _number_groups(source, target, key)


def _row_coverage(values: pd.Series, targets: pd.Series, steps, target_steps) -> float:
    from app.rules.transforms import TransformError, apply_steps

    t_steps = [TransformStep.model_validate(s) for s in target_steps]

    def conv(st, v):
        try:
            return apply_steps(st, v)
        except TransformError:
            return None

    target_set = {conv(t_steps, v) for v in set(targets)}
    cache = {v: conv(steps, v) in target_set for v in set(values)}
    return round(float(values.map(cache).mean()), 3) if len(values) else 0.0


def single_relations(tables: Tables) -> list[Relation]:
    keys = target_keys(tables)
    found = []
    for source in tables.all():
        if not is_identifier_like(source):
            continue
        for target, key in keys:
            if target.ref == source.ref:
                continue
            if target.table == source.table and source.name in tables.pk[source.table]:
                continue  # 자기 표의 키끼리는 비교하지 않는다
            rel = _single(source, target, key)
            if rel is not None:
                found.append(rel)
    return _prune(found)


def _prune(relations: list[Relation]) -> list[Relation]:
    by_source: dict[str, list[Relation]] = {}
    for r in relations:
        by_source.setdefault(r.source[0].ref, []).append(r)
    kept = []
    for rels in by_source.values():
        confirmed = [r for r in rels if r.hold is None]
        pool = confirmed or rels
        # 값 그대로 거의 다 이어지는 기본키 연결이 있으면 그것만 남긴다
        direct = [
            r
            for r in pool
            if r.rule_type == "same_value"
            and r.target_key == "primary"
            and r.distinct_coverage >= 0.9
        ]
        if direct:
            pool = direct
        elif any(r.target_key == "primary" for r in pool):
            pool = [r for r in pool if r.target_key == "primary"]
        best = max(r.distinct_coverage for r in pool)
        kept += [r for r in pool if r.distinct_coverage >= best - 1e-9]
    # A→B와 B→A가 둘 다 있으면 하나만: 도착이 기본키인 쪽, 아니면 포함률이 높은 쪽
    by_pair: dict[frozenset, Relation] = {}
    for r in kept:
        pair = frozenset((r.source[0].ref, r.target[0].ref))
        other = by_pair.get(pair)
        if other is None or (r.target_key, -r.distinct_coverage) < (
            other.target_key,
            -other.distinct_coverage,
        ):
            by_pair[pair] = r
    return list(by_pair.values())


def _pair_keys(tables: Tables) -> list[tuple[str, str, str, str]]:
    """(표, 컬럼 a, 컬럼 b, primary·alternate): 두 컬럼 조합이 유일한 것."""
    targets = []
    for table, cols in tables.columns.items():
        pk = tables.pk[table]
        df = tables.frames[table]
        names = [c.name for c in cols if is_identifier_like(c)]
        pairs = [tuple(pk)] if len(pk) == 2 else []
        if len(pk) == 2:
            pairs += [(pk[0], n) for n in names if n not in pk]
        for a, b in pairs:
            sub = df[[a, b]].dropna()
            if len(sub) >= 3 and not sub.duplicated().any():
                key = "primary" if (a, b) == tuple(pk) else "alternate"
                targets.append((table, a, b, key))
    return targets


def composite_relations(tables: Tables) -> list[Relation]:
    """도착 표에서 유일한 두 컬럼 조합에 출발 표 두 컬럼 조합이 거의 다 들어가면 복합키 관계."""
    found = []
    for table, a, b, key in _pair_keys(tables):
        t_df = tables.frames[table].dropna(subset=[a, b])
        t_a, t_b = set(t_df[a]), set(t_df[b])
        t_pairs = set(zip(t_df[a], t_df[b]))
        for s_table, s_cols in tables.columns.items():
            if s_table == table:
                continue
            cand_a = [
                c for c in s_cols if is_identifier_like(c) and _contained(c.values, t_a)
            ]
            cand_b = [
                c for c in s_cols if is_identifier_like(c) and _contained(c.values, t_b)
            ]
            for x in cand_a:
                for y in cand_b:
                    if x.name == y.name:
                        continue
                    s_df = tables.frames[s_table][[x.name, y.name]].dropna()
                    if len(s_df) < 3:
                        continue
                    hit = [p in t_pairs for p in zip(s_df[x.name], s_df[y.name])]
                    rows = sum(hit) / len(hit)
                    if rows >= ROW_COVERAGE:
                        tcols = {c.name: c for c in tables.columns[table]}
                        sample = list(zip(s_df[x.name], s_df[y.name]))[:3]
                        found.append(
                            Relation(
                                [x, y],
                                [tcols[a], tcols[b]],
                                target_key=key,
                                distinct_coverage=round(rows, 3),
                                row_coverage=round(rows, 3),
                                matched=[(f"{p}|{q}", f"{p}|{q}") for p, q in sample],
                            )
                        )
    return found


def via_relations(tables: Tables, singles: list[Relation]) -> list[Relation]:
    """출발 표에 없는 복합키 컬럼 a를, 출발이 이미 이어지는 표에서 가져와 (a, b) 복합키로 잇는다.

    컬럼 이름이 같은 것끼리만 본다.
    예: 공정 실적(wo_no, op_seq) → 작업지시(wo_no로 찾아 product_code) → 라우팅(product_code, op_seq)
    """
    found = []
    for table, a, b, key in _pair_keys(tables):
        t_df = tables.frames[table].dropna(subset=[a, b]).astype(str)
        t_pairs = set(zip(t_df[a], t_df[b]))
        tcols = {c.name: c for c in tables.columns[table]}
        for r in singles:
            if r.hold or len(r.source) != 1 or r.rule_type != "same_value":
                continue
            src, hop = r.source[0], r.target[0]
            s_cols = {c.name: c for c in tables.columns[src.table]}
            h_cols = {c.name: c for c in tables.columns[hop.table]}
            if (
                table in (src.table, hop.table)
                or a in s_cols
                or a not in h_cols
                or b not in s_cols
            ):
                continue
            h_df = tables.frames[hop.table].dropna(subset=[hop.name, a]).astype(str)
            lookup = dict(zip(h_df[hop.name], h_df[a]))
            s_df = tables.frames[src.table].dropna(subset=[src.name, b]).astype(str)
            pairs = list(zip(s_df[src.name].map(lookup), s_df[b]))
            if len(pairs) < 3:
                continue
            rows = sum(p in t_pairs for p in pairs) / len(pairs)
            if rows >= ROW_COVERAGE:
                found.append(
                    Relation(
                        [src, s_cols[b]],
                        [tcols[a], tcols[b]],
                        target_key=key,
                        distinct_coverage=round(rows, 3),
                        row_coverage=round(rows, 3),
                        matched=[(f"{p}|{q}", f"{p}|{q}") for p, q in pairs[:3]],
                        hold=f"{hop.table}를 거쳐야 이어지는 관계라 연결 규칙(두 객체의 속성 비교)으로 표현할 수 없음. 관계 유형만 제안",
                        via=(hop, h_cols[a]),
                    )
                )
    return found


def category_relations(tables: Tables, taken: set[str]) -> list[Relation]:
    """도착이 키가 아닌 종류 컬럼. 출발 컬럼 이름에 도착 표 이름과 컬럼 이름이 다 들어 있을 때만 본다.

    예: routing.equipment_kind → equipment.kind (특정 설비가 아니라 설비 종류를 가리킴)
    """
    targets = [
        c
        for c in tables.all()
        if is_identifier_like(c)
        and not c.unique
        and c.name not in tables.pk[c.table]
        and c.values.nunique() >= 2
    ]
    found = []
    for source in tables.all():
        if source.ref in taken or not is_identifier_like(source):
            continue
        words = set(source.name.lower().split("_"))
        for target in targets:
            table_words = set(target.table.split(".")[1].split("_")) - GENERIC_NAMES
            if (
                target.table == source.table
                or target.name == source.name
                or not set(target.name.split("_")) <= words
                or not table_words & words
            ):
                continue
            distinct = set(source.values)
            if distinct <= set(target.values):
                found.append(
                    Relation(
                        [source],
                        [target],
                        target_key="none",
                        distinct_coverage=1.0,
                        row_coverage=1.0,
                        matched=[(v, v) for v in sorted(distinct)[:3]],
                    )
                )
    return found


def _contained(values: pd.Series, target: set) -> bool:
    distinct = set(values)
    return len(distinct) >= 1 and len(distinct & target) / len(distinct) >= ROW_COVERAGE


def conditional_relations(tables: Tables, singles: list[Relation]) -> list[Relation]:
    """출발 컬럼이 한 표로만 다 이어지지 않을 때, 같은 표의 구분 컬럼 값마다 다른 표로 모두 이어지는지 본다."""
    keys = target_keys(tables)
    covered = {r.source[0].ref: r for r in singles if r.hold is None}
    found = []
    for table, cols in tables.columns.items():
        df = tables.frames[table]
        categories = [
            c
            for c in cols
            if 2 <= c.values.nunique() <= CATEGORY_MAX
            and not c.declared.startswith(("int", "bigint", "numeric", *TIME_TYPES))
            and c.analysis.null_count == 0
        ]
        for source in cols:
            if not is_identifier_like(source) or source.name in tables.pk[table]:
                continue
            single = covered.get(source.ref)
            if single and single.row_coverage >= 0.99:
                continue
            for cat in categories:
                if cat.name == source.name:
                    continue
                parts = []
                for value, group in df.dropna(subset=[source.name]).groupby(cat.name):
                    vals = group[source.name].astype(str)
                    best = None
                    for target, key in keys:
                        if target.table == table:
                            continue
                        rows = vals.isin(set(target.values)).mean()
                        if rows >= 0.99 and (best is None or key < best[1]):
                            best = (target, key, rows)
                    if best is None or vals.nunique() < 1:
                        parts = []
                        break
                    parts.append((value, best, vals))
                if len(parts) >= 2 and len({p[1][0].table for p in parts}) >= 2:
                    for value, (target, key, rows), vals in parts:
                        found.append(
                            Relation(
                                [source],
                                [target],
                                target_key=key,
                                condition=(cat, str(value)),
                                distinct_coverage=1.0,
                                row_coverage=round(float(rows), 3),
                                matched=[(v, v) for v in sorted(set(vals))[:3]],
                            )
                        )
                    break
    return found


def _cardinality(rel: Relation, tables: Tables) -> str:
    if rel.target_key == "none":
        return "MANY_TO_MANY"
    df = tables.frames[rel.source[0].table]
    cols = [c.name for c in rel.source]
    sub = df.dropna(subset=cols)
    if rel.condition:
        sub = sub[sub[rel.condition[0].name].astype(str) == rel.condition[1]]
    if rel.via:  # 거쳐 가져온 값으로 센다(작업지시 여러 건이 같은 품목일 수 있음)
        match, take = rel.via
        h_df = tables.frames[match.table].astype(str)
        lookup = dict(zip(h_df[match.name], h_df[take.name]))
        sub = sub.astype(str).assign(**{cols[0]: sub[cols[0]].astype(str).map(lookup)})
    return (
        "ONE_TO_ONE"
        if len(sub) and not sub.duplicated(subset=cols).any()
        else "MANY_TO_ONE"
    )


def to_candidates(relations: list[Relation], tables: Tables) -> list[Candidate]:
    out = []
    for rel in relations:
        s_table, t_table = rel.source[0].table, rel.target[0].table
        s_code, t_code = to_code(s_table.split(".")[1]), to_code(t_table.split(".")[1])
        cols = "_".join(c.name for c in rel.source)
        key = f"{s_table.split('.')[1]}_{cols}_to_{t_table.split('.')[1]}"
        if rel.condition:
            key += f"_when_{rel.condition[0].name}"
        if rel.via:
            key += f"_via_{rel.via[0].table.split('.')[1]}"
        code = key.upper()  # 관계 유형 코드는 대문자. 후보 ID는 소문자 그대로 둔다
        cardinality = _cardinality(rel, tables)
        # 개수 관계는 데이터에서 관찰한 값이다. 1:1로만 보여도 업무상 1:N일 수 있다
        card_note = (
            " 개수 관계는 데이터에서 관찰한 값(1:1). 업무상 한쪽이 여러 건일 수 있어 검토 필요."
            if cardinality == "ONE_TO_ONE"
            else " 개수 관계는 데이터에서 관찰한 값."
        )
        conditions = [
            LinkRuleCondition(
                source_property=f"{s_code}.{s.name}",
                target_property=f"{t_code}.{t.name}",
                source_transforms=[
                    TransformStep.model_validate(x) for x in rel.source_steps
                ],
                target_transforms=[
                    TransformStep.model_validate(x) for x in rel.target_steps
                ],
            )
            for s, t in zip(rel.source, rel.target)
        ]
        cond_text = (
            f" ({rel.condition[0].name} = {rel.condition[1]}일 때)"
            if rel.condition
            else ""
        )
        if rel.via:
            cond_text += f" ({rel.source[0].name}로 {rel.via[0].table}를 찾아 그 {rel.via[1].name}로 비교)"
        key_text = {
            "primary": "기본키",
            "alternate": "다른 유일 키",
            "none": "종류 값(키 아님)",
        }[rel.target_key]
        how = (
            "값을 그대로 비교"
            if rel.rule_type == "same_value"
            else "변환 후 비교: "
            + ", ".join(s["op"] for s in rel.source_steps + rel.target_steps)
        )
        reason = (
            f"{' + '.join(c.ref for c in rel.source)}의 값이 {t_table}의 {key_text} "
            f"{' + '.join(c.name for c in rel.target)}로 이어진다{cond_text}. {how}. "
            f"서로 다른 값 기준 {rel.distinct_coverage:.0%}, 행 기준 {rel.row_coverage:.0%} 이어짐."
            + (f" 안 이어지는 값 예: {rel.unmatched}." if rel.unmatched else "")
        )
        single = len(rel.source) == 1
        evidence = [
            Evidence(
                source_id=s_table.split(".")[0],
                locator=rel.source[0].ref
                if single
                else s_table + "." + "+".join(c.name for c in rel.source),
                snippet=f"출발 값 예 {[m[0] for m in rel.matched]}",
                values=[m[0] for m in rel.matched] if single else [],
            ),
            Evidence(
                source_id=t_table.split(".")[0],
                locator=rel.target[0].ref
                if single
                else t_table + "." + "+".join(c.name for c in rel.target),
                snippet=f"도착 값 예 {[m[1] for m in rel.matched]}",
                values=[m[1] for m in rel.matched] if single else [],
            ),
        ]
        link_type = LinkTypePayload(
            code=code,
            name=f"{s_code} → {t_code}",
            source_type_code=s_code,
            target_type_code=t_code,
            cardinality=cardinality,
            description=f"{s_code}가 {t_code}를 가리킨다{cond_text}.{card_note}",
        )
        status = {"review_status": "HELD", "hold_reason": rel.hold} if rel.hold else {}
        status["confidence"] = score.build(
            score.link(
                rel.distinct_coverage,
                rel.row_coverage,
                rel.target_key,
                single and name_evidence(rel.source[0], rel.target[0]),
            ),
            hold=rel.hold,
        )
        out.append(
            Candidate(
                candidate_id=f"LT-{key}",
                title=f"관계 {link_type.name}{cond_text}",
                payload=link_type,
                reason=reason,
                evidence=evidence,
                **status,
            )
        )
        if rel.via:
            continue  # 연결 규칙 형식으로 표현할 수 없어 관계 유형만 낸다
        out.append(
            Candidate(
                candidate_id=f"LR-{key}",
                title=f"연결 규칙 {code}",
                payload=LinkRulePayload(
                    code=code,
                    link_type_code=code,
                    pattern=RuleType.TRANSFORM
                    if rel.rule_type == "transform"
                    else RuleType.SAME_VALUE,
                    conditions=conditions,
                    source_filter=SourceFilter(
                        property=f"{s_code}.{rel.condition[0].name}",
                        equals=rel.condition[1],
                    )
                    if rel.condition
                    else None,
                    description=how,
                ),
                reason=reason,
                evidence=evidence,
                **status,
            )
        )
    return out


def propose_links(
    root: Path, analysis: StructuredAnalysis | None = None
) -> list[Candidate]:
    analysis = analysis or analyze_sources(root)
    tables = Tables(root, analysis)
    singles = single_relations(tables)
    conditional = conditional_relations(tables, singles)
    conditional_sources = {r.source[0].ref for r in conditional}
    singles = [r for r in singles if r.source[0].ref not in conditional_sources]
    taken = {r.source[0].ref for r in singles + conditional}
    relations = (
        singles
        + composite_relations(tables)
        + via_relations(tables, singles)
        + category_relations(tables, taken)
        + conditional
    )
    return to_candidates(relations, tables)
