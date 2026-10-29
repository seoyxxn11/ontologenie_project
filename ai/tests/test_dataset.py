import csv
import itertools
import json
from datetime import datetime, timedelta
from decimal import Decimal

import pytest

from app.dataset import schema
from app.dataset.build import SO_MAIN, SO_RISK, WO_A, WO_C, generate
from app.dataset.checks import EXPECTED
from app.validators.gold_check import check_gold


@pytest.fixture(scope="module")
def out(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t2", root=root)
    return root


def _rows(out, key):
    source, table = key.split(".")
    with (out / "data-t2" / source / f"{table}.csv").open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _gold(out, name):
    return json.loads((out / "gold-t2" / name).read_text(encoding="utf-8"))


def test_twenty_nine_table_structure_plus_operation_and_change_logs():
    erp = [t for t in schema.TABLES if t.source == "erp" and t.name != "change_log"]
    mes = [
        t
        for t in schema.TABLES
        if t.source == "mes"
        and t.name not in ("change_log", "work_order_operation", "handover_note")
    ]
    # 기업 예시 구성(ERP 17 · MES 12)에 공정 배정·인수인계 메모·변경 이력 표를 더했다
    assert (len(erp), len(mes)) == (17, 12)


def test_comments_do_not_name_other_tables():
    for t in schema.TABLES:
        for _, _, comment in t.columns:
            assert (
                "→" not in comment and "erp_" not in comment and "mes_" not in comment
            )


def test_gold_format_and_nine_integrity_checks(out):
    assert check_gold(out / "gold-t2") == []
    assert {
        c["check_id"]: c["verdict"] for c in _gold(out, "integrity_checks.json")
    } == EXPECTED


def test_primary_keys_are_unique(out):
    for t in schema.TABLES:
        keys = [tuple(r[c] for c in t.primary_key) for r in _rows(out, t.key)]
        assert len(keys) == len(set(keys)), t.key


def test_five_scenario_data_events(out):
    wos = {r["wo_no"]: r for r in _rows(out, "mes.work_order")}
    ops = {(r["wo_no"], r["op_seq"]): r for r in _rows(out, "mes.work_order_operation")}
    assert wos[WO_A]["so_ref"] == SO_MAIN[3:7] + SO_MAIN[8:]
    assert (
        ops[(WO_A, "20")]["equipment_code"]
        == ops[(WO_A, "30")]["equipment_code"]
        == "P103"
    )
    downtime = next(
        r
        for r in _rows(out, "mes.downtime")
        if r["equipment_code"] == "P103" and not r["ended_at"]
    )
    assert downtime["reason_code"] == "BRK"
    routing = {(r["product_code"], r["op_seq"]): r for r in _rows(out, "mes.routing")}
    assert routing[("BRK-210R", "20")]["min_tonnage"] == "400"
    assert (
        wos[WO_C]["so_ref"] == SO_RISK[3:7] + SO_RISK[8:]
        and wos[WO_C]["status"] == "대기"
    )


def test_scenario_answer(out):
    s4 = _gold(out, "scenario_answers.json")["S4_P103_재계획"]
    assert s4["대체_설비"] == {"P101": "가능", "P201": "불가 (300톤 < 400톤)"}
    assert s4["계산"]["가공_시간"] == 17.9 and s4["계산"]["납기_내"]
    # 교대·준비 시간·대기열을 반영해도 납기(11/4) 안. 느린 경우(1.3배)도 마찬가지
    schedule = s4["교대_반영_일정"]
    assert schedule["표준"]["납기_내"] and schedule["느린_경우_1.3배"]["납기_내"]


def test_history_is_consistent(out):
    results, defects = _rows(out, "mes.production_result"), _rows(out, "mes.defect")
    a3 = _gold(out, "manifest.json")["cases"]["A3_wo"]
    for wo in _rows(out, "mes.work_order"):
        if wo["status"] != "완료" or wo["wo_no"] == a3:
            continue
        ng = sum(int(r["ng_qty"]) for r in results if r["wo_no"] == wo["wo_no"])
        assert (
            int(wo["defect_qty"])
            == ng
            == sum(int(r["qty"]) for r in defects if r["wo_no"] == wo["wo_no"])
        )
    shipments = _rows(out, "erp.shipment")
    for line in _rows(out, "erp.sales_order_line"):
        shipped = sum(
            Decimal(s["qty"])
            for s in shipments
            if s["so_no"] == line["so_no"] and s["item_code"] == line["item_code"]
        )
        assert Decimal(line["shipped_qty"]) == shipped


def test_sixty_notes_with_exact_spans_and_real_identifiers(out):
    # 메모 원문은 MES 표(mes.handover_note.content)에만 있다
    notes = {r["note_id"]: r["content"] for r in _rows(out, "mes.handover_note")}
    assert len(notes) == 60
    by_id = {
        a["doc_id"]: a
        for a in _gold(out, "note_annotations.json")
        if a["source"] == "note"
    }
    for doc_id, text in notes.items():
        assert by_id[doc_id]["text"] == text
        assert all(
            text[e["start"] : e["end"]] == e["text"] for e in by_id[doc_id]["entities"]
        )
    breakdown = {e["text"] for e in by_id["HN-261029-N"]["entities"]}
    assert {"3호기", "RP-26-041", WO_A} <= breakdown
    assert all(
        e["resolution"]["status"] == "ambiguous"
        for d in by_id.values()
        for e in d["entities"]
        if e["text"] == "1호기"
    )


def test_same_seed_same_files(out, tmp_path):
    generate("t2", root=tmp_path)
    for kind in ("data-t2", "gold-t2", "scenario-t2"):
        first = {
            p.relative_to(out / kind): p.read_bytes()
            for p in (out / kind).rglob("*")
            if p.is_file()
        }
        second = {
            p.relative_to(tmp_path / kind): p.read_bytes()
            for p in (tmp_path / kind).rglob("*")
            if p.is_file()
        }
        assert first == second


def test_sample_has_no_gold_information(out):
    for path in (out / "data-t2").rglob("*"):
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            assert (
                "planted" not in text
                and "review_note" not in text
                and "R-SO" not in text
            )


def test_equipment_is_never_double_booked_or_working_while_down(out):
    results = _rows(out, "mes.production_result")
    by_equipment = {}
    for r in results:
        by_equipment.setdefault(r["equipment_code"], []).append(
            (r["started_at"], r["ended_at"])
        )
    for spans in by_equipment.values():
        spans.sort()
        assert all(a[1] <= b[0] for a, b in itertools.pairwise(spans))
    for d in _rows(out, "mes.downtime"):
        end = d["ended_at"] or "2026-10-30 14:00:00"
        assert not any(
            d["started_at"] <= s and e <= end
            for s, e in by_equipment.get(d["equipment_code"], [])
        )


def test_work_order_times_and_stock_follow_the_records(out):
    results = _rows(out, "mes.production_result")
    for wo in _rows(out, "mes.work_order"):
        mine = [r for r in results if r["wo_no"] == wo["wo_no"]]
        if not mine:
            continue
        assert wo["actual_start"] == min(r["started_at"] for r in mine)
        if wo["status"] == "완료":
            assert wo["actual_end"] == max(r["ended_at"] for r in mine)
    for s in _rows(out, "erp.stock"):
        assert Decimal(s["qty"]) >= 0
        assert Decimal(s["available_qty"]) == Decimal(s["qty"]) - Decimal(
            s["reserved_qty"]
        )


def test_workers_with_results_were_at_work(out):
    present = {
        (a["emp_no"], a["work_date"])
        for a in _rows(out, "erp.attendance")
        if a["work_type"] == "근무"
    }
    for r in _rows(out, "mes.production_result"):
        started = datetime.fromisoformat(r["started_at"])
        # 야간 새벽에 시작한 실적은 전날 시작한 야간 교대 몫
        day = (started - timedelta(hours=8)).date().isoformat()
        emp = f"GP{int(r['worker_id']):04d}"
        if r["worker_id"] == "57":  # 직원 표에 없는 작업자(검사 K2)
            continue
        assert (emp, day) in present


def test_gold_matches_source_values(out):
    """정답의 표·컬럼·키·관계가 원천에 있고, 안 이어지는 값은 모두 정답에 기록된 사례다."""
    from app.validators.gold_source_check import check

    report = check(out / "data-t2", out / "gold-t2")
    assert report["errors"] == []
    assert report["unexplained_unmatched"] == []
    composite = [r for r in report["relations"] if len(r["from"]) > 1]
    conditional = [r for r in report["relations"] if r.get("condition")]
    assert len(composite) == 7 and len(conditional) == 2
    assert all(r["coverage"] == 1 for r in composite + conditional)
