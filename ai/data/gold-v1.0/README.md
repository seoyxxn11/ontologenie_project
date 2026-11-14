# 정답셋 gold-v1.0 (00프레스 데이터 data-v1.0)

> **AI 입력·원천 DB에 넣지 않습니다.** 제안기 평가와 시연 확인에만 씁니다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `ontology.json` | 객체 유형 29 · 속성 214 · 관계 57 |
| `mapping_rules.json` | ERP·MES 식별자 연결 규칙 5 |
| `id_links.json` | 연결 규칙의 값 단위 정답 (연결·미연결·결측·모호) |
| `integrity_checks.json` | 정합성 검사 8건의 기대 판정 (고정자산 K3는 금지 표라 뺌) |
| `integrity_issues.json` | 문제가 있는 값 목록 (미연결·제약 위반) |
| `scenario_answers.json` | 시나리오·페르소나 질문의 기대 답 |
| `note_annotations.json` | 메모·정비 기록의 개체·관계와 원문 위치 |
| `planted_cases.json` | 일부러 넣은 사례 목록 |

검증: `ai/`에서 `.\.venv\Scripts\python.exe -m app.validators.gold_source_check data/data-v1.0 data/gold-v1.0` → 오류 0, 설명 안 된 미연결 0이 나와야 합니다.

## 객체 유형

원천 표 한 행이 객체 하나입니다. 변경 이력 표는 동기화용 기록이라 제외했습니다.

| 객체 | 이름 | 종류 | 원천 표 | 키 |
| --- | --- | --- | --- | --- |
| Item | 품목 | 기준 정보 | `erp.item` | item_code |
| Vendor | 거래처 | 기준 정보 | `erp.vendor` | vendor_code |
| PurchaseOrder | 구매 발주 | 거래 문서 | `erp.purchase_order` | po_no |
| PurchaseOrderLine | 구매 발주 상세 | 문서 상세 줄 | `erp.purchase_order_line` | po_no, line_no |
| GoodsReceipt | 입고 | 발생 기록 | `erp.goods_receipt` | gr_no |
| Stock | 창고별 재고 | 현재 상태 | `erp.stock` | warehouse, item_code |
| SalesOrder | 수주 | 거래 문서 | `erp.sales_order` | so_no |
| SalesOrderLine | 수주 상세 | 문서 상세 줄 | `erp.sales_order_line` | so_no, line_no |
| Shipment | 출하 | 발생 기록 | `erp.shipment` | ship_no |
| SalesInvoice | 매출 전표 | 거래 문서 | `erp.sales_invoice` | invoice_no |
| PurchaseInvoice | 매입 전표 | 거래 문서 | `erp.purchase_invoice` | invoice_no |
| GlAccount | 회계 계정 | 기준 정보 | `erp.gl_account` | account_code |
| JournalEntry | 분개 | 문서 상세 줄 | `erp.journal_entry` | entry_no, line_no |
| Employee | 직원 | 기준 정보 | `erp.employee` | emp_no |
| Department | 부서 | 기준 정보 | `erp.department` | dept_code |
| Equipment | 설비 | 기준 정보 | `mes.equipment` | code |
| Worker | 현장 작업자 | 기준 정보 | `mes.worker` | id |
| Routing | 제품별 공정 | 기준 정보 | `mes.routing` | product_code, op_seq |
| WorkOrder | 작업지시 | 거래 문서 | `mes.work_order` | wo_no |
| WorkOrderOperation | 작업지시 공정별 배정 | 문서 상세 줄 | `mes.work_order_operation` | wo_no, op_seq |
| ProductionResult | 공정 실적 | 발생 기록 | `mes.production_result` | result_id |
| MaterialIssue | 자재 투입 | 발생 기록 | `mes.material_issue` | issue_id |
| Downtime | 설비 비가동 | 발생 기록 | `mes.downtime` | downtime_id |
| SensorReading | 설비 센서 측정 | 발생 기록 | `mes.sensor_reading` | equipment_code, measured_at, metric |
| Defect | 불량 기록 | 발생 기록 | `mes.defect` | defect_id |
| Inspection | 품질 검사 | 발생 기록 | `mes.inspection` | inspection_id |
| Die | 금형 | 기준 정보 | `mes.die` | die_code |
| MaintenanceOrder | 정비 지시 | 거래 문서 | `mes.maintenance_order` | mo_no |
| HandoverNote | 교대 인수인계 메모 | 발생 기록 | `mes.handover_note` | note_id |

## 속성

모든 컬럼이 속성입니다(관계 조건은 속성끼리 비교). 식별자는 기본키, 참조는 관계의 출발 컬럼입니다.
필수 여부는 기본키만 정답으로 두었습니다(나머지는 채점하지 않음). 개인정보 컬럼: `erp.vendor.contact_name`, `mes.worker.skill_level`, `mes.maintenance_order.technician`

## 관계

| 출발 → 도착 | 키 | 개수 | 조건·규칙 | 비고 |
| --- | --- | --- | --- | --- |
| `erp.purchase_order.vendor_code` → `erp.vendor.vendor_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.purchase_order_line.po_no` → `erp.purchase_order.po_no` | 기본키 | MANY_TO_ONE |  | 업무상 발주 1건에 상세 여러 줄(1:N). 이 데이터는 발주마다 상세가 1줄뿐이라 값만 보면 1:1로 보인다 |
| `erp.purchase_order_line.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.goods_receipt.po_no` → `erp.purchase_order.po_no` | 기본키 | MANY_TO_ONE |  |  |
| `erp.goods_receipt.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.goods_receipt.po_no + erp.goods_receipt.item_code` → `erp.purchase_order_line.po_no + erp.purchase_order_line.item_code` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `erp.goods_receipt.inspector` → `erp.department.dept_name` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `erp.purchase_order.buyer` → `erp.department.dept_name` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `erp.stock.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.sales_order.customer_code` → `erp.vendor.vendor_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.sales_order.sales_rep` → `erp.department.dept_name` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `erp.sales_order_line.so_no` → `erp.sales_order.so_no` | 기본키 | MANY_TO_ONE |  |  |
| `erp.sales_order_line.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.shipment.so_no` → `erp.sales_order.so_no` | 기본키 | MANY_TO_ONE |  |  |
| `erp.shipment.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.shipment.so_no + erp.shipment.item_code` → `erp.sales_order_line.so_no + erp.sales_order_line.item_code` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `erp.sales_invoice.customer_code` → `erp.vendor.vendor_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.purchase_invoice.vendor_code` → `erp.vendor.vendor_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.journal_entry.account_code` → `erp.gl_account.account_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.journal_entry.vendor_code` → `erp.vendor.vendor_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.employee.dept_code` → `erp.department.dept_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.department.parent_code` → `erp.department.dept_code` | 기본키 | MANY_TO_ONE |  |  |
| `erp.department.manager_emp_no` → `erp.employee.emp_no` | 기본키 | ONE_TO_ONE |  |  |
| `mes.worker.id` → `erp.employee.emp_no` | 기본키 | ONE_TO_ONE | R-W |  |
| `mes.routing.product_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE | R-ITEM |  |
| `mes.routing.equipment_kind` → `mes.equipment.kind` | 키 아님 | MANY_TO_MANY |  | 특정 설비가 아니라 설비 종류를 가리킴 |
| `mes.work_order.product_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE | R-ITEM |  |
| `mes.work_order.so_ref` → `erp.sales_order.so_no` | 기본키 | MANY_TO_ONE | R-SO |  |
| `mes.work_order.die_drawing_code` → `mes.die.drawing_code` | 다른 유일 키 | MANY_TO_ONE |  |  |
| `mes.work_order_operation.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.work_order_operation.wo_no + mes.work_order_operation.op_seq` → `mes.routing.product_code + mes.routing.op_seq` | 기본키 | MANY_TO_ONE | mes.work_order 거쳐 | 작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴 |
| `mes.work_order_operation.equipment_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE |  |  |
| `mes.production_result.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.production_result.wo_no + mes.production_result.op_seq` → `mes.work_order_operation.wo_no + mes.work_order_operation.op_seq` | 기본키 | MANY_TO_ONE |  |  |
| `mes.production_result.wo_no + mes.production_result.op_seq` → `mes.routing.product_code + mes.routing.op_seq` | 기본키 | MANY_TO_ONE | mes.work_order 거쳐 | 작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴 |
| `mes.production_result.equipment_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE |  |  |
| `mes.production_result.worker_id` → `mes.worker.id` | 기본키 | MANY_TO_ONE |  |  |
| `mes.material_issue.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.material_issue.item_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE | R-ITEM |  |
| `mes.downtime.equipment_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE |  |  |
| `mes.downtime.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.sensor_reading.equipment_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE |  |  |
| `mes.defect.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.defect.wo_no + mes.defect.op_seq` → `mes.work_order_operation.wo_no + mes.work_order_operation.op_seq` | 기본키 | MANY_TO_ONE |  |  |
| `mes.defect.wo_no + mes.defect.op_seq` → `mes.routing.product_code + mes.routing.op_seq` | 기본키 | MANY_TO_ONE | mes.work_order 거쳐 | 작업지시의 품목 코드를 거쳐 라우팅 한 줄을 가리킴 |
| `mes.defect.equipment_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE |  |  |
| `mes.defect.detected_by` → `mes.worker.id` | 기본키 | MANY_TO_ONE |  |  |
| `mes.inspection.wo_no` → `mes.work_order.wo_no` | 기본키 | MANY_TO_ONE |  |  |
| `mes.die.product_code` → `erp.item.item_code` | 기본키 | MANY_TO_ONE | R-ITEM |  |
| `mes.maintenance_order.target_code` → `mes.equipment.code` | 기본키 | MANY_TO_ONE | target_type = 설비 |  |
| `mes.maintenance_order.target_code` → `mes.die.die_code` | 기본키 | MANY_TO_ONE | target_type = 금형 |  |
| `mes.handover_note.author_id` → `mes.worker.id` | 기본키 | MANY_TO_ONE |  |  |
| `erp.shipment.lot_no` → `mes.inspection.lot_no` | 다른 유일 키 | ONE_TO_ONE | R-LOT |  |

메모 본문에서만 확인되는 관계(비정형 추출로 찾음): mentions_equipment → Equipment, refers_maintenance → MaintenanceOrder, about_work_order → WorkOrder, about_lot → Shipment

관계가 아니라고 판단한 컬럼:

- `erp.gl_account.parent_code`: 상위 계정 칸이지만 값이 모두 비어 있어 데이터로 확인할 수 없음
- `erp.stock.warehouse`: 창고 마스터 표가 없음 (이름 값)
- `erp.goods_receipt.warehouse`: 창고 마스터 표가 없음 (이름 값)
- `mes.equipment.line`: 라인 마스터 표가 없음 (공통 속성 값)
- `mes.worker.line`: 라인 마스터 표가 없음 (공통 속성 값)
- `mes.work_order.line`: 라인 마스터 표가 없음 (공통 속성 값)
- `erp.change_log.row_key`: 동기화용 기록. 표마다 다른 키를 담음
- `mes.change_log.row_key`: 동기화용 기록. 표마다 다른 키를 담음

## 연결 규칙

| 규칙 | 출발 → 도착 | 설명 |
| --- | --- | --- |
| R-SO | `mes.work_order.so_ref` → `erp.sales_order.so_no` | 양쪽에서 숫자만 남겨 비교함. 2610021 = OD-2610-021 |
| R-W | `mes.worker.id` → `erp.employee.emp_no` | 번호를 4자리로 0 채우고 앞에 GP를 붙임 |
| R-ITEM | `mes.*.product_code` → `erp.item.item_code` | 제품·자재 코드를 그대로 비교함 |
| R-LOT | `erp.shipment.lot_no` → `mes.inspection.lot_no` | 출하 로트와 최종 검사 로트를 그대로 비교함 |
| R-ALIAS | `mes.handover_note.content` → `mes.equipment.code` | 설비 이름·'L라인 N호'·'L-N호'·코드 표기를 설비 코드로 연결함 |

## 시나리오 기대 답

| 질문 | 기대 답 |
| --- | --- |
| P103이 멈춰서 납기가 위험한 수주는? | JB2610-0141 → OD-2610-021 (납기 2026-11-04). 대체 설비 {'P101': '가능', 'P201': '불가 (300톤 < 400톤)'}. 가공 17.9시간, 교대 반영 완료 2026-10-31 15:34 → 납기 내. 연결 작업지시 JB2610-0145가 대기(계획 시작 2026-11-02) — 납기 2026-10-31 위험 |
| JB2610-0141 남은 수량을 P101에서 찍으면 몇 시간? | 3,200개 × 11.5초 = 10.2시간 (준비 70분 포함 11.4시간) |
| P103 진동이 언제부터 상한을 넘었어? | 2026-10-28 09:00:00 (4.90 > 4.50) |
| LT261025-2 로트 검사 결과는? | 최종 검사 기록 없음 — 연결된 검사 행 없음('데이터 없음'으로 안내) |
| 수주 참조 규칙 적용 시 연결률은? | 134 / 136 = 98.5% (빈 값 3건 제외) |

## 일부러 넣은 사례

| 사례 | 내용 | 위치 |
| --- | --- | --- |
| D1 | 작업지시 수주 참조(so_ref) — ERP 수주 번호와 표기가 다름 (2610021 ↔ OD-2610-021) | JB2610-0141, JB2610-0138, JB2610-0145 |
| D2 | 공정별 배정: JB2610-0141의 성형·트리밍 = P103 | JB2610-0141 |
| D3 | P103 고장(비가동 진행 중)·정비 진행, 진동이 10/28부터 상한 초과 | DT-0460, RP-26-041 |
| D4 | 공정 최소 톤수: 브래킷 성형 400톤 → P201(300톤) 불가 | BRK-210R |
| D5 | OD-2610-019 ↔ JB2610-0145: 작업지시가 대기(11/2 시작)라 납기 10/31 위험 | OD-2610-019, JB2610-0145 |
| S-FULL | 수주 참조가 이미 ERP 표기(OD-YYMM-NNN)로 입력됨 | JB2609-0108, JB2608-0110, JB2608-0137, JB2610-0123 … |
| S-BLANK | 수주 참조가 빈 작업지시 (오류 결측) | JB2608-0139, JB2608-0136, JB2609-0106 |
| S-MISSING | ERP에 없는 수주 번호를 참조 | JB2610-0103, JB2609-0141 |
