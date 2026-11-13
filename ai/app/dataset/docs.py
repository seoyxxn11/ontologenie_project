"""전달용 안내 문서를 생성기 정의에서 바로 만든다. 문서와 데이터가 어긋나지 않게 하기 위함."""

from app.dataset import schema

KIND_NAMES = {
    "master": "기준 정보",
    "transaction": "거래 문서",
    "detail": "문서 상세 줄",
    "history": "발생 기록",
    "snapshot": "현재 상태",
}


KEY_NAMES = {"primary": "기본키", "alternate": "다른 유일 키", "none": "키 아님"}


def load_scripts(counts: dict[str, int]) -> dict[str, str]:
    """ERP·MES DB별 적재 스크립트와 적재 후 행 수 확인 SQL."""

    def load(sources) -> str:
        lines = ["-- 이 폴더에서 실행: psql -f <이 파일>"]
        lines += [f"\\i {s}/schema.sql" for s in sources]
        lines += [
            f"\\copy {t.key} from '{t.source}/{t.name}.csv' with (format csv, header true, null '')"
            for t in schema.TABLES
            if t.source in sources
        ]
        return "\n".join(lines) + "\n"

    def verify(sources) -> str:
        parts = [
            f"select '{t.key}' as table_name, count(*) as actual, {counts[t.key]} as expected from {t.key}"
            for t in schema.TABLES
            if t.source in sources
        ]
        body = "\nunion all\n".join(parts)
        return (
            "-- 적재 후 실행. ok가 모두 true여야 함.\n"
            f"select table_name, actual, expected, actual = expected as ok from (\n{body}\n) c\n"
            "order by ok, table_name;\n"
        )

    return {
        "load_erp.psql": load(("erp",)),
        "load_mes.psql": load(("mes",)),
        "verify_counts.sql": verify(("erp", "mes")),
    }


def data_readme(version: str, counts: dict[str, int], snapshot: str) -> str:
    total = sum(counts.values())
    erp = sum(1 for t in schema.TABLES if t.source == "erp")
    mes = sum(1 for t in schema.TABLES if t.source == "mes")
    return f"""# 00프레스(가상) ERP·MES 원천 데이터 {version}

자동차 차체 부품 프레스 회사의 3개월(2026-08-03 ~ 10-30) ERP·MES 데이터입니다. 기준 시각은 {snapshot}이고, 이때 P103은 고장으로 정비 중입니다.
표 {erp + mes}개(ERP {erp} · MES {mes}, 변경 이력 표 포함), {total:,}행이며 값은 모두 가상입니다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `erp/schema.sql`, `mes/schema.sql` | 스키마·표 생성 (기본키, 컬럼 주석) |
| `erp/*.csv`, `mes/*.csv` | 데이터. UTF-8, 첫 줄 헤더, 빈 칸 = NULL. 메모 원문은 줄바꿈을 포함함 |
| `load_erp.psql`, `load_mes.psql` | ERP DB·MES DB 적재 |
| `verify_counts.sql` | 적재 후 표별 행 수 확인 |
| `CHECKSUMS.sha256` | 파일 무결성 확인 (`sha256sum -c CHECKSUMS.sha256`) |
| `../scenario-{version}/s5_fix.sql` | 시나리오 5 시연 때만 적용하는 원천 수정분 |
| `../scenario-{version}/cdc_*.sql` | CDC·재분석 확인용 변경 재현 단계(추가·수정·삭제·컬럼 추가·되돌리기). 기대 결과는 `cdc_changes.json` |

## 적재

1. 이 폴더로 이동합니다(`\\copy`가 상대 경로를 씁니다).
2. ERP DB에 `psql -f load_erp.psql`, MES DB에 `psql -f load_mes.psql`을 실행합니다.
3. `psql -f verify_counts.sql`을 실행해 모든 행의 `ok`가 true인지 확인합니다.

## 적재 후 확인

- [ ] `verify_counts.sql` 결과가 모두 true
- [ ] 한글이 깨지지 않음: `select item_name from erp.item limit 3;`
- [ ] 빈 칸이 NULL로 들어감: `select count(*) from mes.work_order where so_ref is null;` → 3
- [ ] 메모 줄바꿈 유지: `select count(*) from mes.handover_note where content like '%' || chr(10) || '%';` → 60
- [ ] 분석용 읽기 전용 계정으로 두 스키마의 모든 표를 SELECT 할 수 있음

## 지킬 것

- 값을 직접 고치지 않습니다. 일부 값은 정합성 시연을 위해 일부러 어긋나게 넣었습니다. 바꿔야 하면 생성기로 다시 만듭니다.
- 정답셋(`../gold-{version}`)은 원천 DB와 AI 입력에 넣지 않습니다.
- `s5_fix.sql`은 시나리오 5 시연 때만 적용합니다.
- `cdc_*.sql`은 CDC 커넥터 등록과 최초 적재 뒤 번호 순서로 적용하고, 끝나면 `cdc_99_restore.sql`로 되돌립니다. 기존 행은 바꾸지 않습니다.

## 알려진 한계

- 발주마다 상세가 1줄뿐입니다. 업무상 발주와 상세는 1:N입니다.
- 표 사이에 외래키 제약이 없습니다. 실제 레거시 원천처럼 관계를 값으로만 확인할 수 있습니다.

## 다시 만들기

`ai/` 폴더에서 `.\\.venv\\Scripts\\python.exe -m app.dataset.build --version {version} --force`를 실행합니다. 같은 시드면 같은 파일이 나옵니다(CHECKSUMS 동일).
"""


def gold_readme(
    version: str, onto: dict, rules: list[dict], answers: dict, planted: list[dict]
) -> str:
    types = onto["object_types"]
    links = onto["link_types"]
    props = onto["properties"]
    lines = [
        f"# 정답셋 {version} (00프레스 데이터 {version.replace('gold', 'data')})",
        "",
        "> **AI 입력·원천 DB에 넣지 않습니다.** 제안기 평가와 시연 확인에만 씁니다.",
        "",
        "## 파일",
        "",
        "| 파일 | 내용 |",
        "| --- | --- |",
        f"| `ontology.json` | 객체 유형 {len(types)} · 속성 {len(props)} · 관계 {len(links)} |",
        f"| `mapping_rules.json` | ERP·MES 식별자 연결 규칙 {len(rules)} |",
        "| `id_links.json` | 연결 규칙의 값 단위 정답 (연결·미연결·결측·모호) |",
        "| `integrity_checks.json` | 정합성 검사 8건의 기대 판정 (고정자산 K3는 금지 표라 뺌) |",
        "| `integrity_issues.json` | 문제가 있는 값 목록 (미연결·제약 위반) |",
        "| `scenario_answers.json` | 시나리오·페르소나 질문의 기대 답 |",
        "| `note_annotations.json` | 메모·정비 기록의 개체·관계와 원문 위치 |",
        "| `planted_cases.json` | 일부러 넣은 사례 목록 |",
        "",
        "검증: `ai/`에서 `.\\.venv\\Scripts\\python.exe -m app.validators.gold_source_check data/data-v1.0 data/gold-v1.0` → 오류 0, 설명 안 된 미연결 0이 나와야 합니다.",
        "",
        "## 객체 유형",
        "",
        "원천 표 한 행이 객체 하나입니다. 변경 이력 표는 동기화용 기록이라 제외했습니다.",
        "",
        "| 객체 | 이름 | 종류 | 원천 표 | 키 |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {t['code']} | {t['name']} | {KIND_NAMES.get(t.get('category'), '-')} | `{', '.join(t['source_tables'])}` | {', '.join(c.rsplit('.', 1)[1] for c in t['key_columns'])} |"
        for t in types
    ]
    personal = [p["source_column"] for p in props if p.get("is_personal")]
    lines += [
        "",
        "## 속성",
        "",
        "모든 컬럼이 속성입니다(관계 조건은 속성끼리 비교). 식별자는 기본키, 참조는 관계의 출발 컬럼입니다.",
        f"필수 여부는 기본키만 정답으로 두었습니다(나머지는 채점하지 않음). 개인정보 컬럼: {', '.join(f'`{c}`' for c in personal)}",
        "",
        "## 관계",
        "",
        "| 출발 → 도착 | 키 | 개수 | 조건·규칙 | 비고 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for link in links:
        if link["derived_from"] != "column":
            continue
        via = link["via"]
        side = lambda v: " + ".join(v) if isinstance(v, list) else v
        cond = (
            f"{via['condition']['column'].rsplit('.', 1)[1]} = {via['condition']['equals']}"
            if via.get("condition")
            else ""
        )
        path = (
            f"{via['through']['take'].rsplit('.', 1)[0]} 거쳐"
            if via.get("through")
            else ""
        )
        extra = [x for x in (cond, path, link.get("mapping_rule") or "") if x]
        notes = [link.get("note", "")] + [
            f"{k['value']}: {k['reason']}" for k in link.get("known_unmatched", [])
        ]
        lines.append(
            f"| `{side(via['from'])}` → `{side(via['to'])}` | {KEY_NAMES[via.get('key', 'primary')]} "
            f"| {link['cardinality']} | {' · '.join(extra)} | {' · '.join(n for n in notes if n)} |"
        )
    text_links = [link for link in links if link["derived_from"] == "text"]
    lines += [
        "",
        "메모 본문에서만 확인되는 관계(비정형 추출로 찾음): "
        + ", ".join(f"{link['code']} → {link['target_type']}" for link in text_links),
        "",
        "관계가 아니라고 판단한 컬럼:",
        "",
    ]
    lines += [f"- `{col}`: {why}" for col, why in schema.NOT_RELATIONS.items()]
    lines += [
        "",
        "## 연결 규칙",
        "",
        "| 규칙 | 출발 → 도착 | 설명 |",
        "| --- | --- | --- |",
    ]
    lines += [
        f"| {r['rule_id']} | `{r['from']}` → `{r['to']}` | {r['description']} |"
        for r in rules
    ]
    lines += ["", "## 시나리오 기대 답", "", "| 질문 | 기대 답 |", "| --- | --- |"]
    s4 = answers["S4_P103_재계획"]
    calc = s4["계산"]
    lines.append(
        f"| {s4['질문']} | {', '.join(s4['연결_수주'])} → {next(iter(s4['연결_수주'].values()))['수주']} "
        f"(납기 {next(iter(s4['연결_수주'].values()))['납기']}). 대체 설비 {s4['대체_설비']}. "
        f"가공 {calc['가공_시간']}시간, 교대 반영 완료 {s4['교대_반영_일정']['표준']['완료']} → 납기 내. "
        f"{'; '.join(s4['납기_위험_수주'].values())} |"
    )
    for value in answers.values():
        if isinstance(value, dict) and "질문" in value and "답" in value:
            lines.append(f"| {value['질문']} | {value['답']} |")
    lines += [
        "",
        "## 일부러 넣은 사례",
        "",
        "| 사례 | 내용 | 위치 |",
        "| --- | --- | --- |",
    ]
    lines += [
        f"| {p['case_id']} | {p['description']} | {', '.join(map(str, p['rows'][:4]))}{' …' if len(p['rows']) > 4 else ''} |"
        for p in planted
        if not p["case_id"].startswith(("CASE-", "N", "T"))
    ]
    return "\n".join(lines) + "\n"
