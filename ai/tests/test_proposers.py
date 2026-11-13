import json

import pandas as pd
import pytest

from app.analyzers.structured import ColumnAnalysis
from app.dataset.build import generate
from app.models.source import ColumnSchema, TableSchema
from app.proposers.objects import classify, looks_like_person_names
from app.proposers.run import run
from app.validators.evaluate_ontology import evaluate


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t3", root=root)
    return root


@pytest.fixture(scope="module")
def result(data):
    return run(data / "data-t3")


def _col(name, declared, comment=None):
    return ColumnAnalysis(
        ref=f"x.t.{name}",
        declared_type=declared,
        comment=comment,
        is_declared_key=False,
        row_count=3,
        null_count=0,
        distinct_count=3,
        duplicate_count=0,
        top_pattern=None,
        top_pattern_ratio=0,
        sample_values=[],
        is_key_candidate=False,
        is_personal=False,
    )


def _schema(pk, *cols):
    return TableSchema(
        source_id="x",
        name="t",
        columns=[
            ColumnSchema(name=n, declared_type=t, is_primary_key=n in pk)
            for n, t in cols
        ],
    )


def test_category_rules():
    cols = [("doc_no", "varchar(10)"), ("line_no", "int"), ("qty", "int")]
    analysis = [_col(n, t) for n, t in cols]
    values = {n: ["1"] for n, _ in cols}
    assert (
        classify(
            _schema(["doc_no", "line_no"], *cols), analysis, values, set(), {"doc_no"}
        )[0]
        == "detail"
    )
    cols = [
        ("id", "varchar(10)"),
        ("status", "varchar(10)"),
        ("ordered", "date"),
        ("due", "date"),
    ]
    analysis = [_col(n, t) for n, t in cols]
    values = {n: ["a"] for n, _ in cols}
    assert (
        classify(_schema(["id"], *cols), analysis, values, {"x.line"}, set())[0]
        == "transaction"
    )
    assert (
        classify(
            _schema(["id"], *cols[:2]), analysis[:2], values, {"x.a", "x.b"}, set()
        )[0]
        == "master"
    )
    log = [
        ("change_id", "bigint"),
        ("row_key", "varchar(10)"),
        ("operation", "varchar(10)"),
    ]
    assert (
        classify(
            _schema(["change_id"], *log),
            [_col(n, t) for n, t in log],
            {n: [] for n, _ in log},
            set(),
            set(),
        )[0]
        is None
    )


def test_person_names_by_value():
    assert looks_like_person_names(["김민준", "박서연", "이도윤", "최하은"])
    assert not looks_like_person_names(["P101", "P103", "B101"])
    assert not looks_like_person_names(
        ["김민준", "박서연"]
    )  # 값 종류가 너무 적으면 판단하지 않음


def test_repeated_names_stay_personal_but_repeated_category_words_do_not():
    assert looks_like_person_names(["김민수", "이서준", "박지호", "최유진"] * 5)
    assert not looks_like_person_names(["공구", "기계장치", "차량"] * 3)


def _property_with_view(role, is_personal, data_type="STRING", comment=""):
    from app.models.candidate import Candidate, DataType, Evidence, PropertyPayload
    from app.proposers.semantics import TableView, apply

    prop = Candidate(
        candidate_id="P-mes.equipment.asset_type",
        title="t",
        payload=PropertyPayload(
            object_type_code="EQUIPMENT",
            code="asset_type",
            name="자산 종류",
            data_type=DataType(data_type),
            description=comment,
        ),
        reason="r",
        evidence=[
            Evidence(source_id="mes", locator="mes.equipment.asset_type", snippet="s")
        ],
    )
    view = TableView(
        object_name="설비",
        meaning="설비 기준 정보",
        category="master",
        category_reason="오래 쓰는 대상",
        columns=[
            {
                "name": "asset_type",
                "meaning": "자산 종류",
                "role": role,
                "data_type": data_type,
                "is_personal": is_personal,
            }
        ],
    )
    return apply([prop], {"mes.equipment": view})[0].payload.sensitivity.value


def test_llm_personal_is_kept_only_for_name_or_person_columns():
    # LLM이 개인정보라고 해도 이름 칸·사람 칸이 아니면 따르지 않는다(A에서 22개 중 18개 오답)
    assert _property_with_view("status", True) == "NORMAL"
    assert _property_with_view("time", True, data_type="DATE") == "NORMAL"
    assert _property_with_view("name", True) == "RESTRICTED"
    assert _property_with_view("attribute", True, comment="검사자") == "RESTRICTED"
    assert _property_with_view("name", False) == "NORMAL"


def test_person_column_with_one_name_among_departments_is_personal(tmp_path):
    # 정비 담당 칸에 부서명 사이로 사람 이름이 섞여 있으면 개인정보로 본다
    from test_links_general import write_table

    from app.proposers.objects import propose_objects

    write_table(
        tmp_path,
        "mes",
        "maintenance_order",
        ["mo_no"],
        {
            "mo_no": ("varchar(10)", [f"MO-{i:03d}" for i in range(12)]),
            "technician": ("varchar(20)", ["보전팀"] * 6 + ["설비팀"] * 5 + ["홍보람"]),
        },
    )
    props = [
        c
        for c in propose_objects(tmp_path)
        if c.candidate_id == "P-mes.maintenance_order.technician"
    ]
    assert props and props[0].payload.sensitivity.value == "RESTRICTED"


def test_proposals_cover_tables_and_columns_with_rule_method(data, result):
    kinds = result["summary"]["by_kind"]
    assert (
        kinds["OBJECT_TYPE"] == kinds["OBJECT_MAPPING"] == 31
    )  # 변경 이력 표 2개는 객체가 아님
    assert all(c["method"] == "rule" for c in result["candidates"])
    assert all(0 < c["ai_confidence"] <= 1.0 for c in result["candidates"])
    assert all(
        c["ai_confidence"] <= 0.6
        for c in result["candidates"]
        if c["review_status"] == "HELD"
    )
    assert all(e["verified"] for c in result["candidates"] for e in c["evidence"])
    personal = {
        c["payload"]["code"]
        for c in result["candidates"]
        if c["payload"]["kind"] == "PROPERTY"
        and c["payload"]["sensitivity"] == "RESTRICTED"
    }
    assert {"emp_name", "name", "contact_name"} <= personal
    report = evaluate(result["candidates"], data / "gold-t3")
    assert report["object_types"]["recall"] == 1 and report["properties"]["recall"] == 1


def _rules(result):
    return {
        c["candidate_id"].removeprefix("LR-"): c
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_RULE"
    }


def test_link_rules_cover_composite_conditional_and_hold_number_only(result):
    rules = _rules(result)
    composite = rules["production_result_wo_no_op_seq_to_work_order_operation"]
    assert len(composite["payload"]["conditions"]) == 2
    die = rules["maintenance_order_target_code_to_die_when_target_type"]
    assert die["payload"]["source_filter"] == {
        "property": "MAINTENANCE_ORDER.target_type",
        "equals": "금형",
    }
    so_ref = rules["work_order_so_ref_to_sales_order"]
    assert so_ref["review_status"] == "PENDING"  # 일부 값은 이미 ERP 표기와 똑같다
    worker = rules["worker_id_to_employee"]
    assert worker["review_status"] == "HELD"  # 숫자에 0 채우기·접두어를 붙여야만 같아짐


def test_link_rules_cover_name_key_category_and_via(result):
    rules = _rules(result)
    # 부서 이름은 다른 유일 키. 값이 1종뿐이라 보류
    buyer = rules["purchase_order_buyer_to_department"]
    assert (
        buyer["payload"]["conditions"][0]["target_property"] == "DEPARTMENT.dept_name"
    )
    assert buyer["review_status"] == "HELD"
    kind = rules["routing_equipment_kind_to_equipment"]
    assert kind["payload"]["conditions"][0]["target_property"] == "EQUIPMENT.kind"
    types = {
        c["candidate_id"].removeprefix("LT-"): c
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_TYPE"
    }
    # 거쳐 가는 관계는 연결 규칙 없이 관계 유형만 보류로 낸다
    via = "production_result_wo_no_op_seq_to_routing_via_work_order"
    assert via not in rules
    assert types[via]["review_status"] == "HELD"
    kind_type = types["routing_equipment_kind_to_equipment"]["payload"]
    assert kind_type["cardinality"] == "MANY_TO_MANY"
    via_type = types["work_order_operation_wo_no_op_seq_to_routing_via_work_order"]
    assert via_type["payload"]["cardinality"] == "MANY_TO_ONE"


def test_link_evaluation_counts_pending_and_held_separately(data, result):
    links = evaluate(result["candidates"], data / "gold-t3")["links"]
    assert links["gold"] == 56
    assert links["found_pending"] + links["found_held"] <= links["gold"]
    assert links["proposed_pending"] + links["proposed_held"] == len(_rules(result))


def test_excluded_tables_leave_both_gold_and_candidates(data, result):
    accounting = {
        "erp.sales_invoice",
        "erp.purchase_invoice",
        "erp.gl_account",
        "erp.journal_entry",
    }
    full = evaluate(result["candidates"], data / "gold-t3")
    scoped = evaluate(result["candidates"], data / "gold-t3", accounting)

    assert scoped["object_types"]["gold"] == full["object_types"]["gold"] - 4
    assert scoped["links"]["gold"] == 52
    assert not any(t in accounting for t in scoped["object_types"]["false_positive"])
    assert not any(
        ".".join(c.split(".")[:2]) in accounting
        for c in scoped["properties"]["missed"] + scoped["properties"]["false_positive"]
    )


def test_llm_semantics_with_mock_merges_and_holds(data, result):
    from app.llm.client import MockLLMClient
    from app.models.candidate import Candidate
    from app.proposers.semantics import interpret, prepare

    candidates = [Candidate.model_validate(c) for c in result["candidates"]]
    prompts = {t: p for t, _, _, p in prepare(data / "data-t3", candidates)}
    worker_names = {
        c["payload"]["code"]
        for c in result["candidates"]
        if c["candidate_id"].startswith("P-mes.worker.")
    }
    assert "name" in worker_names
    import pandas as pd

    names = pd.read_csv(data / "data-t3" / "mes" / "worker.csv", dtype=str)["name"]
    assert not any(n in prompts["mes.worker"] for n in names)

    def answer(prompt):
        if "표: mes.worker " in prompt:
            return {
                "object_name": "현장 작업자",
                "meaning": "MES 작업자 기준 정보",
                "category": "master",
                "category_reason": "다른 기록이 작업자를 가리킨다",
                "columns": [
                    {
                        "name": "name",
                        "meaning": "작업자 이름",
                        "role": "name",
                        "data_type": "STRING",
                        "is_personal": True,
                    },
                    {
                        "name": "없는컬럼",
                        "meaning": "지어냄",
                        "role": "attribute",
                        "data_type": "STRING",
                    },
                ],
                "links": [
                    {
                        "code": "WORKER_ID_TO_EMPLOYEE",
                        "meaningful": True,
                        "reason": "작업자는 직원이다",
                    },
                    {"code": "지어낸_관계", "meaningful": True, "reason": "없음"},
                ],
            }
        if "표: mes.defect " in prompt:
            return {
                "object_name": "불량",
                "meaning": "공정 불량 기록",
                "category": "transaction",
                "category_reason": "발생 시각과 수량",
                "links": [
                    {
                        "code": "DEFECT_OP_SEQ_TO_DEPARTMENT",
                        "meaningful": False,
                        "reason": "공정 순번은 부서 코드가 아니다",
                    }
                ],
            }
        return {
            "object_name": "x",
            "meaning": "x",
            "category": "잘못된 값",
            "category_reason": "x",
        }

    merged, report = interpret(data / "data-t3", candidates, MockLLMClient(answer))
    by_id = {c.candidate_id: c for c in merged}
    by_id_rule = {c.candidate_id: c for c in candidates}
    assert report["interpreted"] == 2 and len(report["failed"]) == report["tables"] - 2
    worker = by_id["OT-mes.worker"]
    assert worker.method == "rule+llm" and worker.payload.name == "현장 작업자"
    assert (
        worker.llm_judgement and "기준 정보" in worker.llm_judgement
    )  # 표 종류는 한글로
    # LLM이 표 종류를 바꾸면 이유도 바꾸고 표 구조로 본 분류는 괄호에 남긴다
    defect, defect_rule = by_id["OT-mes.defect"], by_id_rule["OT-mes.defect"]
    if defect_rule.payload.category != "transaction":
        assert defect.payload.category == "transaction"
        assert "분류: 거래 문서 (표 구조로는" in defect.reason
    assert worker.ai_confidence == by_id_rule["OT-mes.worker"].ai_confidence
    # 보류 권고 규칙을 LLM이 맞다고 보면 보류는 그대로, 상한만 0.6
    agreed, agreed_rule = (
        by_id["LR-worker_id_to_employee"],
        by_id_rule["LR-worker_id_to_employee"],
    )
    assert agreed_rule.review_status == "HELD" and agreed_rule.ai_confidence < 0.6
    assert agreed.review_status == "HELD" and agreed.ai_confidence == 0.6
    assert "LLM이 업무상 맞다고 봄" in agreed.confidence.cap_reason
    # 규칙 보류 + LLM 반대면 내보내지 않는다
    assert by_id_rule["LR-defect_op_seq_to_department"].review_status == "HELD"
    assert "LR-defect_op_seq_to_department" not in by_id
    assert "LT-defect_op_seq_to_department" not in by_id
    assert report["rejected_links"] == {
        "DEFECT_OP_SEQ_TO_DEPARTMENT": "공정 순번은 부서 코드가 아니다"
    }
    assert "지어낸_관계" not in {
        c.payload.code for c in merged if hasattr(c.payload, "code")
    }
    untouched = by_id["OT-erp.item"]
    assert untouched.method == "rule"  # 형식이 틀린 답은 버리고 규칙 결과를 그대로 둔다


def test_api_format_is_camel_case(result):
    from app.models.candidate import Candidate
    from app.proposers.api_format import has_snake_keys, to_api

    api = [to_api(Candidate.model_validate(c)) for c in result["candidates"][:50]]
    assert not has_snake_keys(api)
    assert set(api[0]) == {"itemKind", "payload", "evidence", "aiConfidence", "options"}
    assert {"reason", "items", "holdRecommended"} <= set(api[0]["evidence"])
    types = [a["payload"]["code"] for a in api if a["itemKind"] == "OBJECT_TYPE"]
    assert types and all(t == t.upper() for t in types)  # 객체 유형 코드는 대문자
    tables = {
        a["payload"]["objectTypeCode"]: a["payload"]["resourceKey"]
        for a in api
        if a["itemKind"] == "OBJECT_MAPPING"
    }
    mappings = [a["payload"] for a in api if a["itemKind"] == "PROPERTY_MAPPING"]
    assert mappings and all(m["sourceColumn"]["resourceKey"] for m in mappings)
    paired = [m for m in mappings if m["objectTypeCode"] in tables]
    assert paired
    for m in paired:
        assert m["sourceColumn"]["resourceKey"] == tables[m["objectTypeCode"]]


def _bundled(result):
    from app.models.candidate import Candidate
    from app.proposers.api_format import to_api_proposals

    kept = [Candidate.model_validate(c) for c in result["candidates"]]
    return kept, to_api_proposals(kept)


def test_bundles_one_table_into_one_object_proposal_without_losing_columns(result):
    kept, api = _bundled(result)
    kinds = {a["itemKind"] for a in api}
    assert not kinds & {"PROPERTY", "OBJECT_MAPPING", "PROPERTY_MAPPING"}
    objects = {a["payload"]["code"]: a for a in api if a["itemKind"] == "OBJECT_TYPE"}
    assert len(objects) == sum(c.payload.kind.value == "OBJECT_TYPE" for c in kept)
    props = [c for c in kept if c.payload.kind.value == "PROPERTY"]
    bundled = [
        (code, p["code"])
        for code, a in objects.items()
        for p in a["payload"]["properties"]
    ]
    assert sorted(bundled) == sorted(
        (c.payload.object_type_code, c.payload.code) for c in props
    )
    for a in objects.values():
        payload, evidence = a["payload"], a["evidence"]
        mapping = payload["objectMapping"]
        assert mapping["keyColumns"] and "objectTypeCode" not in mapping
        for prop in payload["properties"]:
            assert prop["sourceColumn"]["resourceKey"] == mapping["resourceKey"]
            assert "objectTypeCode" not in prop
        assert [e["code"] for e in evidence["properties"]] == [
            p["code"] for p in payload["properties"]
        ]
        assert all(e["items"] and e["mapping"]["items"] for e in evidence["properties"])


def test_bundles_link_type_and_its_rule_into_one_relation_proposal(result):
    from app.proposers.api_format import to_api

    kept, api = _bundled(result)
    types = {c.payload.code: c for c in kept if c.payload.kind.value == "LINK_TYPE"}
    rules = [c for c in kept if c.payload.kind.value == "LINK_RULE"]
    bundled = [a for a in api if a["itemKind"] == "LINK_RULE"]
    assert len(bundled) == len(rules)
    for a in bundled:
        link_type = a["payload"]["linkType"]
        assert link_type["code"] == a["payload"]["linkTypeCode"]
        # 이름·설명만 readable이 바꾼다
        original = to_api(types[link_type["code"]])["payload"]
        assert {
            k: v for k, v in link_type.items() if k not in ("name", "description")
        } == {k: v for k, v in original.items() if k not in ("name", "description")}
    alone = {a["payload"]["code"] for a in api if a["itemKind"] == "LINK_TYPE"}
    assert alone == set(types) - {r.payload.link_type_code for r in rules}
    held_rules = sum(
        r.review_status == "HELD"
        or types[r.payload.link_type_code].review_status == "HELD"
        for r in rules
    )
    assert sum(a["evidence"]["holdRecommended"] for a in bundled) == held_rules
    assert len(api) < len(kept) / 3


def cand(cid, payload, **extra):
    from app.models.candidate import Candidate

    return Candidate(
        candidate_id=cid,
        title=cid,
        payload=payload,
        reason="r",
        evidence=[{"source_id": "x", "locator": cid[3:], "snippet": "s"}],
        **extra,
    )


def _ot(cid, code):
    return cand(cid, {"kind": "OBJECT_TYPE", "code": code, "name": code})


def _prop(cid, owner, code, **extra):
    payload = {
        "kind": "PROPERTY",
        "object_type_code": owner,
        "code": code,
        "name": code,
        "data_type": "STRING",
    }
    return cand(cid, payload, **extra)


def _om(cid, owner, key):
    payload = {
        "kind": "OBJECT_MAPPING",
        "object_type_code": owner,
        "source_id": "x",
        "resource_key": key,
        "key_columns": ["id"],
    }
    return cand(cid, payload)


def test_bundling_never_drops_parts_when_codes_or_mappings_repeat():
    from app.proposers.api_format import to_api_proposals

    parts = [
        # erp·mes에 같은 이름의 표가 있으면 객체 유형 코드가 겹친다 → 묶지 않고 따로
        _ot("OT-erp.log", "LOG"),
        _ot("OT-mes.log", "LOG"),
        _prop("P-erp.log.a", "LOG", "a"),
        # 객체 매핑이 둘이면 하나만 묶고 나머지는 따로
        _ot("OT-x.t", "T"),
        _om("OM-x.t", "T", "x.t"),
        _om("OM-x.t2", "T", "x.t2"),
    ]

    api = to_api_proposals(parts)

    kinds = [a["itemKind"] for a in api]
    assert kinds.count("OBJECT_TYPE") == 3
    assert kinds.count("PROPERTY") == 1  # LOG 묶음이 없으니 속성은 따로
    assert kinds.count("OBJECT_MAPPING") == 1
    bundle = next(a for a in api if a["payload"]["code"] == "T")
    assert bundle["payload"]["objectMapping"]["resourceKey"] == "x.t"
    logs = [a for a in api if a["payload"].get("code") == "LOG"]
    assert all("properties" not in a["payload"] for a in logs)


def test_every_rule_sharing_a_link_type_carries_it():
    from app.proposers.api_format import to_api_proposals

    link_type = cand(
        "LT-a",
        {
            "kind": "LINK_TYPE",
            "code": "A_TO_B",
            "name": "A → B",
            "source_type_code": "A",
            "target_type_code": "B",
            "cardinality": "MANY_TO_ONE",
        },
    )

    def rule(cid, prop):
        payload = {
            "kind": "LINK_RULE",
            "code": cid,
            "link_type_code": "A_TO_B",
            "pattern": "same_value",
            "conditions": [{"source_property": f"A.{prop}", "target_property": "B.id"}],
        }
        return cand(cid, payload)

    api = to_api_proposals([link_type, rule("LR-1", "x"), rule("LR-2", "y")])

    assert [a["itemKind"] for a in api] == ["LINK_RULE", "LINK_RULE"]
    assert all(a["payload"]["linkType"]["code"] == "A_TO_B" for a in api)


def test_hold_in_any_part_marks_the_whole_bundle_with_reasons():
    from app.proposers.api_format import to_api_proposals

    head = cand("OT-x.t", {"kind": "OBJECT_TYPE", "code": "T", "name": "t"})
    prop = cand(
        "P-x.t.a",
        {
            "kind": "PROPERTY",
            "object_type_code": "T",
            "code": "a",
            "name": "a",
            "data_type": "STRING",
        },
        review_status="HELD",
        hold_reason="개인정보 확인 필요",
    )
    orphan = cand(
        "P-x.u.b",
        {
            "kind": "PROPERTY",
            "object_type_code": "U",
            "code": "b",
            "name": "b",
            "data_type": "STRING",
        },
    )

    api = to_api_proposals([head, prop, orphan])

    obj = next(a for a in api if a["itemKind"] == "OBJECT_TYPE")
    assert obj["evidence"]["holdRecommended"] is True
    assert obj["evidence"]["holdReason"] == "개인정보 확인 필요"
    # 어느 속성 때문인지 속성별 근거에도 남는다
    part = obj["evidence"]["properties"][0]
    assert part["holdRecommended"] is True
    assert part["holdReason"] == "개인정보 확인 필요"
    assert "objectMapping" not in obj["payload"]  # 객체 매핑 후보가 없으면 칸도 없다
    # 묶을 객체 유형이 없는 속성은 원래 종류로 따로 나간다
    assert [a["itemKind"] for a in api] == ["OBJECT_TYPE", "PROPERTY"]


def test_sample_rows_show_blank_and_differently_shaped_values():
    import pandas as pd

    from app.proposers.semantics import pick_sample_positions

    values = [f"{2608000 + i}" for i in range(100)]
    values[57] = "OD-2609-004"  # 이미 ERP 표기로 적힌 값
    values[83] = None  # 빈 값
    df = pd.DataFrame({"so_ref": values})
    picked = pick_sample_positions(df, 20)
    assert len(picked) == 20
    assert {57, 83} <= set(picked)
    assert picked == pick_sample_positions(df, 20)


def _value_col(table, name, values):
    from app.proposers.links import Column

    return Column(table, name, None, pd.Series(values, dtype=str))


def test_number_groups_link_different_notations():
    from app.proposers.links import _number_groups

    item = _value_col("erp.item", "item_cd", [f"PL-{n:04d}" for n in range(300, 330)])
    part = _value_col("mes.mold", "part_code", [f"pl{n}" for n in range(300, 330)])
    rel = _number_groups(part, item, "primary")
    assert [s["op"] for s in rel.source_steps] == ["EXTRACT_NUMBER", "TO_INTEGER"]
    assert rel.hold is None  # 글자(PL)도 같고 숫자도 같다

    order = _value_col(
        "erp.sales_order", "so_no", [f"SO26-{n:04d}" for n in range(1, 40)]
    )
    ref = _value_col("mes.work_order", "order_ref", [f"26-{n}" for n in range(1, 40)])
    rel = _number_groups(ref, order, "primary")
    assert rel.source_steps[0]["op"] == "MAP_VALUE" and rel.hold  # 대응표라 보류 권고

    receipt = _value_col(
        "erp.goods_receipt", "gr_no", [f"GR2610-{n:03d}" for n in range(1, 40)]
    )
    sales = _value_col(
        "erp.sales_order", "so_no", [f"SO2610-{n:03d}" for n in range(1, 40)]
    )
    assert (
        _number_groups(receipt, sales, "primary") is None
    )  # 다른 문서 번호끼리는 잇지 않는다


def test_sequence_key_needs_name_evidence():
    from app.proposers.links import Relation, _check_sequence_key, name_evidence

    mold = _value_col("mes.mold", "mold_no", [str(n) for n in range(1, 46)])
    worker = _value_col(
        "mes.prod_result", "worker_no", [str(n % 40 + 3) for n in range(100)]
    )
    rel = Relation([worker], [mold])
    _check_sequence_key(rel, worker, mold)
    assert rel.hold  # 작업자 번호가 금형 순번과 우연히 겹친다

    used = _value_col(
        "mes.work_order", "mold_no", [str(n % 45 + 1) for n in range(100)]
    )
    rel = Relation([used], [mold])
    _check_sequence_key(rel, used, mold)
    assert rel.hold is None and name_evidence(used, mold)


def test_single_value_reference_is_held(tmp_path):
    """값이 한 종류뿐인 참조(모든 발주의 담당 부서가 같음)는 우연일 수 있어 보류한다."""
    erp = tmp_path / "erp"
    erp.mkdir()
    tables = {
        "department": (
            ["dept_code"],
            {"dept_code": "varchar(10)", "dept_name": "varchar(50)"},
        ),
        "purchase_order": (["po_no"], {"po_no": "varchar(20)", "dept": "varchar(10)"}),
    }
    (erp / "_schema.json").write_text(
        json.dumps(
            {
                "tables": {
                    name: {
                        "description": name,
                        "primary_key": pk,
                        "columns": {
                            c: {"type": t, "comment": ""} for c, t in cols.items()
                        },
                    }
                    for name, (pk, cols) in tables.items()
                }
            }
        ),
        encoding="utf-8",
    )
    (erp / "department.csv").write_text(
        "dept_code,dept_name\n" + "".join(f"DP{n}0,부서{n}\n" for n in range(1, 6)),
        encoding="utf-8",
    )
    (erp / "purchase_order.csv").write_text(
        "po_no,dept\n" + "".join(f"PO-{n:03d},DP20\n" for n in range(25)),
        encoding="utf-8",
    )
    rules = _rules(run(tmp_path))
    small = rules["purchase_order_dept_to_department"]
    assert small["review_status"] == "HELD" and "1개" in small["hold_reason"]


def test_llm_prompt_shows_registered_table_note():
    from app.models.source import TableSchema
    from app.proposers.semantics import build_prompt

    schema = TableSchema(
        source_id="mes", name="work_order", description="작업지시", note="동의어: 작지"
    )
    assert "표 참고: 동의어: 작지" in build_prompt("mes.work_order", schema, [], [], [])
    schema.note = None
    assert "표 참고" not in build_prompt("mes.work_order", schema, [], [], [])


def _write_source(folder, tables):
    """tables: {표: (기본키, {컬럼: 자료형}, [행])}"""
    folder.mkdir(parents=True)
    schema = {
        name: {
            "description": name,
            "primary_key": pk,
            "columns": {c: {"type": t, "comment": ""} for c, t in cols.items()},
        }
        for name, (pk, cols, _) in tables.items()
    }
    (folder / "_schema.json").write_text(
        json.dumps({"tables": schema}, ensure_ascii=False), encoding="utf-8"
    )
    for name, (_, cols, rows) in tables.items():
        lines = [",".join(cols)] + [",".join(map(str, r)) for r in rows]
        (folder / f"{name}.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")


def test_serial_numbers_with_different_prefixes_are_held_not_pending(tmp_path):
    """'L01' → 'EQ-001'처럼 일련번호만 같은 값은 대기로 내지 않는다."""
    _write_source(
        tmp_path / "erp",
        {
            "customer": (
                ["cust_cd"],
                {"cust_cd": "varchar(10)", "cust_name": "varchar(50)"},
                [(f"C{n:03d}", f"거래처 {n:02d}") for n in range(1, 31)],
            )
        },
    )
    _write_source(
        tmp_path / "mes",
        {
            "equipment": (
                ["eqp_id"],
                {
                    "eqp_id": "varchar(10)",
                    "eqp_name": "varchar(50)",
                    "line_cd": "varchar(5)",
                },
                [
                    (f"EQ-{n:03d}", f"설비 {n:02d}", f"L{n % 9 + 1:02d}")
                    for n in range(1, 41)
                ],
            ),
            "defect_log": (
                ["defect_id"],
                {"defect_id": "int", "eqp_id": "varchar(10)", "defect_count": "int"},
                [(n, f"EQ-{n % 40 + 1:03d}", n % 9 + 1) for n in range(1, 61)],
            ),
        },
    )

    result = run(tmp_path)

    pending = {
        (c["evidence"][0]["locator"], c["evidence"][1]["locator"])
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_TYPE" and c["review_status"] == "PENDING"
    }
    assert pending == {("mes.defect_log.eqp_id", "mes.equipment.eqp_id")}


def test_prefix_swap_and_letters_include_hangul():
    from app.proposers.links import _letters, _prefix_swap

    assert _letters("거래처 01") == "거래처" and _letters("pl-0354") == "PL"
    assert _prefix_swap(
        [{"op": "STRIP_PREFIX", "arg": "L"}, {"op": "ADD_PREFIX", "arg": "EQ-"}]
    ) == ("L", "EQ-")
    # 글자가 같고 표기만 다르면(소문자 pl ↔ PL-) 접두어 바꾸기가 아니다
    assert (
        _prefix_swap(
            [{"op": "STRIP_PREFIX", "arg": "pl"}, {"op": "ADD_PREFIX", "arg": "PL-"}]
        )
        is None
    )


def test_llm_negative_on_pending_link_only_holds_it(data, result):
    from app.llm.client import MockLLMClient
    from app.models.candidate import Candidate
    from app.proposers.semantics import interpret

    candidates = [Candidate.model_validate(c) for c in result["candidates"]]
    pending = next(
        c
        for c in candidates
        if c.candidate_id.startswith("LR-") and c.review_status == "PENDING"
    )
    table = pending.evidence[0].locator.rsplit(".", 1)[0]

    def answer(prompt):
        return {
            "object_name": "x",
            "meaning": "x",
            "category": "master",
            "category_reason": "x",
            "links": [
                {"code": pending.payload.code, "meaningful": False, "reason": "우연"}
            ],
        }

    merged, report = interpret(
        data / "data-t3", candidates, MockLLMClient(answer), only={table}
    )
    by_id = {c.candidate_id: c for c in merged}

    # 규칙이 대기로 둔 관계는 LLM이 반대해도 빼지 않고 보류로만 바꾼다
    assert by_id[pending.candidate_id].review_status == "HELD"
    assert report["rejected_links"] == {}


def test_map_value_table_keeps_source_values_as_is():
    # 대응표 키를 camelCase로 바꾸면 변환에서 '대응표에 없는 값'이 된다
    from app.proposers.api_format import camel_keys, has_snake_keys

    step = {
        "op": "MAP_VALUE",
        "arg": {"in_progress": "진행중", "IN_PROGRESS": "진행중", "done": "완료"},
    }
    out = camel_keys({"transform_steps": [step]})
    assert out == {"transformSteps": [step]}
    assert not has_snake_keys(out)


def test_column_that_is_only_part_of_its_own_key_points_many_to_one(result):
    types = {
        c["candidate_id"].removeprefix("LT-"): c["payload"]
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_TYPE"
    }
    # 발주 상세의 발주 번호·창고별 재고의 품번은 데이터에서 겹치지 않아도 업무상 여러 행이 한 대상을 가리킨다
    assert (
        types["purchase_order_line_po_no_to_purchase_order"]["cardinality"]
        == "MANY_TO_ONE"
    )
    assert types["stock_item_code_to_item"]["cardinality"] == "MANY_TO_ONE"


def test_unmatched_examples_that_are_all_hidden_names_are_not_shown_as_stars(result):
    rules = _rules(result)
    reason = rules["maintenance_order_technician_to_department"]["reason"]
    assert "***" not in reason
    assert "불일치 값은 개인정보라 표시하지 않음" in reason


def test_english_property_name_is_replaced_by_llm_label_only_when_not_korean():
    from app.models.candidate import Candidate, DataType, Evidence, PropertyPayload
    from app.proposers.semantics import TableView, apply

    def renamed(current, label):
        prop = Candidate(
            candidate_id="P-wms.inbound.supplier_biz_no",
            title="t",
            payload=PropertyPayload(
                object_type_code="INBOUND",
                code="supplier_biz_no",
                name=current,
                data_type=DataType.STRING,
                description="",
            ),
            reason="r",
            evidence=[
                Evidence(
                    source_id="wms", locator="wms.inbound.supplier_biz_no", snippet="s"
                )
            ],
        )
        view = TableView(
            object_name="입고 전표",
            meaning="입고 기록",
            category="history",
            category_reason="발생",
            columns=[
                {
                    "name": "supplier_biz_no",
                    "label": label,
                    "meaning": "공급업체 사업자번호",
                    "role": "reference",
                    "data_type": "STRING",
                }
            ],
        )
        return apply([prop], {"wms.inbound": view})[0].payload.name

    assert renamed("supplier tax id", "공급업체 사업자번호") == "공급업체 사업자번호"
    assert renamed("사업자번호", "공급업체 사업자번호") == "사업자번호"
    assert renamed("supplier tax id", None) == "supplier tax id"


def test_event_table_pointing_to_a_primary_key_is_many_to_one_even_if_data_looks_one_to_one():
    from app.models.candidate import (
        Candidate,
        Evidence,
        LinkTypePayload,
        ObjectTypePayload,
    )
    from app.proposers.links import ONE_NOTE, business_cardinality

    ev = [Evidence(source_id="mes", locator="mes.downtime.wo_no", snippet="s")]

    def object_type(code, category):
        return Candidate(
            candidate_id=f"OT-mes.{code.lower()}",
            title="t",
            payload=ObjectTypePayload(code=code, name=code, category=category),
            reason="r",
            evidence=ev,
        )

    def link_type(code, source, reason):
        lt = Candidate(
            candidate_id=f"LT-{code.lower()}",
            title="t",
            payload=LinkTypePayload(
                code=code,
                name="n",
                source_type_code=source,
                target_type_code="WORK_ORDER",
                cardinality="ONE_TO_ONE",
                description="d." + ONE_NOTE,
            ),
            reason="r",
            evidence=ev,
        )
        rule = cand(
            f"LR-{code.lower()}",
            {
                "kind": "LINK_RULE",
                "code": code,
                "link_type_code": code,
                "pattern": "same_value",
                "conditions": [
                    {
                        "source_property": f"{source}.wo_no",
                        "target_property": "WORK_ORDER.wo_no",
                    }
                ],
            },
        ).model_copy(update={"reason": reason})
        return [lt, rule]

    items = [
        object_type("DOWNTIME", "history"),
        object_type("ASSET", "master"),
        *link_type("DOWNTIME_TO_WO", "DOWNTIME", "대상 키: 작업지시 wo_no (기본키)"),
        *link_type("ASSET_TO_WO", "ASSET", "대상 키: 작업지시 wo_no (기본키)"),
        *link_type("LOT_TO_WO", "DOWNTIME", "대상 키: 작업지시 lot_no (유일값)"),
    ]
    out = {
        c.payload.code: c.payload
        for c in business_cardinality(items)
        if c.payload.kind.value == "LINK_TYPE"
    }
    # 발생 기록이 기본키를 가리키면 N:1, 기준 정보나 기본키가 아닌 고유값은 그대로 1:1
    assert out["DOWNTIME_TO_WO"].cardinality == "MANY_TO_ONE"
    assert "(1:1)" not in out["DOWNTIME_TO_WO"].description
    assert out["ASSET_TO_WO"].cardinality == "ONE_TO_ONE"
    assert out["LOT_TO_WO"].cardinality == "ONE_TO_ONE"


def test_llm_prompt_masks_table_description_note_and_comments(result):
    from app.models.candidate import Candidate
    from app.proposers.semantics import build_prompt

    prop = next(
        Candidate.model_validate(c)
        for c in result["candidates"]
        if c["candidate_id"].startswith("P-mes.work_order.")
    )
    prop = prop.model_copy(
        update={
            "payload": prop.payload.model_copy(
                update={"description": "작성자 (김 대리 010-1234-5678)"}
            )
        }
    )
    schema = TableSchema(
        source_id="mes",
        name="work_order",
        description="작업지시 (담당 홍길동님 010.2222.3333)",
        note="문의 이영희 책임 kim@example.com",
    )

    prompt = build_prompt("mes.work_order", schema, [], [prop], [])

    for secret in ("김 대리", "1234", "홍길동", "2222", "이영희", "kim@example.com"):
        assert secret not in prompt, secret
    assert "표 참고: 문의 *** 책임 ***" in prompt


class _UsageLLM:
    """답마다 사용량을 남기는 가짜 LLM. answer가 예외면 사용량을 남긴 뒤 던진다(형식 틀린 답)."""

    model = "gpt-6-luna"

    def __init__(self, answer):
        self.answer = answer
        self.last_usage = None
        self.prompts = []

    def complete_json(self, prompt):
        self.prompts.append(prompt)
        result = self.answer(len(self.prompts))
        if isinstance(result, Exception):
            if not isinstance(result, TypeError):
                self.last_usage = {"input_tokens": 1000, "output_tokens": 200}
            raise result
        self.last_usage = {"input_tokens": 1000, "output_tokens": 200}
        return result


def test_llm_credits_count_answers_in_wrong_format(data, result):
    from app.llm.client import LLMError
    from app.models.candidate import Candidate
    from app.proposers.semantics import interpret

    candidates = [Candidate.model_validate(c) for c in result["candidates"]]
    answers = [LLMError("GMS가 JSON이 아닌 답을 보냈습니다: x"), {"bad": 1}]

    llm = _UsageLLM(lambda n: answers[n - 1])
    _, report = interpret(
        data / "data-t3", candidates, llm, only={"mes.worker", "mes.work_order"}
    )

    # 형식이 틀린 답 두 번도 쓴 토큰(입력 1,000·출력 200 = 2크레딧씩)은 센다
    assert len(report["failed"]) == 2
    assert report["credits_used"] == 4.0


def test_llm_outage_or_unexpected_error_does_not_stop_semantics(data, result):
    from app.llm.client import LLMError
    from app.models.candidate import Candidate
    from app.proposers.semantics import interpret, prepare

    candidates = [Candidate.model_validate(c) for c in result["candidates"]]
    tables = [t for t, *_ in prepare(data / "data-t3", candidates)]
    assert len(tables) >= 5

    # 첫 표에서 예상하지 못한 오류가 나도 그 표만 실패로 두고, 연속 3번 응답이 없으면 남은 표는 부르지 않는다
    def answer(n):
        if n == 1:
            return TypeError("예상하지 못한 답")
        return LLMError("GMS 응답이 없습니다: TimeoutError")

    llm = _UsageLLM(answer)
    merged, report = interpret(data / "data-t3", candidates, llm)

    assert len(llm.prompts) == 4
    assert len(report["failed"]) == len(tables)
    assert report["failed"][0] == {"table": tables[0], "error": "예상하지 못한 답"}
    assert all("남은 호출을 건너뜀" in f["error"] for f in report["failed"][4:])
    assert len(merged) == len(candidates)


def test_register_with_own_name_is_master_and_status_with_dates_is_transaction():
    # 참조되지 않고 일자가 있어도 자기 이름 칸(자산명)이 있으면 대장(기준 정보), 상태·일자 여럿이면 거래
    cols = [
        ("asset_no", "varchar(10)"),
        ("asset_name", "varchar(20)"),
        ("acquired_on", "date"),
    ]
    analysis = [
        _col("asset_no", "varchar(10)"),
        _col("asset_name", "varchar(20)", "자산명"),
        _col("acquired_on", "date", "취득일"),
    ]
    values = {
        "asset_no": ["A1"],
        "asset_name": ["프레스"],
        "acquired_on": ["2026-01-01"],
    }
    kind, reason = classify(
        _schema(["asset_no"], *cols).model_copy(update={"name": "fixed_asset"}),
        analysis,
        values,
        set(),
        set(),
    )
    assert kind == "master" and "asset_name" in reason

    cols = [
        ("mo_no", "varchar(10)"),
        ("status", "varchar(10)"),
        ("planned", "date"),
        ("done", "date"),
    ]
    analysis = [_col(n, t) for n, t in cols]
    values = {n: ["2026-01-01"] if t == "date" else ["a"] for n, t in cols}
    assert (
        classify(_schema(["mo_no"], *cols), analysis, values, set(), set())[0]
        == "transaction"
    )

    # 이름 칸이 담당자 이름이면 대장으로 보지 않는다
    cols = [
        ("log_id", "varchar(10)"),
        ("manager_name", "varchar(20)"),
        ("logged", "date"),
    ]
    analysis = [_col(n, t) for n, t in cols]
    values = {"log_id": ["L1"], "manager_name": ["x"], "logged": ["2026-01-01"]}
    assert (
        classify(_schema(["log_id"], *cols), analysis, values, set(), set())[0]
        == "history"
    )


def test_record_tables_with_copied_names_stay_records():
    # 기록·실적 표의 이름 칸(불량명·공정명)이나 다른 것의 이름(원료명)은 대장 근거가 아니다
    from app.proposers.objects import _own_name_column

    def cols(*names_and_comments):
        return [_col(n, "varchar(20)", c) for n, c in names_and_comments]

    assert _own_name_column("defect_log", cols(("defect_name", "불량명"))) is None
    assert _own_name_column("mes_batch_step", cols(("step_nm", "공정명"))) is None
    assert (
        _own_name_column(
            "raw_material_receiving_inspection", cols(("raw_material_name", "원료명"))
        )
        is None
    )
    assert _own_name_column("work_log", cols(("operator_name", "작업자명"))) is None
    # 대장은 표의 주체 낱말과 같은 이름 칸이 있다
    assert (
        _own_name_column("fixed_asset", cols(("asset_name", "자산명"))) == "asset_name"
    )
    assert _own_name_column("eqp_mst", cols(("eqp_nm", "설비명"))) == "eqp_nm"
    assert _own_name_column("equipment", cols(("name", "설비 이름"))) == "name"
