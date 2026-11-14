"""FN-AIP-04 객체 유형·속성 후보. 코드 규칙으로만 만든다(method=rule, LLM 미사용).

원천 표 한 행 = 객체 하나. 이름과 설명은 표·컬럼 주석을 그대로 쓴다.
표 종류(category)는 데이터 모델링 규칙(classify)으로 정하고, 정답셋을 보고 규칙을 고치지 않는다.
"""

import hashlib
import re
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path

from app.analyzers.structured import (
    MASK,
    ColumnAnalysis,
    StructuredAnalysis,
    analyze_sources,
    split_ref,
)
from app.llm.privacy import SURNAMES, is_person_column, looks_like_name, mask_value
from app.models.candidate import (
    Candidate,
    DataType,
    Evidence,
    ObjectMappingPayload,
    ObjectTypePayload,
    PropertyMappingPayload,
    PropertyPayload,
    Sensitivity,
)
from app.models.source import ColumnRef, TableSchema
from app.proposers import confidence as score
from app.proposers.access import (
    GROUP_TEXT,
    access_group,
    is_free_text,
    sensitivity_category,
)
from app.source_policy import is_restricted
from app.sources.csv_reader import CsvSourceReader

TIME_TYPES = ("date", "timestamp", "datetime", "time")
PAIR_KEY_COLUMNS = 6  # 기본키 없는 표에서 두 컬럼 키를 찾을 때 앞에서부터 볼 컬럼 수


HANGUL = re.compile(r"[가-힣]")


def property_name(column: str, comment: str | None) -> str:
    """검토 화면의 속성 이름. 한글 컬럼 이름은 그대로 업무 이름으로 쓰고, 아니면 주석의 괄호 앞부분을 쓴다.

    주석이 단위·값 목록(EA, kg, 지시/진행/완료)이면 이름이 아니므로 한글 컬럼 이름을 우선한다. 둘 다 한글이 아니면
    LLM 해석에서 받은 짧은 이름으로 바꾼다(semantics).
    """
    if HANGUL.search(column):
        return column
    head = (comment or "").split("(")[0].strip()
    return head or column


def observed_pair_key(df, names: list[str]) -> list[str]:
    """기본키도 값이 모두 다른 한 컬럼도 없는 표에서, 빈 값 없이 조합이 모두 다른 두 컬럼.
    오래된 시스템의 상세 표는 기본키를 선언하지 않은 경우가 많다."""
    names = names[:PAIR_KEY_COLUMNS]
    for i, a in enumerate(names):
        for b in names[i + 1 :]:
            sub = df[[a, b]]
            if len(sub) >= 3 and sub.notna().all().all() and not sub.duplicated().any():
                return [a, b]
    return []


def key_like_columns(schema: TableSchema, columns: list[ColumnAnalysis]) -> list[str]:
    """키로 쓸 만한 컬럼: 문자형·정수형, 개인정보·날짜·소수가 아님."""
    by_name = {c.column: c for c in columns}
    out = []
    for col in schema.columns:
        stats = by_name[col.name]
        declared = (stats.declared_type or "").lower()
        if stats.is_personal or declared.startswith(
            (*TIME_TYPES, "numeric", "decimal", "float", "double")
        ):
            continue
        out.append(col.name)
    return out


INT_TYPES = {
    "int",
    "integer",
    "bigint",
    "smallint",
    "tinyint",
    "mediumint",
    "int2",
    "int4",
    "int8",
    "serial",
    "bigserial",
    "smallserial",
}
PERSON_NAME = re.compile(rf"[{SURNAMES}][가-힣]{{1,3}}")


def base_type(declared: str | None) -> str:
    """선언 타입의 이름만. 'varchar(20)' → 'varchar', 'int unsigned' → 'int', 'interval' → 'interval'."""
    return re.split(r"[\s(\[]", (declared or "").strip().lower(), maxsplit=1)[0]


def is_integer_type(declared: str | None) -> bool:
    """정수 타입인지 이름으로 정확히 본다. startswith('int')는 interval까지 정수로 본다."""
    return base_type(declared) in INT_TYPES


# 백엔드 검사(ObjectType·LinkType·PropertyCreateRequest)와 같은 코드 규칙
TYPE_CODE = re.compile(r"[A-Z][A-Z0-9_]*")
PROPERTY_CODE = re.compile(r"[a-z][a-z0-9_]*")
CODE_MAX = 100
_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")


def _short_hash(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:8]


def _snake(name: str) -> str:
    """camelCase·대문자·하이픈·공백을 밑줄 소문자로. 영문·숫자가 아닌 글자(한글 등)는 원래 이름을 가리는 해시로 바꾼다."""
    text = _CAMEL.sub("_", name)
    snake = re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9]+", "_", text)).strip("_").lower()
    # 한글처럼 코드에 못 쓰는 글자를 뺐으면 '검사_no'·'입고_no'가 같아지지 않게 해시를 붙인다.
    # 영문·숫자가 하나도 없으면 빈 문자열(부르는 쪽이 col_·T_ 접두어와 해시로 만든다)
    if snake and re.search(r"[^A-Za-z0-9_\-\s.]", name):
        snake = f"{snake}_{_short_hash(name)}"
    return snake


def _fit(code: str, original: str) -> str:
    if len(code) <= CODE_MAX:
        return code
    return f"{code[: CODE_MAX - 9].rstrip('_')}_{_short_hash(original)}"


def property_code(column: str) -> str:
    """속성 코드(백엔드 규칙 [a-z][a-z0-9_]*, 100자 이하). 맞는 이름은 그대로, 아니면 snake_case로 바꾼다.
    원래 컬럼 이름은 속성 이름·원천 매핑(source_column)에 그대로 남는다."""
    if PROPERTY_CODE.fullmatch(column) and len(column) <= CODE_MAX:
        return column
    code = _snake(column)
    if not code or not code[0].isalpha() or not code.isascii():
        code = f"col_{code}" if code else f"col_{_short_hash(column)}"
    return _fit(code, column)


def property_codes(columns: Iterable[str]) -> dict[str, str]:
    """표 한 개의 컬럼 이름 → 속성 코드. 바꾼 코드가 다른 컬럼과 겹치면(itemCd·item_cd) 해시를 붙인다.
    규칙에 맞는 이름이 먼저 자리를 잡아 원래 코드가 바뀌지 않는다."""
    names = list(columns)
    out: dict[str, str] = {}
    used: set[str] = set()
    for name in sorted(names, key=lambda n: property_code(n) != n):
        code = property_code(name)
        if code in used:
            code = _fit(f"{code}_{_short_hash(name)}", name)
        used.add(code)
        out[name] = code
    return {name: out[name] for name in names}


def to_code(table: str) -> str:
    """객체·관계 유형 코드(백엔드 규칙 [A-Z][A-Z0-9_]*, 100자 이하). 대문자로 바꿔 맞으면 그대로(WORK_ORDER),
    아니면(하이픈·한글) snake_case 대문자로 바꾼다."""
    upper = table.upper()
    if TYPE_CODE.fullmatch(upper):
        return _fit(upper, table)
    code = _snake(table).upper()
    if not code or not code[0].isalpha():
        code = f"T_{code}" if code else f"T_{_short_hash(table).upper()}"
    return _fit(code, table)


def display_name(code: str, table: str) -> str:
    """제목·설명에 보일 객체 유형 이름. 한글 표 이름처럼 코드가 해시로 바뀐 경우(T_728E454E)는
    원천 표 이름(거래처)을 보인다. 코드 자체는 백엔드 규칙대로 둔다."""
    return code if table.isascii() else table


def type_codes(table_keys: Iterable[str]) -> dict[str, str]:
    """'원천.표' → 객체 유형 코드. 다른 원천에 같은 코드가 되는 표가 있으면(erp.item, mes.item)
    그 표들만 원천 이름을 앞에 붙인다(ERP_ITEM). 겹치지 않으면 to_code 그대로다."""
    keys = list(table_keys)
    plain = {key: to_code(key.split(".", 1)[1]) for key in keys}
    counts: dict[str, int] = defaultdict(int)
    for code in plain.values():
        counts[code] += 1
    out = {
        key: code if counts[code] == 1 else to_code(key.replace(".", "_", 1))
        for key, code in plain.items()
    }
    seen: dict[str, int] = defaultdict(int)
    for code in out.values():
        seen[code] += 1
    return {
        key: code
        if seen[code] == 1
        else _fit(f"{code}_{_short_hash(key).upper()}", key)
        for key, code in out.items()
    }


def is_personal_column(
    stats: ColumnAnalysis, name: str, comment: str | None, values: list
) -> bool:
    """개인정보 컬럼. 이름·주석, 원천 정책의 민감 컬럼, 값이 사람 이름 모양, 담당·검사자처럼 사람을 적는 칸에 이름이 섞인 경우."""
    return (
        stats.is_personal
        or is_restricted(stats.ref)
        or looks_like_person_names(values)
        or (
            is_person_column(f"{name} {comment or ''}")
            and any(isinstance(v, str) and looks_like_name(v) for v in values)
        )
    )


def data_type(column: ColumnAnalysis, values: list[str]) -> DataType:
    declared = (column.declared_type or "").lower()
    if declared:
        if is_integer_type(declared):
            return DataType.INTEGER
        if declared.startswith(("numeric", "decimal", "float", "double", "real")):
            return DataType.DECIMAL
        if declared == "date":
            return DataType.DATE
        if declared.startswith(("timestamp", "datetime")):
            return DataType.DATETIME
        if declared.startswith("time"):
            return DataType.TIME
        if declared.startswith("bool"):
            return DataType.BOOLEAN
        return DataType.STRING
    sample = [v for v in values if v][:200]
    if sample and all(re.fullmatch(r"-?\d+", v) for v in sample):
        return DataType.INTEGER
    if sample and all(re.fullmatch(r"-?\d+\.\d+", v) for v in sample):
        return DataType.DECIMAL
    if sample and all(re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) for v in sample):
        return DataType.DATE
    if sample and all(
        re.fullmatch(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?", v) for v in sample
    ):
        return DataType.DATETIME
    if sample and all(re.fullmatch(r"\d{2}:\d{2}(:\d{2})?", v) for v in sample):
        return DataType.TIME
    return DataType.STRING


def looks_like_person_names(values: list[str]) -> bool:
    """주석이 없어도 값이 한국 사람 이름 모양(성 + 1~3글자)이면 개인정보로 본다."""
    distinct = {v for v in values if v}
    if len(distinct) < 3:
        return False
    # 몇 가지 값이 여러 행에 되풀이되고 세 글자 이름 모양도 드물면 종류 코드다('공구'·'기계장치'·'차량').
    # 작업자 몇 명이 되풀이되는 이름 칸('김민수'·'이서준')은 세 글자라 그대로 이름으로 본다
    three = sum(1 for v in distinct if len(v) == 3) / len(distinct)
    if (
        len(distinct) < 10
        and len([v for v in values if v]) >= 2 * len(distinct)
        and three < 0.8
    ):
        return False
    matched = sum(1 for v in distinct if PERSON_NAME.fullmatch(v))
    return matched / len(distinct) >= 0.8


def is_time(column: ColumnAnalysis, values: list[str]) -> bool:
    declared = (column.declared_type or "").lower()
    if declared:
        return declared.startswith(TIME_TYPES)
    return data_type(column, values) in (
        DataType.DATE,
        DataType.DATETIME,
        DataType.TIME,
    )


def _is_status(name: str, comment: str | None) -> bool:
    return "status" in name.lower() or "상태" in (comment or "")


# 표 이름 끝이 이 낱말이면 기록·실적 표라 이름 칸이 있어도 대장으로 보지 않는다(defect_log, batch_step)
_RECORD_WORDS = {
    "log",
    "logs",
    "history",
    "hist",
    "step",
    "steps",
    "result",
    "results",
    "record",
    "records",
    "inspection",
    "receiving",
    "line",
    "lines",
    "detail",
}
_MASTER_WORDS = {"mst", "master", "m"}


def _own_name_column(table: str, columns: list[ColumnAnalysis]) -> str | None:
    """그 표 자신의 이름 칸. 이름 칸 앞말이 표의 주체 낱말과 같아야 한다(fixed_asset의 asset_name, eqp_mst의 eqp_nm).
    다른 것의 이름을 옮겨 적은 칸(원료명·담당자명)과 기록·실적 표는 대장 근거로 쓰지 않는다."""
    words = [w for w in table.lower().split("_") if w]
    if not words or words[-1] in _RECORD_WORDS:
        return None
    subject = words[-2] if words[-1] in _MASTER_WORDS and len(words) > 1 else words[-1]
    for c in columns:
        name = c.column.lower()
        match = re.fullmatch(r"(?:(\w+)_)?(name|nm)", name)
        if not match or is_person_column(f"{name} {c.comment or ''}") or c.is_personal:
            continue
        if match.group(1) in (None, subject):
            return c.column
    return None


def classify(
    schema: TableSchema,
    columns: list[ColumnAnalysis],
    values: dict[str, list[str]],
    in_tables: set[str],
    pk_out: set[str],
) -> tuple[str | None, str]:
    """(표 종류, 판단 이유). 종류가 None이면 객체로 내지 않는다."""
    names = {c.column: c for c in columns}
    if "row_key" in names and "operation" in names:
        return None, "변경 대상 행 키와 작업 종류 컬럼이 있는 동기화 기록"
    pk = schema.primary_key
    times = [n for n, c in names.items() if is_time(c, values[n])]
    if pk and any(n in times for n in pk):
        return (
            "history",
            f"기본키에 일자 컬럼 {', '.join(n for n in pk if n in times)} 포함",
        )
    if len(pk) > 1:
        refs = [n for n in pk if n in pk_out]
        seq = [
            n
            for n in pk
            if n not in refs and data_type(names[n], values[n]) == DataType.INTEGER
        ]
        if refs and seq:
            return (
                "detail",
                f"복합 기본키 {_cols(pk)} 중 {_cols(refs)} 상위 참조, {_cols(seq)} 순번",
            )
        return "snapshot", f"복합 기본키 {_cols(pk)} 기준"
    # 상태와 일자 여럿(계획·완료)이 있으면 참조되지 않아도 거래다(정비 지시)
    status = [n for n, c in names.items() if _is_status(n, c.comment)]
    if status and len(times) >= 2:
        return (
            "transaction",
            f"상태 컬럼 {', '.join(status)}, 일자 컬럼 {len(times)}개 보유",
        )
    if not in_tables and times:
        named = _own_name_column(schema.name, columns)
        if named:
            # 자기 이름 칸이 있는 대장(고정자산의 자산명)은 일자가 있어도 기준 정보다
            return "master", f"이름 컬럼 {named} 보유(대장)"
        return (
            "history",
            f"참조하는 테이블 없음, 일자 컬럼 {', '.join(times)} 보유",
        )
    return (
        "master",
        f"다른 표 {len(in_tables)}개가 참조" if in_tables else "기타 분류 조건 미해당",
    )


def _cols(names) -> str:
    """컬럼 목록을 a, b로 쓴다."""
    return ", ".join(str(n) for n in names)


TYPE_TEXT = {
    "STRING": "문자",
    "INTEGER": "정수",
    "DECIMAL": "소수",
    "DATE": "날짜",
    "TIME": "시각",
    "DATETIME": "일시",
    "BOOLEAN": "예/아니요",
    "JSON": "JSON",
}

CATEGORY_TEXT = {
    "master": "기준 정보",
    "transaction": "거래 문서",
    "detail": "문서 상세 줄",
    "history": "발생 기록",
    "snapshot": "현재 상태",
}


def propose_objects(
    root: Path, analysis: StructuredAnalysis | None = None
) -> list[Candidate]:
    root = Path(root)
    analysis = analysis or analyze_sources(root)
    in_tables, pk_out = defaultdict(set), defaultdict(set)
    for link in analysis.links:
        from_table, from_col = split_ref(link.from_ref)
        to_table, _ = split_ref(link.to_ref)
        in_tables[to_table].add(from_table)
        pk_out[from_table].add(from_col)
    codes = type_codes(analysis.tables)
    candidates = []
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        reader = CsvSourceReader(folder.name, folder)
        for table in reader.list_tables():
            key = f"{folder.name}.{table}"
            if key not in analysis.tables:
                continue
            candidates += _table_candidates(
                reader,
                folder.name,
                table,
                analysis,
                in_tables[key],
                pk_out[key],
                codes[key],
            )
    return candidates


def _table_candidates(
    reader, source, table, analysis, in_tables, pk_out, code
) -> list[Candidate]:
    key = f"{source}.{table}"
    schema = analysis.schemas.get(key) or reader.read_schema(table)
    df = analysis.frames[key] if key in analysis.frames else reader.read_rows(table)
    columns = analysis.tables[key]
    values = {c.name: df[c.name].dropna().astype(str).tolist() for c in schema.columns}
    category, why = classify(schema, columns, values, in_tables, pk_out)
    if category is None:
        return []
    observed = [k for k in analysis.table_keys.get(key, []) if k.source == "observed"]
    # 값으로 찾은 키는 중복이 하나도 없을 때만 식별 키로 쓴다. 중복이 있으면 같은 객체를 다시 찾을 수 없다
    keys = (
        schema.primary_key
        or [k.columns[0] for k in observed if k.duplicate_rows == 0][:1]
        or observed_pair_key(df, key_like_columns(schema, columns))
    )
    duplicated_key = next((k for k in observed if k.duplicate_rows), None)
    no_key_reason = (
        f"값으로 찾은 키 후보 {_cols(duplicated_key.columns)}에 중복 {duplicated_key.duplicate_rows}행이 있어 "
        "같은 객체를 다시 찾을 수 없음"
        if duplicated_key
        else "식별 키를 찾지 못해 같은 객체를 다시 찾을 수 없음"
    )
    prop_codes = property_codes(c.name for c in schema.columns)
    shown_code = display_name(code, table)
    name = schema.description or table
    table_evidence = Evidence(
        source_id=source,
        locator=key,
        snippet=f"{len(df):,}행 · 기본키 {_cols(keys) or '없음'} · 참조 테이블 {_cols(sorted(in_tables)) or '없음'}",
    )
    group, why_group = access_group(key)
    out = [
        Candidate(
            candidate_id=f"OT-{key}",
            title=f"객체 유형 {shown_code} ({name})",
            payload=ObjectTypePayload(
                code=code,
                name=name,
                description=f"원천 {key}의 한 행",
                category=category,
                access_group=group,
            ),
            reason=f"분류: {CATEGORY_TEXT.get(category, category)} ({why})"
            + (
                f" · 조회 그룹: {GROUP_TEXT[group]} ({why_group})"
                if group
                else f" · 조회 그룹: 없음 ({why_group})"
            ),
            evidence=[table_evidence],
            confidence=score.build(
                score.object_type(bool(schema.primary_key), bool(keys), len(in_tables)),
                hold=None if keys else "식별 키 없음",
            ),
            review_status="PENDING" if keys else "HELD",
            hold_reason=None if keys else no_key_reason,
        )
    ]
    if keys:
        out.append(
            Candidate(
                candidate_id=f"OM-{key}",
                title=f"{shown_code} ← {key}",
                payload=ObjectMappingPayload(
                    object_type_code=code,
                    source_id=source,
                    resource_key=key,
                    key_columns=keys,
                ),
                reason=f"행 식별: 기본키 {_cols(keys)}"
                if schema.primary_key
                else f"행 식별: 중복 없는 컬럼 {_cols(keys)} (선언된 기본키 없음)",
                evidence=[table_evidence],
                confidence=score.build(score.object_mapping(bool(schema.primary_key))),
            )
        )
    by_name = {c.column: c for c in columns}
    for col in schema.columns:
        stats = by_name[col.name]
        col_values = values[col.name]
        prop = prop_codes[col.name]
        # 담당·검사자처럼 사람을 적는 칸에 이름이 섞여 있으면(부서명 사이 '홍보람') 일부만 이름이어도 개인정보로 본다
        personal_value = is_personal_column(stats, col.name, col.comment, col_values)
        # 민감 분류 기준(392): 사람을 알아볼 수 있는 값, 인사 속성, 평가. 자유 글은 검토만 권한다
        # 행 구분 키는 식별 속성이 되어 민감일 수 없다(승인 반영이 거절한다)
        category = None if col.name in keys else sensitivity_category(key, col.name)
        personal = personal_value or category is not None
        dtype = data_type(stats, col_values)
        # 다른 칸에 섞인 사람 이름 모양 값은 근거·LLM 질문에 그대로 쓰지 않는다
        hint = f"{col.name} {col.comment or ''}"
        examples = [
            mask_value(v, hint) if isinstance(v, str) else v
            for v in stats.sample_values[:3]
        ]
        shown = [MASK] if personal else examples
        ref = f"{key}.{col.name}"
        # 후보 ID의 컬럼 부분에 마침표가 있으면 표를 찾는 쪽('P-원천.표.컬럼'을 뒤에서 쪼갬)이 잘못 읽어 코드를 쓴다
        id_ref = ref if "." not in col.name else f"{key}.{prop}"
        evidence = Evidence(
            source_id=source,
            locator=ref,
            snippet=f"값 {stats.row_count - stats.null_count}/{stats.row_count}, 서로 다른 값 {stats.distinct_count}, 예 {shown}",
            values=[] if personal else [v for v in examples if v != MASK],
        )
        why_personal = (
            "원천 정책의 민감 컬럼"
            if is_restricted(stats.ref)
            else "사람을 알아볼 수 있는 값: 이름·주석이 개인정보를 가리킴"
            if stats.is_personal
            else "사람을 알아볼 수 있는 값: 값이 사람 이름 모양"
            if personal_value
            else category
            if category
            else ""
        )
        free_text = (
            not personal
            and dtype == DataType.STRING
            and is_free_text(col.name, col.comment)
        )
        out.append(
            Candidate(
                candidate_id=f"P-{id_ref}",
                title=f"속성 {shown_code}.{col.name}",
                payload=PropertyPayload(
                    object_type_code=code,
                    code=prop,
                    name=property_name(col.name, col.comment),
                    data_type=dtype,
                    description=col.comment or "",
                    is_identifier=col.name in keys,
                    is_required=stats.null_count == 0,
                    sensitivity=Sensitivity.RESTRICTED
                    if personal
                    else Sensitivity.NORMAL,
                ),
                reason=f"자료형: {TYPE_TEXT.get(dtype.value, dtype.value)} "
                + (
                    f"({col.declared_type})"
                    if col.declared_type
                    else "(값 모양으로 추정)"
                )
                + (" · 식별 키" if col.name in keys else "")
                + (
                    " · 빈 값 없음"
                    if stats.null_count == 0
                    else f" · 빈 값 {stats.null_count}행"
                )
                + (f" · 민감 ({why_personal})" if personal else "")
                + (
                    " · 자유 글: 사람 이름이 섞일 수 있어 민감 여부를 검토하세요"
                    if free_text
                    else ""
                ),
                evidence=[evidence],
                confidence=score.build(
                    score.property_(
                        bool(col.declared_type),
                        stats.null_count / stats.row_count if stats.row_count else 1.0,
                    )
                ),
            )
        )
        out.append(
            Candidate(
                candidate_id=f"PM-{id_ref}",
                title=f"{shown_code}.{col.name} ← {ref}",
                payload=PropertyMappingPayload(
                    object_type_code=code,
                    property_code=prop,
                    source_column=ColumnRef(
                        source_id=source,
                        table=table,
                        column=col.name,
                        resource_key=key,  # OBJECT_MAPPING resourceKey와 같은 값
                    ),
                ),
                reason="원천 컬럼 값을 그대로 속성에 넣는다",
                evidence=[evidence],
                confidence=score.build(score.property_mapping()),
            )
        )
    return out
