"""정답셋. 형식은 docs/dataset.md 6절. 아래 두 파일도 함께 만든다.

- integrity_checks.json: 정합성 검사 9건의 기대 판정과 근거 값
- scenario_answers.json: 시나리오·페르소나 질문의 기대 답과 계산 근거
"""

import json
import math
import re
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from app.dataset import schema
from app.dataset.checks import worker_to_employee
from app.dataset.notes import Mention
from app.validators.gold_check import check_gold

SKIP = {"erp.change_log", "mes.change_log"}


@dataclass
class Recorder:
    id_links: list[dict] = field(default_factory=list)
    issues: list[dict] = field(default_factory=list)
    planted: list[dict] = field(default_factory=list)

    def link(
        self,
        rule_id,
        from_value,
        to_value,
        status,
        row_keys,
        reason="",
        candidates=(),
        planted_id=None,
    ):
        self.id_links.append(
            {
                "rule_id": rule_id,
                "from_value": from_value,
                "to_value": to_value,
                "status": status,
                "candidates": list(candidates),
                "reason": reason,
                "planted_id": planted_id,
                "row_keys": list(row_keys),
            }
        )

    def issue(self, kind, column, row_key, raw_value, rule, reason, planted_id):
        self.issues.append(
            {
                "issue_id": f"G-I{len(self.issues) + 1}",
                "kind": kind,
                "column": column,
                "row_key": row_key,
                "raw_value": raw_value,
                "rule": rule,
                "reason": reason,
                "planted_id": planted_id,
            }
        )

    def plant(self, case_id, description, location, rows=(), values=None):
        self.planted.append(
            {
                "case_id": case_id,
                "description": description,
                "location": location,
                "rows": list(rows),
                "values": values,
            }
        )


def group_links(rec: Recorder, rule_id: str, entries: list[tuple]) -> None:
    """(from_value, to_value, status, row_key, reason, candidates, planted_id)를 같은 값끼리 묶어 한 건으로 기록한다."""
    grouped = defaultdict(list)
    order = []
    for (
        from_value,
        to_value,
        status,
        row_key,
        reason,
        candidates,
        planted_id,
    ) in entries:
        key = (from_value, to_value, status, reason, tuple(candidates), planted_id)
        if key not in grouped:
            order.append(key)
        grouped[key].append(row_key)
    for key in order:
        from_value, to_value, status, reason, candidates, planted_id = key
        rec.link(
            rule_id,
            from_value,
            to_value,
            status,
            grouped[key],
            reason,
            candidates,
            planted_id,
        )


def alias_entry(m: Mention, row_key: str) -> tuple:
    """메모 속 설비 언급 하나를 설비 별칭 연결 정답(R-ALIAS) 한 줄로 바꾼다."""
    code = m.ref.split(":")[1] if m.ref else None
    if m.status == "resolved":
        return (m.text, code, "matched", row_key, "", (), None)
    if m.status == "ambiguous":
        codes = tuple(c.split(":")[1] for c in m.candidates)
        return (
            m.text,
            None,
            "ambiguous",
            row_key,
            "라인 표시가 없어 같은 호기의 설비가 여럿",
            codes,
            "M5-A",
        )
    return (
        m.text,
        None,
        "unmatched_error",
        row_key,
        "설비 목록에 없는 이름",
        (),
        "M5-U",
    )


def so_from_ref(ref: str) -> str | None:
    """수주 참조 → 수주 번호. 2610021 → OD-2610-021. 이미 OD-2610-021이면 그대로."""
    if re.fullmatch(r"OD-\d{4}-\d{3}", ref):
        return ref
    return f"OD-{ref[:4]}-{ref[4:]}" if re.fullmatch(r"\d{7}", ref) else None


def _code(key: str) -> str:
    return "".join(w.capitalize() for w in key.split(".", 1)[1].split("_"))


def _data_type(sql_type: str) -> str:
    t = sql_type.lower()
    if t.startswith(("varchar", "char")):
        return "STRING"
    if t.startswith(("int", "bigint")):
        return "INTEGER"
    if t.startswith("numeric"):
        return "DECIMAL"
    if t == "date":
        return "DATE"
    if t.startswith("timestamp"):
        return "DATETIME"
    if t == "time":
        return "TIME"
    return "STRING"


def _link_code(rel: schema.Relation) -> str:
    table = rel.source[0].rsplit(".", 1)[0].split(".")[1]
    columns = "_".join(c.rsplit(".", 1)[1] for c in rel.source)
    code = f"{table}_{columns}"
    if rel.condition or rel.through:
        code += "_" + rel.target[0].rsplit(".", 1)[0].split(".")[1]
    return code


def _side(columns: tuple[str, ...]):
    return columns[0] if len(columns) == 1 else list(columns)


def ontology() -> dict:
    """원천 표 한 행 = 객체 하나(FN-MAP-02). 모든 컬럼이 속성이고, 관계 조건은 속성끼리 비교한다(ERD link_mapping_condition)."""
    tables = [t for t in schema.TABLES if t.key not in SKIP]
    reference_columns = {c for rel in schema.RELATIONS for c in rel.source}
    object_types, properties, links = [], [], []
    for t in tables:
        object_types.append(
            {
                "code": _code(t.key),
                "name": t.description,
                "synonyms": [],
                "category": schema.KIND_OF[t.key],
                "source_tables": [t.key],
                "key_columns": [f"{t.key}.{c}" for c in t.primary_key],
                "level": "must",
            }
        )
        for name, sql_type, comment in t.columns:
            ref = f"{t.key}.{name}"
            properties.append(
                {
                    "object_type": _code(t.key),
                    "code": name,
                    "name": comment.split("(")[0].strip(),
                    "data_type": _data_type(sql_type),
                    "source_column": ref,
                    "value_map": None,
                    "is_identifier": name in t.primary_key,
                    "is_required": True if name in t.primary_key else None,
                    "is_reference": ref in reference_columns,
                    "is_personal": ref in schema.PERSONAL_COLUMNS,
                    "level": "must",
                }
            )
    for rel in schema.RELATIONS:
        s_table = rel.source[0].rsplit(".", 1)[0]
        d_table = rel.target[0].rsplit(".", 1)[0]
        via = {
            "from": _side(rel.source),
            "to": _side(rel.target),
            "key": rel.target_key,
        }
        if rel.condition:
            via["condition"] = {"column": rel.condition[0], "equals": rel.condition[1]}
        if rel.through:
            via["through"] = {"match": rel.through[0], "take": rel.through[1]}
        link = {
            "code": _link_code(rel),
            "name": f"{' + '.join(rel.source)} → {' + '.join(rel.target)}",
            "source_type": _code(s_table),
            "target_type": _code(d_table),
            "cardinality": rel.cardinality,
            "via": via,
            "mapping_rule": rel.rule,
            "derived_from": "column",
            "level": "must",
        }
        if rel.known_unmatched:
            link["known_unmatched"] = [
                {"value": v, "reason": why} for v, why in rel.known_unmatched
            ]
        if rel.note:
            link["note"] = rel.note
        links.append(link)
    codes = [link["code"] for link in links]
    if len(codes) != len(set(codes)):
        raise ValueError("관계 code가 겹칩니다")
    return {"object_types": object_types, "properties": properties, "link_types": links}


def mapping_rules(full_example: str) -> list[dict]:
    """full_example: 수주 참조가 이미 ERP 표기로 입력된 값 하나 (예: OD-2609-004)."""
    none = {"source_transforms": [], "target_transforms": []}
    digits = [{"op": "EXTRACT_NUMBER", "arg": None}]
    return [
        {
            "rule_id": "R-SO",
            "rule_type": "transform",
            "from": "mes.work_order.so_ref",
            "to": "erp.sales_order.so_no",
            "description": "양쪽에서 숫자만 남겨 비교함. 2610021 = OD-2610-021",
            "examples": [
                {"from": "2610021", "to": "OD-2610-021"},
                {"from": full_example, "to": full_example},
            ],
            "notes": "일부는 이미 ERP 표기로 입력됨. 빈 값·없는 번호 사례 포함",
            "reference_transforms": {
                "source_transforms": digits,
                "target_transforms": digits,
            },
        },
        {
            "rule_id": "R-W",
            "rule_type": "transform",
            "from": "mes.worker.id",
            "to": "erp.employee.emp_no",
            "description": "번호를 4자리로 0 채우고 앞에 GP를 붙임",
            "examples": [{"from": "31", "to": "GP0031"}],
            "notes": "작업자 57 → GP0057은 직원 표에 없음(검사 K2)",
            "reference_transforms": {
                "source_transforms": [
                    {"op": "PAD_ZEROS", "arg": 4},
                    {"op": "ADD_PREFIX", "arg": "GP"},
                ],
                "target_transforms": [],
            },
        },
        {
            "rule_id": "R-ASSET",
            "rule_type": "mapping_column",
            "from": "erp.fixed_asset.equipment_code",
            "to": "mes.equipment.code",
            "description": "자산 표의 설비 코드 칸을 그대로 비교함",
            "examples": [{"from": "P103", "to": "P103"}],
            "notes": "설비가 아닌 자산은 빈 값이 정상. P201 자산은 오타 P2O1",
            "reference_transforms": none,
        },
        {
            "rule_id": "R-ITEM",
            "rule_type": "same_value",
            "from": "mes.*.product_code",
            "to": "erp.item.item_code",
            "description": "제품·자재 코드를 그대로 비교함",
            "examples": [{"from": "BRK-210L", "to": "BRK-210L"}],
            "notes": "금형의 제품 BRK-990T는 품목 마스터에 없음(검사 K4)",
            "reference_transforms": none,
        },
        {
            "rule_id": "R-LOT",
            "rule_type": "same_value",
            "from": "erp.shipment.lot_no",
            "to": "mes.inspection.lot_no",
            "description": "출하 로트와 최종 검사 로트를 그대로 비교함",
            "examples": [],
            "notes": "최근 출하 로트 하나는 검사 기록 없음",
            "reference_transforms": none,
        },
        {
            "rule_id": "R-ALIAS",
            "rule_type": "name_alias",
            "from": "mes.handover_note.content",
            "to": "mes.equipment.code",
            "description": "설비 이름·'L라인 N호'·'L-N호'·코드 표기를 설비 코드로 연결함",
            "examples": [
                {"from": "1라인 3호 프레스", "to": "P103"},
                {"from": "3호기", "to": "P103"},
            ],
            "notes": "'1호기'는 1라인·2라인 1호 프레스가 모두 있어 후보가 여럿이다.",
        },
    ]


def id_links(rec: Recorder, rows) -> None:
    employees = {r["emp_no"] for r in rows["erp.employee"]}
    group_links(
        rec,
        "R-W",
        [
            (w["id"], worker_to_employee(w["id"]), "matched", w["id"], "", (), None)
            if worker_to_employee(w["id"]) in employees
            else (
                w["id"],
                None,
                "unmatched_error",
                w["id"],
                f"직원 표에 {worker_to_employee(w['id'])} 없음",
                (),
                "K2",
            )
            for w in rows["mes.worker"]
        ],
    )
    orders = {r["so_no"] for r in rows["erp.sales_order"]}
    entries = []
    for wo in rows["mes.work_order"]:
        ref, target = wo["so_ref"], so_from_ref(wo["so_ref"]) if wo["so_ref"] else None
        if not ref:
            entries.append(
                ("", None, "missing", wo["wo_no"], "수주 참조가 빈 값", (), "S-BLANK")
            )
        elif target in orders:
            reason = "이미 ERP 형식으로 입력됨" if ref == target else ""
            entries.append(
                (
                    ref,
                    target,
                    "matched",
                    wo["wo_no"],
                    reason,
                    (),
                    "S-FULL" if reason else None,
                )
            )
        else:
            entries.append(
                (
                    ref,
                    None,
                    "unmatched_error",
                    wo["wo_no"],
                    f"변환 값 {target}에 해당하는 수주 없음",
                    (),
                    "S-MISSING",
                )
            )
    group_links(rec, "R-SO", entries)
    codes = {r["code"] for r in rows["mes.equipment"]}
    group_links(
        rec,
        "R-ASSET",
        [
            (
                a["equipment_code"],
                a["equipment_code"],
                "matched",
                a["asset_no"],
                "",
                (),
                None,
            )
            if a["equipment_code"] in codes
            else (
                "",
                None,
                "unmatched_expected",
                a["asset_no"],
                "설비가 아닌 자산이라 비어 있는 것이 정상",
                (),
                None,
            )
            if not a["equipment_code"]
            else (
                a["equipment_code"],
                None,
                "unmatched_error",
                a["asset_no"],
                "숫자 0 대신 영문 O로 입력된 오타 (실제 P201)",
                ("P201",),
                "M-ASSET-TYPO",
            )
            for a in rows["erp.fixed_asset"]
        ],
    )
    items = {r["item_code"] for r in rows["erp.item"]}
    entries = sorted(
        {
            (
                r["product_code"],
                r["product_code"] if r["product_code"] in items else None,
                "matched" if r["product_code"] in items else "unmatched_error",
                f"{key}:{r['product_code']}",
                "" if r["product_code"] in items else "품목 마스터에 없음",
                (),
                None if r["product_code"] in items else "K4",
            )
            for key in ("mes.work_order", "mes.routing", "mes.die")
            for r in rows[key]
        }
    )
    group_links(rec, "R-ITEM", entries)
    inspected = {r["lot_no"] for r in rows["mes.inspection"]}
    group_links(
        rec,
        "R-LOT",
        [
            (
                s["lot_no"],
                s["lot_no"] if s["lot_no"] in inspected else None,
                "matched" if s["lot_no"] in inspected else "unmatched_expected",
                s["ship_no"],
                "" if s["lot_no"] in inspected else "아직 최종 검사 기록이 없는 로트",
                (),
                None,
            )
            for s in rows["erp.shipment"]
        ],
    )


def integrity_issues(rec: Recorder, rows, checks, cases) -> None:
    by_id = {c.check_id: c for c in checks}
    rec.issue(
        "unlinked",
        "mes.worker.id",
        cases["K2_worker"],
        cases["K2_worker"],
        "R-W",
        by_id["K2"].detail,
        "K2",
    )
    rec.issue(
        "unlinked",
        "mes.die.product_code",
        cases["K4_die"],
        cases["K4_product"],
        "R-ITEM",
        by_id["K4"].detail,
        "K4",
    )
    rec.issue(
        "unlinked",
        "mes.material_issue.lot_no",
        cases["K5_lot"],
        cases["K5_lot"],
        "자재 로트 = 입고 로트",
        f"{by_id['K5'].detail} — 확인 필요",
        "K5",
    )
    rec.issue(
        "constraint_violation",
        "mes.work_order.defect_qty",
        cases["A3_wo"],
        "",
        "작업지시 불량 = 실적 ng 합 = 불량 기록 합",
        by_id["A3"].detail,
        "A3",
    )
    rec.issue(
        "constraint_violation",
        "erp.journal_entry.debit",
        cases["A4_entry"],
        "",
        "차변 합 = 대변 합",
        by_id["A4"].detail + " (부가세 줄 누락)",
        "A4",
    )
    orders = {r["so_no"] for r in rows["erp.sales_order"]}
    for wo in rows["mes.work_order"]:
        if not wo["so_ref"]:
            rec.issue(
                "unlinked",
                "mes.work_order.so_ref",
                wo["wo_no"],
                "",
                "R-SO",
                "수주 참조가 비어 있어 연결할 수주를 알 수 없음 (결측)",
                "S-BLANK",
            )
        elif so_from_ref(wo["so_ref"]) not in orders:
            rec.issue(
                "unlinked",
                "mes.work_order.so_ref",
                wo["wo_no"],
                wo["so_ref"],
                "R-SO",
                f"ERP 수주에 {so_from_ref(wo['so_ref'])} 없음",
                "S-MISSING",
            )
    typo = next(a for a in rows["erp.fixed_asset"] if a["equipment_code"] == "P2O1")
    rec.issue(
        "unlinked",
        "erp.fixed_asset.equipment_code",
        typo["asset_no"],
        "P2O1",
        "R-ASSET",
        "설비 코드에 P2O1이 없음 (오타)",
        "M-ASSET-TYPO",
    )


def scenario_answers(builder, now: datetime) -> dict:
    from app.dataset.build import DT_BREAKDOWN, MO_BREAKDOWN, SO_RISK, WO_A, WO_B, WO_C

    rows = builder.rows
    wo = {r["wo_no"]: r for r in rows["mes.work_order"]}
    so = {r["so_no"]: r for r in rows["erp.sales_order"]}
    equipment = {r["code"]: r for r in rows["mes.equipment"]}
    routing = {(r["product_code"], int(r["op_seq"])): r for r in rows["mes.routing"]}
    affected = [
        o
        for o in rows["mes.work_order_operation"]
        if o["equipment_code"] == "P103" and o["status"] != "완료"
    ]
    need = max(
        int(
            routing[(wo[o["wo_no"]]["product_code"], int(o["op_seq"]))]["min_tonnage"]
            or 0
        )
        for o in affected
    )
    alternatives = {}
    for code, e in equipment.items():
        if e["kind"] != "PRS" or code == "P103":
            continue
        ton = int(e["capacity_ton"] or 0)
        alternatives[code] = (
            "가능"
            if ton >= need and e["status"] == "가동"
            else f"불가 ({ton}톤 < {need}톤)"
            if ton < need
            else f"불가 ({e['status']})"
        )
    product = wo[WO_A]["product_code"]
    cycle = sum(float(routing[(product, s)]["std_cycle_sec"]) for s in (20, 30))
    setup = sum(int(routing[(product, s)]["std_setup_min"]) for s in (20, 30))
    rem_a = int(wo[WO_A]["planned_qty"]) - int(wo[WO_A]["produced_qty"])
    rem_b = int(wo[WO_B]["planned_qty"]) - int(wo[WO_B]["produced_qty"])
    seconds = (rem_a + rem_b) * cycle
    finish = now + timedelta(seconds=seconds + setup * 60)
    schedule = p101_schedule(rows, routing, now, rem_a, rem_b, product)
    main = so_from_ref(wo[WO_A]["so_ref"])
    readings = sorted(
        (
            r
            for r in rows["mes.sensor_reading"]
            if r["equipment_code"] == "P103"
            and r["metric"] == "vibration_mm_s"
            and float(r["value"]) > float(r["threshold_high"])
        ),
        key=lambda r: r["measured_at"],
    )
    asset = next(a for a in rows["erp.fixed_asset"] if a["equipment_code"] == "P103")
    mo = next(m for m in rows["mes.maintenance_order"] if m["mo_no"] == MO_BREAKDOWN)
    refs = [w for w in rows["mes.work_order"] if w["so_ref"]]
    linked = [w for w in refs if so_from_ref(w["so_ref"]) in so]
    p103_downtimes = [d for d in rows["mes.downtime"] if d["equipment_code"] == "P103"]
    lot = builder.cases["P3_lot"]
    return {
        "now": now.isoformat(),
        "S4_P103_재계획": {
            "질문": "P103이 멈춰서 납기가 위험한 수주는?",
            "고장": {
                "downtime_id": DT_BREAKDOWN,
                "시작": "2026-10-30 03:10:00",
                "정비": f"{MO_BREAKDOWN} (진행)",
            },
            "영향_공정": [f"{o['wo_no']} {o['op_seq']}" for o in affected],
            "연결_수주": {
                WO_A: {
                    "so_ref": wo[WO_A]["so_ref"],
                    "수주": main,
                    "고객": so[main]["customer_code"],
                    "납기": so[main]["due_date"],
                }
            },
            "납기_위험_수주": {
                SO_RISK: f"연결 작업지시 {WO_C}가 대기(계획 시작 {wo[WO_C]['planned_start']}) — 납기 {so[SO_RISK]['due_date']} 위험"
            },
            "대체_설비": alternatives,
            "계산": {
                f"{WO_B}_잔량(P101 진행 중)": rem_b,
                f"{WO_A}_잔량": rem_a,
                "사이클_초(성형+트리밍)": cycle,
                "가공_시간": round(seconds / 3600, 1),
                "준비_시간_분": setup,
                "연속_가동_가정_완료": f"{finish:%Y-%m-%d %H:%M}",
                "납기_내": finish.date().isoformat() <= so[main]["due_date"],
            },
            "교대_반영_일정": schedule,
            "조치": f"재배정 계획 생성 ({WO_A} 성형·트리밍: P103 → P101). P201 선택 시 서버 검증에서 차단",
        },
        "P1_작업지시A_P101_시간": {
            "질문": f"{WO_A} 남은 수량을 P101에서 찍으면 몇 시간?",
            "답": f"{rem_a:,}개 × {cycle}초 = {rem_a * cycle / 3600:.1f}시간 (준비 {setup}분 포함 {(rem_a * cycle + setup * 60) / 3600:.1f}시간)",
        },
        "P2_P103_진동": {
            "질문": "P103 진동이 언제부터 상한을 넘었어?",
            "답": f"{readings[0]['measured_at']} ({readings[0]['value']} > {readings[0]['threshold_high']})",
        },
        "P2_P103_수리비": {
            "질문": "P103 수리비가 장부가 대비 얼마야?",
            "답": f"{MO_BREAKDOWN} 부품비 {float(mo['parts_cost']):,.0f}원 / 장부가({asset['asset_no']}) {float(asset['book_value']):,.0f}원 = "
            f"{float(mo['parts_cost']) / float(asset['book_value']):.2%}",
        },
        "P3_로트_검사": {
            "질문": f"{lot} 로트 검사 결과는?",
            "답": "최종 검사 기록 없음 — 연결된 검사 행 없음('데이터 없음'으로 안내)",
        },
        "P4_수주참조_연결률": {
            "질문": "수주 참조 규칙 적용 시 연결률은?",
            "답": f"{len(linked)} / {len(refs)} = {len(linked) / len(refs):.1%} (빈 값 {len(rows['mes.work_order']) - len(refs)}건 제외)",
        },
        "S3_P103_조회": {
            "질문": "P103의 최근 비가동 이력과 ERP 장부가",
            "비가동_건수": len(p103_downtimes),
            "최근": max(p103_downtimes, key=lambda d: d["started_at"])["downtime_id"],
            "장부가": float(asset["book_value"]),
        },
        "S2_규칙_보류": {
            "규칙": "R-W 승인",
            "개별_보류": [
                f"{builder.cases['K2_worker']} → {worker_to_employee(builder.cases['K2_worker'])} (직원 없음)"
            ],
        },
    }


def p101_schedule(rows, routing, now, rem_a, rem_b, product) -> dict:
    """P101에 B 잔량 → A 이관분을 교대 시간표대로 넣어 끝나는 시각을 구한다 (표준 시간·느린 경우 1.3배)."""
    from app.dataset.build import WO_A, WO_B, WO_C, Calendar

    wo = {r["wo_no"]: r for r in rows["mes.work_order"]}
    due = next(
        o["due_date"]
        for o in rows["erp.sales_order"]
        if o["so_no"] == so_from_ref(wo[WO_A]["so_ref"])
    )
    queue = [  # (작업지시, 공정, 수량, 준비 필요)
        (WO_B, 20, rem_b, False),  # 지금 찍는 중이라 준비 없음
        (WO_B, 30, rem_b, True),
        (WO_A, 20, rem_a, True),
        (WO_A, 30, rem_a, True),
    ]
    result = {}
    for label, factor in (("표준", 1.0), ("느린_경우_1.3배", 1.3)):
        cal = Calendar()
        cal.free["P101"] = now
        end = now
        for _, seq, qty, setup in queue:
            r = routing[(product, seq)]
            minutes = math.ceil(qty * float(r["std_cycle_sec"]) * factor / 60)
            minutes += int(r["std_setup_min"]) if setup else 0
            end = cal.book("P101", end, minutes)[-1][2]
        result[label] = {
            "완료": f"{end:%Y-%m-%d %H:%M}",
            "납기_내": end.date().isoformat() <= due,
        }
    c = wo[WO_C]
    return {
        "조건": [
            "P101 대기열 순서: B(진행 중) 잔량 → A 이관분 → C(11/2 계획)",
            "잔량은 마지막 실적 기록(10/30 05:00 야간 마감) 기준. 10/30 주간 생산분은 아직 기록 전이라 빼지 않음(보수적)",
            "교대: 주간 08~17시, 야간 20~05시. 토요일 야간·일요일 휴무",
            "금형 교체는 표준 준비 시간(성형 40분, 트리밍 30분)으로 반영",
            "P101 예정 비가동 없음, 교대별 작업자 배치 가능",
        ],
        **result,
        "뒤_작업": f"{WO_C}({c['planned_qty']}개, 계획 시작 {c['planned_start']})는 A 이관 뒤로 밀린다. 연결 수주 납기 10/31은 이미 위험",
    }


def note_gold(rec: Recorder, onto: dict, notes, maint_docs) -> list[dict]:
    entries = []
    for doc in notes:
        for e in doc.entities:
            if e["type"] == "EQUIPMENT":
                r = e["resolution"]
                entries.append(
                    alias_entry(
                        Mention(
                            e["text"], r["status"], r["ref"], tuple(r["candidates"])
                        ),
                        doc.doc_id,
                    )
                )
    group_links(rec, "R-ALIAS", entries)
    # 메모 객체는 mes.handover_note 표에서 나온다. 작성자는 컬럼 관계(author_id → worker.id)다.
    # 아래는 메모 본문에서만 확인되는 관계라 비정형 추출(FN-AIP-02·03)로 찾아야 한다.
    for code, target, rule, level in [
        ("mentions_equipment", "Equipment", "R-ALIAS", "must"),
        ("refers_maintenance", "MaintenanceOrder", None, "must"),
        ("about_work_order", "WorkOrder", None, "must"),
        ("about_lot", "Shipment", None, "must"),
    ]:
        onto["link_types"].append(
            {
                "code": code,
                "name": code,
                "source_type": "HandoverNote",
                "target_type": target,
                "cardinality": "MANY_TO_MANY",
                "via": None,
                "mapping_rule": rule,
                "derived_from": "text",
                "level": level,
            }
        )
    return [d.to_gold() for d in maint_docs] + [d.to_gold() for d in notes]


def write_gold(
    builder, checks, folder: Path, now: datetime, notes, maint_docs, version: str
) -> dict:
    folder.mkdir(parents=True, exist_ok=True)
    rec = Recorder()
    id_links(rec, builder.rows)
    integrity_issues(rec, builder.rows, checks, builder.cases)
    onto = ontology()
    full = next(p for p in builder.planted if p["case_id"] == "S-FULL")["rows"][0]
    rules = mapping_rules(
        next(w["so_ref"] for w in builder.rows["mes.work_order"] if w["wo_no"] == full)
    )
    annotations = note_gold(rec, onto, notes, maint_docs)
    patterns = {}
    for doc in notes:
        for p in doc.patterns:
            patterns.setdefault(p, []).append(doc.doc_id)
    planted = builder.planted + [
        {
            "case_id": p,
            "description": "메모 문장 패턴",
            "location": "mes.handover_note.content",
            "rows": ids,
            "values": None,
        }
        for p, ids in sorted(patterns.items())
    ]
    files = {
        "ontology.json": onto,
        "mapping_rules.json": rules,
        "id_links.json": rec.id_links,
        "integrity_issues.json": rec.issues,
        "note_annotations.json": annotations,
        "integrity_checks.json": [c.__dict__ for c in checks],
        "scenario_answers.json": scenario_answers(builder, now),
        "planted_cases.json": planted,
        "manifest.json": {
            "gold_version": version,
            "data_version": version,
            "purpose": "00프레스(가상) 데이터셋의 정답. 사람이 검토한 뒤 reviewed_by를 채운다.",
            "generator": {"tool": "app.dataset.build"},
            "cases": builder.cases,
            "created_by": "김서연",
            "reviewed_by": None,
            "revisions": [],
            "counts": {
                "object_types": len(onto["object_types"]),
                "properties": len(onto["properties"]),
                "link_types": len(onto["link_types"]),
                "mapping_rules": len(rules),
                "id_links": len(rec.id_links),
                "integrity_issues": len(rec.issues),
                "note_documents": len(annotations),
                "note_entities": sum(len(a["entities"]) for a in annotations),
            },
        },
    }
    for name, content in files.items():
        (folder / name).write_text(
            json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    problems = check_gold(folder)
    if problems:
        raise ValueError("정답셋 점검 실패:\n" + "\n".join(problems))
    return files["manifest.json"]["counts"]


def write_s5_fix(builder, folder: Path) -> None:
    """시나리오 5: 원천 담당자가 오류 2건(자산 설비 코드 오타, 시제품 품목 누락)을 고치고 변경 이력에 남긴다."""
    typo = next(
        a for a in builder.rows["erp.fixed_asset"] if a["equipment_code"] == "P2O1"
    )
    at = "2026-10-31 10:00:00"
    next_id = len(builder.rows["erp.change_log"]) + 1
    fix = {
        "description": "시나리오 5 원천 수정분. 시연 때 원천 DB에 반영한다(분석 입력 아님).",
        "changes": [
            {
                "table": "erp.fixed_asset",
                "key": {"asset_no": typo["asset_no"]},
                "set": {"equipment_code": "P201"},
                "resolves": "M-ASSET-TYPO",
            },
            {
                "table": "erp.item",
                "insert": {
                    "item_code": "BRK-990T",
                    "item_name": "브래킷 시제품",
                    "spec": "시제품",
                    "unit": "EA",
                    "item_type": "FG",
                    "std_price": "0.00",
                    "safety_stock": "0",
                    "use_yn": "Y",
                    "updated_at": at,
                },
                "resolves": "K4",
            },
        ],
        "change_log": [
            {
                "change_id": next_id,
                "table_name": "fixed_asset",
                "row_key": typo["asset_no"],
                "operation": "UPDATE",
                "changed_at": at,
            },
            {
                "change_id": next_id + 1,
                "table_name": "item",
                "row_key": "BRK-990T",
                "operation": "INSERT",
                "changed_at": at,
            },
        ],
    }
    sql = [
        f"-- {fix['description']}",
        f"update erp.fixed_asset set equipment_code = 'P201' where asset_no = '{typo['asset_no']}';",
        "insert into erp.item values ('BRK-990T', '브래킷 시제품', '시제품', 'EA', 'FG', 0, 0, 'Y', '2026-10-31 10:00:00');",
    ]
    sql += [
        f"insert into erp.change_log values ({c['change_id']}, '{c['table_name']}', '{c['row_key']}', '{c['operation']}', '{c['changed_at']}');"
        for c in fix["change_log"]
    ]
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "s5_fix.json").write_text(
        json.dumps(fix, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (folder / "s5_fix.sql").write_text("\n".join(sql) + "\n", encoding="utf-8")
