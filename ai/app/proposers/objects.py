"""FN-AIP-04 객체 유형·속성 후보. 코드 규칙으로만 만든다(method=rule, LLM 미사용).

원천 표 한 행 = 객체 하나(FN-MAP-02). 표마다 OBJECT_TYPE·OBJECT_MAPPING, 컬럼마다 PROPERTY·PROPERTY_MAPPING 후보를 낸다.
이름과 설명은 표·컬럼 주석을 그대로 쓴다. 주석이 없으면 이름만 바꿔 쓰므로 업무 의미 해석은 하지 않은 것이다.
표 종류(category)는 아래 데이터 모델링 규칙으로 정한다. 정답셋을 보고 규칙을 고치지 않는다.

1. 컬럼에 변경 대상 행 키(row_key)와 작업 종류(operation)가 함께 있으면 동기화 기록 → 객체로 내지 않는다.
2. 기본키에 날짜·시각 컬럼이 있으면 시점별 기록 → history.
3. 기본키가 여러 컬럼이고, 한 컬럼이 다른 표를 가리키며 나머지가 정수 순번이면 → detail.
4. 그 밖의 여러 컬럼 기본키 → snapshot (조합별 현재 상태).
5. 한 컬럼 기본키에서 다른 표가 가리키지 않고 날짜·시각 컬럼이 있으면 → history (발생 기록).
6. 상태 컬럼(이름·주석에 status·상태)이 있고 날짜·시각 컬럼이 2개 이상이면 → transaction.
7. 그 밖 → master.
"""

import re
from collections import defaultdict
from pathlib import Path

from app.analyzers.structured import (
    MASK,
    ColumnAnalysis,
    StructuredAnalysis,
    analyze_sources,
)
from app.llm.privacy import SURNAMES
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
from app.sources.csv_reader import CsvSourceReader

TIME_TYPES = ("date", "timestamp", "datetime", "time")
INT_TYPES = ("int", "integer", "bigint", "smallint")
PERSON_NAME = re.compile(rf"[{SURNAMES}][가-힣]{{1,3}}")


def to_code(table: str) -> str:
    """객체 유형 코드. API 공통 규칙: 객체·관계 유형은 대문자(WORK_ORDER), 속성은 컬럼 이름 그대로."""
    return table.upper()


def data_type(column: ColumnAnalysis, values: list[str]) -> DataType:
    declared = (column.declared_type or "").lower()
    if declared:
        if declared.startswith(INT_TYPES):
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
    # 선언 타입이 없으면 값 모양으로 추정한다
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


def classify(
    schema: TableSchema,
    columns: list[ColumnAnalysis],
    values: dict[str, list[str]],
    in_tables: set[str],
    pk_out: set[str],
) -> tuple[str | None, str]:
    """(표 종류, 판단 이유). 종류가 None이면 객체로 내지 않는다."""
    names = {c.ref.rsplit(".", 1)[1]: c for c in columns}
    if "row_key" in names and "operation" in names:
        return None, "변경 대상 행 키와 작업 종류 컬럼이 있는 동기화 기록"
    pk = schema.primary_key
    times = [n for n, c in names.items() if is_time(c, values[n])]
    if pk and any(n in times for n in pk):
        return (
            "history",
            f"기본키에 날짜·시각({', '.join(n for n in pk if n in times)})이 있음",
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
                f"기본키 {pk} 중 {refs}가 다른 표를 가리키고 {seq}가 정수 순번",
            )
        return "snapshot", f"여러 컬럼 기본키 {pk}의 조합별 기록"
    if not in_tables and times:
        return (
            "history",
            f"다른 표가 가리키지 않고 날짜·시각 컬럼({', '.join(times)})이 있음",
        )
    status = [n for n, c in names.items() if _is_status(n, c.comment)]
    if status and len(times) >= 2:
        return (
            "transaction",
            f"상태 컬럼({', '.join(status)})과 날짜·시각 컬럼 {len(times)}개",
        )
    return (
        "master",
        f"다른 표 {len(in_tables)}개가 가리킴"
        if in_tables
        else "기준 정보로 판단(다른 규칙에 해당 없음)",
    )


def propose_objects(
    root: Path, analysis: StructuredAnalysis | None = None
) -> list[Candidate]:
    root = Path(root)
    analysis = analysis or analyze_sources(root)
    in_tables, pk_out = defaultdict(set), defaultdict(set)
    for link in analysis.links:
        from_table, from_col = link.from_ref.rsplit(".", 1)
        to_table = link.to_ref.rsplit(".", 1)[0]
        in_tables[to_table].add(from_table)
        pk_out[from_table].add(from_col)
    candidates = []
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        reader = CsvSourceReader(folder.name, folder)
        for table in reader.list_tables():
            key = f"{folder.name}.{table}"
            if key not in analysis.tables:
                continue
            candidates += _table_candidates(
                reader, folder.name, table, analysis, in_tables[key], pk_out[key]
            )
    return candidates


def _table_candidates(
    reader, source, table, analysis, in_tables, pk_out
) -> list[Candidate]:
    key = f"{source}.{table}"
    schema = reader.read_schema(table)
    df = reader.read_rows(table)
    columns = analysis.tables[key]
    values = {c.name: df[c.name].dropna().astype(str).tolist() for c in schema.columns}
    category, why = classify(schema, columns, values, in_tables, pk_out)
    if category is None:
        return []
    code = to_code(table)
    keys = (
        schema.primary_key
        or [
            k.columns[0]
            for k in analysis.table_keys.get(key, [])
            if k.source == "observed"
        ][:1]
    )
    name = schema.description or table
    table_evidence = Evidence(
        source_id=source,
        locator=key,
        snippet=f"{len(df)}행, 기본키 {keys or '없음'}, 이 표를 가리키는 표 {sorted(in_tables) or '없음'}",
    )
    out = [
        Candidate(
            candidate_id=f"OT-{key}",
            title=f"객체 유형 {code} ({name})",
            payload=ObjectTypePayload(
                code=code,
                name=name,
                description=f"원천 {key}의 한 행",
                category=category,
            ),
            reason=f"원천 표 {key}의 한 행을 객체 하나로 본다. 표 종류: {why}. "
            + (
                "이름은 표 주석에서 가져왔다."
                if schema.description
                else "표 주석이 없어 표 이름을 그대로 썼다."
            ),
            evidence=[table_evidence],
            confidence=score.build(
                score.object_type(bool(schema.primary_key), bool(keys), len(in_tables)),
                hold=None if keys else "식별 키 없음",
            ),
            review_status="PENDING" if keys else "HELD",
            hold_reason=None
            if keys
            else "식별 키를 찾지 못해 같은 객체를 다시 찾을 수 없음",
        )
    ]
    if keys:
        out.append(
            Candidate(
                candidate_id=f"OM-{key}",
                title=f"{code} ← {key}",
                payload=ObjectMappingPayload(
                    object_type_code=code,
                    source_id=source,
                    resource_key=key,
                    key_columns=keys,
                ),
                reason=f"{key}의 기본키 {keys}로 행을 구분한다"
                if schema.primary_key
                else f"{key}에 선언된 기본키가 없어 값이 모두 다른 컬럼 {keys}를 키로 쓴다",
                evidence=[table_evidence],
                confidence=score.build(score.object_mapping(bool(schema.primary_key))),
            )
        )
    by_name = {c.ref.rsplit(".", 1)[1]: c for c in columns}
    for col in schema.columns:
        stats = by_name[col.name]
        col_values = values[col.name]
        personal = stats.is_personal or looks_like_person_names(col_values)
        dtype = data_type(stats, col_values)
        shown = [MASK] if personal else stats.sample_values[:3]
        ref = f"{key}.{col.name}"
        evidence = Evidence(
            source_id=source,
            locator=ref,
            snippet=f"값 {stats.row_count - stats.null_count}/{stats.row_count}, 서로 다른 값 {stats.distinct_count}, 예 {shown}",
            values=[] if personal else stats.sample_values[:3],
        )
        why_personal = (
            "이름·주석이 개인정보를 가리킴"
            if stats.is_personal
            else "값이 사람 이름 모양"
            if personal
            else ""
        )
        out.append(
            Candidate(
                candidate_id=f"P-{ref}",
                title=f"속성 {code}.{col.name}",
                payload=PropertyPayload(
                    object_type_code=code,
                    code=col.name,
                    name=col.comment.split("(")[0].strip() if col.comment else col.name,
                    data_type=dtype,
                    description=col.comment or "",
                    is_identifier=col.name in keys,
                    is_required=stats.null_count == 0,
                    sensitivity=Sensitivity.RESTRICTED
                    if personal
                    else Sensitivity.NORMAL,
                ),
                reason=f"자료형 {dtype.value}"
                + (
                    f"(선언 {col.declared_type})"
                    if col.declared_type
                    else "(값 모양으로 추정)"
                )
                + (", 식별 키" if col.name in keys else "")
                + (
                    ", 빈 값 없음(관찰)"
                    if stats.null_count == 0
                    else f", 빈 값 {stats.null_count}행"
                )
                + (f", 개인정보: {why_personal}" if personal else ""),
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
                candidate_id=f"PM-{ref}",
                title=f"{code}.{col.name} ← {ref}",
                payload=PropertyMappingPayload(
                    object_type_code=code,
                    property_code=col.name,
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
