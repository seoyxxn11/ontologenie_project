"""조회 그룹 기본값과 민감 분류 기준(S15P31C106-392)."""

import pytest

from app.proposers.access import (
    PRODUCTION,
    SALES_SUPPLY,
    access_group,
    is_free_text,
    sensitivity_category,
)

# 2026-11-13 운영 지정. 금지 표(erp.attendance·erp.fixed_asset)는 분석하지 않으므로 뺐다.
OPERATED = {
    PRODUCTION: [
        "erp.department",
        "erp.employee",
        "mes.defect",
        "mes.die",
        "mes.downtime",
        "mes.equipment",
        "mes.handover_note",
        "mes.inspection",
        "mes.maintenance_order",
        "mes.production_result",
        "mes.routing",
        "mes.sensor_reading",
        "mes.worker",
        "mes.work_order",
        "mes.work_order_operation",
    ],
    SALES_SUPPLY: [
        "erp.goods_receipt",
        "erp.item",
        "erp.purchase_order",
        "erp.purchase_order_line",
        "erp.sales_order",
        "erp.sales_order_line",
        "erp.shipment",
        "erp.stock",
        "erp.vendor",
        "mes.material_issue",
    ],
}


@pytest.mark.parametrize(
    "resource_key, group",
    [(key, group) for group, keys in OPERATED.items() for key in keys],
)
def test_default_group_matches_operated_assignment(resource_key, group):
    assert access_group(resource_key)[0] == group


def test_unknown_or_mixed_area_is_left_empty_with_reason():
    group, why = access_group("erp.ledger")
    assert group is None and "비공개" in why
    group, why = access_group("erp.item_inspection")
    assert group is None and "함께" in why


def test_mes_tables_default_to_production():
    assert access_group("mes.unknown_table")[0] == PRODUCTION


@pytest.mark.parametrize(
    "resource_key, column, category",
    [
        ("mes.worker", "skill_level", "평가"),
        ("erp.employee", "position", "인사 속성"),
        ("erp.employee", "hire_date", "인사 속성"),
        ("erp.employee", "phone_no", "사람을 알아볼 수 있는 값"),
        ("mes.worker", "shift", None),
        ("erp.employee", "dept_code", None),
        # 사람 표가 아니면 낱말이 같아도 사람 평가가 아니다
        ("mes.inspection", "score", None),
        ("erp.vendor", "rating", None),
        # 연결 키일 수 있는 코드·번호 칸과 기계 주소는 보지 않는다
        ("erp.employee", "position_code", None),
        ("mes.worker", "skill_id", None),
        ("erp.employee", "ip_address", None),
        ("erp.employee", "email", "사람을 알아볼 수 있는 값"),
    ],
)
def test_sensitivity_category_only_in_person_tables(resource_key, column, category):
    assert sensitivity_category(resource_key, column) == category


def test_free_text_is_flagged_not_restricted():
    assert is_free_text("content", None)
    assert is_free_text("remark", None)
    assert is_free_text("etc", "비고")
    assert not is_free_text("status", "상태")


def test_group_default_does_not_change_fingerprint():
    from app.proposers.api_format import fingerprint_payload

    payload = {"code": "ITEM", "properties": []}
    assert fingerprint_payload(payload) == fingerprint_payload(
        {**payload, "accessGroup": SALES_SUPPLY}
    )


def test_demo_proposals_carry_group_default_and_sensitivity_reasons():
    from pathlib import Path

    from app.proposers.run import api_result, generate

    data = Path(__file__).resolve().parents[1] / "data" / "data-v1.0"
    proposals = api_result(generate(data)[0])["proposals"]
    bundles = {
        p["payload"]["objectMapping"]["resourceKey"]: p
        for p in proposals
        if p["itemKind"] == "OBJECT_TYPE" and p["payload"].get("objectMapping")
    }
    assert bundles["erp.vendor"]["payload"]["accessGroup"] == SALES_SUPPLY
    assert bundles["mes.equipment"]["payload"]["accessGroup"] == PRODUCTION
    assert "조회 그룹: 영업·구매·재고" in bundles["erp.vendor"]["evidence"]["reason"]

    worker = bundles["mes.worker"]
    skill = next(
        p for p in worker["payload"]["properties"] if p["code"] == "skill_level"
    )
    assert skill["sensitivity"] == "RESTRICTED"
    reasons = {
        part["code"]: part["reason"] for part in worker["evidence"]["properties"]
    }
    assert "민감 (" in reasons["skill_level"]
