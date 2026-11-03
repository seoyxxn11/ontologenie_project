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
