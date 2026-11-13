import json

import pytest

from app.analyzers.structured import (
    _digits_comparison_makes_sense,
    _shape,
    analyze_sources,
    is_personal,
    value_pattern,
)
from app.dataset.build import generate


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t3", root=root)
    return root


@pytest.fixture(scope="module")
def analysis(data):
    return analyze_sources(data / "data-t3")


def _link(analysis, from_ref, to_ref):
    return next(
        (l for l in analysis.links if l.from_ref == from_ref and l.to_ref == to_ref),
        None,
    )


def test_finds_38_gold_column_relations(data, analysis):
    # 고정 분석기 기준값 (docs/evaluation.md 평가 결과). 분석기를 바꾸면 이 숫자도 다시 기록한다.
    gold = json.loads((data / "gold-t3" / "ontology.json").read_text(encoding="utf-8"))
    found = {(l.from_ref, l.to_ref) for l in analysis.links}
    found |= {(t, f) for f, t in found}
    pairs = [
        (lt["via"]["from"], lt["via"]["to"])
        for lt in gold["link_types"]
        if lt.get("via")
        and isinstance(lt["via"]["from"], str)
        and not lt["via"]["from"].endswith(".content")
    ]
    # 원천 정책의 금지 표 관계(근태 → 직원, 고정자산 → 설비)는 정답에서 빠져 40에서 38이 됐다
    assert sum(pair in found for pair in pairs) == 38


def test_order_reference_needs_number_extraction(analysis):
    link = _link(analysis, "mes.work_order.so_ref", "erp.sales_order.so_no")
    assert link.rule_type == "transform"
    assert (
        link.source_transforms
        == link.target_transforms
        == [{"op": "EXTRACT_NUMBER", "arg": None}]
    )
    assert link.from_distinct - link.matched_distinct == 2  # ERP에 없는 수주 번호


def test_asset_typo_stays_unmatched(analysis):
    link = _link(analysis, "erp.fixed_asset.equipment_code", "mes.equipment.code")
    assert link.rule_type == "same_value"
    assert link.unmatched_examples == ["P2O1"]  # 숫자 0 대신 영문 O


def test_composite_keys(analysis):
    keys = {t: [k.columns for k in ks] for t, ks in analysis.table_keys.items()}
    assert keys["mes.work_order_operation"] == [["wo_no", "op_seq"]]
    assert keys["erp.stock"] == [["warehouse", "item_code"]]


def test_personal_columns_are_masked(analysis):
    columns = {c.ref: c for cols in analysis.tables.values() for c in cols}
    assert columns["mes.worker.name"].is_personal
    assert set(columns["mes.worker.name"].sample_values) == {"***"}
    assert not columns["mes.equipment.name"].is_personal


def test_personal_detection_rules():
    assert is_personal("emp_name", "이름(가명)")
    assert is_personal("contact", "담당자 전화번호")
    assert not is_personal("equip_name", "설비 이름")


def test_value_pattern():
    assert value_pattern("OD-2608-012") == "AA-0000-000"
    assert value_pattern("1라인") == "0가가"


def test_digits_comparison_guard():
    assert _digits_comparison_makes_sense(["2608012", "2608013"], ["OD-2608-012"])
    assert not _digits_comparison_makes_sense(
        ["CU0001", "CU0002"], ["FA-0001", "FA-0002"]
    )


def test_shape_needs_common_prefix_and_width():
    assert _shape(["GP0007", "GP0031", "GP0101"]) == ("GP", 4)
    assert _shape(["GP0007", "EM0031"]) is None


def test_values_merged_by_transform_are_reported(analysis):
    # 수주 참조는 2610021과 OD-2610-021 두 표기가 섞여 있다
    link = next(l for l in analysis.links if l.from_ref == "mes.work_order.so_ref")
    assert link.merged_examples
    for group in link.merged_examples:
        assert len(set(group)) == len(group) > 1
