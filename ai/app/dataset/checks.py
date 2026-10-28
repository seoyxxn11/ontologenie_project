"""정합성 검사 9건. 기업 「정합성 예시」와 같은 종류의 검사를 우리 데이터의 값으로 계산한다.

키 연결 검사 5건(K1~K5), 숫자 대조 검사 4건(A1~A4). 기대 판정: 통과 4 · 실패 4 · 확인 필요 1.
검사 대상 값(cases)은 생성기가 정해 정답셋에 함께 남긴다.
"""

from dataclasses import dataclass

PASS, FAIL, REVIEW = "통과", "실패", "확인 필요"
EXPECTED = {
    "K1": PASS,
    "K2": FAIL,
    "K3": PASS,
    "K4": FAIL,
    "K5": REVIEW,
    "A1": PASS,
    "A2": PASS,
    "A3": FAIL,
    "A4": FAIL,
}


@dataclass
class CheckResult:
    check_id: str
    kind: str  # key_link · amount_match
    title: str
    verdict: str
    detail: str


def _num(value) -> int:
    return int(float(value)) if str(value).strip() else 0


def worker_to_employee(worker_id: str) -> str:
    """작업자 번호 → 사번. 31 → GP0031"""
    return f"GP{int(worker_id):04d}"


def run_checks(rows: dict[str, list[dict]], cases: dict[str, str]) -> list[CheckResult]:
    employees = {r["emp_no"] for r in rows["erp.employee"]}
    items = {r["item_code"] for r in rows["erp.item"]}
    equipment = {r["code"] for r in rows["mes.equipment"]}
    results = []

    for check_id, key in (("K1", "K1_worker"), ("K2", "K2_worker")):
        worker = cases[key]
        target = worker_to_employee(worker)
        ok = target in employees
        results.append(
            CheckResult(
                check_id,
                "key_link",
                f"MES 작업자 {worker} → ERP 직원 {target}",
                PASS if ok else FAIL,
                "있음" if ok else f"직원 표에 {target} 없음",
            )
        )

    asset = next(
        r for r in rows["erp.fixed_asset"] if r["asset_no"] == cases["K3_asset"]
    )
    code = asset["equipment_code"]
    results.append(
        CheckResult(
            "K3",
            "key_link",
            f"ERP 고정자산 {asset['asset_no']} → MES 설비 {code}",
            PASS if code in equipment else FAIL,
            f"equipment_code = {code}",
        )
    )

    product = cases["K4_product"]
    results.append(
        CheckResult(
            "K4",
            "key_link",
            f"MES 금형 {cases['K4_die']}의 제품 {product} → ERP 품목",
            PASS if product in items else FAIL,
            "있음" if product in items else "품목 마스터에 없음",
        )
    )

    receipt_columns = (
        set(rows["erp.goods_receipt"][0]) if rows["erp.goods_receipt"] else set()
    )
    has_lot = "lot_no" in receipt_columns
    results.append(
        CheckResult(
            "K5",
            "key_link",
            f"MES 자재 로트 {cases['K5_lot']} → ERP 입고 로트",
            PASS if has_lot else REVIEW,
            "로트 컬럼 있음" if has_lot else "입고 표에 로트 컬럼 없음",
        )
    )

    so = cases["A1_so"]
    ordered = sum(
        _num(r["shipped_qty"]) for r in rows["erp.sales_order_line"] if r["so_no"] == so
    )
    shipped = sum(_num(r["qty"]) for r in rows["erp.shipment"] if r["so_no"] == so)
    results.append(
        CheckResult(
            "A1",
            "amount_match",
            f"수주 {so} 출하 수량",
            PASS if ordered == shipped else FAIL,
            f"수주 상세 {ordered:,} / 출하 합 {shipped:,}",
        )
    )

    po = cases["A2_po"]
    received = sum(
        _num(r["received_qty"])
        for r in rows["erp.purchase_order_line"]
        if r["po_no"] == po
    )
    receipts = sum(
        _num(r["qty"]) for r in rows["erp.goods_receipt"] if r["po_no"] == po
    )
    results.append(
        CheckResult(
            "A2",
            "amount_match",
            f"발주 {po} 입고 수량",
            PASS if received == receipts else FAIL,
            f"발주 상세 {received:,} / 입고 합 {receipts:,}",
        )
    )

    wo_no = cases["A3_wo"]
    wo = next(r for r in rows["mes.work_order"] if r["wo_no"] == wo_no)
    ng = sum(
        _num(r["ng_qty"]) for r in rows["mes.production_result"] if r["wo_no"] == wo_no
    )
    defects = sum(_num(r["qty"]) for r in rows["mes.defect"] if r["wo_no"] == wo_no)
    same = _num(wo["defect_qty"]) == ng == defects
    results.append(
        CheckResult(
            "A3",
            "amount_match",
            f"작업지시 {wo_no} 불량 수량",
            PASS if same else FAIL,
            f"작업지시 {_num(wo['defect_qty'])} / 실적 ng 합 {ng} / 불량 기록 합 {defects}",
        )
    )

    entry = cases["A4_entry"]
    lines = [r for r in rows["erp.journal_entry"] if r["entry_no"] == entry]
    debit, credit = (
        sum(_num(r["debit"]) for r in lines),
        sum(_num(r["credit"]) for r in lines),
    )
    results.append(
        CheckResult(
            "A4",
            "amount_match",
            f"분개 {entry} 차변·대변",
            PASS if debit == credit else FAIL,
            f"차변 {debit:,} / 대변 {credit:,}",
        )
    )
    return results
