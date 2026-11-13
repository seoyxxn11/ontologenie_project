"""FN-AIP-04 관계 유형·연결 규칙 후보. 코드 규칙으로만 만든다(method=rule, LLM 미사용).

정형 분석기(structured.py)의 비교를 넓혀 쓰고, 우연히 겹쳤을 수 있는 연결은 보류(HELD)로 낸다.
"""

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

import pandas as pd

from app.analyzers.structured import (
    MIN_MATCHED,
    ColumnAnalysis,
    ColumnValues,
    StructuredAnalysis,
    _best_link,
    analyze_sources,
    value_pattern,
)
from app.llm.privacy import MASK, mask_value
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
from app.proposers.korean import josa, particle
from app.proposers.objects import (
    TIME_TYPES,
    display_name,
    is_integer_type,
    key_like_columns,
    looks_like_person_names,
    observed_pair_key,
    property_codes,
    to_code,
    type_codes,
)
from app.rules.transforms import (
    TransformError,
    TransformStep,
    apply_steps,
    describe_steps,
)
from app.sources.csv_reader import CsvSourceReader

MIN_ROWS_FOR_SMALL = 20  # 값 종류가 적어도 이만큼 행이 모두 이어지면 보류 후보로 낸다
ROW_COVERAGE = 0.9
CATEGORY_MAX = 5  # 조건 컬럼으로 볼 값 종류 상한
GROUP_COVERAGE = 0.8  # 숫자 묶음 비교로 이을 때 필요한 서로 다른 값 기준 비율
# 서로 다른 값 기준으로 이보다 덜 이어지면 확정 후보가 아니라 보류로 낸다
LOW_COVERAGE = 0.8
# 숫자만 겹친 연결에서 출발 값 종류가 이보다 적고 도착의 FEW_SHARE 미만이면 우연으로 본다
FEW_VALUES = 10
FEW_SHARE = 0.3
FEW_OF_MASTER = (
    0.1  # 값 몇 개가 도착 코드표의 이 비율보다 적게 닿으면 다른 코드일 수 있다
)
# 순번 키 여러 개에 겹칠 때 하나를 고르려면 도착 값 종류의 이만큼에 닿아야 한다
TIGHT_SHARE = 0.8
GENERIC_NAMES = {"no", "id", "cd", "code", "seq", "num", "key", "ref", "nbr"}
QUANTITY_WORDS = {"qty", "quantity", "count", "cnt", "amount", "amt"}


@dataclass
class Column:
    table: str  # 원천.표
    name: str
    analysis: ColumnAnalysis
    values: pd.Series  # 빈 값 제외, 문자열
    # 사람 이름 등 개인정보(objects와 같은 판단). 연결 비교에 쓰지 않고 근거 값에도 내지 않는다
    personal: bool = False

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
    # 복합키에서 컬럼마다 변환이 다를 때. 없으면 모든 컬럼에 source_steps·target_steps
    column_steps: list[tuple[list[dict], list[dict]]] | None = None
    # 도착이 순번 키이고 이름 근거도 없어 숫자만 겹친 연결
    sequence_only: bool = False
    # 보류 연결 가운데 숫자(일련번호)만 같아 이어진 것
    number_only: bool = False
    # 대소문자·구분자만 다른 같은 코드로 이은 것. 숫자 우연 거르기에서 뺀다
    canon_match: bool = False
    # 실제로 이어진 출발 행 수. 없으면 row_coverage로 센다(복합키·경유·조건 관계는 직접 센다)
    matched_rows: int | None = None


class Tables:
    def __init__(self, root: Path, analysis: StructuredAnalysis):
        self.columns: dict[str, list[Column]] = {}
        self.pk: dict[str, list[str]] = {}
        self.pair_key: dict[str, list[str]] = {}
        self.frames: dict[str, pd.DataFrame] = {}
        # 객체 유형 코드(다른 원천에 같은 이름의 표가 있으면 원천 접두어)와 표별 속성 코드
        self.type_code: dict[str, str] = type_codes(analysis.tables)
        self.prop_code: dict[str, dict[str, str]] = {}
        for folder in sorted(p for p in Path(root).iterdir() if p.is_dir()):
            reader = CsvSourceReader(folder.name, folder)
            for name in reader.list_tables():
                key = f"{folder.name}.{name}"
                if key not in analysis.tables:
                    continue
                # 분석 단계에서 읽은 표를 다시 쓴다(CSV를 여러 번 읽지 않게)
                schema = analysis.schemas.get(key) or reader.read_schema(name)
                df = (
                    analysis.frames[key]
                    if key in analysis.frames
                    else reader.read_rows(name)
                )
                self.frames[key] = df
                self.pk[key] = schema.primary_key
                self.prop_code[key] = property_codes(c.name for c in schema.columns)
                by_name = {c.column: c for c in analysis.tables[key]}
                # 중복 없는 한 컬럼 키가 없을 때만 두 컬럼 키를 찾는다(objects와 같은 기준)
                if not schema.primary_key and not any(
                    c.is_key_candidate and c.duplicate_count == 0
                    for c in analysis.tables[key]
                ):
                    self.pair_key[key] = observed_pair_key(
                        df, key_like_columns(schema, analysis.tables[key])
                    )
                columns = []
                for c in schema.columns:
                    values = df[c.name].dropna().astype(str)
                    # 값 대부분이 사람 이름이면 비교하지 않는다. 담당자 칸처럼 이름이 일부만 섞인 칸은 부서 이름으로
                    # 이어질 수 있어(정비 담당 → 부서) 비교하되, 이름 모양 값은 근거에서 가린다(_shown_values)
                    personal = by_name[c.name].is_personal or looks_like_person_names(
                        values.tolist()
                    )
                    columns.append(
                        Column(key, c.name, by_name[c.name], values, personal=personal)
                    )
                self.columns[key] = columns

    def all(self):
        return [c for cols in self.columns.values() for c in cols]

    def code(self, table: str) -> str:
        return self.type_code.get(table) or to_code(table.split(".", 1)[1])

    def shown(self, table: str) -> str:
        """제목·설명에 보일 이름(objects.display_name)."""
        return display_name(self.code(table), table.split(".", 1)[1])

    def label(self, table: str) -> str:
        """후보 ID에 쓰는 표 이름. 다른 원천에 같은 이름의 표가 있으면 원천 이름을 붙인다(erp_item)."""
        name = table.split(".", 1)[1]
        if self.code(table) == to_code(name):
            return name
        return table.replace(".", "_", 1)

    def prop(self, col: Column) -> str:
        """'객체유형코드.속성코드' (objects의 속성 코드와 같은 값)."""
        codes = self.prop_code.get(col.table) or property_codes([col.name])
        return f"{self.code(col.table)}.{codes.get(col.name, col.name)}"


def is_identifier_like(col: Column) -> bool:
    """식별자로 비교해 볼 컬럼: 문자형이거나 정수형. 시각·소수·개인정보(사람 이름 포함)는 뺀다."""
    if col.analysis.is_personal or col.personal or len(col.values) == 0:
        return False
    d = col.declared
    if d.startswith((*TIME_TYPES, "numeric", "decimal", "float", "double")):
        return False
    if d.startswith(("varchar", "char", "text")):
        return True
    if is_integer_type(d):
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
                and not is_integer_type(col.declared)
                and (
                    not col.values.str.fullmatch(r"[\d.\-]+").any()
                    or _hyphen_number_code(col.values)
                    or _fixed_digit_code(col)
                )
            ):
                out.append((col, "alternate"))
            elif not pk and col.analysis.is_key_candidate and col.unique:
                # 기본키 없는 표는 값이 모두 다른 컬럼만 기본키처럼 본다(중복이 있으면 한 대상으로 못 정함)
                out.append((col, "primary"))
    return out


def _fixed_digit_code(col: Column) -> bool:
    """바코드처럼 글자형으로 선언된 고정 길이 숫자 코드."""
    values = col.values
    return bool(
        col.declared.startswith(("char", "varchar", "text", "character"))
        and values.str.fullmatch(r"\d+").all()
        and values.str.len().nunique() == 1
        and values.str.len().iloc[0] >= 8
    )


def _hyphen_number_code(values: pd.Series) -> bool:
    """사업자번호(123-45-67890)처럼 하이픈으로 나뉜 숫자 번호. 날짜(2026-03-05)는 뺀다."""
    return bool(
        values.str.fullmatch(r"\d+(-\d+)+").all()
        and not values.str.fullmatch(r"\d{4}-\d{2}-\d{2}").any()
        and values.str.len().min() >= 8
    )


# 이름을 낱말로 나눈다: 밑줄·하이픈과 camelCase 경계(customerId → customer, id). 숫자는 앞 낱말에 붙여 둔다(line2)
_NAME_WORD = re.compile(r"[A-Z]?[a-z0-9]+|[A-Z0-9]+(?![a-z])|[가-힣]+")


def _singular(word: str) -> str:
    """복수형 영어 낱말을 단수로(customers → customer, boxes → box, categories → category)."""
    if len(word) > 4 and word.endswith("ies"):
        return word[:-3] + "y"
    if len(word) > 4 and word.endswith(("ses", "xes", "zes", "ches", "shes")):
        return word[:-2]
    if len(word) > 3 and word.endswith("s") and not word.endswith(("ss", "us", "is")):
        return word[:-1]
    return word


def name_words(name: str) -> set[str]:
    return {_singular(w.lower()) for w in _NAME_WORD.findall(name)}


def name_evidence(source: Column, target: Column) -> bool:
    """출발 컬럼 이름이 도착 표·키 이름과 이어지는지. 예: wo_id → work_order.wo_id, order_ref → sales_order,
    customerId → customers. id·no·seq처럼 흔한 낱말만 같은 것(customer.id ↔ item.id)은 근거로 치지 않는다."""
    words = name_words(source.name) - GENERIC_NAMES
    table = target.table.split(".", 1)[1]
    target_words = name_words(table) | name_words(target.name)
    return bool(words & (target_words - GENERIC_NAMES))


def is_sequence_key(col: Column) -> bool:
    """1, 2, 3…이나 1001, 1002…처럼 촘촘히 이어지는 정수 키."""
    values = col.values
    # 18자리 넘는 숫자는 int64를 넘는다
    if len(values) < 3 or not values.str.fullmatch(r"\d{1,18}").all():
        return False
    numbers = values.astype("int64")
    return numbers.is_unique and numbers.max() - numbers.min() + 1 <= 1.5 * len(numbers)


def _sequence_key(col: Column) -> bool:
    return _cached(col, "sequence", lambda: is_sequence_key(col))


def _check_sequence_key(rel: Relation, source: Column, target: Column) -> None:
    if (
        rel.hold is None
        and _sequence_key(target)
        and _all_digits(source)
        and not name_evidence(source, target)
    ):
        rel.hold = "도착이 1, 2, 3…처럼 이어지는 순번 키라 숫자가 우연히 겹칠 수 있고, 컬럼 이름에도 근거가 없음"
        rel.sequence_only = True
        rel.number_only = True
    elif (
        rel.hold is None
        and _sequence_key(target)
        and len(_distinct(source)) < FEW_VALUES
        and len(_distinct(source)) < FEW_SHARE * len(_distinct(target))
    ):
        rel.hold = (
            f"값 종류 {len(_distinct(source))}개가 1, 2, 3…처럼 이어지는 순번 키의 일부에만 닿음. "
            "번호가 아닌 숫자(용량·등급)일 수 있어 확인 필요"
        )


def _is_name_text(col: Column) -> bool:
    """값 대부분에 띄어쓰기가 있으면 이름·설명('유화기 1호', 'ABS 레진 12형')이지 번호가 아니다."""
    return bool(col.values.str.contains(" ", regex=False).mean() > 0.5)


def _drop_number_coincidences(
    relations: list[Relation], tables: Tables
) -> list[Relation]:
    """숫자(일련번호)만 같아 보류로 낸 연결 가운데, 구조로 보아 참조일 수 없는 것은 내지 않는다.
    대부분 틀린 보류라 검토자 잡음이 된다. 다른 시스템의 기본키끼리는 같은 대상일 수 있어 남긴다."""

    def structural(rel: Relation) -> bool:
        source, target = rel.source[0], rel.target[0]
        own_key = tables.pk.get(source.table) or tables.pair_key.get(source.table) or []
        few = len(_distinct(source)) < FEW_VALUES and (
            len(_distinct(source)) < FEW_SHARE * len(_distinct(target))
        )
        return (
            source.table == target.table
            or few
            or _is_quantity(source)
            or _is_name_text(source)
            or source.name in own_key[1:]
            or (rel.sequence_only and own_key == [source.name])
        )

    weak = [r for r in relations if r.number_only and len(r.source) == 1]
    targets_of: dict[str, int] = {}
    for r in weak:
        targets_of[r.source[0].ref] = targets_of.get(r.source[0].ref, 0) + 1
    drop = {id(r) for r in weak if structural(r) or targets_of[r.source[0].ref] >= 2}
    return [r for r in relations if id(r) not in drop]


def _cached(col: Column, name: str, make):
    """같은 도착 컬럼이 출발 컬럼 수천 개와 비교되어 컬럼마다 한 번만 계산해 둔다."""
    cache = col.__dict__.setdefault("_cache", {})
    if name not in cache:
        cache[name] = make()
    return cache[name]


def _distinct(col: Column) -> set[str]:
    return _cached(col, "distinct", lambda: set(col.values))


def _patterns(col: Column) -> set[str]:
    return _cached(col, "patterns", lambda: {value_pattern(v) for v in _distinct(col)})


def _canon_index(col: Column) -> dict | None:
    """_canon 값 → 원래 값. 서로 다른 값이 같은 _canon이 되면 쓸 수 없어 None."""

    def make():
        index = {}
        for value in _distinct(col):
            canon = _canon(value)
            if not canon or canon in index:
                return None
            index[canon] = value
        return index

    return _cached(col, "canon_index", make)


def _all_digits(col: Column) -> bool:
    return _cached(col, "digits", lambda: bool(col.values.str.fullmatch(r"\d+").all()))


def _all_long_digits(col: Column) -> bool:
    return _cached(
        col, "digits6", lambda: bool(col.values.str.fullmatch(r"\d{6,}").all())
    )


def _digit_strings(col: Column) -> dict[str, str]:
    return _cached(
        col,
        "digit_strings",
        lambda: {
            v: "".join(ch for ch in v if ch.isdigit() or ch == ".")
            for v in _distinct(col)
        },
    )


def _column_values(col: Column) -> ColumnValues:
    """정형 분석기 비교(_best_link)용 값과 변환 결과. 출발·도착으로 수천 번 비교되어 컬럼마다 한 번만 만든다."""
    return _cached(col, "column_values", lambda: ColumnValues(col.values.tolist()))


def _letters_of(col: Column) -> dict[str, str]:
    return _cached(col, "letters", lambda: {v: _letters(v) for v in _distinct(col)})


def _letter_set(col: Column) -> set[str]:
    return _cached(col, "letter_set", lambda: set(_letters_of(col).values()))


def _canon_of(col: Column) -> dict[str, str]:
    return _cached(col, "canon", lambda: {v: _canon(v) for v in _distinct(col)})


def _canon_suffix_of(col: Column) -> dict[str, str]:
    return _cached(
        col,
        "canon_suffix",
        lambda: {v: _canon_without_suffix(v) for v in _distinct(col)},
    )


def _groups_of(col: Column) -> dict[str, tuple[int, ...]]:
    return _cached(col, "groups", lambda: {v: _groups(v) for v in _distinct(col)})


def _lookup(keys_of: dict, index: dict) -> dict:
    """출발 값 → index[그 값의 키]. 겹치는 키가 없으면 값마다 돌지 않는다(대부분의 쌍)."""
    if index.keys().isdisjoint(keys_of.values()):
        return {}
    return {v: index[k] for v, k in keys_of.items() if k in index}


def _isdigit_all(col: Column) -> bool:
    return _cached(col, "isdigit", lambda: all(v.isdigit() for v in _distinct(col)))


@lru_cache(maxsize=200_000)
def _groups(value: str) -> tuple[int, ...]:
    """값 속 숫자 묶음. 'SO26-0020' → (26, 20), 'pl354' → (354,)"""
    return tuple(int(x) for x in re.findall(r"\d+", value))


def _letters(value: str) -> str:
    """글자 부분(한글 포함). '거래처 01' → '거래처', 'pl-0354' → 'PL'"""
    return re.sub(r"[\W\d_]", "", value).upper()


def _group_index(target: Column) -> dict | None:
    """숫자 묶음 → 도착 값. 묶음이 같은 값이 둘 이상이면 하나로 정할 수 없어 None."""
    if not hasattr(target, "_index"):
        index = {}
        for value, groups in _groups_of(target).items():
            if groups in index:
                index = None
                break
            if groups:
                index[groups] = value
        target._index = index
    return target._index


def _number_groups(source: Column, target: Column, key: str) -> Relation | None:
    """표기가 달라도 숫자 묶음이 같으면 잇는다. 한 묶음이면 허용 변환, 여러 묶음이면 대응표."""
    distinct = _distinct(source)
    # 원래 값이 절반 넘게 같으면 앞의 비교가 본다
    if len(distinct) < 3 or len(distinct & _distinct(target)) >= len(distinct) / 2:
        return None
    if _isdigit_all(source) or _all_digits(target):
        # 숫자뿐인 키로 가면 'INJ-07' → 7처럼 아무 번호나 이어진다
        return None
    index = _group_index(target)
    if not index:
        return None
    hit = _lookup(_groups_of(source), index)
    coverage = len(hit) / len(distinct)
    if coverage < GROUP_COVERAGE:
        return None
    pairs = sorted(hit.items())
    # 글자가 다른 번호끼리(GR2610-001 ↔ SO2610-001)는 날짜·순번만 같은 다른 문서다
    agree = sum(not _letters(v) or _letters(v) == _letters(t) for v, t in pairs)
    letters_differ = agree < 0.9 * len(pairs)
    if letters_differ and not _other_system_code(source, target, hit, coverage):
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
    if letters_differ and all(len(_groups(v)) >= 2 for v in hit):
        # 진짜 참조(SO-2026-0012 ↔ ORD2026-0012)일 수도 있어 빼지 않고 보류로 보인다
        hold = (
            f"연도·순번 문서 번호가 숫자만 같음({example}). 같은 해·같은 순번의 다른 문서일 수 있어 "
            "업무 의미 확인 필요"
        )
    elif letters_differ:
        hold = (
            f"다른 시스템의 번호를 숫자로만 이었다({example}). 글자가 달라 다른 대상일 수 있어 "
            "업무 의미 확인 필요"
        )
    elif not single:
        hold = f"숫자 묶음만 같아({example}) 대응표로 이었다. 새 값은 연결되지 않으니 변환 함수 추가 검토 필요"
    elif not all(_letters(v) for v in hit):
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


def _one_to_one(hit: dict) -> bool:
    """출발 값(글자·숫자 묶음이 같으면 하나로 셈)과 도착 값이 하나씩 짝지어지는지."""
    return len(set(hit.values())) == len({(_letters(v), _groups(v)) for v in hit})


def _other_system_code(
    source: Column, target: Column, hit: dict, coverage: float
) -> bool:
    """다른 시스템이 같은 대상을 다른 글자로 번호 매긴 경우(SUP-0012 ↔ S12).
    같은 시스템 안의 글자 다른 번호는 일련번호만 같은 경우가 많아, 시스템이 다르고 거의 다 짝지어질 때만 본다."""
    rows = source.values.isin(hit).mean() if len(source.values) else 0.0
    return (
        (coverage >= 0.9 or rows >= 0.95)
        and len(hit) >= 10
        # 다른 출발 값 여럿이 같은 도착 번호로 모이면(RM_SOY_001·RM_SUG_001 → L0001) 숫자만 겹친 것이다
        and _one_to_one({v: t for v, t in hit.items() if v != t})
        and target.table.split(".")[0] != source.table.split(".")[0]
    )


def _canon(value: str) -> str:
    """대소문자·구분자(-, _, 공백, 마침표)를 무시한 값. 'rm-soy-031' = 'RM_SOY_031'"""
    return re.sub(r"[^0-9A-Z가-힣]", "", value.upper())


def _canon_without_suffix(value: str) -> str:
    """끝의 차수 글자('-A', '_B')를 뗀 _canon. 'SO2026-0012-A' = 'SO-2026-0012'"""
    return _canon(re.sub(r"[-_ ][A-Za-z]$", "", value.strip()))


def _loose(source: Column, target: Column, key: str) -> Relation | None:
    """허용 변환 조합으로 못 이은 값을 대소문자·구분자, 숫자 앞자리를 무시하고 다시 본다."""
    distinct = _distinct(source)
    targets = _distinct(target)
    if len(distinct) < 3 or not distinct.isdisjoint(targets):
        return None
    index = _canon_index(target)
    if index:
        hit = _lookup(_canon_of(source), index)
        suffix = False
        if len(hit) / len(distinct) < GROUP_COVERAGE:
            trimmed = _lookup(_canon_suffix_of(source), index)
            if len(trimmed) > len(hit):
                hit, suffix = trimmed, True
        coverage = len(hit) / len(distinct)
        if coverage >= GROUP_COVERAGE and len(hit) >= 3:
            pairs = sorted(hit.items())
            example = f"{pairs[0][0]} = {pairs[0][1]}"
            # EXTRACT_NUMBER는 소수점을 남기므로 소수점 있는 값은 대응표로 간다
            if not suffix and all(
                _canon(v).isdigit() and "." not in v + t for v, t in hit.items()
            ):
                steps = [{"op": "EXTRACT_NUMBER", "arg": None}]
                target_steps = steps
                hold = f"구분자만 달라 숫자만 남겨 비교했다({example}). 업무 의미 확인 필요"
            else:
                steps = [{"op": "MAP_VALUE", "arg": dict(pairs)}]
                target_steps = []
                what = (
                    "끝의 차수 글자를 떼면 같아" if suffix else "대소문자·구분자만 달라"
                )
                hold = (
                    f"{what} 대응표로 이었다({example}). 새 값은 연결되지 않으니 "
                    "변환 함수(구분자 바꾸기·접미사 떼기) 추가 검토 필요"
                )
            return Relation(
                [source],
                [target],
                "transform",
                steps,
                target_steps,
                key,
                distinct_coverage=round(coverage, 3),
                row_coverage=_row_coverage(
                    source.values,
                    target.values,
                    [TransformStep.model_validate(x) for x in steps],
                    target_steps,
                ),
                matched=pairs[:3],
                unmatched=sorted(distinct - set(hit))[:3],
                hold=hold,
                canon_match=True,
            )
    for short, long_, long_is_target in (
        (target, source, False),
        (source, target, True),
    ):
        rel = _digits_tail(source, target, short, long_, long_is_target, key)
        if rel is not None:
            return rel
    return None


def _distinct_long_numbers(col: Column) -> bool:
    """숫자만 남기면 모두 5자리 이상이고 서로 다른 번호인지('PO-26-00012')."""
    numbers = list(_digit_strings(col).values())
    return bool(
        numbers
        and all(len(n) >= 5 and "." not in n for n in numbers)
        and len(set(numbers)) == len(numbers)
    )


def _tail_numbers(short: Column, min_len: int) -> tuple[set[str], list[int]]:
    """짧은 쪽 번호들과 그 길이(긴 것부터). 긴 쪽 숫자의 뒷부분과 맞춰 볼 때 쓴다(앞자리가 짧은 것 우선)."""
    shorts = (
        _distinct(short)
        if _all_long_digits(short)
        else set(_digit_strings(short).values())
    )
    return shorts, sorted({len(s) for s in shorts if len(s) >= min_len}, reverse=True)


def _digits_tail(
    source: Column,
    target: Column,
    short: Column,
    long_: Column,
    long_is_target: bool,
    key: str,
) -> Relation | None:
    """한쪽은 숫자뿐인 번호, 다른 쪽은 숫자만 남기면 같은 앞자리 + 그 번호인 경우.
    'PO20260315-012' → '260315012'. 변환은 긴 쪽에 걸고, 짧은 쪽에 구분자가 있으면 짧은 쪽에도 건다."""
    short_digits: dict[str, str] | None = None
    min_len = 6
    if not _all_long_digits(short):
        if not _cached(short, "tail_digits", lambda: _distinct_long_numbers(short)):
            return None
        short_digits, min_len = _digit_strings(short), 5
    if short_digits and short.table.split(".")[0] == long_.table.split(".")[0]:
        # 같은 시스템 안에서 글자가 다른 번호는 다른 문서다
        short_letters = _letter_set(short)
        if short_letters != {""} and short_letters != _letter_set(long_):
            return None
    longs = _distinct(long_)
    if len(longs) < 3 or not longs.isdisjoint(_distinct(short)):
        return None
    shorts, lengths = _cached(
        short, f"tail_numbers_{min_len}", lambda: _tail_numbers(short, min_len)
    )
    digits = _digit_strings(long_)
    heads: dict[str, int] = {}
    for number in digits.values():
        for size in lengths:
            if size < len(number) and number[-size:] in shorts:
                heads[number[:-size]] = heads.get(number[:-size], 0) + 1
                break
    if not heads:
        return None
    head, _ = max(heads.items(), key=lambda x: x[1])
    converted = {v: d[len(head) :] for v, d in digits.items() if d.startswith(head)}
    steps = [
        {"op": "EXTRACT_NUMBER", "arg": None},
        {"op": "STRIP_PREFIX", "arg": head},
    ]
    short_steps = [{"op": "EXTRACT_NUMBER", "arg": None}] if short_digits else []
    if long_is_target:
        if len(set(converted.values())) != len(converted):
            return None  # 도착 값 둘이 같은 번호가 되면 하나로 정할 수 없다
        back = {c: v for v, c in converted.items()}
        number_of = short_digits or {v: v for v in _distinct(source)}
        hit = {v: back[number_of[v]] for v in _distinct(source) if number_of[v] in back}
        source_steps, target_steps = short_steps, steps
    else:
        original = {n: v for v, n in (short_digits or {}).items()}
        hit = {v: original.get(c, c) for v, c in converted.items() if c in shorts}
        source_steps, target_steps = steps, short_steps
    distinct = set(source.values)
    coverage = len(hit) / len(distinct)
    if coverage < GROUP_COVERAGE or len(hit) < 3:
        return None
    pairs = sorted(hit.items())
    return Relation(
        [source],
        [target],
        "transform",
        source_steps,
        target_steps,
        key,
        distinct_coverage=round(coverage, 3),
        row_coverage=_row_coverage(
            source.values,
            target.values,
            [TransformStep.model_validate(x) for x in source_steps],
            target_steps,
        ),
        matched=pairs[:3],
        unmatched=sorted(distinct - set(hit))[:3],
        hold=(
            f"숫자만 남기고 앞자리 '{head}'를 빼야 같아짐({pairs[0][0]} = {pairs[0][1]}). "
            "업무 의미 확인 필요"
        ),
    )


def _single(source: Column, target: Column, key: str) -> Relation | None:
    rel = _compare(source, target, key)
    if rel is not None:
        _check_sequence_key(rel, source, target)
        if rel.hold is None and _quantity_name(source):
            rel.hold = "수량·개수 컬럼이라 번호와 숫자가 우연히 겹칠 수 있음"
            rel.number_only = True
        few, targets = len(_distinct(source)), len(_distinct(target))
        if rel.hold is None and _hierarchy(source, target) and few < MIN_MATCHED:
            # 위계로 보고 값이 적어도 비교했다. 이어진 상위 값이 한두 개라 우연일 수 있어 확인을 받는다
            rel.hold = f"같은 표의 상위 코드인데 이어진 값이 {few}개뿐이라 확인 필요"
        if (
            rel.hold is None
            and few < FEW_VALUES
            and few < FEW_OF_MASTER * targets
            and not name_evidence(source, target)
        ):
            rel.hold = (
                f"코드 값 {few}개가 도착 값 {targets}개 중 일부에만 닿고 컬럼 이름에도 근거가 없음. "
                "같은 모양의 다른 코드일 수 있어 확인 필요"
            )
        # 원래 값은 하나도 같지 않은 보류 연결은 숫자만 같아서 이어진 것
        if (
            rel.hold is not None
            and not rel.canon_match
            and not (_distinct(source) & _distinct(target))
        ):
            rel.number_only = True
    return rel


def _prefix_swap(steps: list[dict]) -> tuple[str, str] | None:
    """접두어를 빼고 글자가 다른 접두어를 붙이는 변환이면 (뺀 것, 붙인 것). 예: 'L01' → 'EQ-001'."""
    strip = [s["arg"] for s in steps if s["op"] == "STRIP_PREFIX"]
    add = [s["arg"] for s in steps if s["op"] == "ADD_PREFIX"]
    if strip and add and _letters(str(strip[0])) != _letters(str(add[0])):
        return str(strip[0]), str(add[0])
    return None


# 같은 표의 위계(부서의 상위 부서). 위쪽 행이 몇 개 없어 값 종류가 적어도 비교한다
_HIERARCHY = re.compile(r"(^|_)(parent|upper|super)(_|$)|상위|부모", re.IGNORECASE)


def _hierarchy(source: Column, target: Column) -> bool:
    """같은 표 안에서 상위 코드가 기본키를 가리키는지(parent_code → dept_code)."""
    return source.table == target.table and bool(
        _HIERARCHY.search(source.name)
        or _HIERARCHY.search(source.analysis.comment or "")
    )


def _compare(source: Column, target: Column, key: str) -> Relation | None:
    link = _best_link(
        source.analysis,
        target.analysis,
        _column_values(source),
        _column_values(target),
        min_matched=1
        if key == "primary" and _hierarchy(source, target)
        else MIN_MATCHED,
    )
    targets = _distinct(target)
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
        numeric_source = _all_digits(source)
        no_raw_overlap = _distinct(source).isdisjoint(targets)
        target_letters = _letter_set(target)
        other_letters = [
            v
            for v, letters in _letters_of(source).items()
            if letters and letters not in target_letters
        ]
        if digits and no_raw_overlap:
            rel.hold = "원래 값이 하나도 같지 않고 숫자만 남겼을 때 같아짐. 업무 의미 확인 필요"
        elif digits and len(other_letters) > 0.1 * len(_distinct(source)):
            sample = min(other_letters)
            rel.hold = (
                f"글자가 다른 값까지 숫자만 남겨 같아짐(예: {sample}). "
                "글자가 같은 값만 우연히 겹쳤을 수 있어 업무 의미 확인 필요"
            )
        elif link.rule_type == "transform" and numeric_source and no_raw_overlap:
            rel.hold = "숫자뿐인 값에 0 채우기·접두어를 붙여야 같아짐. 번호가 우연히 같을 수 있어 업무 의미 확인 필요"
        elif (swap := _prefix_swap(link.source_transforms)) and no_raw_overlap:
            old, new = particle(swap[0], "을/를"), particle(swap[1], "으로/로")
            rel.hold = f"접두어 '{swap[0]}'{old} '{swap[1]}'{new} 바꿔야 같아짐. 일련번호만 우연히 같을 수 있어 업무 의미 확인 필요"
        elif (
            no_raw_overlap
            and any(s["op"] == "STRIP_PREFIX" for s in link.source_transforms)
            and _all_digits(target)
        ):
            rel.hold = "출발 값의 글자를 떼야 숫자뿐인 코드와 같아짐. 번호만 우연히 같을 수 있어 업무 의미 확인 필요"
        elif link.coverage < LOW_COVERAGE:
            rel.hold = f"서로 다른 값 기준 {link.coverage:.0%}만 이어짐. 우연히 겹쳤거나 원천 값이 지저분할 수 있어 확인 필요"
        return rel
    # 분석기 기준(서로 다른 값 3개 이상·포함률 0.6)에 못 미치는 경우
    distinct = _distinct(source)
    hit = distinct & targets
    if (
        len(distinct) < 3
        and _sequence_key(target)
        and _all_digits(source)
        and not name_evidence(source, target)
    ):
        # 숫자 한두 가지가 순번 키에 닿는 것은 우연이다. 이름이 이어지면 아래에서 보류로 본다
        return None
    if (
        hit
        and len(source.values) >= 10
        and (rows := source.values.isin(targets).mean()) >= 0.8
        and _patterns(source) <= _patterns(target)
    ):
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
    return _number_groups(source, target, key) or _loose(source, target, key)


def _row_coverage(values: pd.Series, targets: pd.Series, steps, target_steps) -> float:
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
        own_key = tables.pk[source.table] == [source.name]
        for target, key in keys:
            if target.ref == source.ref:
                continue
            if target.table == source.table and source.name in tables.pk[source.table]:
                continue  # 자기 표의 키끼리는 비교하지 않는다
            same_system_key = (
                own_key
                and _system(source) == _system(target)
                and not name_evidence(source, target)
            )
            if same_system_key and key == "primary":
                # 같은 원천의 기본키끼리(customer.id ↔ item.id)는 일련번호가 겹칠 뿐인 경우가 많아
                # 컬럼 이름이 대상 표·키와 이어질 때만 비교한다
                continue
            rel = _single(source, target, key)
            if rel is None:
                continue
            if same_system_key and rel.number_only:
                # 같은 원천에서 기본키가 원래 값은 하나도 같지 않고 변환해야만 맞는 보류(customers.id 1 ↔ '품목1')는
                # 거의 우연이라 내지 않는다. 다른 원천의 기본키끼리는 같은 대상을 다른 번호로 매긴 경우가 있어
                # (mes.worker.id 6 ↔ erp.employee.emp_no E006) 보류로 남긴다
                continue
            found.append(rel)
    return _prune(found, tables)


def _system(col: Column) -> str:
    return col.table.split(".", 1)[0]


def _target_share(rel: Relation) -> float:
    """도착 값 종류 가운데 출발 값이 닿는 비율."""
    targets = _distinct(rel.target[0])
    return len(_distinct(rel.source[0]) & targets) / len(targets) if targets else 0.0


def _tightest_sequence(pool: list[Relation]) -> list[Relation]:
    """순번 키 여러 개에 숫자만 겹칠 때 진짜 참조로 볼 하나. 진짜 참조는 대상 표 번호를 거의 다 쓴다."""
    ranked = sorted(pool, key=_target_share, reverse=True)
    best = _target_share(ranked[0])
    second = _target_share(ranked[1]) if len(ranked) > 1 else 0.0
    if (
        best >= TIGHT_SHARE
        and best >= 2 * second
        and ranked[0].distinct_coverage >= 0.9
    ):
        return [ranked[0]]
    return []


def _prune(relations: list[Relation], tables: Tables) -> list[Relation]:
    by_source: dict[str, list[Relation]] = {}
    for r in relations:
        by_source.setdefault(r.source[0].ref, []).append(r)
    kept = []
    for rels in by_source.values():
        confirmed = [r for r in rels if r.hold is None]
        pool = confirmed or rels
        sequence = [r for r in pool if r.sequence_only]
        if sequence and len(tight := _tightest_sequence(sequence)) == 1:
            pool = tight
        elif sequence and any(
            not r.sequence_only
            and r.row_coverage >= 0.95
            and tables.pk.get(r.source[0].table) != [r.source[0].name]
            for r in pool
        ):
            # 촘촘하지 않은 순번 키는 우연이라, 변환해서 이어지는 진짜 후보를 밀어내지 않게 뺀다
            pool = [r for r in pool if not r.sequence_only]
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
        # 'alternate' < 'primary'라 문자열 비교로는 반대가 된다
        if other is None or (r.target_key != "primary", -r.distinct_coverage) < (
            other.target_key != "primary",
            -other.distinct_coverage,
        ):
            by_pair[pair] = r
    return list(by_pair.values())


def _pair_keys(tables: Tables) -> list[tuple[str, str, str, str]]:
    """(표, 컬럼 a, 컬럼 b, primary·alternate): 두 컬럼 조합이 유일한 것."""
    targets = []
    for table, cols in tables.columns.items():
        pk = tables.pk[table] or tables.pair_key.get(table, [])
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
            tcols = {c.name: c for c in tables.columns[table]}
            for x in cand_a:
                for y in cand_b:
                    # 수량·금액은 순번과 숫자가 우연히 겹친다(composite_transformed와 같은 기준)
                    if x.name == y.name or _is_quantity(y):
                        continue
                    s_df = tables.frames[s_table][[x.name, y.name]].dropna()
                    if len(s_df) < 3:
                        continue
                    pairs = list(zip(s_df[x.name], s_df[y.name]))
                    hit = [p in t_pairs for p in pairs]
                    rows = sum(hit) / len(hit)
                    if rows >= ROW_COVERAGE:
                        loose = [
                            c.name
                            for c, values in ((x, t_a), (y, t_b))
                            if _contained_by(c.values, values) == "rows"
                        ]
                        hold = (
                            f"{', '.join(loose)}은(는) 행 기준으로만 도착 값에 들어가(없는 값이 섞임) 확인 필요"
                            if loose
                            else _number_pair_hold(y, tcols[b])
                        )
                        found.append(
                            Relation(
                                [x, y],
                                [tcols[a], tcols[b]],
                                target_key=key,
                                distinct_coverage=round(rows, 3),
                                row_coverage=round(rows, 3),
                                matched=[
                                    (f"{p}|{q}", f"{p}|{q}") for p, q in pairs[:3]
                                ],
                                unmatched=_unmatched_pairs(pairs, hit),
                                matched_rows=sum(hit),
                                hold=hold,
                            )
                        )
    return _best_composites(found)


def _number_pair_hold(second: Column, target: Column) -> str | None:
    """복합키 둘째 컬럼이 1, 2, 3…같은 작은 숫자인데 도착 컬럼과 이름 근거가 없으면 우선순위·등급 같은 다른 숫자일 수 있다.
    (item_cd, priority 1~5)가 라우팅 (item_cd, step_no)에 맞아 보이는 경우. 0을 채운 고정 자릿수 번호(SAP 품목 번호
    000010)는 형식이 있는 번호라 보지 않는다."""
    small = _cached(
        second,
        "small_numbers",
        lambda: bool(second.values.str.fullmatch(r"[1-9]\d{0,2}|0").all()),
    )
    if small and not name_evidence(second, target):
        return (
            f"순번 숫자 컬럼 {josa(second.name, '이/가')} 도착 {josa(target.name, '과/와')} 숫자로만 겹치고 "
            "컬럼 이름에도 근거가 없음. "
            "우선순위·등급 같은 다른 숫자일 수 있어 확인 필요"
        )
    return None


def _unmatched_pairs(pairs: list[tuple], hit: list[bool]) -> list[str]:
    """도착에 없는 출발 값 조합 예(최대 3개)."""
    return sorted({f"{p}|{q}" for (p, q), ok in zip(pairs, hit) if not ok})[:3]


def _best_composites(found: list[Relation]) -> list[Relation]:
    """첫 컬럼이 같은 출발 조합이 같은 도착 표로 여럿 이어지면 도착 기본키 쪽, 그다음 많이 이어지는 쪽 하나만 남긴다."""
    best: dict[tuple, Relation] = {}

    def rank(rel: Relation) -> tuple:
        return (rel.target_key == "primary", rel.distinct_coverage)

    for rel in found:
        group = (rel.source[0].ref, rel.target[0].table)
        if group not in best or rank(rel) > rank(best[group]):
            best[group] = rel
    return list(best.values())


def _value_map(rel: Relation) -> Callable[[str], str | None]:
    """관계의 변환으로 출발 값 → 도착 원래 값. 안 이어지면 None."""
    s_steps = [TransformStep.model_validate(x) for x in rel.source_steps]
    t_steps = [TransformStep.model_validate(x) for x in rel.target_steps]
    index = {}
    for value in set(rel.target[0].values):
        try:
            index[apply_steps(t_steps, value)] = value
        except TransformError:
            continue

    def convert(value: str) -> str | None:
        try:
            return index.get(apply_steps(s_steps, value))
        except TransformError:
            return None

    return convert


def _as_int(value: str) -> str | None:
    value = value.strip()
    return str(int(value)) if re.fullmatch(r"[0-9]+", value) else None


def _quantity_name(col: Column) -> bool:
    """컬럼 이름에 수량·금액 낱말이 있는지(plan_qty, orderQty, ORDER_QTY)."""
    words = {w.lower() for w in re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])", col.name)}
    return bool(words & QUANTITY_WORDS)


def _is_quantity(col: Column) -> bool:
    """수량·금액 컬럼(plan_qty, orderQty, 수량). 순번과 숫자가 우연히 겹친다."""
    comment = col.analysis.comment or ""
    return _quantity_name(col) or any(k in comment for k in ("수량", "금액", "개수"))


def composite_transformed(tables: Tables) -> list[Relation]:
    """다른 시스템의 두 컬럼 조합이 변환해야 도착 복합키와 같아지는 관계."""
    found = []
    for table, a, b, key in _pair_keys(tables):
        t_df = tables.frames[table].dropna(subset=[a, b])
        t_pairs = set(zip(t_df[a], t_df[b]))
        tcols = {c.name: c for c in tables.columns[table]}
        target_a = Column(
            table, a, tcols[a].analysis, t_df[a].astype(str).drop_duplicates()
        )
        t_b = set(t_df[b])
        t_b_int = {_as_int(v): v for v in t_b}
        system = table.split(".")[0]
        for s_table, s_cols in tables.columns.items():
            if s_table.split(".")[0] == system:
                continue
            ids = [c for c in s_cols if is_identifier_like(c)]
            for x in ids:
                if _contained(x.values, set(target_a.values)):
                    continue  # 그대로 이어지면 composite_relations가 본다
                rel = _compare(x, target_a, key)
                if (
                    rel is None
                    or rel.rule_type != "transform"
                    or rel.distinct_coverage < 0.8
                ):
                    continue
                to_a = _value_map(rel)
                for y in ids:
                    if y.name == x.name or _is_quantity(y):
                        continue
                    if _contained(y.values, t_b):
                        y_steps = []

                        def to_b(v):
                            return v
                    elif None not in t_b_int and all(_as_int(v) for v in set(y.values)):
                        y_steps = [{"op": "TO_INTEGER", "arg": None}]

                        def to_b(v, index=t_b_int):
                            return index.get(_as_int(v))
                    else:
                        continue
                    s_df = tables.frames[s_table][[x.name, y.name]].dropna()
                    if len(s_df) < 3:
                        continue
                    pairs = list(zip(s_df[x.name], s_df[y.name]))
                    mapped = [(to_a(p), to_b(q)) for p, q in pairs]
                    hit = [m in t_pairs for m in mapped]
                    rows = sum(hit) / len(mapped)
                    if rows < ROW_COVERAGE:
                        continue
                    sample = [
                        (f"{p}|{q}", f"{m[0]}|{m[1]}")
                        for (p, q), m in zip(pairs, mapped)
                        if m in t_pairs
                    ][:3]
                    found.append(
                        Relation(
                            [x, y],
                            [tcols[a], tcols[b]],
                            "transform",
                            target_key=key,
                            distinct_coverage=round(rows, 3),
                            row_coverage=round(rows, 3),
                            matched=sample,
                            unmatched=_unmatched_pairs(pairs, hit),
                            matched_rows=sum(hit),
                            hold=rel.hold
                            or "다른 시스템의 두 컬럼을 변환해 복합키로 이었다. 업무 의미 확인 필요",
                            column_steps=[
                                (rel.source_steps, rel.target_steps),
                                (y_steps, y_steps),
                            ],
                        )
                    )
    return found


def via_relations(tables: Tables, singles: list[Relation]) -> list[Relation]:
    """출발 표에 없는 복합키 컬럼 a를, 출발이 이미 이어지는 표에서 가져와 (a, b) 복합키로 잇는다.
    연결 규칙은 두 객체의 속성만 비교하므로 관계 유형만 보류로 낸다."""
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
            hit = [p in t_pairs for p in pairs]
            rows = sum(hit) / len(pairs)
            if rows >= ROW_COVERAGE:
                found.append(
                    Relation(
                        [src, s_cols[b]],
                        [tcols[a], tcols[b]],
                        target_key=key,
                        distinct_coverage=round(rows, 3),
                        row_coverage=round(rows, 3),
                        matched=[(f"{p}|{q}", f"{p}|{q}") for p, q in pairs[:3]],
                        # 안 이어진 행은 출발 표에 있는 값으로 보인다
                        unmatched=_unmatched_pairs(
                            list(zip(s_df[src.name], s_df[b])), hit
                        ),
                        matched_rows=sum(hit),
                        hold=f"{hop.table} 경유 관계라 연결 규칙 생성 불가. 관계 유형만 제안",
                        via=(hop, h_cols[a]),
                    )
                )
    return found


def category_relations(tables: Tables, taken: set[str]) -> list[Relation]:
    """도착이 키가 아닌 종류 컬럼(routing.equipment_kind → equipment.kind). 이름이 다 들어 있을 때만 본다."""
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


def _contained_by(values: pd.Series, target: set) -> str | None:
    """복합키 한 컬럼이 도착 컬럼 값 안에 드는지: 'distinct'(서로 다른 값 기준), 'rows'(행 기준만), None.
    값 종류가 적으면 없는 번호 하나에도 서로 다른 값 기준이 크게 떨어져 행 기준도 보되, 맞는 값이 3종류 이상일 때만."""
    distinct = set(values)
    if not distinct:
        return None
    hit = distinct & target
    if len(hit) / len(distinct) >= ROW_COVERAGE:
        return "distinct"
    if len(hit) >= 3 and values.isin(target).mean() >= ROW_COVERAGE:
        return "rows"
    return None


def _contained(values: pd.Series, target: set) -> bool:
    return _contained_by(values, target) is not None


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
            and not is_integer_type(c.declared)
            and not c.declared.startswith(("numeric", *TIME_TYPES))
            and c.analysis.null_count == 0
            and not (c.personal or c.analysis.is_personal)
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
                        rows = vals.isin(_distinct(target)).mean()
                        # 기본키를 유일값보다 먼저 고른다(문자열 비교로는 'alternate' < 'primary'라 반대가 된다)
                        if rows >= 0.99 and (
                            best is None or (key != "primary") < (best[1] != "primary")
                        ):
                            best = (target, key, rows)
                    if best is None or vals.nunique() < 1:
                        parts = []
                        break
                    parts.append((value, best, vals))
                if len(parts) >= 2 and len({p[1][0].table for p in parts}) >= 2:
                    for value, (target, key, rows), vals in parts:
                        targets = _distinct(target)
                        hit = vals.isin(targets)
                        found.append(
                            Relation(
                                [source],
                                [target],
                                target_key=key,
                                condition=(cat, str(value)),
                                distinct_coverage=1.0,
                                row_coverage=round(float(rows), 3),
                                matched=[(v, v) for v in sorted(set(vals))[:3]],
                                unmatched=sorted(set(vals[~hit]))[:3],
                                matched_rows=int(hit.sum()),
                            )
                        )
                    break
    return found


def _cardinality(rel: Relation, tables: Tables) -> str:
    if rel.target_key == "none":
        return "MANY_TO_MANY"
    df = tables.frames[rel.source[0].table]
    cols = [c.name for c in rel.source]
    own_key = tables.pk.get(rel.source[0].table) or []
    if not rel.via and set(cols) < set(own_key):
        # 출발 기본키의 일부만 가리키면(발주 상세의 발주 번호, 창고별 재고의 품번) 업무상 여러 행이 한 대상을 가리킨다
        return "MANY_TO_ONE"
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


def _floor_percent(rate: float) -> str:
    return f"{int(rate * 100 + 1e-9)}%"


def _how_text(rel: Relation, steps_by_column: list[tuple[list, list]]) -> str:
    """제안 이유의 비교 방법. 변환 이름 대신 사람이 읽는 말로 쓰고 이어진 값 한 쌍을 예로 붙인다."""
    if rel.rule_type == "same_value":
        return "비교 방법: 값을 그대로 비교"
    parts = []
    for (s, t), (s_steps, t_steps) in zip(zip(rel.source, rel.target), steps_by_column):
        sides = [
            f"{josa(col.name, '은/는')} {describe_steps([TransformStep.model_validate(x) for x in steps])}"
            for col, steps in ((s, s_steps), (t, t_steps))
            if steps
        ]
        if sides:
            parts.append(", ".join(sides))
    how = "비교 방법: " + " / ".join(parts) if parts else "비교 방법: 값을 그대로 비교"
    example = next((m for m in rel.matched if m[0] != m[1]), None)
    if example:
        how += f" (예: '{example[0]}' ↔ '{example[1]}')"
    return how


ONE_NOTE = (
    " 개수 관계는 데이터에서 관찰한 값(1:1). 업무상 한쪽이 여러 건일 수 있어 검토 필요."
)
# 발생 기록·거래 문서·상세 줄·현재 상태 표는 여러 행이 같은 대상을 가리키는 것이 업무상 자연스럽다
MANY_SIDE = {"history", "transaction", "detail", "snapshot"}


def business_cardinality(candidates: list[Candidate]) -> list[Candidate]:
    """데이터에서 1:1로 보였어도 발생·거래·상세·현재 상태 표가 다른 표의 기본키를 가리키면 N:1로 바꾼다.

    표 종류는 LLM 해석이 있으면 그 판단을 쓴다. 기준 정보 표(부서장·고정자산 설비)나 기본키가 아닌 고유값(제품 로트)은
    1:1이 맞을 수 있어 그대로 둔다(정답셋 1:1 관계 4개 모두 이 경우).
    """
    category = {
        c.payload.code: getattr(c.payload, "category", None)
        for c in candidates
        if c.payload.kind.value == "OBJECT_TYPE"
    }
    to_primary = {
        c.payload.link_type_code
        for c in candidates
        if c.payload.kind.value == "LINK_RULE" and "(기본키)" in c.reason
    }
    out = []
    for c in candidates:
        payload = c.payload
        if (
            payload.kind.value == "LINK_TYPE"
            and payload.cardinality == "ONE_TO_ONE"
            and payload.code in to_primary
            and category.get(payload.source_type_code) in MANY_SIDE
        ):
            description = payload.description.replace(
                ONE_NOTE, " 개수 관계는 표 종류로 정함(여러 건이 한 대상을 가리킴)."
            )
            c = c.model_copy(
                update={
                    "payload": payload.model_copy(
                        update={
                            "cardinality": "MANY_TO_ONE",
                            "description": description,
                        }
                    )
                }
            )
        out.append(c)
    return out


def _shown_values(values: list, columns: list[Column]) -> list[str]:
    """근거에 보일 값 예. 개인정보 컬럼이면 모두 가리고, 아니어도 사람 이름·전화번호 모양은 가린다."""
    if any(c.personal or c.analysis.is_personal for c in columns):
        return [MASK for _ in values]
    hint = " ".join(f"{c.name} {c.analysis.comment or ''}" for c in columns)
    return [mask_value(str(v), hint) for v in values]


def _checkable(matched: list[tuple], side: int, shown: list[str]) -> list[str]:
    """원천에서 다시 찾아볼 값: 가리지 않은 것만(가린 값은 근거로 내보내지 않는다)."""
    return [m[side] for m, s in zip(matched, shown) if s == str(m[side])]


def _relation_key(rel: Relation, tables: Tables) -> str:
    """후보 ID(LT-·LR-) 뒷부분. 다른 원천에 같은 이름의 표가 있으면 표 이름에 원천을 붙인다."""
    s_table, t_table = rel.source[0].table, rel.target[0].table
    cols = "_".join(c.name for c in rel.source)
    key = f"{tables.label(s_table)}_{cols}_to_{tables.label(t_table)}"
    if rel.condition:
        key += f"_when_{rel.condition[0].name}"
    if rel.via:
        key += f"_via_{tables.label(rel.via[0].table)}"
    return key


def _relation_keys(relations: list[Relation], tables: Tables) -> list[str]:
    """관계마다 겹치지 않는 키. 조건 값 여럿이 같은 도착 표로 가면 키가 같아져 조건 값을 붙인다."""
    keys = [_relation_key(rel, tables) for rel in relations]
    counts: dict[str, int] = {}
    for key in keys:
        counts[key] = counts.get(key, 0) + 1
    out = []
    for key, rel in zip(keys, relations):
        if counts[key] > 1 and rel.condition:
            key += f"_{rel.condition[1]}"
        out.append(key)
    return out


def _numbered(code: str, n: int) -> str:
    """겹친 관계 유형 코드에 번호를 붙인다. 백엔드 코드 길이(100자)를 넘지 않게 앞을 줄인다."""
    suffix = f"_{n}"
    return code[: 100 - len(suffix)] + suffix


def to_candidates(relations: list[Relation], tables: Tables) -> list[Candidate]:
    out = []
    used_codes: set[str] = set()
    for rel, key in zip(relations, _relation_keys(relations, tables)):
        s_table, t_table = rel.source[0].table, rel.target[0].table
        s_code, t_code = tables.code(s_table), tables.code(t_table)
        # 관계 유형 코드는 대문자(백엔드 규칙에 맞게 바꿈). 후보 ID는 원래 이름 그대로 둔다.
        # 하이픈·밑줄만 다른 컬럼 이름은 같은 코드가 되므로 겹치면 번호를 붙인다
        code = to_code(key)
        if code in used_codes:
            n = 2
            while (numbered := _numbered(code, n)) in used_codes:
                n += 1
            code = numbered
        used_codes.add(code)
        cardinality = _cardinality(rel, tables)
        card_note = (
            ONE_NOTE
            if cardinality == "ONE_TO_ONE"
            else " 개수 관계는 데이터에서 관찰한 값."
        )
        steps_by_column = rel.column_steps or [
            (rel.source_steps, rel.target_steps) for _ in rel.source
        ]
        conditions = [
            LinkRuleCondition(
                source_property=tables.prop(s),
                target_property=tables.prop(t),
                source_transforms=[TransformStep.model_validate(x) for x in s_steps],
                target_transforms=[TransformStep.model_validate(x) for x in t_steps],
            )
            for (s, t), (s_steps, t_steps) in zip(
                zip(rel.source, rel.target), steps_by_column
            )
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
            "alternate": "유일값",
            "none": "키 아님",
        }[rel.target_key]
        how = _how_text(rel, steps_by_column)
        # 안 이어지는 값은 사람 이름일 수 있어 가린다
        source_cols = " ".join(c.name for c in rel.source)
        unmatched = [mask_value(str(v), source_cols) for v in rel.unmatched]
        target_cols = " + ".join(c.name for c in rel.target)
        # 승인 근거로 몇 행을 맞춰 봤는지 함께 적는다(값이 있는 출발 행 기준)
        checked = tables.frames[s_table].dropna(subset=[c.name for c in rel.source])
        if rel.condition:
            checked = checked[
                checked[rel.condition[0].name].astype(str) == rel.condition[1]
            ]
        total = len(checked)
        matched_rows = (
            rel.matched_rows
            if rel.matched_rows is not None
            else round(rel.row_coverage * total)
        )
        if rel.unmatched:
            # 비율은 소수 셋째 자리로 반올림돼 큰 표에서는 몇 건이 빠져도 100%가 될 수 있다
            matched_rows = min(matched_rows, total - 1)
        if matched_rows >= total:
            matched_text = f"일치율 100% ({total:,}행 모두 연결)"
        else:
            # 다 이어지지 않았으면 100%로 올려 쓰지 않는다(400행 중 399행 → 99%)
            matched_text = (
                f"일치율 {_floor_percent(rel.row_coverage)} ({matched_rows:,}/{total:,}행 연결,"
                f" 값 종류 기준 {_floor_percent(rel.distinct_coverage)})"
            )
        parts = [
            f"대상 키: {t_table} {target_cols} ({key_text})",
            "비교: 값 그대로"
            if rel.rule_type == "same_value"
            else f"비교: {how.removeprefix('비교 방법: ')}",
            matched_text,
        ]
        if rel.condition:
            parts.append(f"적용 조건: {rel.condition[0].name} = {rel.condition[1]}")
        if rel.via:
            parts.append(
                f"경유: {rel.via[0].table} ({rel.source[0].name} → {rel.via[1].name})"
            )
        shown = [v for v in unmatched if v != MASK]
        if shown:
            parts.append(f"불일치 예: {', '.join(shown)}")
        elif unmatched:
            # 가린 값만 남으면 "***" 대신 무엇을 가렸는지 적는다
            parts.append("불일치 값은 개인정보라 표시하지 않음")
        reason = " · ".join(parts)
        single = len(rel.source) == 1
        source_values = _shown_values([m[0] for m in rel.matched], rel.source)
        target_values = _shown_values([m[1] for m in rel.matched], rel.target)
        evidence = [
            Evidence(
                source_id=s_table.split(".")[0],
                locator=rel.source[0].ref
                if single
                else s_table + "." + "+".join(c.name for c in rel.source),
                snippet="출발 값 예: " + ", ".join(source_values),
                values=_checkable(rel.matched, 0, source_values) if single else [],
            ),
            Evidence(
                source_id=t_table.split(".")[0],
                locator=rel.target[0].ref
                if single
                else t_table + "." + "+".join(c.name for c in rel.target),
                snippet="도착 값 예: " + ", ".join(target_values),
                values=_checkable(rel.matched, 1, target_values) if single else [],
            ),
        ]
        link_type = LinkTypePayload(
            code=code,
            name=f"{tables.shown(s_table)} → {tables.shown(t_table)}",
            source_type_code=s_code,
            target_type_code=t_code,
            cardinality=cardinality,
            description=f"{tables.shown(s_table)}가 {tables.shown(t_table)}를 가리킨다{cond_text}.{card_note}",
        )
        status = {"review_status": "HELD", "hold_reason": rel.hold} if rel.hold else {}
        status["confidence"] = score.build(
            score.link(
                rel.distinct_coverage,
                rel.row_coverage,
                rel.target_key,
                single and name_evidence(rel.source[0], rel.target[0]),
                cross_same_value=rel.rule_type == "same_value"
                and s_table.split(".")[0] != t_table.split(".")[0],
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
                # 한글 표·컬럼 이름이라 코드가 해시로 바뀌었으면 원래 이름(develop과 같은 대문자 표기)으로 보인다
                title=f"연결 규칙 {code if key.isascii() else key.upper()}",
                payload=LinkRulePayload(
                    code=code,
                    link_type_code=code,
                    pattern=RuleType.TRANSFORM
                    if rel.rule_type == "transform"
                    else RuleType.SAME_VALUE,
                    conditions=conditions,
                    source_filter=SourceFilter(
                        property=tables.prop(rel.condition[0]),
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
    singles = _drop_number_coincidences(single_relations(tables), tables)
    conditional = conditional_relations(tables, singles)
    conditional_sources = {r.source[0].ref for r in conditional}
    singles = [r for r in singles if r.source[0].ref not in conditional_sources]
    taken = {r.source[0].ref for r in singles + conditional}
    relations = (
        singles
        + composite_relations(tables)
        + composite_transformed(tables)
        + via_relations(tables, singles)
        + category_relations(tables, taken)
        + conditional
    )
    return to_candidates(relations, tables)
