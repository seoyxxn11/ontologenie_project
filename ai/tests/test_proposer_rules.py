"""코드 리뷰에서 재현한 제안기 문제(S15P31C106-327): 정수 id·같은 표 이름·특수 컬럼 이름·개인정보·성능."""

import json
import re
import time
from pathlib import Path

import pandas as pd
import pytest
from test_links_general import rules, write_table

from app.analyzers.structured import ColumnAnalysis, analyze_sources
from app.models.candidate import (
    Candidate,
    Confidence,
    Evidence,
    ObjectTypePayload,
    ScoreItem,
)
from app.proposers import confidence as score
from app.proposers.evidence import verify
from app.proposers.links import Column, name_evidence, name_words, propose_links
from app.proposers.objects import (
    data_type,
    property_code,
    propose_objects,
    to_code,
    type_codes,
)
from app.proposers.run import api_result, generate, run
from app.proposers.semantics import prepare

DATA = Path(__file__).resolve().parents[1] / "data" / "eval"
TYPE_CODE = re.compile(r"[A-Z][A-Z0-9_]*")
PROPERTY_CODE = re.compile(r"[a-z][a-z0-9_]*")


def _ids(n, start=1):
    return [str(i) for i in range(start, start + n)]


def test_integer_ids_of_different_tables_are_not_linked_but_plural_and_camel_fks_are(
    tmp_path,
):
    # customers·items·orders 모두 1부터 매긴 정수 id. 기본키끼리는 숫자만 겹친다
    write_table(
        tmp_path,
        "shop",
        "customers",
        ["id"],
        {
            "id": ("int", _ids(30)),
            "name": ("varchar(20)", [f"고객{i}" for i in range(30)]),
        },
    )
    write_table(
        tmp_path,
        "shop",
        "items",
        ["id"],
        {
            "id": ("int", _ids(30)),
            "title": ("varchar(20)", [f"품목{i}" for i in range(30)]),
        },
    )
    write_table(
        tmp_path,
        "shop",
        "orders",
        ["id"],
        {
            "id": ("int", _ids(60)),
            "customer_id": ("int", [str(i % 30 + 1) for i in range(60)]),
            "itemId": ("int", [str((i * 7) % 30 + 1) for i in range(60)]),
        },
    )

    found = rules(tmp_path)

    # id ↔ id는 이름 근거가 아니고, 같은 원천의 기본키끼리는 표 이름 근거 없이 잇지 않는다
    assert not [k for k in found if k[0].endswith(".id")]
    # 복수형 표 이름(customers)·camelCase 컬럼(itemId)도 이름 근거가 되어 확정 후보로 남는다
    customer = found[("shop.orders.customer_id", "shop.customers.id")]
    item = found[("shop.orders.itemId", "shop.items.id")]
    assert customer.review_status == "PENDING" and item.review_status == "PENDING"


def test_name_words_split_camel_case_and_plural():
    assert name_words("customerId") == {"customer", "id"}
    assert name_words("ORDER_LINES") == {"order", "line"}
    assert name_words("categories") == {"category"}
    assert name_words("status") == {"status"}

    def col(table, name):
        return Column(table, name, None, pd.Series([], dtype=str))

    assert not name_evidence(col("erp.customer", "id"), col("erp.item", "id"))
    assert name_evidence(col("erp.employee", "emp_id"), col("erp.person", "emp_id"))
    assert name_evidence(col("erp.orders", "customerId"), col("erp.customers", "id"))


def test_same_table_name_in_two_sources_gets_source_prefix_only_then(tmp_path):
    items = [f"IT{i:03d}" for i in range(20)]
    for system in ("erp", "mes"):
        write_table(
            tmp_path,
            system,
            "item",
            ["item_cd"],
            {"item_cd": ("varchar(8)", items), "nm": ("varchar(20)", items)},
        )
    write_table(
        tmp_path,
        "mes",
        "work",
        ["wo"],
        {
            "wo": ("varchar(8)", [f"W{i:03d}" for i in range(40)]),
            "item_cd": ("varchar(8)", items * 2),
        },
    )

    result = run(tmp_path)
    ids = [c["candidate_id"] for c in result["candidates"]]
    codes = {
        c["payload"]["code"]
        for c in result["candidates"]
        if c["payload"]["kind"] == "OBJECT_TYPE"
    }

    assert len(ids) == len(set(ids))
    assert {"ERP_ITEM", "MES_ITEM", "WORK"} <= codes
    link_types = [
        c["payload"]["code"]
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_TYPE"
    ]
    assert len(link_types) == len(set(link_types))
    # 겹치지 않으면 지금 코드 그대로
    assert type_codes(["erp.item", "mes.work_order"]) == {
        "erp.item": "ITEM",
        "mes.work_order": "WORK_ORDER",
    }


def test_column_names_with_dots_do_not_break_analysis(tmp_path):
    folder = tmp_path / "erp"
    folder.mkdir()
    rows = ["spec_cd,spec.size,code,code"] + [
        f"S{i:03d},{i}x{i},C{i},D{i}" for i in range(10)
    ]
    (folder / "spec.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (folder / "_schema.json").write_text(
        json.dumps({"tables": {"spec": {"primary_key": ["spec_cd"], "columns": {}}}}),
        encoding="utf-8",
    )

    result = run(tmp_path)  # 예전에는 KeyError로 분석 전체가 실패했다
    props = {
        c["payload"]["code"]: c
        for c in result["candidates"]
        if c["payload"]["kind"] == "PROPERTY"
    }

    # pandas는 중복 헤더를 code.1로 읽는다. 둘 다 속성으로 나오고 원천 근거도 확인된다
    assert {"spec_size", "code", "code_1"} <= set(props)
    assert all(e["verified"] for p in props.values() for e in p["evidence"])
    mapping = next(
        c
        for c in result["candidates"]
        if c["payload"]["kind"] == "PROPERTY_MAPPING"
        and c["payload"]["property_code"] == "spec_size"
    )
    assert mapping["payload"]["source_column"]["column"] == "spec.size"


def test_person_name_column_is_not_compared_nor_shown_as_evidence(tmp_path):
    names = [
        "김민수",
        "이서준",
        "박지훈",
        "최유진",
        "정하은",
        "강도윤",
        "조서연",
        "윤지호",
    ]
    write_table(
        tmp_path,
        "erp",
        "staff",
        ["staff_no"],
        {
            "staff_no": ("varchar(6)", [f"S{i:03d}" for i in range(len(names))]),
            "staff_nm": ("varchar(10)", names),
        },
    )
    write_table(
        tmp_path,
        "mes",
        "task",
        ["task_no"],
        {
            "task_no": ("varchar(6)", [f"T{i:03d}" for i in range(32)]),
            "owner": ("varchar(10)", names * 4),
        },
    )

    candidates = propose_links(tmp_path)

    assert not [c for c in candidates if "owner" in c.evidence[0].locator]
    shown = " ".join(
        e.snippet + " ".join(e.values) for c in candidates for e in c.evidence
    )
    assert not any(n in shown for n in names)


def test_codes_follow_backend_rules_and_keep_original_names(tmp_path):
    long_name = "measured_" + "value_" * 20
    write_table(
        tmp_path,
        "qms",
        "work-order",
        ["orderNo"],
        {
            "orderNo": ("varchar(8)", [f"O{i:03d}" for i in range(10)]),
            "ITEM_CD": ("varchar(8)", [f"I{i:03d}" for i in range(10)]),
            "품목명": ("varchar(20)", [f"품목{i}" for i in range(10)]),
            "unit-price": ("numeric", [str(i) for i in range(10)]),
            long_name: ("numeric", [str(i) for i in range(10)]),
        },
    )

    candidates = propose_objects(tmp_path)
    props = [c.payload for c in candidates if c.payload.kind.value == "PROPERTY"]
    mappings = [
        c.payload for c in candidates if c.payload.kind.value == "PROPERTY_MAPPING"
    ]
    types = [c.payload for c in candidates if c.payload.kind.value == "OBJECT_TYPE"]

    assert all(PROPERTY_CODE.fullmatch(p.code) and len(p.code) <= 100 for p in props)
    assert all(TYPE_CODE.fullmatch(t.code) for t in types)
    assert types[0].code == "WORK_ORDER"
    by_column = {m.source_column.column: m.property_code for m in mappings}
    assert by_column["orderNo"] == "order_no" and by_column["ITEM_CD"] == "item_cd"
    assert by_column["unit-price"] == "unit_price"
    assert by_column["품목명"].startswith("col_")
    assert len(set(by_column.values())) == len(by_column)
    # 원래 이름은 속성 이름에 남는다
    assert any(p.name == "품목명" for p in props)
    # 규칙에 맞는 이름은 그대로(시연 데이터 코드가 바뀌지 않음)
    assert (
        property_code("item_code") == "item_code"
        and to_code("work_order") == "WORK_ORDER"
    )


def test_link_codes_and_properties_match_converted_codes(tmp_path):
    write_table(
        tmp_path,
        "scm",
        "Supplier",
        ["supplierId"],
        {"supplierId": ("varchar(6)", [f"SP{i:03d}" for i in range(12)])},
    )
    write_table(
        tmp_path,
        "scm",
        "PurchaseOrder",
        ["poNo"],
        {
            "poNo": ("varchar(8)", [f"PO{i:04d}" for i in range(24)]),
            "supplierId": ("varchar(6)", [f"SP{i % 12:03d}" for i in range(24)]),
        },
    )

    result = run(tmp_path)
    props = {
        f"{c['payload']['object_type_code']}.{c['payload']['code']}"
        for c in result["candidates"]
        if c["payload"]["kind"] == "PROPERTY"
    }
    link_rules = [
        c["payload"]
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_RULE"
    ]

    assert link_rules
    for rule in link_rules:
        assert TYPE_CODE.fullmatch(rule["code"])
        for condition in rule["conditions"]:
            assert condition["source_property"] in props
            assert condition["target_property"] in props
            # 'SUPPLIER.supplierId'가 아니라 바꾼 속성 코드('SUPPLIER.supplier_id')를 가리킨다
            assert PROPERTY_CODE.fullmatch(condition["source_property"].split(".")[1])


def test_observed_key_with_duplicates_is_not_an_identifier(tmp_path):
    lots = [f"LT{i:03d}" for i in range(39)] + ["LT000"]  # 40행 중 1행 중복
    write_table(
        tmp_path,
        "mes",
        "lot_history",
        [],
        {
            "lot_no": ("varchar(8)", lots),
            "weight": ("numeric", [f"{i}.5" for i in range(40)]),
        },
    )
    write_table(
        tmp_path,
        "mes",
        "pack",
        ["pack_no"],
        {
            "pack_no": ("varchar(8)", [f"P{i:03d}" for i in range(40)]),
            "lot_no": ("varchar(8)", lots[:39] + ["LT001"]),
        },
    )

    candidates = propose_objects(tmp_path)
    object_type = next(c for c in candidates if c.candidate_id == "OT-mes.lot_history")

    assert object_type.review_status == "HELD"
    assert "중복 1행" in object_type.hold_reason
    assert not [c for c in candidates if c.candidate_id == "OM-mes.lot_history"]
    assert not [
        c
        for c in candidates
        if c.payload.kind.value == "PROPERTY"
        and c.payload.object_type_code == "LOT_HISTORY"
        and c.payload.is_identifier
    ]
    # 중복 있는 컬럼을 기본키처럼 도착으로 쓰지 않는다
    assert not [
        c
        for c in propose_links(tmp_path)
        if c.evidence[1].locator == "mes.lot_history.lot_no" and "(기본키)" in c.reason
    ]


def _conditional_tables(root):
    eq = [f"EQ{i:02d}" for i in range(12)]
    die = [f"DIE{i:02d}" for i in range(6)]
    write_table(
        root,
        "mes",
        "equipment",
        ["eq_code"],
        # 기본키와 같은 값을 가진 유일값 컬럼도 있다
        {"eq_code": ("varchar(6)", eq), "old_code": ("varchar(6)", eq)},
    )
    write_table(root, "mes", "die", ["die_code"], {"die_code": ("varchar(6)", die)})
    targets = [(t, c) for t, c in zip(["PRESS", "ROBOT"] * 6, eq)] + [
        ("DIE", c) for c in die
    ]
    write_table(
        root,
        "mes",
        "maint",
        ["mo_no"],
        {
            "mo_no": ("varchar(6)", [f"M{i:03d}" for i in range(len(targets))]),
            "target_type": ("varchar(6)", [t for t, _ in targets]),
            "target_code": ("varchar(6)", [c for _, c in targets]),
        },
    )


def test_conditional_relation_prefers_primary_key_and_ids_do_not_collide(tmp_path):
    _conditional_tables(tmp_path)

    candidates = propose_links(tmp_path)
    conditional = [
        c
        for c in candidates
        if c.payload.kind.value == "LINK_RULE" and c.payload.source_filter
    ]
    ids = [c.candidate_id for c in candidates]

    assert len(ids) == len(set(ids))
    to_equipment = [c for c in conditional if "mes.equipment" in c.evidence[1].locator]
    assert {c.payload.source_filter.equals for c in to_equipment} == {"PRESS", "ROBOT"}
    # 'alternate' < 'primary' 문자열 비교로 유일값을 고르지 않는다
    assert all(c.evidence[1].locator == "mes.equipment.eq_code" for c in to_equipment)
    assert len({c.payload.code for c in conditional}) == len(conditional)


def test_small_number_second_column_without_name_evidence_is_held(tmp_path):
    items = [f"IT{i:02d}" for i in range(10)]
    write_table(
        tmp_path,
        "mes",
        "routing",
        ["item_cd", "step_no"],
        {
            "item_cd": ("varchar(6)", [i for i in items for _ in range(5)]),
            "step_no": ("int", ["1", "2", "3", "4", "5"] * len(items)),
            "op_name": (
                "varchar(10)",
                ["절단", "용접", "도장", "조립", "검사"] * len(items),
            ),
        },
    )
    write_table(
        tmp_path,
        "mes",
        "supplier_priority",
        ["sp_id"],
        {
            "sp_id": ("varchar(6)", [f"SP{i:03d}" for i in range(50)]),
            "item_cd": ("varchar(6)", [i for i in items for _ in range(5)]),
            "priority": ("int", ["1", "2", "3", "4", "5"] * len(items)),
        },
    )

    rel = rules(tmp_path).get(
        ("mes.supplier_priority.item_cd+priority", "mes.routing.item_cd+step_no")
    )

    # 우선순위 1~5가 공정 순번 1~5와 숫자만 같다. 확정(0.85)으로 내지 않는다
    assert rel is None or (rel.review_status == "HELD" and rel.ai_confidence <= 0.45)


def test_composite_with_unmatched_row_does_not_claim_all_rows(tmp_path):
    so = [f"SO{n:03d}" for n in range(1, 16)]
    write_table(
        tmp_path,
        "erp",
        "order_line",
        ["so_no", "line_no"],
        {
            "so_no": ("varchar(8)", [s for s in so for _ in (1, 2)]),
            "line_no": ("int", ["1", "2"] * len(so)),
        },
    )
    lines = [(s, n) for s in so for n in ("1", "2")]
    lines[-1] = ("SO099", "2")  # 도착에 없는 줄 하나
    write_table(
        tmp_path,
        "erp",
        "shipment",
        ["ship_no"],
        {
            "ship_no": ("varchar(8)", [f"SH{i:03d}" for i in range(len(lines))]),
            "so_no": ("varchar(8)", [s for s, _ in lines]),
            "line_no": ("int", [n for _, n in lines]),
        },
    )

    rel = rules(tmp_path)[
        ("erp.shipment.so_no+line_no", "erp.order_line.so_no+line_no")
    ]

    assert "모두 연결" not in rel.reason
    assert "29/30행" in rel.reason and "SO099|2" in rel.reason


def test_hold_from_evidence_check_caps_confidence(tmp_path):
    write_table(
        tmp_path, "erp", "item", ["item_cd"], {"item_cd": ("varchar(6)", ["A1", "A2"])}
    )
    candidate = Candidate(
        candidate_id="OT-test",
        title="t",
        payload=ObjectTypePayload(code="ITEM", name="품목"),
        reason="r",
        evidence=[
            Evidence(source_id="erp", locator="erp.item.item_cd", snippet="s"),
            Evidence(source_id="erp", locator="erp.item.missing", snippet="s"),
        ],
        confidence=Confidence(items=[ScoreItem(label="근거", weight=0.95)]),
    )

    kept, _ = verify(tmp_path, [candidate])

    assert kept[0].review_status == "HELD"
    assert kept[0].ai_confidence <= 0.6
    assert score.hold_cap(kept[0].hold_reason)[0] == kept[0].confidence.cap


def test_source_tables_are_read_once_per_run(tmp_path, monkeypatch):
    from app.sources.csv_reader import CsvSourceReader

    _conditional_tables(tmp_path)
    reads = []
    original = CsvSourceReader.read_rows

    def counting(self, table, *args, **kwargs):
        reads.append(table)
        return original(self, table, *args, **kwargs)

    monkeypatch.setattr(CsvSourceReader, "read_rows", counting)
    run(tmp_path)

    assert sorted(reads) == ["die", "equipment", "maint"]


def test_many_tables_finish_quickly(tmp_path):
    # 표 8개 × 2,000행. 고치기 전에는 쌍마다 값 전체를 다시 변환해 표 수의 제곱 이상으로 느려졌다
    rows = 2000
    names = [f"t{i}" for i in range(8)]
    for i, name in enumerate(names):
        ref = names[(i + 1) % len(names)]
        write_table(
            tmp_path,
            "erp",
            name,
            ["id"],
            {
                "id": ("int", _ids(rows)),
                f"{name}_code": (
                    "varchar(12)",
                    [f"{name.upper()}-{k:05d}" for k in range(rows)],
                ),
                f"{ref}_code": (
                    "varchar(12)",
                    [f"{ref.upper()}-{(k * 7) % rows:05d}" for k in range(rows)],
                ),
                "lot_no": (
                    "varchar(12)",
                    [f"L{(k * 13) % 9973:07d}" for k in range(rows)],
                ),
            },
        )

    started = time.perf_counter()
    candidates = propose_links(tmp_path, analyze_sources(tmp_path))
    elapsed = time.perf_counter() - started

    # CI 기계 속도 차이를 감안해 넉넉하게 둔다(고친 뒤 로컬 약 2초, 고치기 전 35초 이상)
    assert elapsed < 60
    assert len([c for c in candidates if c.payload.kind.value == "LINK_RULE"]) >= len(
        names
    )


def test_interval_is_not_an_integer_type():
    column = ColumnAnalysis(
        ref="x.t.wait",
        declared_type="interval",
        comment=None,
        is_declared_key=False,
        row_count=1,
        null_count=0,
        distinct_count=1,
        duplicate_count=0,
        top_pattern=None,
        top_pattern_ratio=0,
        sample_values=[],
        is_key_candidate=False,
        is_personal=False,
    )

    assert data_type(column, ["1 day"]).value == "STRING"


def test_backslash_in_names_does_not_break_api_format(tmp_path):
    write_table(
        tmp_path,
        "erp",
        "SPEC_HDR",
        ["spec_cd"],
        {"spec_cd": ("varchar(6)", [f"SP{i:02d}" for i in range(12)])},
    )
    schema_file = tmp_path / "erp" / "_schema.json"
    schema = json.loads(schema_file.read_text(encoding="utf-8"))
    # 괄호 안은 이름을 줄일 때 빠지므로 괄호 밖에 둔다. '\i'는 re 치환 문법에서 bad escape
    schema["tables"]["SPEC_HDR"]["description"] = "규격 mm\\inch"
    schema["tables"]["SPEC_HDR"]["columns"]["spec_cd"]["comment"] = "치수 mm\\inch"
    schema_file.write_text(json.dumps(schema, ensure_ascii=False), encoding="utf-8")
    write_table(
        tmp_path,
        "mes",
        "work",
        ["wo"],
        {
            "wo": ("varchar(6)", [f"W{i:03d}" for i in range(24)]),
            "spec_cd": ("varchar(6)", [f"SP{i % 12:02d}" for i in range(24)]),
        },
    )

    result = run(tmp_path)
    kept = [Candidate.model_validate(c) for c in result["candidates"]]
    proposals = api_result(kept)["proposals"]  # 예전에는 re.error: bad escape

    assert any("\\" in json.dumps(p, ensure_ascii=False) for p in proposals)


@pytest.mark.parametrize(
    "name, system, table, column",
    [("b3", "scm", "supplier", "contactName"), ("b9", "erp", "거래처", "대표자명")],
)
def test_llm_prompt_masks_person_names_even_when_property_code_changed(
    name, system, table, column
):
    # 속성 코드가 바뀐 컬럼(contactName → contact_name, 대표자명 → col_해시)도 원래 컬럼 이름으로 가린다
    root = DATA / name / "data"
    kept, _ = generate(root)
    names = (
        pd.read_csv(root / system / f"{table}.csv", dtype=str, encoding="utf-8-sig")[
            column
        ]
        .dropna()
        .tolist()
    )

    (_, _, _, prompt), *_ = prepare(root, kept, only={f"{system}.{table}"})

    assert names and not [n for n in names if n in prompt]
    # 컬럼 줄과 표본 행이 같은 이름(원래 컬럼 이름)을 쓴다
    assert f"- {column} |" in prompt


def test_rule_text_uses_property_names_for_renamed_columns():
    # 속성 코드(default_supplier_id)가 아니라 글에 있는 원래 컬럼 이름(defaultSupplierId)을 찾아 바꾼다(develop과 같은 문구)
    kept, _ = generate(DATA / "b3" / "data")
    proposals = api_result(kept)["proposals"]
    rule = next(
        p
        for p in proposals
        if p["evidence"]["candidateId"] == "LR-material_defaultSupplierId_to_supplier"
    )

    assert rule["evidence"]["reason"] == (
        "자재·상품 품목 마스터의 기본 공급업체로 공급업체 마스터를 찾습니다."
        " 자재·상품 품목 마스터 135행이 모두 공급업체 마스터와 이어집니다."
        " 승인하면 자재·상품 품목 마스터와 공급업체 마스터가 이어져 탐색 화면과 Agent에서 함께 찾을 수 있습니다."
    )
    assert rule["payload"]["description"] == (
        "연결 기준: 자재·상품 품목 마스터 기본 공급업체 = 공급업체 마스터 공급업체 ID"
    )


def test_hashed_codes_are_not_shown_in_titles(tmp_path):
    # 한글 표 이름은 코드가 해시(T_…)가 되지만 제목·설명에는 표 이름을 보인다
    write_table(
        tmp_path,
        "erp",
        "거래처",
        ["거래처코드"],
        {"거래처코드": ("varchar(6)", [f"C{i:03d}" for i in range(12)])},
    )
    write_table(
        tmp_path,
        "erp",
        "주문",
        ["주문번호"],
        {
            "주문번호": ("varchar(6)", [f"O{i:03d}" for i in range(24)]),
            "거래처코드": ("varchar(6)", [f"C{i % 12:03d}" for i in range(24)]),
        },
    )

    kept, _ = generate(tmp_path)
    by_id = {c.candidate_id: c for c in kept}
    object_type = by_id["OT-erp.거래처"]
    link_type = next(c for c in kept if c.payload.kind.value == "LINK_TYPE")
    rule = next(c for c in kept if c.payload.kind.value == "LINK_RULE")

    assert object_type.payload.code.startswith("T_")  # 코드는 백엔드 규칙대로
    assert object_type.title.startswith("객체 유형 거래처")
    assert by_id["P-erp.거래처.거래처코드"].title == "속성 거래처.거래처코드"
    assert "T_" not in link_type.title + link_type.payload.name
    assert "T_" not in link_type.payload.description and "T_" not in rule.title
    assert TYPE_CODE.fullmatch(rule.payload.code)
    # 영문 표 이름은 지금처럼 코드를 보인다(시연 데이터 제목 그대로)
    assert to_code("work_order") == "WORK_ORDER"


def test_relation_codes_that_differ_only_by_separator_get_numbered(tmp_path):
    # item-cd와 item_cd는 대문자 코드로 바꾸면 같아진다. 관계 유형 코드가 겹치면 반영이 실패한다
    codes = [f"IT{i:03d}" for i in range(1, 31)]
    write_table(
        tmp_path,
        "erp",
        "item",
        ["item_cd"],
        {
            "item_cd": ("varchar(10)", codes),
            "item_nm": ("varchar(20)", [f"품목{i}" for i in range(30)]),
        },
    )
    write_table(
        tmp_path,
        "erp",
        "orders",
        ["order_no"],
        {
            "order_no": ("varchar(10)", [f"O{i:03d}" for i in range(30)]),
            "item-cd": ("varchar(10)", codes),
            "item_cd": ("varchar(10)", list(reversed(codes))),
        },
    )
    link_types = [
        c for c in propose_links(tmp_path) if c.payload.kind.value == "LINK_TYPE"
    ]
    link_codes = [c.payload.code for c in link_types]
    assert len(link_codes) == len(set(link_codes))
    assert all(TYPE_CODE.fullmatch(code) for code in link_codes)


def test_numbered_relation_code_stays_within_backend_length():
    from app.proposers.links import _numbered

    code = _numbered("A" * 100, 2)
    assert len(code) == 100 and code.endswith("_2")
    assert TYPE_CODE.fullmatch(code)


def test_parent_code_in_same_table_is_linked_even_with_one_value(tmp_path):
    # 상위 부서가 하나뿐이어도(값 종류 1개) 같은 표의 위계 관계로 제안한다
    write_table(
        tmp_path,
        "erp",
        "department",
        ["dept_code"],
        {
            "dept_code": ("varchar(10)", ["DP10", "DP20", "DP21", "DP30", "DP40"]),
            "parent_code": ("varchar(10)", ["", "DP10", "DP10", "DP10", ""]),
            "backup_code": ("varchar(10)", ["DP10", "", "DP10", "", ""]),
        },
    )
    found = {c.candidate_id: c for c in propose_links(tmp_path)}
    ids = set(found)
    assert "LR-department_parent_code_to_department" in ids
    # 이어진 상위 값이 하나뿐이라 확정이 아니라 보류로 낸다
    assert found["LR-department_parent_code_to_department"].review_status == "HELD"
    # 위계를 뜻하지 않는 칸은 값이 적으면 지금처럼 비교하지 않는다
    assert "LR-department_backup_code_to_department" not in ids


def test_upper_limit_number_is_not_a_hierarchy_link(tmp_path):
    # upper_limit(상한값)도 정규식에 걸리지만 수치라 기본키와 이어지지 않는다
    write_table(
        tmp_path,
        "mes",
        "spec",
        ["spec_id"],
        {
            "spec_id": ("int", [str(i) for i in range(1, 13)]),
            "upper_limit": ("int", ["5"] * 12),
        },
    )
    assert not any(
        "upper_limit" in c.candidate_id
        for c in propose_links(tmp_path)
        if c.candidate_id.startswith("LR-")
    )
