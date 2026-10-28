-- 00프레스(가상) ERP 원천 표 정의. 생성기 app/dataset
create schema if not exists erp;

create table erp.item (
  item_code varchar(20),
  item_name varchar(100),
  spec varchar(100),
  unit varchar(10),
  item_type varchar(2),
  std_price numeric(12,2),
  safety_stock numeric(14,3),
  use_yn char(1),
  updated_at timestamp,
  primary key (item_code)
);
comment on table erp.item is '품목';
comment on column erp.item.item_code is '품번';
comment on column erp.item.item_name is '품명';
comment on column erp.item.spec is '규격';
comment on column erp.item.unit is '단위(EA 개 · KG 킬로그램)';
comment on column erp.item.item_type is '품목 구분(FG 완제품 · SF 반제품 · RM 원자재)';
comment on column erp.item.std_price is '표준 단가(원)';
comment on column erp.item.safety_stock is '안전 재고 수량';
comment on column erp.item.use_yn is '사용 여부(Y/N)';
comment on column erp.item.updated_at is '마지막 수정 시각';

create table erp.vendor (
  vendor_code varchar(20),
  vendor_name varchar(100),
  biz_no varchar(20),
  vendor_type char(1),
  contact_name varchar(50),
  payment_terms varchar(50),
  use_yn char(1),
  primary key (vendor_code)
);
comment on table erp.vendor is '거래처';
comment on column erp.vendor.vendor_code is '거래처 코드';
comment on column erp.vendor.vendor_name is '거래처 상호';
comment on column erp.vendor.biz_no is '사업자등록번호(뒷자리 가림)';
comment on column erp.vendor.vendor_type is '거래 구분(P 매입처 · S 매출처 · B 매입·매출)';
comment on column erp.vendor.contact_name is '담당자 이름(가명)';
comment on column erp.vendor.payment_terms is '결제 조건';
comment on column erp.vendor.use_yn is '사용 여부(Y/N)';

create table erp.purchase_order (
  po_no varchar(20),
  vendor_code varchar(20),
  po_date date,
  due_date date,
  status varchar(10),
  total_amount numeric(16,2),
  buyer varchar(50),
  remark varchar(100),
  primary key (po_no)
);
comment on table erp.purchase_order is '구매 발주';
comment on column erp.purchase_order.po_no is '발주 번호';
comment on column erp.purchase_order.vendor_code is '공급 거래처 코드';
comment on column erp.purchase_order.po_date is '발주일';
comment on column erp.purchase_order.due_date is '입고 요청일';
comment on column erp.purchase_order.status is '진행 상태(발주 · 부분입고 · 입고완료 · 취소)';
comment on column erp.purchase_order.total_amount is '발주 금액(원, 부가세 별도)';
comment on column erp.purchase_order.buyer is '구매 담당';
comment on column erp.purchase_order.remark is '비고';

create table erp.purchase_order_line (
  po_no varchar(20),
  line_no int,
  item_code varchar(20),
  qty numeric(14,3),
  unit_price numeric(12,2),
  received_qty numeric(14,3),
  due_date date,
  primary key (po_no, line_no)
);
comment on table erp.purchase_order_line is '구매 발주 상세';
comment on column erp.purchase_order_line.po_no is '발주 번호';
comment on column erp.purchase_order_line.line_no is '상세 순번';
comment on column erp.purchase_order_line.item_code is '발주 품번';
comment on column erp.purchase_order_line.qty is '발주 수량';
comment on column erp.purchase_order_line.unit_price is '발주 단가(원)';
comment on column erp.purchase_order_line.received_qty is '누적 입고 수량';
comment on column erp.purchase_order_line.due_date is '상세별 입고 요청일';

create table erp.goods_receipt (
  gr_no varchar(20),
  po_no varchar(20),
  item_code varchar(20),
  gr_date date,
  qty numeric(14,3),
  accepted_qty numeric(14,3),
  rejected_qty numeric(14,3),
  warehouse varchar(50),
  inspector varchar(50),
  primary key (gr_no)
);
comment on table erp.goods_receipt is '입고';
comment on column erp.goods_receipt.gr_no is '입고 번호';
comment on column erp.goods_receipt.po_no is '근거 발주 번호';
comment on column erp.goods_receipt.item_code is '입고 품번';
comment on column erp.goods_receipt.gr_date is '입고일';
comment on column erp.goods_receipt.qty is '입고 수량';
comment on column erp.goods_receipt.accepted_qty is '검수 합격 수량';
comment on column erp.goods_receipt.rejected_qty is '검수 불합격 수량';
comment on column erp.goods_receipt.warehouse is '입고 창고';
comment on column erp.goods_receipt.inspector is '검수 담당';

create table erp.stock (
  warehouse varchar(50),
  item_code varchar(20),
  qty numeric(14,3),
  reserved_qty numeric(14,3),
  available_qty numeric(14,3),
  last_in_date date,
  last_out_date date,
  primary key (warehouse, item_code)
);
comment on table erp.stock is '창고별 재고';
comment on column erp.stock.warehouse is '창고';
comment on column erp.stock.item_code is '품번';
comment on column erp.stock.qty is '현재 재고 수량';
comment on column erp.stock.reserved_qty is '출고 예약 수량';
comment on column erp.stock.available_qty is '가용 수량(현재 − 예약)';
comment on column erp.stock.last_in_date is '최근 입고일';
comment on column erp.stock.last_out_date is '최근 출고일';

create table erp.sales_order (
  so_no varchar(20),
  customer_code varchar(20),
  so_date date,
  due_date date,
  status varchar(10),
  total_amount numeric(16,2),
  sales_rep varchar(50),
  primary key (so_no)
);
comment on table erp.sales_order is '수주';
comment on column erp.sales_order.so_no is '수주 번호';
comment on column erp.sales_order.customer_code is '주문 고객 코드';
comment on column erp.sales_order.so_date is '수주일';
comment on column erp.sales_order.due_date is '납기일';
comment on column erp.sales_order.status is '진행 상태(접수 · 생산중 · 출하완료 · 취소)';
comment on column erp.sales_order.total_amount is '수주 금액(원)';
comment on column erp.sales_order.sales_rep is '영업 담당';

create table erp.sales_order_line (
  so_no varchar(20),
  line_no int,
  item_code varchar(20),
  qty numeric(14,3),
  unit_price numeric(12,2),
  shipped_qty numeric(14,3),
  primary key (so_no, line_no)
);
comment on table erp.sales_order_line is '수주 상세';
comment on column erp.sales_order_line.so_no is '수주 번호';
comment on column erp.sales_order_line.line_no is '상세 순번';
comment on column erp.sales_order_line.item_code is '주문 품번';
comment on column erp.sales_order_line.qty is '주문 수량';
comment on column erp.sales_order_line.unit_price is '판매 단가(원)';
comment on column erp.sales_order_line.shipped_qty is '누적 출하 수량';

create table erp.shipment (
  ship_no varchar(20),
  so_no varchar(20),
  item_code varchar(20),
  ship_date date,
  qty numeric(14,3),
  carrier varchar(50),
  lot_no varchar(20),
  primary key (ship_no)
);
comment on table erp.shipment is '출하';
comment on column erp.shipment.ship_no is '출하 번호';
comment on column erp.shipment.so_no is '근거 수주 번호';
comment on column erp.shipment.item_code is '출하 품번';
comment on column erp.shipment.ship_date is '출하일';
comment on column erp.shipment.qty is '출하 수량';
comment on column erp.shipment.carrier is '운송사';
comment on column erp.shipment.lot_no is '출하 제품 로트';

create table erp.sales_invoice (
  invoice_no varchar(20),
  customer_code varchar(20),
  invoice_date date,
  supply_amount numeric(16,2),
  vat numeric(16,2),
  total_amount numeric(16,2),
  collected_yn char(1),
  collected_date date,
  primary key (invoice_no)
);
comment on table erp.sales_invoice is '매출 전표';
comment on column erp.sales_invoice.invoice_no is '매출 전표 번호';
comment on column erp.sales_invoice.customer_code is '청구 고객 코드';
comment on column erp.sales_invoice.invoice_date is '발행일';
comment on column erp.sales_invoice.supply_amount is '공급가액(원)';
comment on column erp.sales_invoice.vat is '부가세(원)';
comment on column erp.sales_invoice.total_amount is '합계(원)';
comment on column erp.sales_invoice.collected_yn is '수금 완료 여부(Y/N)';
comment on column erp.sales_invoice.collected_date is '수금일';

create table erp.purchase_invoice (
  invoice_no varchar(20),
  vendor_code varchar(20),
  invoice_date date,
  supply_amount numeric(16,2),
  vat numeric(16,2),
  total_amount numeric(16,2),
  paid_yn char(1),
  paid_date date,
  primary key (invoice_no)
);
comment on table erp.purchase_invoice is '매입 전표';
comment on column erp.purchase_invoice.invoice_no is '매입 전표 번호';
comment on column erp.purchase_invoice.vendor_code is '공급 거래처 코드';
comment on column erp.purchase_invoice.invoice_date is '발행일';
comment on column erp.purchase_invoice.supply_amount is '공급가액(원)';
comment on column erp.purchase_invoice.vat is '부가세(원)';
comment on column erp.purchase_invoice.total_amount is '합계(원)';
comment on column erp.purchase_invoice.paid_yn is '지급 완료 여부(Y/N)';
comment on column erp.purchase_invoice.paid_date is '지급일';

create table erp.gl_account (
  account_code varchar(10),
  account_name varchar(50),
  account_type varchar(10),
  parent_code varchar(10),
  use_yn char(1),
  primary key (account_code)
);
comment on table erp.gl_account is '회계 계정';
comment on column erp.gl_account.account_code is '계정 코드';
comment on column erp.gl_account.account_name is '계정명';
comment on column erp.gl_account.account_type is '계정 구분(자산 · 부채 · 자본 · 수익 · 비용)';
comment on column erp.gl_account.parent_code is '상위 계정 코드';
comment on column erp.gl_account.use_yn is '사용 여부(Y/N)';

create table erp.journal_entry (
  entry_no varchar(20),
  line_no int,
  entry_date date,
  account_code varchar(10),
  vendor_code varchar(20),
  debit numeric(16,2),
  credit numeric(16,2),
  description varchar(100),
  primary key (entry_no, line_no)
);
comment on table erp.journal_entry is '분개';
comment on column erp.journal_entry.entry_no is '전표 번호';
comment on column erp.journal_entry.line_no is '분개 순번';
comment on column erp.journal_entry.entry_date is '전표일';
comment on column erp.journal_entry.account_code is '계정 코드';
comment on column erp.journal_entry.vendor_code is '관련 거래처 코드';
comment on column erp.journal_entry.debit is '차변 금액';
comment on column erp.journal_entry.credit is '대변 금액';
comment on column erp.journal_entry.description is '적요';

create table erp.employee (
  emp_no varchar(20),
  emp_name varchar(50),
  dept_code varchar(10),
  position varchar(20),
  hire_date date,
  employment_type varchar(10),
  status varchar(10),
  primary key (emp_no)
);
comment on table erp.employee is '직원';
comment on column erp.employee.emp_no is '사번';
comment on column erp.employee.emp_name is '이름(가명)';
comment on column erp.employee.dept_code is '소속 부서 코드';
comment on column erp.employee.position is '직급';
comment on column erp.employee.hire_date is '입사일';
comment on column erp.employee.employment_type is '고용 형태(정규 · 계약)';
comment on column erp.employee.status is '재직 상태(재직 · 휴직 · 퇴직)';

create table erp.department (
  dept_code varchar(10),
  dept_name varchar(50),
  parent_code varchar(10),
  manager_emp_no varchar(20),
  primary key (dept_code)
);
comment on table erp.department is '부서';
comment on column erp.department.dept_code is '부서 코드';
comment on column erp.department.dept_name is '부서명';
comment on column erp.department.parent_code is '상위 부서 코드';
comment on column erp.department.manager_emp_no is '부서장 사번';

create table erp.attendance (
  emp_no varchar(20),
  work_date date,
  clock_in time,
  clock_out time,
  work_type varchar(10),
  overtime_min int,
  late_yn char(1),
  primary key (emp_no, work_date)
);
comment on table erp.attendance is '근태';
comment on column erp.attendance.emp_no is '사번';
comment on column erp.attendance.work_date is '근무일';
comment on column erp.attendance.clock_in is '출근 시각';
comment on column erp.attendance.clock_out is '퇴근 시각';
comment on column erp.attendance.work_type is '근무 구분(근무 · 외근 · 연차)';
comment on column erp.attendance.overtime_min is '연장 근무 시간(분)';
comment on column erp.attendance.late_yn is '지각 여부(Y/N)';

create table erp.fixed_asset (
  asset_no varchar(20),
  asset_name varchar(100),
  asset_type varchar(20),
  acquired_on date,
  acquisition_cost numeric(16,2),
  book_value numeric(16,2),
  location varchar(50),
  equipment_code varchar(20),
  primary key (asset_no)
);
comment on table erp.fixed_asset is '고정자산';
comment on column erp.fixed_asset.asset_no is '자산 번호';
comment on column erp.fixed_asset.asset_name is '자산명';
comment on column erp.fixed_asset.asset_type is '자산 종류(기계장치 · 차량 · 공구)';
comment on column erp.fixed_asset.acquired_on is '취득일';
comment on column erp.fixed_asset.acquisition_cost is '취득가(원)';
comment on column erp.fixed_asset.book_value is '장부가(원)';
comment on column erp.fixed_asset.location is '설치·보관 위치';
comment on column erp.fixed_asset.equipment_code is '생산 설비이면 현장 시스템의 설비 코드';

create table erp.change_log (
  change_id bigint,
  table_name varchar(50),
  row_key varchar(100),
  operation varchar(10),
  changed_at timestamp,
  primary key (change_id)
);
comment on table erp.change_log is '원천 변경 이력';
comment on column erp.change_log.change_id is '변경 순번';
comment on column erp.change_log.table_name is '변경된 표';
comment on column erp.change_log.row_key is '변경된 행의 키';
comment on column erp.change_log.operation is 'INSERT · UPDATE · DELETE';
comment on column erp.change_log.changed_at is '변경 시각';
