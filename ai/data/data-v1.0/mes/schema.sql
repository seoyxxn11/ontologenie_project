-- 00프레스(가상) MES 원천 표 정의. 생성기 app/dataset
create schema if not exists mes;

create table mes.equipment (
  code varchar(20),
  name varchar(50),
  kind varchar(10),
  line varchar(10),
  capacity_ton int,
  status varchar(10),
  installed_on date,
  maker varchar(50),
  primary key (code)
);
comment on table mes.equipment is '설비';
comment on column mes.equipment.code is '설비 코드';
comment on column mes.equipment.name is '설비 이름';
comment on column mes.equipment.kind is '설비 종류(BLK 블랭킹 · PRS 프레스 · WLD 용접 · INS 검사)';
comment on column mes.equipment.line is '설치 라인';
comment on column mes.equipment.capacity_ton is '프레스 능력(톤)';
comment on column mes.equipment.status is '상태(가동 · 정지 · 정비중)';
comment on column mes.equipment.installed_on is '설치일';
comment on column mes.equipment.maker is '제조사';

create table mes.worker (
  id varchar(10),
  name varchar(50),
  shift varchar(10),
  line varchar(10),
  skill_level int,
  active_yn char(1),
  primary key (id)
);
comment on table mes.worker is '현장 작업자';
comment on column mes.worker.id is '작업자 번호';
comment on column mes.worker.name is '이름(가명)';
comment on column mes.worker.shift is '근무 조(주간 · 야간)';
comment on column mes.worker.line is '배치 라인';
comment on column mes.worker.skill_level is '숙련도(1~5)';
comment on column mes.worker.active_yn is '근무 중 여부(Y/N)';

create table mes.routing (
  product_code varchar(20),
  op_seq int,
  op_name varchar(50),
  equipment_kind varchar(10),
  std_cycle_sec numeric(8,2),
  std_setup_min int,
  min_tonnage int,
  primary key (product_code, op_seq)
);
comment on table mes.routing is '제품별 공정';
comment on column mes.routing.product_code is '제품 코드';
comment on column mes.routing.op_seq is '공정 순서(10 · 20 · 30 …)';
comment on column mes.routing.op_name is '공정명';
comment on column mes.routing.equipment_kind is '필요한 설비 종류';
comment on column mes.routing.std_cycle_sec is '개당 표준 가공 시간(초)';
comment on column mes.routing.std_setup_min is '표준 준비 시간(분)';
comment on column mes.routing.min_tonnage is '프레스 공정의 최소 능력(톤)';

create table mes.work_order (
  wo_no varchar(20),
  product_code varchar(20),
  die_drawing_code varchar(20),
  status varchar(10),
  priority varchar(10),
  line varchar(10),
  planned_qty int,
  produced_qty int,
  defect_qty int,
  planned_start date,
  planned_end date,
  actual_start timestamp,
  actual_end timestamp,
  so_ref varchar(20),
  primary key (wo_no)
);
comment on table mes.work_order is '작업지시';
comment on column mes.work_order.wo_no is '작업지시 번호';
comment on column mes.work_order.product_code is '생산 제품 코드';
comment on column mes.work_order.die_drawing_code is '사용 금형 도면 번호';
comment on column mes.work_order.status is '진행 상태(대기 · 진행 · 완료 · 보류)';
comment on column mes.work_order.priority is '우선순위(긴급 · 보통 · 낮음)';
comment on column mes.work_order.line is '생산 라인';
comment on column mes.work_order.planned_qty is '계획 수량';
comment on column mes.work_order.produced_qty is '생산 수량(마지막 프레스 공정 양품)';
comment on column mes.work_order.defect_qty is '불량 수량';
comment on column mes.work_order.planned_start is '계획 시작일';
comment on column mes.work_order.planned_end is '계획 종료일';
comment on column mes.work_order.actual_start is '실제 시작 시각';
comment on column mes.work_order.actual_end is '실제 종료 시각';
comment on column mes.work_order.so_ref is '주문 참조 번호';

create table mes.work_order_operation (
  wo_no varchar(20),
  op_seq int,
  equipment_code varchar(20),
  status varchar(10),
  remaining_qty int,
  primary key (wo_no, op_seq)
);
comment on table mes.work_order_operation is '작업지시 공정별 배정';
comment on column mes.work_order_operation.wo_no is '작업지시 번호';
comment on column mes.work_order_operation.op_seq is '공정 순서';
comment on column mes.work_order_operation.equipment_code is '배정 설비 코드';
comment on column mes.work_order_operation.status is '공정 상태(대기 · 진행 · 완료 · 중단)';
comment on column mes.work_order_operation.remaining_qty is '남은 수량';

create table mes.production_result (
  result_id bigint,
  wo_no varchar(20),
  op_seq int,
  equipment_code varchar(20),
  worker_id varchar(10),
  shift varchar(10),
  started_at timestamp,
  ended_at timestamp,
  good_qty int,
  ng_qty int,
  primary key (result_id)
);
comment on table mes.production_result is '공정 실적';
comment on column mes.production_result.result_id is '실적 번호';
comment on column mes.production_result.wo_no is '작업지시 번호';
comment on column mes.production_result.op_seq is '공정 순서';
comment on column mes.production_result.equipment_code is '작업 설비 코드';
comment on column mes.production_result.worker_id is '작업자 번호';
comment on column mes.production_result.shift is '근무 조';
comment on column mes.production_result.started_at is '시작 시각';
comment on column mes.production_result.ended_at is '종료 시각';
comment on column mes.production_result.good_qty is '양품 수량';
comment on column mes.production_result.ng_qty is '불량 수량';

create table mes.material_issue (
  issue_id bigint,
  wo_no varchar(20),
  item_code varchar(20),
  lot_no varchar(20),
  qty numeric(14,3),
  unit varchar(10),
  issued_at timestamp,
  primary key (issue_id)
);
comment on table mes.material_issue is '자재 투입';
comment on column mes.material_issue.issue_id is '투입 번호';
comment on column mes.material_issue.wo_no is '작업지시 번호';
comment on column mes.material_issue.item_code is '투입 자재 품번';
comment on column mes.material_issue.lot_no is '자재 로트';
comment on column mes.material_issue.qty is '투입량';
comment on column mes.material_issue.unit is '단위';
comment on column mes.material_issue.issued_at is '투입 시각';

create table mes.downtime (
  downtime_id varchar(20),
  equipment_code varchar(20),
  wo_no varchar(20),
  reason_code varchar(10),
  reason varchar(100),
  started_at timestamp,
  ended_at timestamp,
  minutes int,
  primary key (downtime_id)
);
comment on table mes.downtime is '설비 비가동';
comment on column mes.downtime.downtime_id is '비가동 번호';
comment on column mes.downtime.equipment_code is '멈춘 설비 코드';
comment on column mes.downtime.wo_no is '당시 작업지시(없으면 빈 값)';
comment on column mes.downtime.reason_code is '사유 코드(BRK 고장 · DIE 금형교체 · MAT 자재대기 · PM 예방정비 · QA 품질 정지)';
comment on column mes.downtime.reason is '사유 설명';
comment on column mes.downtime.started_at is '시작 시각';
comment on column mes.downtime.ended_at is '종료 시각(진행 중이면 빈 값)';
comment on column mes.downtime.minutes is '비가동 시간(분)';

create table mes.sensor_reading (
  equipment_code varchar(20),
  measured_at timestamp,
  metric varchar(20),
  value numeric(10,2),
  threshold_high numeric(10,2),
  primary key (equipment_code, measured_at, metric)
);
comment on table mes.sensor_reading is '설비 센서 측정';
comment on column mes.sensor_reading.equipment_code is '설비 코드';
comment on column mes.sensor_reading.measured_at is '측정 시각';
comment on column mes.sensor_reading.metric is '측정 항목(vibration_mm_s 진동 · temp_c 온도)';
comment on column mes.sensor_reading.value is '측정값';
comment on column mes.sensor_reading.threshold_high is '경고 상한';

create table mes.defect (
  defect_id bigint,
  wo_no varchar(20),
  op_seq int,
  equipment_code varchar(20),
  defect_type varchar(20),
  qty int,
  disposition varchar(20),
  detected_at timestamp,
  detected_by varchar(10),
  primary key (defect_id)
);
comment on table mes.defect is '불량 기록';
comment on column mes.defect.defect_id is '불량 번호';
comment on column mes.defect.wo_no is '작업지시 번호';
comment on column mes.defect.op_seq is '발생 공정';
comment on column mes.defect.equipment_code is '발생 설비 코드';
comment on column mes.defect.defect_type is '불량 유형(크랙 · 주름 · 버 · 치수 · 스크래치)';
comment on column mes.defect.qty is '불량 수량';
comment on column mes.defect.disposition is '처리(폐기 · 재작업)';
comment on column mes.defect.detected_at is '발견 시각';
comment on column mes.defect.detected_by is '발견 작업자 번호';

create table mes.inspection (
  inspection_id bigint,
  wo_no varchar(20),
  lot_no varchar(20),
  inspection_type varchar(20),
  item varchar(50),
  spec_low numeric(10,3),
  spec_high numeric(10,3),
  measured numeric(10,3),
  result varchar(5),
  inspected_at timestamp,
  primary key (inspection_id)
);
comment on table mes.inspection is '품질 검사';
comment on column mes.inspection.inspection_id is '검사 번호';
comment on column mes.inspection.wo_no is '작업지시 번호';
comment on column mes.inspection.lot_no is '검사 제품 로트';
comment on column mes.inspection.inspection_type is '검사 종류(초물 · 공정 · 최종)';
comment on column mes.inspection.item is '검사 항목';
comment on column mes.inspection.spec_low is '규격 하한';
comment on column mes.inspection.spec_high is '규격 상한';
comment on column mes.inspection.measured is '측정값';
comment on column mes.inspection.result is '판정(OK · NG)';
comment on column mes.inspection.inspected_at is '검사 시각';

create table mes.die (
  die_code varchar(20),
  drawing_code varchar(20),
  product_code varchar(20),
  total_strokes bigint,
  pm_interval_strokes int,
  last_pm_at date,
  status varchar(10),
  location varchar(50),
  primary key (die_code)
);
comment on table mes.die is '금형';
comment on column mes.die.die_code is '금형 코드';
comment on column mes.die.drawing_code is '금형 도면 번호';
comment on column mes.die.product_code is '생산 제품 코드';
comment on column mes.die.total_strokes is '누적 타수';
comment on column mes.die.pm_interval_strokes is '정비 주기(타수)';
comment on column mes.die.last_pm_at is '마지막 정비일';
comment on column mes.die.status is '상태(사용 · 정비 · 폐기)';
comment on column mes.die.location is '보관 위치';

create table mes.maintenance_order (
  mo_no varchar(20),
  target_type varchar(10),
  target_code varchar(20),
  mo_type varchar(10),
  status varchar(10),
  planned_date date,
  completed_at timestamp,
  technician varchar(50),
  parts_cost numeric(12,2),
  note varchar(100),
  primary key (mo_no)
);
comment on table mes.maintenance_order is '정비 지시';
comment on column mes.maintenance_order.mo_no is '정비 번호';
comment on column mes.maintenance_order.target_type is '정비 대상 구분(설비 · 금형)';
comment on column mes.maintenance_order.target_code is '정비 대상 코드';
comment on column mes.maintenance_order.mo_type is '정비 종류(예방 · 고장 · 개선)';
comment on column mes.maintenance_order.status is '상태(계획 · 진행 · 완료)';
comment on column mes.maintenance_order.planned_date is '계획일';
comment on column mes.maintenance_order.completed_at is '완료 시각(진행 중이면 빈 값)';
comment on column mes.maintenance_order.technician is '담당 부서';
comment on column mes.maintenance_order.parts_cost is '부품비(원)';
comment on column mes.maintenance_order.note is '작업 내용';

create table mes.handover_note (
  note_id varchar(20),
  shift_date date,
  shift varchar(10),
  author_id varchar(10),
  written_at timestamp,
  content text,
  primary key (note_id)
);
comment on table mes.handover_note is '교대 인수인계 메모';
comment on column mes.handover_note.note_id is '메모 번호';
comment on column mes.handover_note.shift_date is '교대가 시작한 날';
comment on column mes.handover_note.shift is '교대(주간 · 야간)';
comment on column mes.handover_note.author_id is '작성 작업자 번호';
comment on column mes.handover_note.written_at is '작성 시각(교대 끝)';
comment on column mes.handover_note.content is '메모 원문(첫 줄 머리말 포함)';

create table mes.change_log (
  change_id bigint,
  table_name varchar(50),
  row_key varchar(100),
  operation varchar(10),
  changed_at timestamp,
  primary key (change_id)
);
comment on table mes.change_log is '원천 변경 이력';
comment on column mes.change_log.change_id is '변경 순번';
comment on column mes.change_log.table_name is '변경된 표';
comment on column mes.change_log.row_key is '변경된 행의 키';
comment on column mes.change_log.operation is 'INSERT · UPDATE · DELETE';
comment on column mes.change_log.changed_at is '변경 시각';
