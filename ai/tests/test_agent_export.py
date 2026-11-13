import csv
import json
from collections import Counter

import pytest

from app.dataset.agent_export import (
    DEFAULT_OUT,
    EXCLUDED_TABLES,
    GOLD_DIR,
    SOURCE_DIR,
    build,
    decode,
    display_name,
    key_text,
    typed_value,
)


@pytest.fixture(scope="module")
def data():
    return build()


def _rows(table):
    source, name = table.split(".")
    with (SOURCE_DIR / source / f"{name}.csv").open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _links(data, code):
    return [link for link in data["links"] if link["linkType"] == code]


def test_object_count_matches_source_rows_for_each_type(data):
    counts = Counter(o["objectType"] for o in data["objects"])

    assert len(data["objectTypes"]) == 25  # 회계 4개와 금지 표 2개(근태·고정자산)를 뺌
    for object_type in data["objectTypes"]:
        assert counts[object_type["code"]] == len(_rows(object_type["sourceResource"]))


def test_accounting_tables_and_their_links_are_left_out(data):
    excluded_types = {"SalesInvoice", "PurchaseInvoice", "GlAccount", "JournalEntry"}

    assert not {t["sourceResource"] for t in data["objectTypes"]} & EXCLUDED_TABLES
    assert not {o["objectType"] for o in data["objects"]} & excluded_types
    for link_type in data["linkTypes"]:
        assert link_type["sourceType"] not in excluded_types
        assert link_type["targetType"] not in excluded_types


def test_scenario_links_are_present(data):
    p103_downtimes = [
        link
        for link in _links(data, "downtime_equipment_code")
        if link["target"]["sourceKey"] == {"code": "P103"}
    ]
    so_links = {
        link["source"]["sourceKey"]["wo_no"]: link["target"]["sourceKey"]["so_no"]
        for link in _links(data, "work_order_so_ref")
    }

    assert len(p103_downtimes) == 10  # S3 비가동 10건
    assert so_links["JB2610-0141"] == "OD-2610-021"  # S4 R-SO 변환 연결
    assert so_links["JB2610-0145"] == "OD-2610-019"  # S4 납기 위험 수주
    # S3의 ERP 장부가(고정자산)는 원천 정책의 금지 표라 데이터에 없다
    assert not _links(data, "fixed_asset_equipment_code")


def test_lot_without_inspection_is_unlinked_with_reason(data):
    linked_lots = {
        link["source"]["sourceKey"]["ship_no"]
        for link in _links(data, "shipment_lot_no")
    }
    unlinked = [
        item
        for item in data["expectedUnlinked"]
        if item["source"]["sourceKey"] == {"ship_no": "DL2610-0054"}
    ]

    assert "DL2610-0054" not in linked_lots  # P3 검사 없음 → 데이터 없음
    assert unlinked[0]["value"] == "LT261025-2"
    assert unlinked[0]["reason"] == "아직 최종 검사 기록이 없는 로트"


def test_every_unlinked_value_has_a_reason(data):
    # 정답 값 목록(id_links)이나 관계의 known_unmatched에서 이유를 찾는다
    assert all(item["reason"] for item in data["expectedUnlinked"])


def test_rule_links_agree_with_gold_value_list(data):
    gold = json.loads((GOLD_DIR / "id_links.json").read_text(encoding="utf-8"))
    rules = {t["code"]: t["goldRule"] for t in data["linkTypes"]}
    linked, pairs = {}, {}
    for link in data["links"]:
        rule = rules[link["linkType"]]
        if rule:
            raw = link["evidence"]["rawValues"]["1"]
            linked.setdefault(rule, set()).add(raw)
            pairs.setdefault(rule, set()).add(
                (raw, link["evidence"]["targetRawValues"]["1"])
            )
    unlinked = {(i["goldRule"], i["value"]) for i in data["expectedUnlinked"]}

    for item in gold:
        if item["rule_id"] == "R-ALIAS" or item["from_value"] == "":
            continue  # 메모 글 관계는 내보내지 않고, 빈 값은 연결 대상이 아니다
        key = (item["rule_id"], item["from_value"])
        if item["status"] == "matched":
            assert item["from_value"] in linked[item["rule_id"]], key
            # 도착 대상도 정답 값과 같아야 한다
            assert (item["from_value"], item["to_value"]) in pairs[item["rule_id"]], key
        else:
            assert item["from_value"] not in linked.get(item["rule_id"], set()), key
            assert key in unlinked, key


def test_link_ends_point_to_exported_objects(data):
    keys = {
        (o["objectType"], json.dumps(o["sourceKey"], sort_keys=True))
        for o in data["objects"]
    }

    for link in data["links"]:
        for end in (link["source"], link["target"]):
            assert (
                end["objectType"],
                json.dumps(end["sourceKey"], sort_keys=True),
            ) in keys


def test_rule_links_carry_backend_evidence_shape(data):
    rule_codes = {r["code"] for r in data["rules"]}
    link = _links(data, "work_order_so_ref")[0]

    assert set(link["evidence"]) == {
        "ruleCode",
        "revision",
        "matchedValues",
        "rawValues",
        "targetRawValues",
    }
    assert link["evidence"]["ruleCode"] in rule_codes
    for link in data["links"]:
        assert (link["origin"] == "RULE") == ("ruleCode" in link["evidence"])


def test_path_links_without_a_rule_are_manual(data):
    manual = {t["code"] for t in data["linkTypes"] if t["linkOrigin"] == "MANUAL"}
    ruled = {r["linkType"] for r in data["rules"]}

    assert manual == {
        "work_order_operation_wo_no_op_seq_routing",
        "production_result_wo_no_op_seq_routing",
        "defect_wo_no_op_seq_routing",
    }
    assert not manual & ruled


def test_link_type_names_are_korean_and_unique(data):
    names = [t["name"] for t in data["linkTypes"]]

    assert len(names) == len(set(names))
    assert "구매 발주 → 거래처" in names


def test_personal_properties_are_restricted_and_never_display(data):
    restricted = {
        (p["objectType"], p["code"])
        for p in data["properties"]
        if p["sensitivity"] == "RESTRICTED"
    }

    assert restricted == {
        ("Vendor", "contact_name"),
        ("Worker", "skill_level"),
        ("MaintenanceOrder", "technician"),
    }
    for object_type in data["objectTypes"]:
        assert (object_type["code"], object_type["displayProperty"]) not in restricted


def test_access_groups_follow_the_agreed_split(data):
    groups = {t["code"]: t["accessGroup"] for t in data["objectTypes"]}

    assert groups["Equipment"] == "PRODUCTION"
    assert groups["SalesOrder"] == "SALES_SUPPLY"
    assert "FixedAsset" not in groups and "Attendance" not in groups  # 금지 표
    assert groups["Employee"] == "HR"


def test_scenario_values_use_backend_notation(data):
    p103 = next(
        o
        for o in data["objects"]
        if o["objectType"] == "Equipment" and o["sourceKey"] == {"code": "P103"}
    )
    vibration = next(
        o
        for o in data["objects"]
        if o["objectType"] == "SensorReading"
        and o["sourceKey"]
        == {
            "equipment_code": "P103",
            "measured_at": "2026-10-28T09:00:00",
            "metric": "vibration_mm_s",
        }
    )

    # 표시 이름은 백엔드처럼 대표 속성 값, 없으면 원천 키를 " / "로 잇는다
    assert p103["displayName"] == "1라인 3호 프레스"
    assert vibration["displayName"] == "P103 / 2026-10-28T09:00:00 / vibration_mm_s"
    assert vibration["properties"]["value"] == "4.90"  # P2 진동 상한 초과
    assert vibration["properties"]["measured_at"] == "2026-10-28T09:00+09:00"


@pytest.mark.parametrize(
    ("raw", "data_type", "expected"),
    [
        ("", "STRING", None),
        ("42", "INTEGER", 42),
        ("830000000.0", "DECIMAL", "830000000.0"),
        ("2026-10-30", "DATE", "2026-10-30"),
        ("08:00:00", "TIME", "08:00"),
        ("08:00:30", "TIME", "08:00:30"),
        ("2026-10-30 14:00:00", "DATETIME", "2026-10-30T14:00+09:00"),
        ("2026-10-30 14:00:05", "DATETIME", "2026-10-30T14:00:05+09:00"),
    ],
)
def test_typed_value_matches_backend_conversion(raw, data_type, expected):
    assert typed_value(raw, data_type) == expected


def test_build_is_repeatable(data):
    assert build() == data


def test_committed_file_is_current(data):
    # CI는 ai/ 폴더만 연결해 백엔드 파일이 없다. 저장소 전체가 있는 로컬에서 확인한다.
    if not DEFAULT_OUT.exists():
        pytest.skip("백엔드 시험 데이터 파일이 없는 환경")
    # 압축 바이트는 zlib 구현에 따라 다를 수 있어 내용만 비교한다
    committed = decode(DEFAULT_OUT.read_bytes())
    assert committed == json.loads(json.dumps(data, ensure_ascii=False))


@pytest.mark.parametrize(
    ("raw", "data_type", "expected"),
    [
        ("WO-0001", "STRING", "WO-0001"),
        ("10", "INTEGER", "10"),
        ("2026-10-30", "DATE", "2026-10-30"),
        ("08:00:00", "TIME", "08:00:00"),
        ("2026-10-28 09:00:00", "DATETIME", "2026-10-28T09:00:00"),
    ],
)
def test_key_text_matches_backend_source_key_text(raw, data_type, expected):
    assert key_text(raw, data_type) == expected


def test_display_name_falls_back_to_source_key_and_is_cut():
    assert display_name({"name": "프레스"}, "name", {"code": "P1"}) == "프레스"
    assert display_name({"name": None}, "name", {"a": "X", "b": "1"}) == "X / 1"
    assert display_name({}, None, {"a": "가" * 301}) == "가" * 300
