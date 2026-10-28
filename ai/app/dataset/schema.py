"""원천 표 정의. 표·컬럼 구성은 기업 제공 예시(ERP 17·MES 12)를 참고했고, 주석 문장은 새로 썼다.

시나리오에 필요한 것(수주 참조 so_ref, 최소 톤수 min_tonnage, 공정별 배정 work_order_operation)과
변경 이력 표(결정 9)를 더했다. 주석에는 다른 표 이름을 적지 않는다(관계는 AI가 찾아야 할 정답이다).
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Table:
    source: str
    name: str
    description: str
    primary_key: tuple[str, ...]
    columns: tuple[tuple[str, str, str], ...]  # (이름, SQL 타입, 주석)

    @property
    def key(self) -> str:
        return f"{self.source}.{self.name}"

    @property
    def column_names(self) -> list[str]:
        return [c[0] for c in self.columns]


def _t(source, name, description, pk, *columns) -> Table:
    return Table(source, name, description, tuple(pk), tuple(columns))


V20, V50, V100 = "varchar(20)", "varchar(50)", "varchar(100)"
TABLES = [
    _t(
        "erp",
        "item",
        "품목",
        ["item_code"],
        ("item_code", V20, "품번"),
        ("item_name", V100, "품명"),
        ("spec", V100, "규격"),
        ("unit", "varchar(10)", "단위(EA 개 · KG 킬로그램)"),
        ("item_type", "varchar(2)", "품목 구분(FG 완제품 · SF 반제품 · RM 원자재)"),
        ("std_price", "numeric(12,2)", "표준 단가(원)"),
        ("safety_stock", "numeric(14,3)", "안전 재고 수량"),
        ("use_yn", "char(1)", "사용 여부(Y/N)"),
        ("updated_at", "timestamp", "마지막 수정 시각"),
    ),
    _t(
        "erp",
        "vendor",
        "거래처",
        ["vendor_code"],
        ("vendor_code", V20, "거래처 코드"),
        ("vendor_name", V100, "거래처 상호"),
        ("biz_no", V20, "사업자등록번호(뒷자리 가림)"),
        ("vendor_type", "char(1)", "거래 구분(P 매입처 · S 매출처 · B 매입·매출)"),
        ("contact_name", V50, "담당자 이름(가명)"),
        ("payment_terms", V50, "결제 조건"),
        ("use_yn", "char(1)", "사용 여부(Y/N)"),
    ),
    _t(
        "erp",
        "purchase_order",
        "구매 발주",
        ["po_no"],
        ("po_no", V20, "발주 번호"),
        ("vendor_code", V20, "공급 거래처 코드"),
        ("po_date", "date", "발주일"),
        ("due_date", "date", "입고 요청일"),
        ("status", "varchar(10)", "진행 상태(발주 · 부분입고 · 입고완료 · 취소)"),
        ("total_amount", "numeric(16,2)", "발주 금액(원, 부가세 별도)"),
        ("buyer", V50, "구매 담당"),
        ("remark", V100, "비고"),
    ),
    _t(
        "erp",
        "purchase_order_line",
        "구매 발주 상세",
        ["po_no", "line_no"],
        ("po_no", V20, "발주 번호"),
        ("line_no", "int", "상세 순번"),
        ("item_code", V20, "발주 품번"),
        ("qty", "numeric(14,3)", "발주 수량"),
        ("unit_price", "numeric(12,2)", "발주 단가(원)"),
        ("received_qty", "numeric(14,3)", "누적 입고 수량"),
        ("due_date", "date", "상세별 입고 요청일"),
    ),
    _t(
        "erp",
        "goods_receipt",
        "입고",
        ["gr_no"],
        ("gr_no", V20, "입고 번호"),
        ("po_no", V20, "근거 발주 번호"),
        ("item_code", V20, "입고 품번"),
        ("gr_date", "date", "입고일"),
        ("qty", "numeric(14,3)", "입고 수량"),
        ("accepted_qty", "numeric(14,3)", "검수 합격 수량"),
        ("rejected_qty", "numeric(14,3)", "검수 불합격 수량"),
        ("warehouse", V50, "입고 창고"),
        ("inspector", V50, "검수 담당"),
    ),
    _t(
        "erp",
        "stock",
        "창고별 재고",
        ["warehouse", "item_code"],
        ("warehouse", V50, "창고"),
        ("item_code", V20, "품번"),
        ("qty", "numeric(14,3)", "현재 재고 수량"),
        ("reserved_qty", "numeric(14,3)", "출고 예약 수량"),
        ("available_qty", "numeric(14,3)", "가용 수량(현재 − 예약)"),
        ("last_in_date", "date", "최근 입고일"),
        ("last_out_date", "date", "최근 출고일"),
    ),
    _t(
        "erp",
        "sales_order",
        "수주",
        ["so_no"],
        ("so_no", V20, "수주 번호"),
        ("customer_code", V20, "주문 고객 코드"),
        ("so_date", "date", "수주일"),
        ("due_date", "date", "납기일"),
        ("status", "varchar(10)", "진행 상태(접수 · 생산중 · 출하완료 · 취소)"),
        ("total_amount", "numeric(16,2)", "수주 금액(원)"),
        ("sales_rep", V50, "영업 담당"),
    ),
    _t(
        "erp",
        "sales_order_line",
        "수주 상세",
        ["so_no", "line_no"],
        ("so_no", V20, "수주 번호"),
        ("line_no", "int", "상세 순번"),
        ("item_code", V20, "주문 품번"),
        ("qty", "numeric(14,3)", "주문 수량"),
        ("unit_price", "numeric(12,2)", "판매 단가(원)"),
        ("shipped_qty", "numeric(14,3)", "누적 출하 수량"),
    ),
    _t(
        "erp",
        "shipment",
        "출하",
        ["ship_no"],
        ("ship_no", V20, "출하 번호"),
        ("so_no", V20, "근거 수주 번호"),
        ("item_code", V20, "출하 품번"),
        ("ship_date", "date", "출하일"),
        ("qty", "numeric(14,3)", "출하 수량"),
        ("carrier", V50, "운송사"),
        ("lot_no", V20, "출하 제품 로트"),
    ),
    _t(
        "erp",
        "sales_invoice",
        "매출 전표",
        ["invoice_no"],
        ("invoice_no", V20, "매출 전표 번호"),
        ("customer_code", V20, "청구 고객 코드"),
        ("invoice_date", "date", "발행일"),
        ("supply_amount", "numeric(16,2)", "공급가액(원)"),
        ("vat", "numeric(16,2)", "부가세(원)"),
        ("total_amount", "numeric(16,2)", "합계(원)"),
        ("collected_yn", "char(1)", "수금 완료 여부(Y/N)"),
        ("collected_date", "date", "수금일"),
    ),
    _t(
        "erp",
        "purchase_invoice",
        "매입 전표",
        ["invoice_no"],
        ("invoice_no", V20, "매입 전표 번호"),
        ("vendor_code", V20, "공급 거래처 코드"),
        ("invoice_date", "date", "발행일"),
        ("supply_amount", "numeric(16,2)", "공급가액(원)"),
        ("vat", "numeric(16,2)", "부가세(원)"),
        ("total_amount", "numeric(16,2)", "합계(원)"),
        ("paid_yn", "char(1)", "지급 완료 여부(Y/N)"),
        ("paid_date", "date", "지급일"),
    ),
    _t(
        "erp",
        "gl_account",
        "회계 계정",
        ["account_code"],
        ("account_code", "varchar(10)", "계정 코드"),
        ("account_name", V50, "계정명"),
        ("account_type", "varchar(10)", "계정 구분(자산 · 부채 · 자본 · 수익 · 비용)"),
        ("parent_code", "varchar(10)", "상위 계정 코드"),
        ("use_yn", "char(1)", "사용 여부(Y/N)"),
    ),
    _t(
        "erp",
        "journal_entry",
        "분개",
        ["entry_no", "line_no"],
        ("entry_no", V20, "전표 번호"),
        ("line_no", "int", "분개 순번"),
        ("entry_date", "date", "전표일"),
        ("account_code", "varchar(10)", "계정 코드"),
        ("vendor_code", V20, "관련 거래처 코드"),
        ("debit", "numeric(16,2)", "차변 금액"),
        ("credit", "numeric(16,2)", "대변 금액"),
        ("description", V100, "적요"),
    ),
    _t(
        "erp",
        "employee",
        "직원",
        ["emp_no"],
        ("emp_no", V20, "사번"),
        ("emp_name", V50, "이름(가명)"),
        ("dept_code", "varchar(10)", "소속 부서 코드"),
        ("position", V20, "직급"),
        ("hire_date", "date", "입사일"),
        ("employment_type", "varchar(10)", "고용 형태(정규 · 계약)"),
        ("status", "varchar(10)", "재직 상태(재직 · 휴직 · 퇴직)"),
    ),
    _t(
        "erp",
        "department",
        "부서",
        ["dept_code"],
        ("dept_code", "varchar(10)", "부서 코드"),
        ("dept_name", V50, "부서명"),
        ("parent_code", "varchar(10)", "상위 부서 코드"),
        ("manager_emp_no", V20, "부서장 사번"),
    ),
    _t(
        "erp",
        "attendance",
        "근태",
        ["emp_no", "work_date"],
        ("emp_no", V20, "사번"),
        ("work_date", "date", "근무일"),
        ("clock_in", "time", "출근 시각"),
        ("clock_out", "time", "퇴근 시각"),
        ("work_type", "varchar(10)", "근무 구분(근무 · 외근 · 연차)"),
        ("overtime_min", "int", "연장 근무 시간(분)"),
        ("late_yn", "char(1)", "지각 여부(Y/N)"),
    ),
    _t(
        "erp",
        "fixed_asset",
        "고정자산",
        ["asset_no"],
        ("asset_no", V20, "자산 번호"),
        ("asset_name", V100, "자산명"),
        ("asset_type", V20, "자산 종류(기계장치 · 차량 · 공구)"),
        ("acquired_on", "date", "취득일"),
        ("acquisition_cost", "numeric(16,2)", "취득가(원)"),
        ("book_value", "numeric(16,2)", "장부가(원)"),
        ("location", V50, "설치·보관 위치"),
        ("equipment_code", V20, "생산 설비이면 현장 시스템의 설비 코드"),
    ),
    _t(
        "mes",
        "equipment",
        "설비",
        ["code"],
        ("code", V20, "설비 코드"),
        ("name", V50, "설비 이름"),
        (
            "kind",
            "varchar(10)",
            "설비 종류(BLK 블랭킹 · PRS 프레스 · WLD 용접 · INS 검사)",
        ),
        ("line", "varchar(10)", "설치 라인"),
        ("capacity_ton", "int", "프레스 능력(톤)"),
        ("status", "varchar(10)", "상태(가동 · 정지 · 정비중)"),
        ("installed_on", "date", "설치일"),
        ("maker", V50, "제조사"),
    ),
    _t(
        "mes",
        "worker",
        "현장 작업자",
        ["id"],
        ("id", "varchar(10)", "작업자 번호"),
        ("name", V50, "이름(가명)"),
        ("shift", "varchar(10)", "근무 조(주간 · 야간)"),
        ("line", "varchar(10)", "배치 라인"),
        ("skill_level", "int", "숙련도(1~5)"),
        ("active_yn", "char(1)", "근무 중 여부(Y/N)"),
    ),
    _t(
        "mes",
        "routing",
        "제품별 공정",
        ["product_code", "op_seq"],
        ("product_code", V20, "제품 코드"),
        ("op_seq", "int", "공정 순서(10 · 20 · 30 …)"),
        ("op_name", V50, "공정명"),
        ("equipment_kind", "varchar(10)", "필요한 설비 종류"),
        ("std_cycle_sec", "numeric(8,2)", "개당 표준 가공 시간(초)"),
        ("std_setup_min", "int", "표준 준비 시간(분)"),
        ("min_tonnage", "int", "프레스 공정의 최소 능력(톤)"),
    ),
    _t(
        "mes",
        "work_order",
        "작업지시",
        ["wo_no"],
        ("wo_no", V20, "작업지시 번호"),
        ("product_code", V20, "생산 제품 코드"),
        ("die_drawing_code", V20, "사용 금형 도면 번호"),
        ("status", "varchar(10)", "진행 상태(대기 · 진행 · 완료 · 보류)"),
        ("priority", "varchar(10)", "우선순위(긴급 · 보통 · 낮음)"),
        ("line", "varchar(10)", "생산 라인"),
        ("planned_qty", "int", "계획 수량"),
        ("produced_qty", "int", "생산 수량(마지막 프레스 공정 양품)"),
        ("defect_qty", "int", "불량 수량"),
        ("planned_start", "date", "계획 시작일"),
        ("planned_end", "date", "계획 종료일"),
        ("actual_start", "timestamp", "실제 시작 시각"),
        ("actual_end", "timestamp", "실제 종료 시각"),
        ("so_ref", V20, "주문 참조 번호"),
    ),
    _t(
        "mes",
        "work_order_operation",
        "작업지시 공정별 배정",
        ["wo_no", "op_seq"],
        ("wo_no", V20, "작업지시 번호"),
        ("op_seq", "int", "공정 순서"),
        ("equipment_code", V20, "배정 설비 코드"),
        ("status", "varchar(10)", "공정 상태(대기 · 진행 · 완료 · 중단)"),
        ("remaining_qty", "int", "남은 수량"),
    ),
    _t(
        "mes",
        "production_result",
        "공정 실적",
        ["result_id"],
        ("result_id", "bigint", "실적 번호"),
        ("wo_no", V20, "작업지시 번호"),
        ("op_seq", "int", "공정 순서"),
        ("equipment_code", V20, "작업 설비 코드"),
        ("worker_id", "varchar(10)", "작업자 번호"),
        ("shift", "varchar(10)", "근무 조"),
        ("started_at", "timestamp", "시작 시각"),
        ("ended_at", "timestamp", "종료 시각"),
        ("good_qty", "int", "양품 수량"),
        ("ng_qty", "int", "불량 수량"),
    ),
    _t(
        "mes",
        "material_issue",
        "자재 투입",
        ["issue_id"],
        ("issue_id", "bigint", "투입 번호"),
        ("wo_no", V20, "작업지시 번호"),
        ("item_code", V20, "투입 자재 품번"),
        ("lot_no", V20, "자재 로트"),
        ("qty", "numeric(14,3)", "투입량"),
        ("unit", "varchar(10)", "단위"),
        ("issued_at", "timestamp", "투입 시각"),
    ),
    _t(
        "mes",
        "downtime",
        "설비 비가동",
        ["downtime_id"],
        ("downtime_id", V20, "비가동 번호"),
        ("equipment_code", V20, "멈춘 설비 코드"),
        ("wo_no", V20, "당시 작업지시(없으면 빈 값)"),
        (
            "reason_code",
            "varchar(10)",
            "사유 코드(BRK 고장 · DIE 금형교체 · MAT 자재대기 · PM 예방정비 · QA 품질 정지)",
        ),
        ("reason", V100, "사유 설명"),
        ("started_at", "timestamp", "시작 시각"),
        ("ended_at", "timestamp", "종료 시각(진행 중이면 빈 값)"),
        ("minutes", "int", "비가동 시간(분)"),
    ),
    _t(
        "mes",
        "sensor_reading",
        "설비 센서 측정",
        ["equipment_code", "measured_at", "metric"],
        ("equipment_code", V20, "설비 코드"),
        ("measured_at", "timestamp", "측정 시각"),
        ("metric", V20, "측정 항목(vibration_mm_s 진동 · temp_c 온도)"),
        ("value", "numeric(10,2)", "측정값"),
        ("threshold_high", "numeric(10,2)", "경고 상한"),
    ),
    _t(
        "mes",
        "defect",
        "불량 기록",
        ["defect_id"],
        ("defect_id", "bigint", "불량 번호"),
        ("wo_no", V20, "작업지시 번호"),
        ("op_seq", "int", "발생 공정"),
        ("equipment_code", V20, "발생 설비 코드"),
        ("defect_type", V20, "불량 유형(크랙 · 주름 · 버 · 치수 · 스크래치)"),
        ("qty", "int", "불량 수량"),
        ("disposition", V20, "처리(폐기 · 재작업)"),
        ("detected_at", "timestamp", "발견 시각"),
        ("detected_by", "varchar(10)", "발견 작업자 번호"),
    ),
    _t(
        "mes",
        "inspection",
        "품질 검사",
        ["inspection_id"],
        ("inspection_id", "bigint", "검사 번호"),
        ("wo_no", V20, "작업지시 번호"),
        ("lot_no", V20, "검사 제품 로트"),
        ("inspection_type", V20, "검사 종류(초물 · 공정 · 최종)"),
        ("item", V50, "검사 항목"),
        ("spec_low", "numeric(10,3)", "규격 하한"),
        ("spec_high", "numeric(10,3)", "규격 상한"),
        ("measured", "numeric(10,3)", "측정값"),
        ("result", "varchar(5)", "판정(OK · NG)"),
        ("inspected_at", "timestamp", "검사 시각"),
    ),
    _t(
        "mes",
        "die",
        "금형",
        ["die_code"],
        ("die_code", V20, "금형 코드"),
        ("drawing_code", V20, "금형 도면 번호"),
        ("product_code", V20, "생산 제품 코드"),
        ("total_strokes", "bigint", "누적 타수"),
        ("pm_interval_strokes", "int", "정비 주기(타수)"),
        ("last_pm_at", "date", "마지막 정비일"),
        ("status", "varchar(10)", "상태(사용 · 정비 · 폐기)"),
        ("location", V50, "보관 위치"),
    ),
    _t(
        "mes",
        "maintenance_order",
        "정비 지시",
        ["mo_no"],
        ("mo_no", V20, "정비 번호"),
        ("target_type", "varchar(10)", "정비 대상 구분(설비 · 금형)"),
        ("target_code", V20, "정비 대상 코드"),
        ("mo_type", "varchar(10)", "정비 종류(예방 · 고장 · 개선)"),
        ("status", "varchar(10)", "상태(계획 · 진행 · 완료)"),
        ("planned_date", "date", "계획일"),
        ("completed_at", "timestamp", "완료 시각(진행 중이면 빈 값)"),
        ("technician", V50, "담당 부서"),
        ("parts_cost", "numeric(12,2)", "부품비(원)"),
        ("note", V100, "작업 내용"),
    ),
    _t(
        "mes",
        "handover_note",
        "교대 인수인계 메모",
        ["note_id"],
        ("note_id", V20, "메모 번호"),
        ("shift_date", "date", "교대가 시작한 날"),
        ("shift", "varchar(10)", "교대(주간 · 야간)"),
        ("author_id", "varchar(10)", "작성 작업자 번호"),
        ("written_at", "timestamp", "작성 시각(교대 끝)"),
        ("content", "text", "메모 원문(첫 줄 머리말 포함)"),
    ),
]
for _source in ("erp", "mes"):
    TABLES.append(
        _t(
            _source,
            "change_log",
            "원천 변경 이력",
            ["change_id"],
            ("change_id", "bigint", "변경 순번"),
            ("table_name", V50, "변경된 표"),
            ("row_key", V100, "변경된 행의 키"),
            ("operation", "varchar(10)", "INSERT · UPDATE · DELETE"),
            ("changed_at", "timestamp", "변경 시각"),
        )
    )
TABLE_BY_KEY = {t.key: t for t in TABLES}

# 표 종류 (정답 object_types.category). 변경 이력 표는 동기화용 기록이라 객체가 아니다.
TABLE_KIND = {
    "master": [
        "erp.item",
        "erp.vendor",
        "erp.gl_account",
        "erp.employee",
        "erp.department",
        "erp.fixed_asset",
        "mes.equipment",
        "mes.worker",
        "mes.routing",
        "mes.die",
    ],
    "transaction": [
        "erp.purchase_order",
        "erp.sales_order",
        "erp.sales_invoice",
        "erp.purchase_invoice",
        "mes.work_order",
        "mes.maintenance_order",
    ],
    "detail": [
        "erp.purchase_order_line",
        "erp.sales_order_line",
        "erp.journal_entry",
        "mes.work_order_operation",
    ],
    "history": [
        "erp.goods_receipt",
        "erp.shipment",
        "erp.attendance",
        "mes.production_result",
        "mes.material_issue",
        "mes.downtime",
        "mes.sensor_reading",
        "mes.defect",
        "mes.inspection",
        "mes.handover_note",
    ],
    "snapshot": ["erp.stock"],
}
KIND_OF = {table: kind for kind, tables in TABLE_KIND.items() for table in tables}
# 개인정보 컬럼 (정답 properties.is_personal)
# 금형 정비 담당 칸에 팀 이름 대신 적힌 보전팀 직원(값은 시드로 정해진 직원 이름)
TECHNICIAN_EMP_NO = "GP0131"
TECHNICIAN_NAME = "홍보람"

PERSONAL_COLUMNS = {
    "mes.maintenance_order.technician",
    "erp.employee.emp_name",
    "mes.worker.name",
    "erp.vendor.contact_name",
}


@dataclass(frozen=True)
class Relation:
    """정답 관계. 주석에는 적지 않고 정답셋에만 둔다.

    source·target은 컬럼 목록(두 개 이상이면 복합키), condition은 (컬럼, 값)일 때만 성립,
    target_key는 도착 컬럼이 기본키(primary)인지, 다른 유일 키(alternate)인지, 키가 아닌지(none).
    through는 (거쳐 가는 표의 키, 가져올 컬럼): 출발 첫 컬럼 값으로 그 표를 찾아 가져온 값으로 잇는다.
    known_unmatched: 정합성 사례가 아닌데 원천에서 안 이어지는 값과 이유(데이터 한계).
    """

    source: tuple[str, ...]
    target: tuple[str, ...]
    rule: str | None = None
    cardinality: str = "MANY_TO_ONE"
    condition: tuple[str, str] | None = None
    target_key: str = "primary"
    known_unmatched: tuple[tuple[str, str], ...] = ()
    note: str = ""
    through: tuple[str, str] | None = None


def _r(source, target, rule=None, **kw) -> Relation:
    def as_tuple(v):
        return (v,) if isinstance(v, str) else tuple(v)

    return Relation(as_tuple(source), as_tuple(target), rule, **kw)


RELATIONS = [
    _r("erp.purchase_order.vendor_code", "erp.vendor.vendor_code"),
    _r(
        "erp.purchase_order_line.po_no",
        "erp.purchase_order.po_no",
        note="업무상 발주 1건에 상세 여러 줄(1:N). 이 데이터는 발주마다 상세가 1줄뿐이라 값만 보면 1:1로 보인다",
    ),
    _r("erp.purchase_order_line.item_code", "erp.item.item_code"),
    _r("erp.goods_receipt.po_no", "erp.purchase_order.po_no"),
    _r("erp.goods_receipt.item_code", "erp.item.item_code"),
    _r(
        ("erp.goods_receipt.po_no", "erp.goods_receipt.item_code"),
        ("erp.purchase_order_line.po_no", "erp.purchase_order_line.item_code"),
        target_key="alternate",
    ),
    _r(
        "erp.goods_receipt.inspector",
        "erp.department.dept_name",
        target_key="alternate",
    ),
    _r("erp.purchase_order.buyer", "erp.department.dept_name", target_key="alternate"),
    _r("erp.stock.item_code", "erp.item.item_code"),
    _r("erp.sales_order.customer_code", "erp.vendor.vendor_code"),
    _r("erp.sales_order.sales_rep", "erp.department.dept_name", target_key="alternate"),
    _r("erp.sales_order_line.so_no", "erp.sales_order.so_no"),
    _r("erp.sales_order_line.item_code", "erp.item.item_code"),
    _r("erp.shipment.so_no", "erp.sales_order.so_no"),
    _r("erp.shipment.item_code", "erp.item.item_code"),
    _r(
        ("erp.shipment.so_no", "erp.shipment.item_code"),
        ("erp.sales_order_line.so_no", "erp.sales_order_line.item_code"),
        target_key="alternate",
    ),
    _r("erp.sales_invoice.customer_code", "erp.vendor.vendor_code"),
    _r("erp.purchase_invoice.vendor_code", "erp.vendor.vendor_code"),
    _r("erp.journal_entry.account_code", "erp.gl_account.account_code"),
    _r("erp.journal_entry.vendor_code", "erp.vendor.vendor_code"),
    _r("erp.employee.dept_code", "erp.department.dept_code"),
    _r("erp.department.parent_code", "erp.department.dept_code"),
    _r(
        "erp.department.manager_emp_no",
        "erp.employee.emp_no",
        cardinality="ONE_TO_ONE",
    ),
    _r("erp.attendance.emp_no", "erp.employee.emp_no"),
    _r(
        "erp.fixed_asset.equipment_code",
        "mes.equipment.code",
        "R-ASSET",
        cardinality="ONE_TO_ONE",
    ),
    _r("mes.worker.id", "erp.employee.emp_no", "R-W", cardinality="ONE_TO_ONE"),
    _r("mes.routing.product_code", "erp.item.item_code", "R-ITEM"),
    _r(
        "mes.routing.equipment_kind",
        "mes.equipment.kind",
        cardinality="MANY_TO_MANY",
        target_key="none",
        note="특정 설비가 아니라 설비 종류를 가리킴",
    ),
    _r("mes.work_order.product_code", "erp.item.item_code", "R-ITEM"),
    _r("mes.work_order.so_ref", "erp.sales_order.so_no", "R-SO"),
    _r(
        "mes.work_order.die_drawing_code",
        "mes.die.drawing_code",
        target_key="alternate",
    ),
    _r("mes.work_order_operation.wo_no", "mes.work_order.wo_no"),
    _r(
        ("mes.work_order_operation.wo_no", "mes.work_order_operation.op_seq"),
        ("mes.routing.product_code", "mes.routing.op_seq"),
        through=("mes.work_order.wo_no", "mes.work_order.product_code"),
        note="작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴",
    ),
    _r("mes.work_order_operation.equipment_code", "mes.equipment.code"),
    _r("mes.production_result.wo_no", "mes.work_order.wo_no"),
    _r(
        ("mes.production_result.wo_no", "mes.production_result.op_seq"),
        ("mes.work_order_operation.wo_no", "mes.work_order_operation.op_seq"),
    ),
    _r(
        ("mes.production_result.wo_no", "mes.production_result.op_seq"),
        ("mes.routing.product_code", "mes.routing.op_seq"),
        through=("mes.work_order.wo_no", "mes.work_order.product_code"),
        note="작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴",
    ),
    _r("mes.production_result.equipment_code", "mes.equipment.code"),
    _r("mes.production_result.worker_id", "mes.worker.id"),
    _r("mes.material_issue.wo_no", "mes.work_order.wo_no"),
    _r("mes.material_issue.item_code", "erp.item.item_code", "R-ITEM"),
    _r("mes.downtime.equipment_code", "mes.equipment.code"),
    _r("mes.downtime.wo_no", "mes.work_order.wo_no"),
    _r("mes.sensor_reading.equipment_code", "mes.equipment.code"),
    _r("mes.defect.wo_no", "mes.work_order.wo_no"),
    _r(
        ("mes.defect.wo_no", "mes.defect.op_seq"),
        ("mes.work_order_operation.wo_no", "mes.work_order_operation.op_seq"),
    ),
    _r(
        ("mes.defect.wo_no", "mes.defect.op_seq"),
        ("mes.routing.product_code", "mes.routing.op_seq"),
        through=("mes.work_order.wo_no", "mes.work_order.product_code"),
        note="작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴",
    ),
    _r("mes.defect.equipment_code", "mes.equipment.code"),
    _r("mes.defect.detected_by", "mes.worker.id"),
    _r("mes.inspection.wo_no", "mes.work_order.wo_no"),
    _r("mes.die.product_code", "erp.item.item_code", "R-ITEM"),
    _r(
        "mes.maintenance_order.target_code",
        "mes.equipment.code",
        condition=("mes.maintenance_order.target_type", "설비"),
    ),
    _r(
        "mes.maintenance_order.target_code",
        "mes.die.die_code",
        condition=("mes.maintenance_order.target_type", "금형"),
    ),
    _r(
        "mes.maintenance_order.technician",
        "erp.department.dept_name",
        target_key="alternate",
        known_unmatched=((TECHNICIAN_NAME, "팀 대신 담당 직원 이름으로 적힘"),),
    ),
    _r("mes.handover_note.author_id", "mes.worker.id"),
    _r(
        "erp.shipment.lot_no",
        "mes.inspection.lot_no",
        "R-LOT",
        cardinality="ONE_TO_ONE",
        target_key="alternate",
    ),
]
# 관계가 아니라고 판단한 컬럼 (정의 문서에 함께 적는다)
NOT_RELATIONS = {
    "erp.gl_account.parent_code": "상위 계정 칸이지만 값이 모두 비어 있어 데이터로 확인할 수 없음",
    "erp.stock.warehouse": "창고 마스터 표가 없음 (이름 값)",
    "erp.goods_receipt.warehouse": "창고 마스터 표가 없음 (이름 값)",
    "mes.equipment.line": "라인 마스터 표가 없음 (공통 속성 값)",
    "mes.worker.line": "라인 마스터 표가 없음 (공통 속성 값)",
    "mes.work_order.line": "라인 마스터 표가 없음 (공통 속성 값)",
    "erp.change_log.row_key": "동기화용 기록. 표마다 다른 키를 담음",
    "mes.change_log.row_key": "동기화용 기록. 표마다 다른 키를 담음",
}


def schema_json(source: str) -> dict:
    return {
        "tables": {
            t.name: {
                "description": t.description,
                "primary_key": list(t.primary_key),
                "columns": {n: {"type": ty, "comment": c} for n, ty, c in t.columns},
            }
            for t in TABLES
            if t.source == source
        }
    }


def schema_sql(source: str) -> str:
    lines = [
        f"-- 00프레스(가상) {source.upper()} 원천 표 정의. 생성기 app/dataset",
        f"create schema if not exists {source};",
        "",
    ]
    for t in (t for t in TABLES if t.source == source):
        cols = (
            ",\n".join(f"  {n} {ty}" for n, ty, _ in t.columns)
            + f",\n  primary key ({', '.join(t.primary_key)})"
        )
        lines += [
            f"create table {t.key} (\n{cols}\n);",
            f"comment on table {t.key} is '{t.description}';",
        ]
        lines += [f"comment on column {t.key}.{n} is '{c}';" for n, _, c in t.columns]
        lines.append("")
    return "\n".join(lines)
