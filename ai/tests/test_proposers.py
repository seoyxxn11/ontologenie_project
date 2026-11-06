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


def test_proposals_cover_tables_and_columns_with_rule_method(data, result):
    kinds = result["summary"]["by_kind"]
    assert (
        kinds["OBJECT_TYPE"] == kinds["OBJECT_MAPPING"] == 31
    )  # 변경 이력 표 2개는 객체가 아님
    assert all(c["method"] == "rule" for c in result["candidates"])
    # 값·키·이름 근거를 다 더해도 0.8을 넘지 않고, 보류 후보는 상한 0.49
    assert all(0 < c["ai_confidence"] <= 0.8 for c in result["candidates"])
    assert all(
        c["ai_confidence"] <= 0.49
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
    # 부서 이름은 값 모양이 제각각이어도 다른 유일 키로 본다. 값이 1종뿐이라 보류
    buyer = rules["purchase_order_buyer_to_department"]
    assert (
        buyer["payload"]["conditions"][0]["target_property"] == "DEPARTMENT.dept_name"
    )
    assert buyer["review_status"] == "HELD"
    # 이름에 표·컬럼 이름이 다 들어간 종류 관계: 도착이 키가 아니어도 잇는다
    kind = rules["routing_equipment_kind_to_equipment"]
    assert kind["payload"]["conditions"][0]["target_property"] == "EQUIPMENT.kind"
    types = {
        c["candidate_id"].removeprefix("LT-"): c
        for c in result["candidates"]
        if c["payload"]["kind"] == "LINK_TYPE"
    }
    # 작업지시를 거쳐야 이어지는 관계는 연결 규칙(두 객체 속성 비교)으로 표현할 수 없어 관계 유형만 보류로 낸다
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
    # 개인정보 컬럼 값은 프롬프트에 들어가지 않는다
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
        if "표: erp.journal_entry " in prompt:
            return {
                "object_name": "분개 줄",
                "meaning": "회계 분개",
                "category": "detail",
                "category_reason": "전표 번호와 줄 번호",
                "links": [
                    {
                        "code": "JOURNAL_ENTRY_LINE_NO_TO_FIXED_ASSET",
                        "meaningful": False,
                        "reason": "줄 번호는 자산 번호가 아니다",
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
    assert worker.llm_judgement and "master" in worker.llm_judgement
    # LLM 동의는 점수에 더하지 않는다
    assert worker.ai_confidence == by_id_rule["OT-mes.worker"].ai_confidence
    agreed = by_id["LR-worker_id_to_employee"]
    assert agreed.ai_confidence == by_id_rule["LR-worker_id_to_employee"].ai_confidence
    wrong = by_id["LR-journal_entry_line_no_to_fixed_asset"]
    assert wrong.review_status == "HELD" and wrong.ai_confidence <= 0.49
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
    # 105 proposals[] 형식: 저장 칸 다섯 개만 쓰고 나머지는 evidence 안에 둔다
    assert set(api[0]) == {"itemKind", "payload", "evidence", "aiConfidence", "options"}
    assert {"reason", "items", "holdRecommended"} <= set(api[0]["evidence"])
    types = [a["payload"]["code"] for a in api if a["itemKind"] == "OBJECT_TYPE"]
    assert types and all(t == t.upper() for t in types)  # 객체 유형 코드는 대문자
    # 속성 매핑도 객체 매핑과 같은 resourceKey(source_resource.resource_key 형식)로 표를 가리킨다
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
    # 컬럼 단위 후보는 객체 후보 안으로 들어가 따로 나가지 않는다
    assert not kinds & {"PROPERTY", "OBJECT_MAPPING", "PROPERTY_MAPPING"}
    objects = {a["payload"]["code"]: a for a in api if a["itemKind"] == "OBJECT_TYPE"}
    assert len(objects) == sum(c.payload.kind.value == "OBJECT_TYPE" for c in kept)
    # 속성·속성 매핑 하나도 빠지지 않고, 각 속성에 원천 컬럼이 붙는다
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
        # 속성마다 근거가 남아 원천에서 다시 확인한 값을 볼 수 있다
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
        assert link_type == to_api(types[link_type["code"]])["payload"]
    # 규칙이 없는 관계 유형만 따로 남는다
    alone = {a["payload"]["code"] for a in api if a["itemKind"] == "LINK_TYPE"}
    assert alone == set(types) - {r.payload.link_type_code for r in rules}
    # 묶어도 보류 권고 수는 관계 기준으로 그대로다
    held_rules = sum(
        r.review_status == "HELD"
        or types[r.payload.link_type_code].review_status == "HELD"
        for r in rules
    )
    assert sum(a["evidence"]["holdRecommended"] for a in bundled) == held_rules
    assert len(api) < len(kept) / 3  # 검토 건수가 크게 준다


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
    """B2(2026-11-04): 'L01' → 'EQ-001', '거래처 01' → '설비 01'처럼 일련번호만 같은 값은 대기로 내지 않는다."""
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
    # 진짜 관계(불량 기록의 설비 → 설비)만 대기로 남는다
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
