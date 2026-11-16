--
-- PostgreSQL database dump
--

\restrict H0GqoWwa7nRV4tLFICtTix0SHBjTpSZB67JmvIbnsDZYkffRhxUXdSHDjwmQeIL

-- Dumped from database version 18.6 (Debian 18.6-1.pgdg13+2)
-- Dumped by pg_dump version 18.6 (Debian 18.6-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: mes; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA mes;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: change_log; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.change_log (
    change_id bigint NOT NULL,
    table_name character varying(50),
    row_key character varying(100),
    operation character varying(10),
    changed_at timestamp without time zone
);


--
-- Name: TABLE change_log; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.change_log IS '원천 변경 이력';


--
-- Name: COLUMN change_log.change_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.change_log.change_id IS '변경 순번';


--
-- Name: COLUMN change_log.table_name; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.change_log.table_name IS '변경된 표';


--
-- Name: COLUMN change_log.row_key; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.change_log.row_key IS '변경된 행의 키';


--
-- Name: COLUMN change_log.operation; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.change_log.operation IS 'INSERT · UPDATE · DELETE';


--
-- Name: COLUMN change_log.changed_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.change_log.changed_at IS '변경 시각';


--
-- Name: defect; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.defect (
    defect_id bigint NOT NULL,
    wo_no character varying(20),
    op_seq integer,
    equipment_code character varying(20),
    defect_type character varying(20),
    qty integer,
    disposition character varying(20),
    detected_at timestamp without time zone,
    detected_by character varying(10)
);


--
-- Name: TABLE defect; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.defect IS '불량 기록';


--
-- Name: COLUMN defect.defect_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.defect_id IS '불량 번호';


--
-- Name: COLUMN defect.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.wo_no IS '작업지시 번호';


--
-- Name: COLUMN defect.op_seq; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.op_seq IS '발생 공정';


--
-- Name: COLUMN defect.equipment_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.equipment_code IS '발생 설비 코드';


--
-- Name: COLUMN defect.defect_type; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.defect_type IS '불량 유형(크랙 · 주름 · 버 · 치수 · 스크래치)';


--
-- Name: COLUMN defect.qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.qty IS '불량 수량';


--
-- Name: COLUMN defect.disposition; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.disposition IS '처리(폐기 · 재작업)';


--
-- Name: COLUMN defect.detected_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.detected_at IS '발견 시각';


--
-- Name: COLUMN defect.detected_by; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.defect.detected_by IS '발견 작업자 번호';


--
-- Name: die; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.die (
    die_code character varying(20) NOT NULL,
    drawing_code character varying(20),
    product_code character varying(20),
    total_strokes bigint,
    pm_interval_strokes integer,
    last_pm_at date,
    status character varying(10),
    location character varying(50)
);


--
-- Name: TABLE die; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.die IS '금형';


--
-- Name: COLUMN die.die_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.die_code IS '금형 코드';


--
-- Name: COLUMN die.drawing_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.drawing_code IS '금형 도면 번호';


--
-- Name: COLUMN die.product_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.product_code IS '생산 제품 코드';


--
-- Name: COLUMN die.total_strokes; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.total_strokes IS '누적 타수';


--
-- Name: COLUMN die.pm_interval_strokes; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.pm_interval_strokes IS '정비 주기(타수)';


--
-- Name: COLUMN die.last_pm_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.last_pm_at IS '마지막 정비일';


--
-- Name: COLUMN die.status; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.status IS '상태(사용 · 정비 · 폐기)';


--
-- Name: COLUMN die.location; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.die.location IS '보관 위치';


--
-- Name: downtime; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.downtime (
    downtime_id character varying(20) NOT NULL,
    equipment_code character varying(20),
    wo_no character varying(20),
    reason_code character varying(10),
    reason character varying(100),
    started_at timestamp without time zone,
    ended_at timestamp without time zone,
    minutes integer
);


--
-- Name: TABLE downtime; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.downtime IS '설비 비가동';


--
-- Name: COLUMN downtime.downtime_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.downtime_id IS '비가동 번호';


--
-- Name: COLUMN downtime.equipment_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.equipment_code IS '멈춘 설비 코드';


--
-- Name: COLUMN downtime.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.wo_no IS '당시 작업지시(없으면 빈 값)';


--
-- Name: COLUMN downtime.reason_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.reason_code IS '사유 코드(BRK 고장 · DIE 금형교체 · MAT 자재대기 · PM 예방정비 · QA 품질 정지)';


--
-- Name: COLUMN downtime.reason; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.reason IS '사유 설명';


--
-- Name: COLUMN downtime.started_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.started_at IS '시작 시각';


--
-- Name: COLUMN downtime.ended_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.ended_at IS '종료 시각(진행 중이면 빈 값)';


--
-- Name: COLUMN downtime.minutes; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.downtime.minutes IS '비가동 시간(분)';


--
-- Name: equipment; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.equipment (
    code character varying(20) NOT NULL,
    name character varying(50),
    kind character varying(10),
    line character varying(10),
    capacity_ton integer,
    status character varying(10),
    installed_on date,
    maker character varying(50)
);


--
-- Name: TABLE equipment; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.equipment IS '설비';


--
-- Name: COLUMN equipment.code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.code IS '설비 코드';


--
-- Name: COLUMN equipment.name; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.name IS '설비 이름';


--
-- Name: COLUMN equipment.kind; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.kind IS '설비 종류(BLK 블랭킹 · PRS 프레스 · WLD 용접 · INS 검사)';


--
-- Name: COLUMN equipment.line; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.line IS '설치 라인';


--
-- Name: COLUMN equipment.capacity_ton; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.capacity_ton IS '프레스 능력(톤)';


--
-- Name: COLUMN equipment.status; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.status IS '상태(가동 · 정지 · 정비중)';


--
-- Name: COLUMN equipment.installed_on; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.installed_on IS '설치일';


--
-- Name: COLUMN equipment.maker; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.equipment.maker IS '제조사';


--
-- Name: handover_note; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.handover_note (
    note_id character varying(20) NOT NULL,
    shift_date date,
    shift character varying(10),
    author_id character varying(10),
    written_at timestamp without time zone,
    content text
);


--
-- Name: TABLE handover_note; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.handover_note IS '교대 인수인계 메모';


--
-- Name: COLUMN handover_note.note_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.note_id IS '메모 번호';


--
-- Name: COLUMN handover_note.shift_date; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.shift_date IS '교대가 시작한 날';


--
-- Name: COLUMN handover_note.shift; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.shift IS '교대(주간 · 야간)';


--
-- Name: COLUMN handover_note.author_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.author_id IS '작성 작업자 번호';


--
-- Name: COLUMN handover_note.written_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.written_at IS '작성 시각(교대 끝)';


--
-- Name: COLUMN handover_note.content; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.handover_note.content IS '메모 원문(첫 줄 머리말 포함)';


--
-- Name: inspection; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.inspection (
    inspection_id bigint NOT NULL,
    wo_no character varying(20),
    lot_no character varying(20),
    inspection_type character varying(20),
    item character varying(50),
    spec_low numeric(10,3),
    spec_high numeric(10,3),
    measured numeric(10,3),
    result character varying(5),
    inspected_at timestamp without time zone
);


--
-- Name: TABLE inspection; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.inspection IS '품질 검사';


--
-- Name: COLUMN inspection.inspection_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.inspection_id IS '검사 번호';


--
-- Name: COLUMN inspection.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.wo_no IS '작업지시 번호';


--
-- Name: COLUMN inspection.lot_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.lot_no IS '검사 제품 로트';


--
-- Name: COLUMN inspection.inspection_type; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.inspection_type IS '검사 종류(초물 · 공정 · 최종)';


--
-- Name: COLUMN inspection.item; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.item IS '검사 항목';


--
-- Name: COLUMN inspection.spec_low; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.spec_low IS '규격 하한';


--
-- Name: COLUMN inspection.spec_high; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.spec_high IS '규격 상한';


--
-- Name: COLUMN inspection.measured; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.measured IS '측정값';


--
-- Name: COLUMN inspection.result; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.result IS '판정(OK · NG)';


--
-- Name: COLUMN inspection.inspected_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.inspection.inspected_at IS '검사 시각';


--
-- Name: maintenance_order; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.maintenance_order (
    mo_no character varying(20) NOT NULL,
    target_type character varying(10),
    target_code character varying(20),
    mo_type character varying(10),
    status character varying(10),
    planned_date date,
    completed_at timestamp without time zone,
    technician character varying(50),
    parts_cost numeric(12,2),
    note character varying(100)
);


--
-- Name: TABLE maintenance_order; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.maintenance_order IS '정비 지시';


--
-- Name: COLUMN maintenance_order.mo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.mo_no IS '정비 번호';


--
-- Name: COLUMN maintenance_order.target_type; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.target_type IS '정비 대상 구분(설비 · 금형)';


--
-- Name: COLUMN maintenance_order.target_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.target_code IS '정비 대상 코드';


--
-- Name: COLUMN maintenance_order.mo_type; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.mo_type IS '정비 종류(예방 · 고장 · 개선)';


--
-- Name: COLUMN maintenance_order.status; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.status IS '상태(계획 · 진행 · 완료)';


--
-- Name: COLUMN maintenance_order.planned_date; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.planned_date IS '계획일';


--
-- Name: COLUMN maintenance_order.completed_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.completed_at IS '완료 시각(진행 중이면 빈 값)';


--
-- Name: COLUMN maintenance_order.technician; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.technician IS '담당 부서';


--
-- Name: COLUMN maintenance_order.parts_cost; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.parts_cost IS '부품비(원)';


--
-- Name: COLUMN maintenance_order.note; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.maintenance_order.note IS '작업 내용';


--
-- Name: material_issue; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.material_issue (
    issue_id bigint NOT NULL,
    wo_no character varying(20),
    item_code character varying(20),
    lot_no character varying(20),
    qty numeric(14,3),
    unit character varying(10),
    issued_at timestamp without time zone
);


--
-- Name: TABLE material_issue; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.material_issue IS '자재 투입';


--
-- Name: COLUMN material_issue.issue_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.issue_id IS '투입 번호';


--
-- Name: COLUMN material_issue.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.wo_no IS '작업지시 번호';


--
-- Name: COLUMN material_issue.item_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.item_code IS '투입 자재 품번';


--
-- Name: COLUMN material_issue.lot_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.lot_no IS '자재 로트';


--
-- Name: COLUMN material_issue.qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.qty IS '투입량';


--
-- Name: COLUMN material_issue.unit; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.unit IS '단위';


--
-- Name: COLUMN material_issue.issued_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.material_issue.issued_at IS '투입 시각';


--
-- Name: production_result; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.production_result (
    result_id bigint NOT NULL,
    wo_no character varying(20),
    op_seq integer,
    equipment_code character varying(20),
    worker_id character varying(10),
    shift character varying(10),
    started_at timestamp without time zone,
    ended_at timestamp without time zone,
    good_qty integer,
    ng_qty integer
);


--
-- Name: TABLE production_result; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.production_result IS '공정 실적';


--
-- Name: COLUMN production_result.result_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.result_id IS '실적 번호';


--
-- Name: COLUMN production_result.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.wo_no IS '작업지시 번호';


--
-- Name: COLUMN production_result.op_seq; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.op_seq IS '공정 순서';


--
-- Name: COLUMN production_result.equipment_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.equipment_code IS '작업 설비 코드';


--
-- Name: COLUMN production_result.worker_id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.worker_id IS '작업자 번호';


--
-- Name: COLUMN production_result.shift; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.shift IS '근무 조';


--
-- Name: COLUMN production_result.started_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.started_at IS '시작 시각';


--
-- Name: COLUMN production_result.ended_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.ended_at IS '종료 시각';


--
-- Name: COLUMN production_result.good_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.good_qty IS '양품 수량';


--
-- Name: COLUMN production_result.ng_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.production_result.ng_qty IS '불량 수량';


--
-- Name: routing; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.routing (
    product_code character varying(20) NOT NULL,
    op_seq integer NOT NULL,
    op_name character varying(50),
    equipment_kind character varying(10),
    std_cycle_sec numeric(8,2),
    std_setup_min integer,
    min_tonnage integer
);


--
-- Name: TABLE routing; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.routing IS '제품별 공정';


--
-- Name: COLUMN routing.product_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.product_code IS '제품 코드';


--
-- Name: COLUMN routing.op_seq; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.op_seq IS '공정 순서(10 · 20 · 30 …)';


--
-- Name: COLUMN routing.op_name; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.op_name IS '공정명';


--
-- Name: COLUMN routing.equipment_kind; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.equipment_kind IS '필요한 설비 종류';


--
-- Name: COLUMN routing.std_cycle_sec; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.std_cycle_sec IS '개당 표준 가공 시간(초)';


--
-- Name: COLUMN routing.std_setup_min; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.std_setup_min IS '표준 준비 시간(분)';


--
-- Name: COLUMN routing.min_tonnage; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.routing.min_tonnage IS '프레스 공정의 최소 능력(톤)';


--
-- Name: sensor_reading; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.sensor_reading (
    equipment_code character varying(20) NOT NULL,
    measured_at timestamp without time zone NOT NULL,
    metric character varying(20) NOT NULL,
    value numeric(10,2),
    threshold_high numeric(10,2)
);


--
-- Name: TABLE sensor_reading; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.sensor_reading IS '설비 센서 측정';


--
-- Name: COLUMN sensor_reading.equipment_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.sensor_reading.equipment_code IS '설비 코드';


--
-- Name: COLUMN sensor_reading.measured_at; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.sensor_reading.measured_at IS '측정 시각';


--
-- Name: COLUMN sensor_reading.metric; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.sensor_reading.metric IS '측정 항목(vibration_mm_s 진동 · temp_c 온도)';


--
-- Name: COLUMN sensor_reading.value; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.sensor_reading.value IS '측정값';


--
-- Name: COLUMN sensor_reading.threshold_high; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.sensor_reading.threshold_high IS '경고 상한';


--
-- Name: work_order; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.work_order (
    wo_no character varying(20) NOT NULL,
    product_code character varying(20),
    die_drawing_code character varying(20),
    status character varying(10),
    priority character varying(10),
    line character varying(10),
    planned_qty integer,
    produced_qty integer,
    defect_qty integer,
    planned_start date,
    planned_end date,
    actual_start timestamp without time zone,
    actual_end timestamp without time zone,
    so_ref character varying(20)
);


--
-- Name: TABLE work_order; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.work_order IS '작업지시';


--
-- Name: COLUMN work_order.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.wo_no IS '작업지시 번호';


--
-- Name: COLUMN work_order.product_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.product_code IS '생산 제품 코드';


--
-- Name: COLUMN work_order.die_drawing_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.die_drawing_code IS '사용 금형 도면 번호';


--
-- Name: COLUMN work_order.status; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.status IS '진행 상태(대기 · 진행 · 완료 · 보류)';


--
-- Name: COLUMN work_order.priority; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.priority IS '우선순위(긴급 · 보통 · 낮음)';


--
-- Name: COLUMN work_order.line; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.line IS '생산 라인';


--
-- Name: COLUMN work_order.planned_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.planned_qty IS '계획 수량';


--
-- Name: COLUMN work_order.produced_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.produced_qty IS '생산 수량(마지막 프레스 공정 양품)';


--
-- Name: COLUMN work_order.defect_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.defect_qty IS '불량 수량';


--
-- Name: COLUMN work_order.planned_start; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.planned_start IS '계획 시작일';


--
-- Name: COLUMN work_order.planned_end; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.planned_end IS '계획 종료일';


--
-- Name: COLUMN work_order.actual_start; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.actual_start IS '실제 시작 시각';


--
-- Name: COLUMN work_order.actual_end; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.actual_end IS '실제 종료 시각';


--
-- Name: COLUMN work_order.so_ref; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order.so_ref IS '주문 참조 번호';


--
-- Name: work_order_operation; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.work_order_operation (
    wo_no character varying(20) NOT NULL,
    op_seq integer NOT NULL,
    equipment_code character varying(20),
    status character varying(10),
    remaining_qty integer
);


--
-- Name: TABLE work_order_operation; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.work_order_operation IS '작업지시 공정별 배정';


--
-- Name: COLUMN work_order_operation.wo_no; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order_operation.wo_no IS '작업지시 번호';


--
-- Name: COLUMN work_order_operation.op_seq; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order_operation.op_seq IS '공정 순서';


--
-- Name: COLUMN work_order_operation.equipment_code; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order_operation.equipment_code IS '배정 설비 코드';


--
-- Name: COLUMN work_order_operation.status; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order_operation.status IS '공정 상태(대기 · 진행 · 완료 · 중단)';


--
-- Name: COLUMN work_order_operation.remaining_qty; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.work_order_operation.remaining_qty IS '남은 수량';


--
-- Name: worker; Type: TABLE; Schema: mes; Owner: -
--

CREATE TABLE mes.worker (
    id character varying(10) NOT NULL,
    name character varying(50),
    shift character varying(10),
    line character varying(10),
    skill_level integer,
    active_yn character(1)
);


--
-- Name: TABLE worker; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON TABLE mes.worker IS '현장 작업자';


--
-- Name: COLUMN worker.id; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.id IS '작업자 번호';


--
-- Name: COLUMN worker.name; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.name IS '이름(가명)';


--
-- Name: COLUMN worker.shift; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.shift IS '근무 조(주간 · 야간)';


--
-- Name: COLUMN worker.line; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.line IS '배치 라인';


--
-- Name: COLUMN worker.skill_level; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.skill_level IS '숙련도(1~5)';


--
-- Name: COLUMN worker.active_yn; Type: COMMENT; Schema: mes; Owner: -
--

COMMENT ON COLUMN mes.worker.active_yn IS '근무 중 여부(Y/N)';


--
-- Data for Name: change_log; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.change_log (change_id, table_name, row_key, operation, changed_at) FROM stdin;
1	handover_note	HN-261027-N	INSERT	2026-10-28 05:00:00
2	production_result	200634	INSERT	2026-10-28 12:57:00
3	production_result	200637	INSERT	2026-10-28 16:55:00
4	handover_note	HN-261028-D	INSERT	2026-10-28 17:00:00
5	production_result	200635	INSERT	2026-10-29 12:14:00
6	production_result	200630	INSERT	2026-10-29 13:58:00
7	production_result	200638	INSERT	2026-10-29 14:17:00
8	production_result	200631	INSERT	2026-10-29 16:52:00
9	handover_note	HN-261029-D	INSERT	2026-10-29 17:00:00
10	production_result	200632	INSERT	2026-10-29 17:00:00
11	production_result	200639	INSERT	2026-10-29 17:00:00
12	production_result	200640	INSERT	2026-10-29 23:09:00
13	production_result	200636	INSERT	2026-10-30 00:04:00
14	downtime	DT-0460	INSERT	2026-10-30 03:10:00
15	equipment	P103	UPDATE	2026-10-30 03:10:00
16	work_order_operation	JB2610-0141|20	UPDATE	2026-10-30 03:10:00
17	production_result	200633	INSERT	2026-10-30 03:18:00
18	handover_note	HN-261029-N	INSERT	2026-10-30 05:00:00
19	maintenance_order	RP-26-041	INSERT	2026-10-30 09:00:00
\.


--
-- Data for Name: defect; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.defect (defect_id, wo_no, op_seq, equipment_code, defect_type, qty, disposition, detected_at, detected_by) FROM stdin;
5001	JB2608-0101	10	B101	주름	8	폐기	2026-08-03 09:58:00	6
5002	JB2608-0101	20	P101	치수	15	폐기	2026-08-03 11:58:00	31
5003	JB2608-0101	30	P101	치수	4	폐기	2026-08-03 16:25:00	36
5004	JB2608-0102	10	B101	치수	11	재작업	2026-08-03 11:58:00	6
5005	JB2608-0102	20	P103	주름	5	폐기	2026-08-03 14:16:00	45
5006	JB2608-0102	30	P103	크랙	13	재작업	2026-08-03 22:46:00	24
5007	JB2608-0103	10	B101	버	12	폐기	2026-08-04 10:00:00	6
5008	JB2608-0103	20	P103	버	33	폐기	2026-08-04 14:22:00	6
5009	JB2608-0103	30	P103	크랙	27	폐기	2026-08-05 02:24:00	74
5010	JB2608-0104	10	B101	크랙	21	폐기	2026-08-04 14:22:00	72
5011	JB2608-0104	20	P201	스크래치	8	폐기	2026-08-04 17:00:00	40
5012	JB2608-0104	30	P201	스크래치	21	재작업	2026-08-05 03:05:00	55
5013	JB2608-0104	40	W201	스크래치	14	폐기	2026-08-05 11:08:00	27
5014	JB2608-0105	10	B101	크랙	16	폐기	2026-08-04 17:00:00	45
5015	JB2608-0105	20	P101	주름	17	재작업	2026-08-04 23:20:00	38
5016	JB2608-0105	30	P101	스크래치	19	폐기	2026-08-05 04:31:00	48
5017	JB2608-0106	10	B101	버	27	폐기	2026-08-05 11:31:00	31
5018	JB2608-0106	20	P103	크랙	23	재작업	2026-08-05 16:13:00	45
5019	JB2608-0106	30	P103	크랙	21	폐기	2026-08-06 05:00:00	74
5020	JB2608-0107	10	B101	치수	6	재작업	2026-08-06 01:58:00	74
5021	JB2608-0107	20	P101	치수	9	재작업	2026-08-06 04:55:00	38
5022	JB2608-0107	30	P101	주름	22	재작업	2026-08-06 16:44:00	45
5023	JB2608-0108	10	B101	치수	3	재작업	2026-08-06 21:27:00	38
5024	JB2608-0108	20	P103	크랙	5	재작업	2026-08-06 23:27:00	24
5025	JB2608-0108	30	P103	주름	6	재작업	2026-08-07 02:45:00	74
5026	JB2608-0109	10	B101	버	6	재작업	2026-08-07 10:12:00	45
5027	JB2608-0109	20	P103	주름	10	재작업	2026-08-07 12:12:00	31
5028	JB2608-0109	30	P103	스크래치	6	재작업	2026-08-07 14:47:00	31
5029	JB2608-0110	10	B101	치수	10	폐기	2026-08-07 11:24:00	6
5030	JB2608-0110	20	P101	버	5	재작업	2026-08-07 13:24:00	45
5031	JB2608-0110	30	P101	치수	5	폐기	2026-08-07 16:13:00	36
5032	JB2608-0111	10	B101	주름	22	폐기	2026-08-07 17:00:00	6
5033	JB2608-0111	20	P103	크랙	31	폐기	2026-08-07 23:35:00	48
5034	JB2608-0111	30	P103	스크래치	9	폐기	2026-08-08 10:28:00	72
5035	JB2608-0112	10	B101	치수	38	재작업	2026-08-07 23:35:00	38
5036	JB2608-0112	20	P101	스크래치	23	폐기	2026-08-08 03:38:00	57
5037	JB2608-0112	30	P101	치수	19	재작업	2026-08-08 15:18:00	36
5038	JB2608-0113	10	B101	주름	10	재작업	2026-08-08 16:14:00	36
5039	JB2608-0113	20	P103	치수	30	폐기	2026-08-10 11:06:00	45
5040	JB2608-0113	30	P103	스크래치	32	재작업	2026-08-10 17:00:00	6
5041	JB2608-0114	10	B101	스크래치	14	폐기	2026-08-10 12:07:00	6
5042	JB2608-0114	20	P101	스크래치	12	폐기	2026-08-10 14:37:00	72
5043	JB2608-0114	30	P101	치수	18	폐기	2026-08-10 23:19:00	48
5044	JB2608-0115	10	B101	주름	8	재작업	2026-08-11 09:17:00	45
5045	JB2608-0115	20	P103	크랙	10	재작업	2026-08-11 11:17:00	36
5046	JB2608-0115	30	P103	스크래치	5	폐기	2026-08-11 14:26:00	72
5047	JB2608-0116	10	B101	주름	27	폐기	2026-08-11 11:17:00	6
5048	JB2608-0116	20	P101	크랙	18	재작업	2026-08-11 15:37:00	31
5049	JB2608-0116	30	P101	스크래치	41	재작업	2026-08-12 04:17:00	38
5050	JB2608-0117	10	B101	치수	8	폐기	2026-08-12 03:47:00	38
5051	JB2608-0117	20	P103	크랙	10	폐기	2026-08-12 05:00:00	57
5052	JB2608-0117	30	P103	주름	3	폐기	2026-08-12 11:57:00	36
5053	JB2608-0118	10	B101	치수	15	폐기	2026-08-12 14:05:00	36
5054	JB2608-0118	20	P103	치수	12	재작업	2026-08-12 16:15:00	31
5055	JB2608-0118	30	P103	치수	23	폐기	2026-08-13 00:10:00	74
5056	JB2608-0119	10	B101	주름	34	재작업	2026-08-12 22:00:00	74
5057	JB2608-0119	20	P101	버	31	재작업	2026-08-13 02:27:00	74
5058	JB2608-0119	30	P101	크랙	34	폐기	2026-08-13 15:00:00	31
5059	JB2608-0120	10	B101	치수	12	재작업	2026-08-13 04:20:00	38
5060	JB2608-0120	20	P103	스크래치	10	폐기	2026-08-13 11:08:00	72
5061	JB2608-0120	30	P103	치수	36	재작업	2026-08-13 22:00:00	24
5062	JB2608-0121	10	B101	치수	33	폐기	2026-08-14 02:43:00	57
5063	JB2608-0121	20	P101	스크래치	11	폐기	2026-08-14 05:00:00	24
5064	JB2608-0121	30	P101	버	23	재작업	2026-08-14 15:26:00	72
5065	JB2608-0122	10	B101	크랙	6	폐기	2026-08-14 16:57:00	72
5066	JB2608-0122	20	P103	크랙	20	재작업	2026-08-14 22:21:00	38
5067	JB2608-0122	30	P103	주름	21	재작업	2026-08-15 03:52:00	38
5068	JB2608-0123	10	B101	치수	13	폐기	2026-08-15 00:05:00	38
5069	JB2608-0123	20	P101	버	6	폐기	2026-08-15 02:07:00	38
5070	JB2608-0123	30	P101	치수	12	재작업	2026-08-15 05:00:00	38
5071	JB2608-0124	10	B101	스크래치	17	폐기	2026-08-15 16:28:00	31
5072	JB2608-0124	20	P103	크랙	10	재작업	2026-08-17 10:10:00	31
5073	JB2608-0124	30	P103	크랙	18	폐기	2026-08-17 15:51:00	6
5074	JB2608-0125	10	B101	크랙	13	재작업	2026-08-17 10:10:00	36
5075	JB2608-0125	20	P101	스크래치	17	재작업	2026-08-17 13:34:00	31
5076	JB2608-0125	30	P101	버	14	재작업	2026-08-17 23:36:00	24
5077	JB2608-0126	10	B101	주름	16	폐기	2026-08-18 05:00:00	24
5078	JB2608-0126	20	P103	치수	12	폐기	2026-08-18 11:55:00	72
5079	JB2608-0126	30	P103	치수	17	재작업	2026-08-18 22:01:00	57
5080	JB2608-0127	10	B101	주름	7	폐기	2026-08-18 11:55:00	36
5081	JB2608-0127	20	P101	스크래치	20	재작업	2026-08-18 14:30:00	6
5082	JB2608-0127	30	P101	크랙	19	재작업	2026-08-18 22:52:00	57
5083	JB2608-0128	10	B101	주름	23	폐기	2026-08-19 02:25:00	24
5084	JB2608-0128	20	P101	스크래치	20	폐기	2026-08-19 04:56:00	74
5085	JB2608-0128	30	P101	크랙	10	재작업	2026-08-19 13:11:00	36
5086	JB2608-0129	10	B101	크랙	23	폐기	2026-08-19 10:00:00	36
5087	JB2608-0129	20	P103	스크래치	19	재작업	2026-08-19 15:03:00	72
5088	JB2608-0129	30	P103	버	33	재작업	2026-08-20 04:04:00	74
5089	JB2608-0130	10	B101	치수	18	폐기	2026-08-19 15:03:00	31
5090	JB2608-0130	20	P201	크랙	14	폐기	2026-08-19 17:00:00	52
5091	JB2608-0130	30	P201	스크래치	5	재작업	2026-08-19 23:56:00	55
5092	JB2608-0130	40	W201	스크래치	18	재작업	2026-08-20 02:56:00	55
5093	JB2608-0131	10	B101	스크래치	21	폐기	2026-08-20 04:53:00	57
5094	JB2608-0131	20	P101	버	11	재작업	2026-08-20 11:43:00	45
5095	JB2608-0131	30	P101	스크래치	24	폐기	2026-08-20 22:13:00	24
5096	JB2608-0132	10	B101	치수	45	폐기	2026-08-21 01:44:00	38
5097	JB2608-0132	20	P103	주름	48	폐기	2026-08-21 05:00:00	24
5098	JB2608-0132	30	P103	치수	19	폐기	2026-08-21 23:32:00	74
5099	JB2608-0133	10	B101	치수	14	재작업	2026-08-21 05:00:00	48
5100	JB2608-0133	20	P201	스크래치	20	폐기	2026-08-21 13:01:00	44
5101	JB2608-0133	30	P201	버	18	재작업	2026-08-21 17:00:00	44
5102	JB2608-0133	40	W201	스크래치	34	재작업	2026-08-22 01:31:00	55
5103	JB2608-0134	10	B101	크랙	5	폐기	2026-08-21 13:01:00	36
5104	JB2608-0134	20	P101	치수	11	재작업	2026-08-21 15:05:00	45
5105	JB2608-0134	30	P101	주름	15	폐기	2026-08-21 22:33:00	57
5106	JB2608-0135	10	B101	스크래치	9	재작업	2026-08-22 10:00:00	72
5107	JB2608-0135	20	P101	버	17	폐기	2026-08-22 12:30:00	6
5108	JB2608-0135	30	P101	스크래치	21	재작업	2026-08-22 17:00:00	31
5109	JB2608-0136	10	B101	치수	5	폐기	2026-08-22 11:57:00	6
5110	JB2608-0136	20	P103	주름	9	재작업	2026-08-22 13:57:00	31
5111	JB2608-0136	30	P103	스크래치	3	폐기	2026-08-22 17:00:00	72
5112	JB2608-0137	10	B101	크랙	9	폐기	2026-08-24 10:10:00	6
5113	JB2608-0137	20	P103	스크래치	34	폐기	2026-08-24 14:30:00	45
5114	JB2608-0137	30	P103	버	37	폐기	2026-08-25 04:20:00	48
5115	JB2608-0138	10	B101	크랙	17	폐기	2026-08-25 01:09:00	48
5116	JB2608-0138	20	P101	크랙	18	재작업	2026-08-25 04:24:00	57
5117	JB2608-0138	30	P101	스크래치	6	재작업	2026-08-25 13:31:00	72
5118	JB2608-0139	10	B101	스크래치	15	폐기	2026-08-25 23:51:00	24
5119	JB2608-0139	20	P101	치수	26	재작업	2026-08-26 02:28:00	74
5120	JB2608-0139	30	P101	치수	19	폐기	2026-08-26 11:19:00	31
5121	JB2608-0140	10	B101	치수	8	폐기	2026-08-26 01:45:00	57
5122	JB2608-0140	20	P103	주름	3	폐기	2026-08-26 03:45:00	38
5123	JB2608-0140	30	P103	버	6	재작업	2026-08-26 05:00:00	74
5124	JB2608-0141	10	B101	크랙	27	폐기	2026-08-26 22:02:00	24
5125	JB2608-0141	20	P101	크랙	25	폐기	2026-08-27 02:09:00	38
5126	JB2608-0141	30	P101	크랙	29	재작업	2026-08-27 15:11:00	45
5127	JB2608-0142	10	B101	스크래치	22	재작업	2026-08-27 02:09:00	74
5128	JB2608-0142	20	P103	스크래치	26	폐기	2026-08-27 05:00:00	74
5129	JB2608-0142	30	P103	크랙	7	재작업	2026-08-27 16:41:00	36
5130	JB2608-0143	10	B101	치수	4	재작업	2026-08-27 05:00:00	48
5131	JB2608-0143	20	P201	주름	6	재작업	2026-08-27 10:09:00	44
5132	JB2608-0143	30	P201	버	5	폐기	2026-08-27 11:54:00	44
5133	JB2608-0143	40	W201	주름	6	재작업	2026-08-27 13:54:00	40
5134	JB2608-0144	10	B101	버	8	폐기	2026-08-28 05:00:00	24
5135	JB2608-0144	20	P101	주름	7	폐기	2026-08-28 11:53:00	45
5136	JB2608-0144	30	P101	치수	12	폐기	2026-08-28 17:00:00	36
5137	JB2608-0145	10	B101	치수	2	재작업	2026-08-28 11:17:00	45
5138	JB2608-0145	20	P103	주름	6	재작업	2026-08-28 13:17:00	31
5139	JB2608-0145	30	P103	주름	4	폐기	2026-08-28 16:35:00	6
5140	JB2608-0146	10	B101	주름	51	재작업	2026-08-29 00:25:00	48
5141	JB2608-0146	20	P101	치수	19	폐기	2026-08-29 05:00:00	48
5142	JB2608-0146	30	P101	주름	30	폐기	2026-08-29 17:00:00	36
5143	JB2608-0147	10	B101	스크래치	22	폐기	2026-08-29 05:00:00	74
5144	JB2608-0147	20	P103	스크래치	6	재작업	2026-08-29 11:48:00	45
5145	JB2608-0147	30	P103	버	16	재작업	2026-08-29 16:12:00	6
5146	JB2608-0148	10	B101	크랙	7	재작업	2026-08-31 17:00:00	36
5147	JB2608-0148	20	P101	주름	11	폐기	2026-08-31 22:37:00	48
5148	JB2608-0148	30	P101	치수	13	재작업	2026-09-01 02:07:00	74
5149	JB2608-0149	10	B101	버	10	재작업	2026-08-31 23:46:00	48
5150	JB2608-0149	20	P103	주름	10	재작업	2026-09-01 01:46:00	57
5151	JB2608-0149	30	P103	스크래치	6	재작업	2026-09-01 04:29:00	38
5152	JB2608-0150	10	B101	주름	26	재작업	2026-09-01 01:46:00	57
5153	JB2608-0150	20	P201	버	29	폐기	2026-09-01 04:53:00	55
5154	JB2608-0150	30	P201	버	15	폐기	2026-09-01 13:06:00	52
5155	JB2608-0150	40	W201	치수	30	재작업	2026-09-01 17:00:00	44
5156	JB2609-0101	10	B101	주름	11	폐기	2026-09-01 05:00:00	74
5157	JB2609-0101	20	P103	버	31	폐기	2026-09-01 13:30:00	31
5158	JB2609-0101	30	P103	주름	33	폐기	2026-09-02 00:28:00	74
5159	JB2609-0102	10	B101	주름	16	폐기	2026-09-01 13:15:00	6
5160	JB2609-0102	20	P101	스크래치	7	재작업	2026-09-01 15:15:00	45
5161	JB2609-0102	30	P101	버	12	재작업	2026-09-01 22:09:00	48
5162	JB2609-0103	10	B101	버	30	폐기	2026-09-02 13:17:00	6
5163	JB2609-0103	20	P101	크랙	17	재작업	2026-09-02 16:45:00	6
5164	JB2609-0103	30	P101	버	30	폐기	2026-09-03 02:44:00	48
5165	JB2609-0104	10	B101	주름	13	재작업	2026-09-02 17:00:00	72
5166	JB2609-0104	20	P103	치수	11	재작업	2026-09-03 00:03:00	57
5167	JB2609-0104	30	P103	스크래치	14	폐기	2026-09-03 04:36:00	24
5168	JB2609-0105	10	B101	치수	8	폐기	2026-09-03 23:13:00	24
5169	JB2609-0105	20	P101	치수	21	폐기	2026-09-04 02:08:00	57
5170	JB2609-0105	30	P101	주름	17	폐기	2026-09-04 11:13:00	6
5171	JB2609-0106	10	B101	버	28	재작업	2026-09-04 02:08:00	48
5172	JB2609-0106	20	P103	치수	21	재작업	2026-09-04 04:54:00	38
5173	JB2609-0106	30	P103	크랙	25	재작업	2026-09-04 13:20:00	45
5174	JB2609-0107	10	B101	버	41	폐기	2026-09-05 00:00:00	57
5175	JB2609-0107	20	P103	치수	29	재작업	2026-09-05 04:22:00	74
5176	JB2609-0107	30	P103	버	26	폐기	2026-09-05 16:20:00	6
5177	JB2609-0108	10	B101	버	32	폐기	2026-09-05 04:24:00	24
5178	JB2609-0108	20	P101	치수	40	재작업	2026-09-05 11:10:00	36
5179	JB2609-0108	30	P101	치수	7	재작업	2026-09-07 10:24:00	45
5180	JB2609-0109	10	B101	주름	23	재작업	2026-09-07 22:44:00	48
5181	JB2609-0109	20	P101	치수	31	재작업	2026-09-08 03:38:00	57
5182	JB2609-0109	30	P101	스크래치	27	재작업	2026-09-08 16:58:00	31
5183	JB2609-0110	10	B101	스크래치	10	폐기	2026-09-08 03:28:00	74
5184	JB2609-0110	20	P103	치수	8	폐기	2026-09-08 05:00:00	24
5185	JB2609-0110	30	P103	주름	7	폐기	2026-09-08 13:04:00	45
5186	JB2609-0111	10	B101	주름	18	재작업	2026-09-09 04:16:00	57
5187	JB2609-0111	20	P103	치수	6	재작업	2026-09-09 05:00:00	48
5188	JB2609-0111	30	P103	스크래치	16	폐기	2026-09-09 14:20:00	6
5189	JB2609-0112	10	B101	치수	15	폐기	2026-09-09 10:00:00	36
5190	JB2609-0112	20	P101	스크래치	15	폐기	2026-09-09 12:16:00	36
5191	JB2609-0112	30	P101	버	11	폐기	2026-09-09 17:00:00	45
5192	JB2609-0113	10	B101	치수	36	폐기	2026-09-09 17:00:00	45
5193	JB2609-0113	20	P103	크랙	13	폐기	2026-09-10 00:59:00	74
5194	JB2609-0113	30	P103	주름	38	재작업	2026-09-10 11:02:00	45
5195	JB2609-0114	10	B101	스크래치	14	폐기	2026-09-10 05:00:00	57
5196	JB2609-0114	20	P101	주름	11	재작업	2026-09-10 15:20:00	31
5197	JB2609-0114	30	P101	주름	26	재작업	2026-09-11 01:43:00	57
5198	JB2609-0115	10	B101	버	25	폐기	2026-09-11 00:22:00	74
5199	JB2609-0115	20	P103	주름	33	폐기	2026-09-11 04:50:00	38
5200	JB2609-0115	30	P103	스크래치	45	재작업	2026-09-11 17:00:00	31
5201	JB2609-0116	10	B101	버	22	재작업	2026-09-11 11:50:00	72
5202	JB2609-0116	20	P101	치수	8	재작업	2026-09-11 14:44:00	6
5203	JB2609-0116	30	P101	버	13	폐기	2026-09-11 23:18:00	38
5204	JB2609-0117	10	B101	주름	20	폐기	2026-09-12 10:35:00	6
5205	JB2609-0117	20	P101	크랙	20	폐기	2026-09-12 13:25:00	72
5206	JB2609-0117	30	P101	치수	23	폐기	2026-09-14 11:31:00	45
5207	JB2609-0118	10	B101	치수	12	재작업	2026-09-12 13:25:00	45
5208	JB2609-0118	20	P103	크랙	21	폐기	2026-09-12 17:00:00	72
5209	JB2609-0118	30	P103	크랙	34	폐기	2026-09-14 17:00:00	72
5210	JB2609-0119	10	B101	주름	29	폐기	2026-09-14 16:11:00	72
5211	JB2609-0119	20	P101	스크래치	20	폐기	2026-09-15 00:37:00	24
5212	JB2609-0119	30	P101	스크래치	17	재작업	2026-09-15 14:59:00	45
5213	JB2609-0120	10	B101	치수	22	재작업	2026-09-15 02:36:00	57
5214	JB2609-0120	20	P103	스크래치	34	폐기	2026-09-15 11:14:00	36
5215	JB2609-0120	30	P103	주름	28	재작업	2026-09-16 02:18:00	74
5216	JB2609-0121	10	B101	버	45	폐기	2026-09-16 02:10:00	38
5217	JB2609-0121	20	P101	주름	26	재작업	2026-09-16 10:12:00	45
5218	JB2609-0121	30	P101	치수	52	재작업	2026-09-16 23:18:00	48
5219	JB2609-0122	10	B101	치수	5	폐기	2026-09-17 01:19:00	74
5220	JB2609-0122	20	P103	스크래치	5	재작업	2026-09-17 03:19:00	38
5221	JB2609-0122	30	P103	주름	9	폐기	2026-09-17 05:00:00	57
5222	JB2609-0123	10	B101	버	49	폐기	2026-09-17 22:36:00	57
5223	JB2609-0123	20	P101	주름	45	재작업	2026-09-18 03:42:00	74
5224	JB2609-0123	30	P101	버	18	재작업	2026-09-18 17:00:00	72
5225	JB2609-0124	10	B101	치수	12	폐기	2026-09-18 03:42:00	38
5226	JB2609-0124	20	P103	버	8	폐기	2026-09-18 05:00:00	38
5227	JB2609-0124	30	P103	버	21	재작업	2026-09-18 14:22:00	45
5228	JB2609-0125	10	B101	크랙	22	폐기	2026-09-18 05:00:00	48
5229	JB2609-0125	20	P201	스크래치	41	재작업	2026-09-18 12:26:00	44
5230	JB2609-0125	30	P201	치수	40	재작업	2026-09-18 17:00:00	52
5231	JB2609-0125	40	W201	스크래치	26	재작업	2026-09-19 01:56:00	55
5232	JB2609-0126	10	B101	크랙	5	폐기	2026-09-18 17:00:00	6
5233	JB2609-0126	20	P103	주름	19	폐기	2026-09-18 23:11:00	48
5234	JB2609-0126	30	P103	주름	13	폐기	2026-09-19 04:00:00	38
5235	JB2609-0127	10	B101	크랙	38	재작업	2026-09-19 16:12:00	45
5236	JB2609-0127	20	P101	치수	30	폐기	2026-09-21 12:08:00	45
5237	JB2609-0127	30	P101	버	30	재작업	2026-09-22 01:21:00	48
5238	JB2609-0128	10	B101	치수	18	폐기	2026-09-21 12:08:00	31
5239	JB2609-0128	20	P103	스크래치	49	재작업	2026-09-21 16:39:00	36
5240	JB2609-0128	30	P103	크랙	43	폐기	2026-09-22 05:00:00	38
5241	JB2609-0129	10	B101	크랙	20	폐기	2026-09-21 16:39:00	36
5242	JB2609-0129	20	P201	버	10	폐기	2026-09-21 22:09:00	55
5243	JB2609-0129	30	P201	주름	29	폐기	2026-09-22 02:26:00	55
5244	JB2609-0129	40	W201	치수	18	폐기	2026-09-22 05:00:00	55
5245	JB2609-0130	10	B101	치수	48	폐기	2026-09-22 16:35:00	45
5246	JB2609-0130	20	P103	치수	23	폐기	2026-09-22 23:55:00	74
5247	JB2609-0130	30	P103	버	29	재작업	2026-09-23 13:12:00	31
5248	JB2609-0131	10	B101	치수	28	폐기	2026-09-22 23:55:00	38
5249	JB2609-0131	20	P101	치수	21	재작업	2026-09-23 02:48:00	74
5250	JB2609-0131	30	P101	버	12	재작업	2026-09-23 11:10:00	45
5251	JB2609-0132	10	B101	버	15	폐기	2026-09-23 02:29:00	48
5252	JB2609-0132	20	P201	버	7	재작업	2026-09-23 04:29:00	55
5253	JB2609-0132	30	P201	스크래치	16	폐기	2026-09-23 10:26:00	44
5254	JB2609-0132	40	W201	버	11	재작업	2026-09-23 12:57:00	79
5255	JB2609-0133	10	B101	크랙	26	폐기	2026-09-23 16:34:00	45
5256	JB2609-0133	20	P101	치수	14	재작업	2026-09-23 22:43:00	48
5257	JB2609-0133	30	P101	크랙	16	재작업	2026-09-24 05:00:00	48
5258	JB2609-0134	10	B101	주름	44	폐기	2026-09-24 05:00:00	24
5259	JB2609-0134	20	P103	스크래치	11	폐기	2026-09-24 14:53:00	72
5260	JB2609-0134	30	P103	스크래치	30	재작업	2026-09-25 03:50:00	24
5261	JB2609-0135	10	B101	스크래치	9	재작업	2026-09-24 14:53:00	31
5262	JB2609-0135	20	P101	크랙	10	폐기	2026-09-24 17:00:00	45
5263	JB2609-0135	30	P101	치수	10	폐기	2026-09-25 02:09:00	48
5264	JB2609-0136	10	B101	크랙	9	재작업	2026-09-25 09:52:00	72
5265	JB2609-0136	20	P101	버	5	재작업	2026-09-25 11:52:00	36
5266	JB2609-0136	30	P101	스크래치	7	폐기	2026-09-25 15:21:00	31
5267	JB2609-0137	10	B101	크랙	18	폐기	2026-09-25 11:52:00	6
5268	JB2609-0137	20	P201	버	19	폐기	2026-09-25 13:53:00	52
5269	JB2609-0137	30	P201	스크래치	10	폐기	2026-09-25 17:00:00	52
5270	JB2609-0137	40	W201	크랙	8	폐기	2026-09-25 23:36:00	55
5271	JB2609-0138	10	B101	버	24	재작업	2026-09-26 00:31:00	24
5272	JB2609-0138	20	P101	크랙	25	재작업	2026-09-26 03:23:00	38
5273	JB2609-0138	30	P101	스크래치	23	폐기	2026-09-26 12:00:00	36
5274	JB2609-0139	10	B101	주름	3	재작업	2026-09-26 02:41:00	74
5275	JB2609-0139	20	P103	치수	10	폐기	2026-09-26 04:41:00	57
5276	JB2609-0139	30	P103	치수	7	재작업	2026-09-26 10:10:00	72
5277	JB2609-0140	10	B101	버	19	재작업	2026-09-28 10:00:00	45
5278	JB2609-0140	20	P103	스크래치	29	폐기	2026-09-28 14:27:00	36
5279	JB2609-0140	30	P103	주름	21	폐기	2026-09-29 02:01:00	38
5280	JB2609-0141	10	B101	주름	3	폐기	2026-09-28 13:46:00	6
5281	JB2609-0141	20	P101	주름	8	폐기	2026-09-28 15:46:00	6
5282	JB2609-0141	30	P101	버	7	폐기	2026-09-28 17:00:00	6
5283	JB2609-0142	10	B101	주름	26	폐기	2026-09-28 23:56:00	48
5284	JB2609-0142	20	P101	주름	12	재작업	2026-09-29 03:22:00	74
5285	JB2609-0142	30	P101	주름	20	폐기	2026-09-29 12:39:00	31
5286	JB2609-0143	10	B101	스크래치	43	재작업	2026-09-29 17:00:00	45
5287	JB2609-0143	20	P103	스크래치	31	폐기	2026-09-30 01:28:00	74
5288	JB2609-0143	30	P103	버	23	폐기	2026-09-30 14:06:00	36
5289	JB2609-0144	10	B101	버	11	폐기	2026-09-30 05:00:00	57
5290	JB2609-0144	20	P101	버	14	재작업	2026-09-30 13:05:00	6
5291	JB2609-0144	30	P101	버	23	폐기	2026-09-30 23:16:00	48
5292	JB2609-0145	10	B101	버	5	폐기	2026-10-01 00:48:00	57
5293	JB2609-0145	20	P103	스크래치	12	재작업	2026-10-01 03:19:00	24
5294	JB2609-0145	30	P103	스크래치	16	폐기	2026-10-01 10:12:00	36
5295	JB2610-0101	10	B101	크랙	12	재작업	2026-10-01 11:59:00	31
5296	JB2610-0101	20	P101	스크래치	6	재작업	2026-10-01 14:17:00	36
5297	JB2610-0101	30	P101	주름	6	재작업	2026-10-01 22:13:00	74
5298	JB2610-0102	10	B101	버	7	재작업	2026-10-01 14:03:00	31
5299	JB2610-0102	20	P103	치수	13	폐기	2026-10-01 16:03:00	36
5300	JB2610-0102	30	P103	주름	17	재작업	2026-10-01 22:52:00	74
5301	JB2610-0103	10	B101	치수	47	폐기	2026-10-02 02:05:00	74
5302	JB2610-0103	20	P103	치수	36	폐기	2026-10-02 05:00:00	24
5303	JB2610-0103	30	P103	주름	10	재작업	2026-10-02 23:05:00	57
5304	JB2610-0104	10	B101	스크래치	7	폐기	2026-10-02 10:00:00	31
5305	JB2610-0104	20	P101	주름	27	재작업	2026-10-02 14:19:00	45
5306	JB2610-0104	30	P101	크랙	15	폐기	2026-10-03 02:35:00	24
5307	JB2610-0105	10	B101	버	13	재작업	2026-10-03 11:18:00	45
5308	JB2610-0105	20	P101	치수	14	재작업	2026-10-03 13:18:00	72
5309	JB2610-0105	30	P101	크랙	13	폐기	2026-10-03 17:00:00	45
5310	JB2610-0106	10	B101	크랙	15	재작업	2026-10-03 17:00:00	31
5311	JB2610-0106	20	P103	크랙	15	폐기	2026-10-05 10:41:00	45
5312	JB2610-0106	30	P103	스크래치	15	폐기	2026-10-05 14:43:00	72
5313	JB2610-0107	10	B101	주름	11	재작업	2026-10-05 09:52:00	6
5314	JB2610-0107	20	P201	크랙	11	폐기	2026-10-05 11:52:00	79
5315	JB2610-0107	30	P201	버	9	폐기	2026-10-05 13:41:00	52
5316	JB2610-0107	40	W201	버	4	재작업	2026-10-05 15:41:00	79
5317	JB2610-0108	10	B101	크랙	31	재작업	2026-10-05 17:00:00	72
5318	JB2610-0108	20	P103	버	10	재작업	2026-10-05 23:56:00	48
5319	JB2610-0108	30	P103	치수	21	폐기	2026-10-06 05:00:00	57
5320	JB2610-0109	10	B101	치수	19	폐기	2026-10-05 23:56:00	38
5321	JB2610-0109	20	P101	버	39	폐기	2026-10-06 03:55:00	74
5322	JB2610-0109	30	P101	치수	21	폐기	2026-10-06 15:57:00	36
5323	JB2610-0110	10	B101	크랙	10	재작업	2026-10-06 03:55:00	24
5324	JB2610-0110	20	P201	크랙	24	폐기	2026-10-06 05:00:00	55
5325	JB2610-0110	30	P201	스크래치	27	재작업	2026-10-06 13:47:00	52
5326	JB2610-0110	40	W201	버	19	재작업	2026-10-06 17:00:00	27
5327	JB2610-0111	10	B101	버	31	재작업	2026-10-06 15:13:00	31
5328	JB2610-0111	20	P103	버	41	재작업	2026-10-06 17:00:00	72
5329	JB2610-0111	30	P103	스크래치	26	재작업	2026-10-07 04:57:00	24
5330	JB2610-0112	10	B101	치수	24	폐기	2026-10-07 14:11:00	6
5331	JB2610-0112	20	P101	버	20	재작업	2026-10-07 16:16:00	45
5332	JB2610-0112	30	P101	스크래치	23	재작업	2026-10-07 23:47:00	38
5333	JB2610-0113	10	B101	크랙	10	재작업	2026-10-07 16:16:00	6
5334	JB2610-0113	20	P103	크랙	23	재작업	2026-10-07 23:27:00	57
5335	JB2610-0113	30	P103	크랙	24	폐기	2026-10-08 11:45:00	45
5336	JB2610-0114	10	B101	치수	18	폐기	2026-10-08 10:03:00	36
5337	JB2610-0114	20	P101	스크래치	38	폐기	2026-10-08 14:03:00	72
5338	JB2610-0114	30	P101	주름	39	폐기	2026-10-09 00:15:00	57
5339	JB2610-0115	10	B101	주름	42	재작업	2026-10-08 22:56:00	57
5340	JB2610-0115	20	P103	버	21	폐기	2026-10-09 04:18:00	57
5341	JB2610-0115	30	P103	스크래치	40	재작업	2026-10-09 17:00:00	45
5342	JB2610-0116	10	B101	치수	14	폐기	2026-10-09 12:39:00	45
5343	JB2610-0116	20	P101	치수	16	폐기	2026-10-09 15:46:00	6
5344	JB2610-0116	30	P101	스크래치	17	폐기	2026-10-10 01:51:00	38
5345	JB2610-0117	10	B101	치수	23	폐기	2026-10-09 15:46:00	31
5346	JB2610-0117	20	P201	치수	9	폐기	2026-10-09 17:00:00	79
5347	JB2610-0117	30	P201	버	10	재작업	2026-10-10 00:50:00	55
5348	JB2610-0117	40	W201	스크래치	14	폐기	2026-10-10 03:42:00	55
5349	JB2610-0118	10	B101	주름	4	폐기	2026-10-10 05:00:00	48
5350	JB2610-0118	20	P101	스크래치	6	폐기	2026-10-10 11:40:00	36
5351	JB2610-0118	30	P101	치수	12	재작업	2026-10-10 16:04:00	31
5352	JB2610-0119	10	B101	치수	25	폐기	2026-10-10 11:40:00	6
5353	JB2610-0119	20	P103	주름	16	재작업	2026-10-10 14:58:00	45
5354	JB2610-0119	30	P103	스크래치	8	폐기	2026-10-12 12:46:00	36
5355	JB2610-0120	10	B101	주름	11	폐기	2026-10-12 10:08:00	72
5356	JB2610-0120	20	P101	크랙	9	재작업	2026-10-12 12:48:00	31
5357	JB2610-0120	30	P101	버	21	폐기	2026-10-12 17:00:00	6
5358	JB2610-0121	10	B101	크랙	23	폐기	2026-10-13 02:08:00	57
5359	JB2610-0121	20	P101	버	26	폐기	2026-10-13 05:00:00	48
5360	JB2610-0121	30	P101	크랙	35	폐기	2026-10-13 22:54:00	24
5361	JB2610-0122	10	B101	크랙	8	폐기	2026-10-13 05:00:00	48
5362	JB2610-0122	20	P201	크랙	24	재작업	2026-10-13 12:39:00	79
5363	JB2610-0122	30	P201	스크래치	25	폐기	2026-10-13 17:00:00	79
5364	JB2610-0122	40	W201	치수	31	폐기	2026-10-14 00:55:00	55
5365	JB2610-0123	10	B101	주름	8	폐기	2026-10-13 12:39:00	45
5366	JB2610-0123	20	P103	버	8	폐기	2026-10-13 14:42:00	72
5367	JB2610-0123	30	P103	스크래치	8	폐기	2026-10-13 22:15:00	24
5368	JB2610-0124	10	B101	치수	11	폐기	2026-10-14 13:01:00	45
5369	JB2610-0124	20	P103	버	17	폐기	2026-10-14 15:36:00	36
5370	JB2610-0124	30	P103	치수	14	재작업	2026-10-14 23:53:00	57
5371	JB2610-0125	10	B101	버	15	재작업	2026-10-14 16:21:00	31
5372	JB2610-0125	20	P101	크랙	4	재작업	2026-10-14 17:00:00	6
5373	JB2610-0125	30	P101	버	3	재작업	2026-10-15 00:53:00	38
5374	JB2610-0126	10	B101	스크래치	27	재작업	2026-10-15 10:00:00	36
5375	JB2610-0126	20	P103	주름	12	폐기	2026-10-15 14:32:00	31
5376	JB2610-0126	30	P103	치수	32	폐기	2026-10-16 02:26:00	57
5377	JB2610-0127	10	B101	치수	9	재작업	2026-10-15 15:19:00	6
5378	JB2610-0127	20	P101	주름	7	재작업	2026-10-15 17:00:00	36
5379	JB2610-0127	30	P101	스크래치	11	재작업	2026-10-15 23:32:00	48
5380	JB2610-0128	10	B101	주름	9	재작업	2026-10-16 01:38:00	38
5381	JB2610-0128	20	P101	스크래치	26	재작업	2026-10-16 05:00:00	74
5382	JB2610-0128	30	P101	주름	31	재작업	2026-10-16 17:00:00	45
5383	JB2610-0129	10	B101	주름	10	재작업	2026-10-16 12:54:00	36
5384	JB2610-0129	20	P103	주름	12	재작업	2026-10-16 14:54:00	6
5385	JB2610-0129	30	P103	버	8	폐기	2026-10-16 17:00:00	31
5386	JB2610-0130	10	B101	버	46	재작업	2026-10-17 10:00:00	36
5387	JB2610-0130	20	P101	크랙	11	재작업	2026-10-17 14:07:00	45
5388	JB2610-0130	30	P101	버	27	폐기	2026-10-19 13:33:00	36
5389	JB2610-0131	10	B101	치수	34	재작업	2026-10-17 14:07:00	45
5390	JB2610-0131	20	P103	치수	46	재작업	2026-10-17 17:00:00	6
5391	JB2610-0131	30	P103	버	23	폐기	2026-10-19 17:00:00	45
5392	JB2610-0132	10	B101	주름	35	폐기	2026-10-20 05:00:00	48
5393	JB2610-0132	20	P101	크랙	36	재작업	2026-10-20 13:23:00	45
5394	JB2610-0132	30	P101	버	28	폐기	2026-10-21 02:07:00	74
5395	JB2610-0133	10	B101	치수	30	재작업	2026-10-20 13:23:00	31
5396	JB2610-0133	20	P103	크랙	34	폐기	2026-10-20 17:00:00	45
5397	JB2610-0133	30	P103	버	18	재작업	2026-10-21 10:40:00	45
5398	JB2610-0134	10	B101	주름	11	재작업	2026-10-21 21:12:00	74
5399	JB2610-0134	20	P101	주름	7	재작업	2026-10-21 23:12:00	24
5400	JB2610-0134	30	P101	크랙	3	재작업	2026-10-22 01:50:00	74
5401	JB2610-0135	10	B101	주름	9	재작업	2026-10-22 10:06:00	31
5402	JB2610-0135	20	P103	주름	26	폐기	2026-10-22 13:56:00	31
5403	JB2610-0135	30	P103	버	12	재작업	2026-10-23 00:47:00	38
5404	JB2610-0136	10	B101	주름	14	재작업	2026-10-22 13:56:00	72
5405	JB2610-0136	20	P101	버	13	재작업	2026-10-22 16:19:00	31
5406	JB2610-0136	30	P101	스크래치	17	재작업	2026-10-23 00:01:00	57
5407	JB2610-0137	10	B101	주름	15	재작업	2026-10-23 05:00:00	57
5408	JB2610-0137	20	P103	크랙	5	재작업	2026-10-23 10:51:00	36
5409	JB2610-0137	30	P103	스크래치	11	재작업	2026-10-23 14:33:00	45
5410	JB2610-0139	10	B101	버	18	폐기	2026-10-23 15:53:00	72
5411	JB2610-0139	20	P101	크랙	23	폐기	2026-10-23 17:00:00	36
5412	JB2610-0139	30	P101	스크래치	13	재작업	2026-10-24 04:19:00	57
5413	JB2610-0140	10	B101	치수	14	재작업	2026-10-23 17:00:00	72
5414	JB2610-0140	20	P103	스크래치	26	폐기	2026-10-24 00:29:00	38
5415	JB2610-0140	30	P103	치수	29	재작업	2026-10-24 05:00:00	24
5416	JB2610-0138	10	B101	버	12	폐기	2026-10-29 10:00:00	31
5417	JB2610-0138	20	P101	버	28	폐기	2026-10-29 11:00:00	72
5418	JB2610-0138	30	P101	주름	12	재작업	2026-10-29 17:00:00	72
5419	JB2610-0141	10	B101	치수	45	재작업	2026-10-28 10:00:00	45
5420	JB2610-0141	20	P103	치수	7	재작업	2026-10-29 10:00:00	36
5421	JB2610-0141	30	P103	치수	12	재작업	2026-10-29 22:00:00	38
5422	JB2610-0142	10	B101	스크래치	18	재작업	2026-10-28 14:57:00	31
5423	JB2610-0142	20	P201	스크래치	19	폐기	2026-10-29 10:00:00	27
5424	JB2610-0142	30	P201	치수	46	폐기	2026-10-29 16:17:00	44
\.


--
-- Data for Name: die; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.die (die_code, drawing_code, product_code, total_strokes, pm_interval_strokes, last_pm_at, status, location) FROM stdin;
MD-BRK210L-01	DWG-BRK-210L	BRK-210L	410500	200000	2026-09-10	사용	1라인 금형대
MD-BRK210R-01	DWG-BRK-210R	BRK-210R	761000	200000	2026-09-19	사용	1라인 금형대
MD-RNF320L-01	DWG-RNF-320L	RNF-320L	1135000	200000	2026-09-09	사용	1라인 금형대
MD-RNF320R-01	DWG-RNF-320R	RNF-320R	1065500	200000	2026-09-23	사용	1라인 금형대
MD-PNL410-01	DWG-PNL-410	PNL-410	973000	200000	2026-09-02	사용	2라인 금형대
MD-990T-01	DWG-BRK-990T	BRK-990T	1463500	200000	2026-09-01	정비	금형창고
\.


--
-- Data for Name: downtime; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.downtime (downtime_id, equipment_code, wo_no, reason_code, reason, started_at, ended_at, minutes) FROM stdin;
DT-0401	P101	\N	BRK	피더 센서 오작동	2026-08-05 06:50:00	2026-08-05 10:50:00	240
DT-0402	P101	\N	PM	윤활 계통 점검	2026-08-06 06:20:00	2026-08-06 10:20:00	240
DT-0403	B101	\N	MAT	코일 대기	2026-08-20 06:30:00	2026-08-20 07:00:00	30
DT-0404	B101	\N	DIE	금형 교체	2026-08-21 06:30:00	2026-08-21 07:14:00	44
DT-0405	P201	\N	PM	윤활 계통 점검	2026-08-24 00:10:00	2026-08-24 02:40:00	150
DT-0406	P201	\N	QA	치수 불량 확인	2026-08-24 12:10:00	2026-08-24 13:00:00	50
DT-0407	P101	JB2608-0141	DIE	금형 교체	2026-08-27 00:30:00	2026-08-27 01:16:00	46
DT-0408	B101	\N	MAT	블랭크 대기	2026-08-31 00:10:00	2026-08-31 00:45:00	35
DT-0409	P103	\N	BRK	가이드 마모	2026-08-31 06:00:00	2026-08-31 09:00:00	180
DT-0410	P103	\N	PM	윤활 계통 점검	2026-08-31 12:10:00	2026-08-31 15:10:00	180
DT-0411	P101	JB2608-0148	DIE	금형 교체	2026-09-01 00:20:00	2026-09-01 00:55:00	35
DT-0412	P101	\N	DIE	금형 교체	2026-09-01 12:20:00	2026-09-01 12:57:00	37
DT-0413	B101	\N	MAT	블랭크 대기	2026-09-03 06:50:00	2026-09-03 08:16:00	86
DT-0414	P101	\N	BRK	유압 압력 저하	2026-09-10 06:20:00	2026-09-10 13:20:00	420
DT-0415	B101	\N	PM	윤활 계통 점검	2026-09-10 06:30:00	2026-09-10 09:00:00	150
DT-0416	P103	\N	QA	치수 불량 확인	2026-09-16 06:10:00	2026-09-16 06:46:00	36
DT-0417	P101	\N	MAT	코일 대기	2026-09-16 06:50:00	2026-09-16 08:12:00	82
DT-0418	B101	\N	PM	윤활 계통 점검	2026-09-17 06:50:00	2026-09-17 10:20:00	210
DT-0419	P101	\N	DIE	금형 교체	2026-09-21 06:40:00	2026-09-21 07:19:00	39
DT-0420	P103	JB2609-0130	QA	치수 불량 확인	2026-09-23 00:50:00	2026-09-23 01:25:00	35
DT-0421	P103	\N	MAT	블랭크 대기	2026-09-29 12:00:00	2026-09-29 12:31:00	31
DT-0422	P103	\N	MAT	코일 대기	2026-10-01 00:10:00	2026-10-01 01:19:00	69
DT-0423	P101	\N	DIE	금형 교체	2026-10-01 06:10:00	2026-10-01 06:49:00	39
DT-0424	P201	\N	BRK	피더 센서 오작동	2026-10-02 06:20:00	2026-10-02 14:20:00	480
DT-0425	P101	\N	BRK	유압 압력 저하	2026-10-09 06:20:00	2026-10-09 09:50:00	210
DT-0426	B101	\N	DIE	금형 교체	2026-10-09 06:50:00	2026-10-09 07:20:00	30
DT-0427	P103	\N	DIE	금형 교체	2026-10-14 06:20:00	2026-10-14 06:55:00	35
DT-0428	P103	\N	DIE	금형 교체	2026-10-14 12:40:00	2026-10-14 13:36:00	56
DT-0429	B101	JB2610-0128	DIE	금형 교체	2026-10-16 00:10:00	2026-10-16 01:03:00	53
DT-0430	B101	\N	BRK	유압 압력 저하	2026-10-19 00:30:00	2026-10-19 06:00:00	330
DT-0431	P103	\N	PM	윤활 계통 점검	2026-10-20 06:10:00	2026-10-20 10:40:00	270
DT-0460	P103	JB2610-0141	BRK	슬라이드 유압 누유	2026-10-30 03:10:00	\N	\N
\.


--
-- Data for Name: equipment; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.equipment (code, name, kind, line, capacity_ton, status, installed_on, maker) FROM stdin;
B101	1라인 블랭킹기	BLK	1라인	250	가동	2017-04-10	한국프레스
P101	1라인 1호 프레스	PRS	1라인	400	가동	2017-04-10	한국프레스
P103	1라인 3호 프레스	PRS	1라인	600	정비중	2022-02-21	대성기계
I101	1라인 검사대	INS	1라인	\N	가동	2023-06-12	자체 제작
P201	2라인 1호 프레스	PRS	2라인	300	가동	2015-09-07	한국프레스
W201	2라인 용접기	WLD	2라인	\N	가동	2021-05-17	세원로보틱스
\.


--
-- Data for Name: handover_note; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.handover_note (note_id, shift_date, shift, author_id, written_at, content) FROM stdin;
HN-260804-D	2026-08-04	주간	52	2026-08-04 17:00:00	[8/4 주간 → 8/4 야간 인수인계] 작성 52\n오후 3시쯤 P201 진동이 평소보다 커서 점검 요청함.\n
HN-260805-N	2026-08-05	야간	24	2026-08-06 05:00:00	[8/5 야간 → 8/6 주간 인수인계] 작성 24\nJB2608-0103 잔량 1,100개는 주간조에 넘김.\n
HN-260806-N	2026-08-06	야간	57	2026-08-07 05:00:00	[8/6 야간 → 8/7 주간 인수인계] 작성 57\n특이사항 없음.\n
HN-260808-D	2026-08-08	주간	79	2026-08-08 17:00:00	[8/8 주간 → 8/8 야간 인수인계] 작성 79\n2라인 1호 프레스 진동 이상 없음.\n
HN-260808-N	2026-08-08	야간	57	2026-08-09 05:00:00	[8/8 야간 → 8/9 주간 인수인계] 작성 57\n2라인 1호 소음은 금형 문제인 듯.\nP103 진동 이상 없음.\n
HN-260809-N	2026-08-09	야간	24	2026-08-10 05:00:00	[8/9 야간 → 8/10 주간 인수인계] 작성 24\n새벽 2시쯤 1라인 3호 프레스 유압 압력이 떨어져서 보전팀 호출함.\n
HN-260812-N	2026-08-12	야간	38	2026-08-13 05:00:00	[8/12 야간 → 8/13 주간 인수인계] 작성 38\n특이사항 없음.\n
HN-260817-N	2026-08-17	야간	24	2026-08-18 05:00:00	[8/17 야간 → 8/18 주간 인수인계] 작성 24\n1호기 진동 있음. 볼트 재체결 했는데 지켜봐야 함.\n
HN-260820-D	2026-08-20	주간	52	2026-08-20 17:00:00	[8/20 주간 → 8/20 야간 인수인계] 작성 52\n이상 없이 교대함.\n
HN-260820-N	2026-08-20	야간	24	2026-08-21 05:00:00	[8/20 야간 → 8/21 주간 인수인계] 작성 24\nJB2608-0129 잔량 1,900개는 주간조에 넘김.\nJB2608-0130 잔량 900개는 주간조에 넘김.\n
HN-260821-D	2026-08-21	주간	36	2026-08-21 17:00:00	[8/21 주간 → 8/21 야간 인수인계] 작성 36\n점심 직후 1라인 1호 프레스 유압 압력이 떨어져서 보전팀 호출함.\n
HN-260821-N	2026-08-21	야간	57	2026-08-22 05:00:00	[8/21 야간 → 8/22 주간 인수인계] 작성 57\nP101 이음 발생.\n
HN-260822-N	2026-08-22	야간	57	2026-08-23 05:00:00	[8/22 야간 → 8/23 주간 인수인계] 작성 57\n전달 사항 없음.\n
HN-260825-N	2026-08-25	야간	48	2026-08-26 05:00:00	[8/25 야간 → 8/26 주간 인수인계] 작성 48\n밤 10시쯤 1라인 블랭킹기 유압 압력이 떨어져서 보전팀 호출함.\n
HN-260829-D	2026-08-29	주간	6	2026-08-29 17:00:00	[8/29 주간 → 8/29 야간 인수인계] 작성 6\nJB2608-0147 잔량 700개는 야간조에 넘김.\n
HN-260831-N	2026-08-31	야간	38	2026-09-01 05:00:00	[8/31 야간 → 9/1 주간 인수인계] 작성 38\nP101 누유 없음.\n
HN-260902-N	2026-09-02	야간	55	2026-09-03 05:00:00	[9/2 야간 → 9/3 주간 인수인계] 작성 55\n새벽 2시쯤 1라인 블랭킹기 유압 압력이 떨어져서 보전팀 호출함.\n
HN-260904-D	2026-09-04	주간	79	2026-09-04 17:00:00	[9/4 주간 → 9/4 야간 인수인계] 작성 79\n1라인 1호 소음은 금형 문제인 듯.\n
HN-260905-D	2026-09-05	주간	40	2026-09-05 17:00:00	[9/5 주간 → 9/5 야간 인수인계] 작성 40\n1-3호 진동↑.\n
HN-260906-N	2026-09-06	야간	48	2026-09-07 05:00:00	[9/6 야간 → 9/7 주간 인수인계] 작성 48\nRP-26-005 윤활 배관 점검·보충 이후 P103 이상 없음.\nP201 이음 발생.\n
HN-260908-D	2026-09-08	주간	44	2026-09-08 17:00:00	[9/8 주간 → 9/8 야간 인수인계] 작성 44\n이상 없이 교대함.\n
HN-260909-D	2026-09-09	주간	31	2026-09-09 17:00:00	[9/9 주간 → 9/9 야간 인수인계] 작성 31\np201 온도 경고 2회.\n
HN-260909-N	2026-09-09	야간	74	2026-09-10 05:00:00	[9/9 야간 → 9/10 주간 인수인계] 작성 74\np201 온도 경고 2회.\n
HN-260910-N	2026-09-10	야간	74	2026-09-11 05:00:00	[9/10 야간 → 9/11 주간 인수인계] 작성 74\n1라인 2호기 쪽에서 이상한 소리 남. 확인 바람.\nJB2609-0115 잔량 900개는 주간조에 넘김.\n
HN-260911-N	2026-09-11	야간	48	2026-09-12 05:00:00	[9/11 야간 → 9/12 주간 인수인계] 작성 48\nRP-26-007 윤활 배관 점검·보충 이후 1라인 블랭킹기 이상 없음.\n
HN-260913-D	2026-09-13	주간	40	2026-09-13 17:00:00	[9/13 주간 → 9/13 야간 인수인계] 작성 40\n점심 직후 1라인 1호 진동이 평소보다 커서 점검 요청함.\n
HN-260914-D	2026-09-14	주간	72	2026-09-14 17:00:00	[9/14 주간 → 9/14 야간 인수인계] 작성 72\nP103 이음 발생.\nP101 누유 없음.\n
HN-260916-D	2026-09-16	주간	27	2026-09-16 17:00:00	[9/16 주간 → 9/16 야간 인수인계] 작성 27\nP201 누유 없음.\n
HN-260916-N	2026-09-16	야간	48	2026-09-17 05:00:00	[9/16 야간 → 9/17 주간 인수인계] 작성 48\nP103 누유 없음.\n
HN-260917-D	2026-09-17	주간	45	2026-09-17 17:00:00	[9/17 주간 → 9/17 야간 인수인계] 작성 45\nJB2609-0121 잔량 2,000개는 야간조에 넘김.\n
HN-260918-N	2026-09-18	야간	57	2026-09-19 05:00:00	[9/18 야간 → 9/19 주간 인수인계] 작성 57\nJB2609-0125 잔량 400개는 주간조에 넘김.\n
HN-260919-N	2026-09-19	야간	24	2026-09-20 05:00:00	[9/19 야간 → 9/20 주간 인수인계] 작성 24\n2라인 3호기 쪽에서 이상한 소리 남. 확인 바람.\n
HN-260920-D	2026-09-20	주간	44	2026-09-20 17:00:00	[9/20 주간 → 9/20 야간 인수인계] 작성 44\n오전 10시쯤 1라인 블랭킹기 이음이 들려서 가동 멈추고 확인함.\n
HN-260920-N	2026-09-20	야간	74	2026-09-21 05:00:00	[9/20 야간 → 9/21 주간 인수인계] 작성 74\n1-1호 이음 발생.\n
HN-260921-D	2026-09-21	주간	36	2026-09-21 17:00:00	[9/21 주간 → 9/21 야간 인수인계] 작성 36\n오후 3시쯤 2라인 1호 프레스 진동이 평소보다 커서 점검 요청함.\n
HN-260923-D	2026-09-23	주간	79	2026-09-23 17:00:00	[9/23 주간 → 9/23 야간 인수인계] 작성 79\n2라인 1호 프레스 진동 이상 없음.\n
HN-260923-N	2026-09-23	야간	48	2026-09-24 05:00:00	[9/23 야간 → 9/24 주간 인수인계] 작성 48\n이상 없이 교대함.\n
HN-260924-D	2026-09-24	주간	40	2026-09-24 17:00:00	[9/24 주간 → 9/24 야간 인수인계] 작성 40\n특이사항 없음.\n
HN-260924-N	2026-09-24	야간	55	2026-09-25 05:00:00	[9/24 야간 → 9/25 주간 인수인계] 작성 55\n교대 직후 1라인 블랭킹기 유압 압력이 떨어져서 보전팀 호출함.\n
HN-260930-D	2026-09-30	주간	27	2026-09-30 17:00:00	[9/30 주간 → 9/30 야간 인수인계] 작성 27\nP201 누유 없음.\n
HN-261006-D	2026-10-06	주간	27	2026-10-06 17:00:00	[10/6 주간 → 10/6 야간 인수인계] 작성 27\nRP-26-005 윤활 배관 점검·보충 이후 P103 이상 없음.\n
HN-261008-D	2026-10-08	주간	52	2026-10-08 17:00:00	[10/8 주간 → 10/8 야간 인수인계] 작성 52\n전달 사항 없음.\n
HN-261009-N	2026-10-09	야간	74	2026-10-10 05:00:00	[10/9 야간 → 10/10 주간 인수인계] 작성 74\nJB2610-0117 잔량 800개는 주간조에 넘김.\n
HN-261010-D	2026-10-10	주간	31	2026-10-10 17:00:00	[10/10 주간 → 10/10 야간 인수인계] 작성 31\n2라인 1호 소음은 금형 문제인 듯.\n
HN-261014-N	2026-10-14	야간	38	2026-10-15 05:00:00	[10/14 야간 → 10/15 주간 인수인계] 작성 38\nLT261017-1 출하 전 외관 확인 완료.\nJB2610-0125 잔량 400개는 주간조에 넘김.\n1호기 소음 계속됨. 금형 교체 후에도 비슷함.\n
HN-261015-D	2026-10-15	주간	52	2026-10-15 17:00:00	[10/15 주간 → 10/15 야간 인수인계] 작성 52\n1호기 진동 있음. 볼트 재체결 했는데 지켜봐야 함.\n교대 직후 P201 이음이 들려서 가동 멈추고 확인함.\n
HN-261015-N	2026-10-15	야간	38	2026-10-16 05:00:00	[10/15 야간 → 10/16 주간 인수인계] 작성 38\n전달 사항 없음.\n
HN-261017-D	2026-10-17	주간	79	2026-10-17 17:00:00	[10/17 주간 → 10/17 야간 인수인계] 작성 79\nJB2610-0131 잔량 300개는 야간조에 넘김.\n
HN-261019-D	2026-10-19	주간	52	2026-10-19 17:00:00	[10/19 주간 → 10/19 야간 인수인계] 작성 52\n점심 직후 P103 이음이 들려서 가동 멈추고 확인함.\n
HN-261020-D	2026-10-20	주간	40	2026-10-20 17:00:00	[10/20 주간 → 10/20 야간 인수인계] 작성 40\n특이사항 없음.\n
HN-261023-N	2026-10-23	야간	48	2026-10-24 05:00:00	[10/23 야간 → 10/24 주간 인수인계] 작성 48\n이상 없이 교대함.\n
HN-261024-D	2026-10-24	주간	31	2026-10-24 17:00:00	[10/24 주간 → 10/24 야간 인수인계] 작성 31\nLT261024-1 출하 전 외관 확인 완료.\n
HN-261024-N	2026-10-24	야간	24	2026-10-25 05:00:00	[10/24 야간 → 10/25 주간 인수인계] 작성 24\nJB2610-0139 잔량 1,100개는 주간조에 넘김.\n
HN-261025-D	2026-10-25	주간	6	2026-10-25 17:00:00	[10/25 주간 → 10/25 야간 인수인계] 작성 6\n특이사항 없음.\n
HN-261026-N	2026-10-26	야간	24	2026-10-27 05:00:00	[10/26 야간 → 10/27 주간 인수인계] 작성 24\n이상 없이 교대함.\n
HN-261027-D	2026-10-27	주간	40	2026-10-27 17:00:00	[10/27 주간 → 10/27 야간 인수인계] 작성 40\n전달 사항 없음.\n
HN-261027-N	2026-10-27	야간	24	2026-10-28 05:00:00	[10/27 야간 → 10/28 주간 인수인계] 작성 24\n새벽 2시쯤 1라인 3호 프레스 진동이 평소보다 커서 점검 요청함.\n
HN-261028-D	2026-10-28	주간	31	2026-10-28 17:00:00	[10/28 주간 → 10/28 야간 인수인계] 작성 31\nP103 진동 상한 넘음.\n
HN-261029-D	2026-10-29	주간	31	2026-10-29 17:00:00	[10/29 주간 → 10/29 야간 인수인계] 작성 31\nP101 누유 없음.\nRP-26-011 유압 펌프 필터 교체 이후 1라인 블랭킹기 압력 저하 없음.\n
HN-261029-N	2026-10-29	야간	57	2026-10-30 05:00:00	[10/29 야간 → 10/30 주간 인수인계] 작성 57\n새벽 3시쯤 3호기 슬라이드 유압 누유로 정지. RP-26-041 정비 진행 중.\nJB2610-0141 성형은 P103 정지로 중단, 잔량 3,200개.\nJB2610-0138 잔량 2,400개는 주간조에 넘김.\n
\.


--
-- Data for Name: inspection; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.inspection (inspection_id, wo_no, lot_no, inspection_type, item, spec_low, spec_high, measured, result, inspected_at) FROM stdin;
90001	JB2608-0101	LT260804-1	최종	홀 지름	8.950	9.050	9.004	OK	2026-08-03 21:19:00
90002	JB2608-0102	LT260805-1	최종	홀 지름	8.950	9.050	9.031	OK	2026-08-04 01:45:00
90003	JB2608-0103	LT260806-1	최종	홀 지름	8.950	9.050	9.037	OK	2026-08-05 10:31:00
90004	JB2608-0104	LT260806-2	최종	홀 지름	8.950	9.050	8.980	OK	2026-08-05 23:26:00
90005	JB2608-0105	LT260806-3	최종	홀 지름	8.950	9.050	8.974	OK	2026-08-05 12:58:00
90006	JB2608-0106	LT260807-1	최종	홀 지름	8.950	9.050	8.963	OK	2026-08-06 14:57:00
90007	JB2608-0107	LT260807-2	최종	홀 지름	8.950	9.050	8.968	OK	2026-08-06 23:13:00
90008	JB2608-0108	LT260808-1	최종	홀 지름	8.950	9.050	9.001	OK	2026-08-07 04:01:00
90009	JB2608-0109	LT260808-2	최종	홀 지름	8.950	9.050	9.080	NG	2026-08-07 16:07:00
90010	JB2608-0110	LT260808-3	최종	홀 지름	8.950	9.050	9.004	OK	2026-08-07 17:24:00
90011	JB2608-0111	LT260809-1	최종	홀 지름	8.950	9.050	9.080	NG	2026-08-08 15:14:00
90012	JB2608-0112	LT260811-1	최종	홀 지름	8.950	9.050	9.014	OK	2026-08-10 11:07:00
90013	JB2608-0113	LT260812-1	최종	홀 지름	8.950	9.050	8.992	OK	2026-08-11 01:51:00
90014	JB2608-0114	LT260812-2	최종	홀 지름	8.950	9.050	9.028	OK	2026-08-11 01:54:00
90015	JB2608-0115	LT260812-3	최종	홀 지름	8.950	9.050	8.972	OK	2026-08-11 15:30:00
90016	JB2608-0116	LT260813-1	최종	홀 지름	8.950	9.050	8.962	OK	2026-08-12 12:18:00
90017	JB2608-0117	LT260813-2	최종	홀 지름	8.950	9.050	9.026	OK	2026-08-12 13:05:00
90018	JB2608-0118	LT260814-1	최종	홀 지름	8.950	9.050	9.015	OK	2026-08-13 03:20:00
90019	JB2608-0119	LT260815-1	최종	홀 지름	8.950	9.050	9.080	NG	2026-08-14 01:43:00
90020	JB2608-0120	LT260815-2	최종	홀 지름	8.950	9.050	9.017	OK	2026-08-14 03:57:00
90021	JB2608-0120	LT260816-1	최종	홀 지름	8.950	9.050	8.989	OK	2026-08-14 03:57:00
90022	JB2608-0121	LT260815-3	최종	홀 지름	8.950	9.050	9.022	OK	2026-08-14 23:05:00
90023	JB2608-0122	LT260816-2	최종	홀 지름	8.950	9.050	8.969	OK	2026-08-15 09:28:00
90024	JB2608-0123	LT260816-3	최종	홀 지름	8.950	9.050	9.022	OK	2026-08-15 11:31:00
90025	JB2608-0124	LT260818-1	최종	홀 지름	8.950	9.050	9.029	OK	2026-08-17 22:42:00
90026	JB2608-0125	LT260819-1	최종	홀 지름	8.950	9.050	8.983	OK	2026-08-18 04:54:00
90027	JB2608-0126	LT260820-1	최종	홀 지름	8.950	9.050	9.011	OK	2026-08-19 01:40:00
90028	JB2608-0127	LT260820-2	최종	홀 지름	8.950	9.050	9.007	OK	2026-08-19 01:25:00
90029	JB2608-0128	LT260820-3	최종	홀 지름	8.950	9.050	8.962	OK	2026-08-19 15:53:00
90030	JB2608-0129	LT260821-1	최종	홀 지름	8.950	9.050	9.019	OK	2026-08-20 12:44:00
90031	JB2608-0129	LT260822-1	최종	홀 지름	8.950	9.050	8.986	OK	2026-08-20 12:44:00
90032	JB2608-0130	LT260821-2	최종	홀 지름	8.950	9.050	8.999	OK	2026-08-20 10:54:00
90033	JB2608-0131	LT260822-2	최종	홀 지름	8.950	9.050	8.989	OK	2026-08-21 02:32:00
90034	JB2608-0132	LT260823-1	최종	홀 지름	8.950	9.050	8.972	OK	2026-08-22 09:52:00
90035	JB2608-0133	LT260823-2	최종	홀 지름	8.950	9.050	8.972	OK	2026-08-22 12:06:00
90036	JB2608-0134	LT260822-3	최종	홀 지름	8.950	9.050	8.966	OK	2026-08-22 00:24:00
90037	JB2608-0135	LT260825-1	최종	홀 지름	8.950	9.050	8.974	OK	2026-08-24 12:09:00
90038	JB2608-0136	LT260825-2	최종	홀 지름	8.950	9.050	8.999	OK	2026-08-24 09:10:00
90039	JB2608-0137	LT260826-1	최종	홀 지름	8.950	9.050	9.007	OK	2026-08-25 12:58:00
90040	JB2608-0137	LT260827-1	최종	홀 지름	8.950	9.050	9.009	OK	2026-08-25 12:58:00
90041	JB2608-0138	LT260826-2	최종	홀 지름	8.950	9.050	9.008	OK	2026-08-25 16:51:00
90042	JB2608-0139	LT260827-2	최종	홀 지름	8.950	9.050	8.985	OK	2026-08-26 15:02:00
90043	JB2608-0140	LT260827-3	최종	홀 지름	8.950	9.050	9.029	OK	2026-08-26 10:51:00
90044	JB2608-0141	LT260828-1	최종	홀 지름	8.950	9.050	8.910	NG	2026-08-27 23:32:00
90045	JB2608-0141	LT260829-1	최종	홀 지름	8.950	9.050	9.019	OK	2026-08-27 23:32:00
90046	JB2608-0142	LT260828-2	최종	홀 지름	8.950	9.050	8.981	OK	2026-08-28 00:10:00
90047	JB2608-0143	LT260828-3	최종	홀 지름	8.950	9.050	9.004	OK	2026-08-27 16:03:00
90048	JB2608-0144	LT260829-2	최종	홀 지름	8.950	9.050	8.993	OK	2026-08-28 23:25:00
90049	JB2608-0145	LT260829-3	최종	홀 지름	8.950	9.050	9.029	OK	2026-08-28 17:38:00
90050	JB2608-0146	LT260901-1	최종	홀 지름	8.950	9.050	8.910	NG	2026-08-31 16:52:00
90051	JB2608-0146	LT260902-1	최종	홀 지름	8.950	9.050	9.035	OK	2026-08-31 16:52:00
90052	JB2608-0147	LT260901-2	최종	홀 지름	8.950	9.050	8.999	OK	2026-08-31 11:35:00
90053	JB2608-0148	LT260902-2	최종	홀 지름	8.950	9.050	8.986	OK	2026-09-01 04:41:00
90054	JB2608-0149	LT260902-3	최종	홀 지름	8.950	9.050	9.013	OK	2026-09-01 05:58:00
90055	JB2608-0150	LT260903-1	최종	홀 지름	8.950	9.050	9.019	OK	2026-09-02 04:18:00
90056	JB2609-0101	LT260903-2	최종	홀 지름	8.950	9.050	8.997	OK	2026-09-02 05:46:00
90057	JB2609-0102	LT260902-4	최종	홀 지름	8.950	9.050	9.014	OK	2026-09-02 00:17:00
90058	JB2609-0103	LT260904-1	최종	홀 지름	8.950	9.050	9.021	OK	2026-09-03 10:13:00
90059	JB2609-0104	LT260904-2	최종	홀 지름	8.950	9.050	8.991	OK	2026-09-03 10:57:00
90060	JB2609-0105	LT260905-1	최종	홀 지름	8.950	9.050	9.003	OK	2026-09-04 15:24:00
90061	JB2609-0106	LT260905-2	최종	홀 지름	8.950	9.050	9.017	OK	2026-09-04 17:00:00
90062	JB2609-0107	LT260908-1	최종	홀 지름	8.950	9.050	9.011	OK	2026-09-07 14:17:00
90063	JB2609-0107	LT260909-1	최종	홀 지름	8.950	9.050	9.012	OK	2026-09-07 14:17:00
90064	JB2609-0108	LT260908-2	최종	홀 지름	8.950	9.050	9.001	OK	2026-09-07 15:44:00
90065	JB2609-0109	LT260910-1	최종	홀 지름	8.950	9.050	9.021	OK	2026-09-09 01:44:00
90066	JB2609-0109	LT260911-1	최종	홀 지름	8.950	9.050	8.978	OK	2026-09-09 01:44:00
90067	JB2609-0110	LT260909-2	최종	홀 지름	8.950	9.050	8.984	OK	2026-09-08 15:16:00
90068	JB2609-0111	LT260910-2	최종	홀 지름	8.950	9.050	8.989	OK	2026-09-09 17:31:00
90069	JB2609-0112	LT260910-3	최종	홀 지름	8.950	9.050	9.017	OK	2026-09-09 23:35:00
90070	JB2609-0113	LT260911-2	최종	홀 지름	8.950	9.050	8.970	OK	2026-09-10 17:22:00
90071	JB2609-0113	LT260912-1	최종	홀 지름	8.950	9.050	8.986	OK	2026-09-10 17:22:00
90072	JB2609-0114	LT260912-2	최종	홀 지름	8.950	9.050	8.990	OK	2026-09-11 10:50:00
90073	JB2609-0115	LT260913-1	최종	홀 지름	8.950	9.050	8.995	OK	2026-09-12 03:57:00
90074	JB2609-0115	LT260914-1	최종	홀 지름	8.950	9.050	8.977	OK	2026-09-12 03:57:00
90075	JB2609-0116	LT260913-2	최종	홀 지름	8.950	9.050	9.012	OK	2026-09-12 03:35:00
90076	JB2609-0117	LT260915-1	최종	홀 지름	8.950	9.050	8.970	OK	2026-09-14 15:11:00
90077	JB2609-0118	LT260916-1	최종	홀 지름	8.950	9.050	9.009	OK	2026-09-15 01:36:00
90078	JB2609-0118	LT260917-1	최종	홀 지름	8.950	9.050	8.986	OK	2026-09-15 01:36:00
90079	JB2609-0119	LT260917-2	최종	홀 지름	8.950	9.050	9.030	OK	2026-09-16 01:10:00
90080	JB2609-0119	LT260918-1	최종	홀 지름	8.950	9.050	9.005	OK	2026-09-16 01:10:00
90081	JB2609-0120	LT260917-3	최종	홀 지름	8.950	9.050	8.988	OK	2026-09-16 12:52:00
90082	JB2609-0120	LT260918-2	최종	홀 지름	8.950	9.050	9.011	OK	2026-09-16 12:52:00
90083	JB2609-0121	LT260918-3	최종	홀 지름	8.950	9.050	9.031	OK	2026-09-17 09:36:00
90084	JB2609-0122	LT260918-4	최종	홀 지름	8.950	9.050	8.965	OK	2026-09-17 10:39:00
90085	JB2609-0123	LT260920-1	최종	홀 지름	8.950	9.050	9.006	OK	2026-09-19 03:12:00
90086	JB2609-0123	LT260921-1	최종	홀 지름	8.950	9.050	9.000	OK	2026-09-19 03:12:00
90087	JB2609-0124	LT260919-1	최종	홀 지름	8.950	9.050	8.985	OK	2026-09-18 16:56:00
90088	JB2609-0125	LT260920-2	최종	홀 지름	8.950	9.050	9.037	OK	2026-09-19 13:59:00
90089	JB2609-0125	LT260921-2	최종	홀 지름	8.950	9.050	8.996	OK	2026-09-19 13:59:00
90090	JB2609-0126	LT260920-3	최종	홀 지름	8.950	9.050	9.007	OK	2026-09-19 09:55:00
90091	JB2609-0127	LT260923-1	최종	홀 지름	8.950	9.050	9.033	OK	2026-09-22 12:26:00
90092	JB2609-0128	LT260923-2	최종	홀 지름	8.950	9.050	9.017	OK	2026-09-22 15:35:00
90093	JB2609-0128	LT260924-1	최종	홀 지름	8.950	9.050	9.003	OK	2026-09-22 15:35:00
90094	JB2609-0129	LT260923-3	최종	홀 지름	8.950	9.050	9.006	OK	2026-09-22 14:53:00
90095	JB2609-0130	LT260924-2	최종	홀 지름	8.950	9.050	8.982	OK	2026-09-23 23:59:00
90096	JB2609-0130	LT260925-1	최종	홀 지름	8.950	9.050	8.961	OK	2026-09-23 23:59:00
90097	JB2609-0131	LT260924-3	최종	홀 지름	8.950	9.050	9.080	NG	2026-09-23 15:34:00
90098	JB2609-0132	LT260924-4	최종	홀 지름	8.950	9.050	8.987	OK	2026-09-23 16:13:00
90099	JB2609-0133	LT260925-2	최종	홀 지름	8.950	9.050	9.007	OK	2026-09-24 11:50:00
90100	JB2609-0134	LT260926-1	최종	홀 지름	8.950	9.050	8.965	OK	2026-09-25 13:04:00
90101	JB2609-0135	LT260926-2	최종	홀 지름	8.950	9.050	8.977	OK	2026-09-25 09:14:00
90102	JB2609-0136	LT260926-3	최종	홀 지름	8.950	9.050	9.023	OK	2026-09-25 17:31:00
90103	JB2609-0137	LT260927-1	최종	홀 지름	8.950	9.050	9.026	OK	2026-09-26 04:23:00
90104	JB2609-0138	LT260927-2	최종	홀 지름	8.950	9.050	8.971	OK	2026-09-26 16:15:00
90105	JB2609-0139	LT260927-3	최종	홀 지름	8.950	9.050	8.977	OK	2026-09-26 11:34:00
90106	JB2609-0140	LT260930-1	최종	홀 지름	8.950	9.050	8.999	OK	2026-09-29 10:39:00
90107	JB2609-0140	LT261001-1	최종	홀 지름	8.950	9.050	8.992	OK	2026-09-29 10:39:00
90108	JB2609-0141	LT260929-1	최종	홀 지름	8.950	9.050	9.029	OK	2026-09-28 22:56:00
90109	JB2609-0142	LT260930-2	최종	홀 지름	8.950	9.050	9.030	OK	2026-09-29 17:24:00
90110	JB2609-0143	LT261001-2	최종	홀 지름	8.950	9.050	8.998	OK	2026-10-01 00:08:00
90111	JB2609-0143	LT261002-1	최종	홀 지름	8.950	9.050	9.030	OK	2026-10-01 00:08:00
90112	JB2609-0144	LT261002-2	최종	홀 지름	8.950	9.050	9.010	OK	2026-10-01 04:59:00
90113	JB2609-0144	LT261003-1	최종	홀 지름	8.950	9.050	8.975	OK	2026-10-01 04:59:00
90114	JB2609-0145	LT261002-3	최종	홀 지름	8.950	9.050	9.037	OK	2026-10-01 12:27:00
90115	JB2610-0101	LT261003-2	최종	홀 지름	8.950	9.050	9.018	OK	2026-10-02 01:28:00
90116	JB2610-0102	LT261003-3	최종	홀 지름	8.950	9.050	8.910	NG	2026-10-02 01:05:00
90117	JB2610-0103	LT261004-1	최종	홀 지름	8.950	9.050	9.038	OK	2026-10-03 04:46:00
90118	JB2610-0103	LT261005-1	최종	홀 지름	8.950	9.050	8.991	OK	2026-10-03 04:46:00
90119	JB2610-0104	LT261004-2	최종	홀 지름	8.950	9.050	9.004	OK	2026-10-03 10:27:00
90120	JB2610-0105	LT261006-1	최종	홀 지름	8.950	9.050	9.019	OK	2026-10-05 10:32:00
90121	JB2610-0106	LT261006-2	최종	홀 지름	8.950	9.050	8.998	OK	2026-10-05 16:55:00
90122	JB2610-0107	LT261006-3	최종	홀 지름	8.950	9.050	9.016	OK	2026-10-05 17:50:00
90123	JB2610-0108	LT261007-1	최종	홀 지름	8.950	9.050	9.012	OK	2026-10-06 14:13:00
90124	JB2610-0109	LT261008-1	최종	홀 지름	8.950	9.050	8.971	OK	2026-10-07 01:11:00
90125	JB2610-0109	LT261009-1	최종	홀 지름	8.950	9.050	9.005	OK	2026-10-07 01:11:00
90126	JB2610-0110	LT261008-2	최종	홀 지름	8.950	9.050	9.027	OK	2026-10-07 01:57:00
90127	JB2610-0111	LT261008-3	최종	홀 지름	8.950	9.050	8.991	OK	2026-10-07 14:09:00
90128	JB2610-0112	LT261009-2	최종	홀 지름	8.950	9.050	8.983	OK	2026-10-08 03:03:00
90129	JB2610-0113	LT261009-3	최종	홀 지름	8.950	9.050	9.018	OK	2026-10-08 21:56:00
90130	JB2610-0113	LT261010-1	최종	홀 지름	8.950	9.050	9.001	OK	2026-10-08 21:56:00
90131	JB2610-0114	LT261010-2	최종	홀 지름	8.950	9.050	8.964	OK	2026-10-09 05:39:00
90132	JB2610-0115	LT261011-1	최종	홀 지름	8.950	9.050	8.979	OK	2026-10-10 03:09:00
90133	JB2610-0115	LT261012-1	최종	홀 지름	8.950	9.050	9.018	OK	2026-10-10 03:09:00
90134	JB2610-0116	LT261011-2	최종	홀 지름	8.950	9.050	9.039	OK	2026-10-10 05:36:00
90135	JB2610-0117	LT261011-3	최종	홀 지름	8.950	9.050	9.021	OK	2026-10-10 11:07:00
90136	JB2610-0118	LT261013-1	최종	홀 지름	8.950	9.050	9.037	OK	2026-10-12 09:08:00
90137	JB2610-0119	LT261013-2	최종	홀 지름	8.950	9.050	9.022	OK	2026-10-12 16:11:00
90138	JB2610-0120	LT261014-1	최종	홀 지름	8.950	9.050	9.080	NG	2026-10-13 01:08:00
90139	JB2610-0121	LT261015-1	최종	홀 지름	8.950	9.050	8.910	NG	2026-10-14 09:32:00
90140	JB2610-0122	LT261015-2	최종	홀 지름	8.950	9.050	9.000	OK	2026-10-14 11:07:00
90141	JB2610-0123	LT261014-2	최종	홀 지름	8.950	9.050	9.080	NG	2026-10-14 00:01:00
90142	JB2610-0124	LT261016-1	최종	홀 지름	8.950	9.050	9.002	OK	2026-10-15 02:32:00
90143	JB2610-0125	LT261016-2	최종	홀 지름	8.950	9.050	8.962	OK	2026-10-15 02:53:00
90144	JB2610-0126	LT261017-1	최종	홀 지름	8.950	9.050	8.975	OK	2026-10-16 12:42:00
90145	JB2610-0126	LT261018-1	최종	홀 지름	8.950	9.050	8.974	OK	2026-10-16 12:42:00
90146	JB2610-0127	LT261016-3	최종	홀 지름	8.950	9.050	9.011	OK	2026-10-16 00:38:00
90147	JB2610-0128	LT261018-2	최종	홀 지름	8.950	9.050	8.974	OK	2026-10-17 01:53:00
90148	JB2610-0128	LT261019-1	최종	홀 지름	8.950	9.050	8.962	OK	2026-10-17 01:53:00
90149	JB2610-0129	LT261017-2	최종	홀 지름	8.950	9.050	9.009	OK	2026-10-16 21:48:00
90150	JB2610-0130	LT261020-1	최종	홀 지름	8.950	9.050	9.005	OK	2026-10-19 22:56:00
90151	JB2610-0130	LT261021-1	최종	홀 지름	8.950	9.050	8.976	OK	2026-10-19 22:56:00
90152	JB2610-0131	LT261021-2	최종	홀 지름	8.950	9.050	9.010	OK	2026-10-20 03:25:00
90153	JB2610-0132	LT261022-1	최종	홀 지름	8.950	9.050	9.000	OK	2026-10-21 13:30:00
90154	JB2610-0132	LT261023-1	최종	홀 지름	8.950	9.050	9.025	OK	2026-10-21 13:30:00
90155	JB2610-0133	LT261022-2	최종	홀 지름	8.950	9.050	9.037	OK	2026-10-21 21:06:00
90156	JB2610-0133	LT261023-2	최종	홀 지름	8.950	9.050	8.998	OK	2026-10-21 21:06:00
90157	JB2610-0134	LT261023-3	최종	홀 지름	8.950	9.050	9.080	NG	2026-10-22 03:11:00
90158	JB2610-0135	LT261024-1	최종	홀 지름	8.950	9.050	8.910	NG	2026-10-23 05:07:00
90159	JB2610-0136	LT261024-2	최종	홀 지름	8.950	9.050	9.035	OK	2026-10-23 02:53:00
90160	JB2610-0137	LT261024-3	최종	홀 지름	8.950	9.050	9.029	OK	2026-10-23 16:47:00
90161	JB2610-0139	LT261025-1	최종	홀 지름	8.950	9.050	8.964	OK	2026-10-24 12:03:00
\.


--
-- Data for Name: maintenance_order; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.maintenance_order (mo_no, target_type, target_code, mo_type, status, planned_date, completed_at, technician, parts_cost, note) FROM stdin;
RP-26-001	설비	P101	고장	완료	2026-08-05	2026-08-05 10:50:00	보전팀	950000.00	피더 센서 교체
RP-26-002	설비	P101	예방	완료	2026-08-06	2026-08-06 10:20:00	보전팀	80000.00	윤활 배관 점검·보충
RP-26-003	설비	P201	예방	완료	2026-08-24	2026-08-24 02:40:00	보전팀	0.00	윤활 배관 점검·보충
RP-26-004	설비	P103	고장	완료	2026-08-31	2026-08-31 09:00:00	보전팀	590000.00	가이드 라이너 교체
RP-26-005	설비	P103	예방	완료	2026-08-31	2026-08-31 15:10:00	보전팀	80000.00	윤활 배관 점검·보충
RP-26-006	설비	P101	고장	완료	2026-09-10	2026-09-10 13:20:00	보전팀	910000.00	유압 펌프 필터 교체
RP-26-007	설비	B101	예방	완료	2026-09-10	2026-09-10 09:00:00	보전팀	0.00	윤활 배관 점검·보충
RP-26-008	설비	B101	예방	완료	2026-09-17	2026-09-17 10:20:00	보전팀	0.00	윤활 배관 점검·보충
RP-26-009	설비	P201	고장	완료	2026-10-02	2026-10-02 14:20:00	보전팀	1050000.00	피더 센서 교체
RP-26-010	설비	P101	고장	완료	2026-10-09	2026-10-09 09:50:00	보전팀	920000.00	유압 펌프 필터 교체
RP-26-011	설비	B101	고장	완료	2026-10-19	2026-10-19 06:00:00	보전팀	350000.00	유압 펌프 필터 교체
RP-26-012	설비	P103	예방	완료	2026-10-20	2026-10-20 10:40:00	보전팀	0.00	윤활 배관 점검·보충
RP-26-013	금형	MD-BRK210L-01	예방	완료	2026-09-03	2026-09-03 15:00:00	홍보람	0.00	펀치 연마
RP-26-014	금형	MD-RNF320L-01	예방	완료	2026-09-24	2026-09-24 15:00:00	홍보람	0.00	펀치 연마
RP-26-041	설비	P103	고장	진행	2026-10-30	\N	보전팀	1650000.00	슬라이드 유압 실린더 씰 교체
\.


--
-- Data for Name: material_issue; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.material_issue (issue_id, wo_no, item_code, lot_no, qty, unit, issued_at) FROM stdin;
70001	JB2608-0101	CL-590	CL590-0803-03	2100.000	KG	2026-08-03 07:30:00
70002	JB2608-0102	CL-590	CL590-0803-04	2800.000	KG	2026-08-03 09:28:00
70003	JB2608-0103	CL-590	CL590-0804-02	4900.000	KG	2026-08-04 07:30:00
70004	JB2608-0104	CL-440	CL440-0804-08	3150.000	KG	2026-08-04 11:52:00
70005	JB2608-0104	BT-M8	BTM8-0805-04	7000.000	EA	2026-08-05 08:38:00
70006	JB2608-0105	CL-590	CL590-0804-05	2800.000	KG	2026-08-04 15:22:00
70007	JB2608-0106	CL-590	CL590-0805-05	5600.000	KG	2026-08-05 09:01:00
70008	JB2608-0107	CL-590	CL590-0805-07	3500.000	KG	2026-08-05 23:28:00
70009	JB2608-0108	CL-590	CL590-0806-02	1400.000	KG	2026-08-06 19:30:00
70010	JB2608-0109	CL-440	CL440-0807-06	600.000	KG	2026-08-07 08:31:00
70011	JB2608-0110	CL-440	CL440-0807-07	600.000	KG	2026-08-07 09:43:00
70012	JB2608-0111	CL-590	CL590-0807-05	4200.000	KG	2026-08-07 14:37:00
70013	JB2608-0112	CL-590	CL590-0807-09	4900.000	KG	2026-08-07 21:05:00
70014	JB2608-0113	CL-590	CL590-0808-07	4200.000	KG	2026-08-08 13:44:00
70015	JB2608-0114	CL-590	CL590-0810-06	2800.000	KG	2026-08-10 09:37:00
70016	JB2608-0115	CL-590	CL590-0811-02	1400.000	KG	2026-08-11 07:30:00
70017	JB2608-0116	CL-590	CL590-0811-07	4900.000	KG	2026-08-11 08:47:00
70018	JB2608-0117	CL-590	CL590-0812-08	1400.000	KG	2026-08-12 02:00:00
70019	JB2608-0118	CL-440	CL440-0812-01	1200.000	KG	2026-08-12 11:35:00
70020	JB2608-0119	CL-440	CL440-0812-01	2700.000	KG	2026-08-12 19:30:00
70021	JB2608-0120	CL-440	CL440-0813-06	2100.000	KG	2026-08-13 01:50:00
70022	JB2608-0121	CL-440	CL440-0814-01	1800.000	KG	2026-08-14 00:13:00
70023	JB2608-0122	CL-590	CL590-0814-02	2800.000	KG	2026-08-14 14:27:00
70024	JB2608-0123	CL-590	CL590-0814-08	2100.000	KG	2026-08-14 21:35:00
70025	JB2608-0124	CL-440	CL440-0815-02	1500.000	KG	2026-08-15 13:58:00
70026	JB2608-0125	CL-440	CL440-0817-07	1800.000	KG	2026-08-17 07:40:00
70027	JB2608-0126	CL-590	CL590-0818-09	3500.000	KG	2026-08-18 03:12:00
70028	JB2608-0127	CL-590	CL590-0818-02	2800.000	KG	2026-08-18 09:25:00
70029	JB2608-0128	CL-590	CL590-0818-08	2800.000	KG	2026-08-18 23:55:00
70030	JB2608-0129	CL-590	CL590-0819-09	5600.000	KG	2026-08-19 07:30:00
70031	JB2608-0130	CL-440	CL440-0819-06	1800.000	KG	2026-08-19 12:33:00
70032	JB2608-0130	BT-M8	BTM8-0820-03	4000.000	EA	2026-08-20 00:26:00
70033	JB2608-0131	CL-590	CL590-0820-02	4200.000	KG	2026-08-20 02:23:00
70034	JB2608-0132	CL-590	CL590-0820-02	6300.000	KG	2026-08-20 23:14:00
70035	JB2608-0133	CL-440	CL440-0821-04	2700.000	KG	2026-08-21 04:21:00
70036	JB2608-0133	BT-M8	BTM8-0821-07	6000.000	EA	2026-08-21 23:01:00
70037	JB2608-0134	CL-590	CL590-0821-04	2100.000	KG	2026-08-21 10:31:00
70038	JB2608-0135	CL-590	CL590-0822-08	2800.000	KG	2026-08-22 07:30:00
70039	JB2608-0136	CL-590	CL590-0822-02	1400.000	KG	2026-08-22 10:00:00
70040	JB2608-0137	CL-590	CL590-0824-07	5600.000	KG	2026-08-24 07:40:00
70041	JB2608-0138	CL-590	CL590-0824-04	3500.000	KG	2026-08-24 22:39:00
70042	JB2608-0139	CL-440	CL440-0825-04	1500.000	KG	2026-08-25 21:21:00
70043	JB2608-0140	CL-590	CL590-0825-08	1400.000	KG	2026-08-25 23:58:00
70044	JB2608-0141	CL-590	CL590-0826-04	4900.000	KG	2026-08-26 19:32:00
70045	JB2608-0142	CL-590	CL590-0826-07	4200.000	KG	2026-08-26 23:39:00
70046	JB2608-0143	CL-440	CL440-0827-08	900.000	KG	2026-08-27 03:29:00
70047	JB2608-0143	BT-M8	BTM8-0827-01	2000.000	EA	2026-08-27 11:24:00
70048	JB2608-0144	CL-590	CL590-0828-05	2800.000	KG	2026-08-28 04:02:00
70049	JB2608-0145	CL-590	CL590-0828-03	1400.000	KG	2026-08-28 09:23:00
70050	JB2608-0146	CL-440	CL440-0828-03	2700.000	KG	2026-08-28 21:55:00
70051	JB2608-0147	CL-440	CL440-0829-04	1200.000	KG	2026-08-29 04:08:00
70052	JB2608-0148	CL-440	CL440-0831-04	900.000	KG	2026-08-31 15:22:00
70053	JB2608-0149	CL-440	CL440-0831-01	600.000	KG	2026-08-31 22:05:00
70054	JB2608-0150	CL-440	CL440-0831-08	2700.000	KG	2026-08-31 23:16:00
70055	JB2608-0150	BT-M8	BTM8-0901-02	6000.000	EA	2026-09-01 15:16:00
70056	JB2609-0101	CL-440	CL440-0901-05	2100.000	KG	2026-09-01 04:28:00
70057	JB2609-0102	CL-440	CL440-0901-02	900.000	KG	2026-09-01 11:00:00
70058	JB2609-0103	CL-440	CL440-0902-07	1800.000	KG	2026-09-02 10:47:00
70059	JB2609-0104	CL-440	CL440-0902-03	1200.000	KG	2026-09-02 16:16:00
70060	JB2609-0105	CL-440	CL440-0903-06	1500.000	KG	2026-09-03 20:43:00
70061	JB2609-0106	CL-440	CL440-0903-01	1500.000	KG	2026-09-03 23:38:00
70062	JB2609-0107	CL-440	CL440-0904-06	2700.000	KG	2026-09-04 21:30:00
70063	JB2609-0108	CL-440	CL440-0905-01	2100.000	KG	2026-09-05 01:54:00
70064	JB2609-0109	CL-590	CL590-0907-08	5600.000	KG	2026-09-07 20:14:00
70065	JB2609-0110	CL-590	CL590-0908-04	2100.000	KG	2026-09-08 01:08:00
70066	JB2609-0111	CL-440	CL440-0909-02	1200.000	KG	2026-09-09 01:46:00
70067	JB2609-0112	CL-440	CL440-0909-04	1200.000	KG	2026-09-09 07:30:00
70068	JB2609-0113	CL-440	CL440-0909-06	2100.000	KG	2026-09-09 16:01:00
70069	JB2609-0114	CL-440	CL440-0910-06	2100.000	KG	2026-09-10 04:05:00
70070	JB2609-0115	CL-440	CL440-0910-03	2700.000	KG	2026-09-10 21:52:00
70071	JB2609-0116	CL-440	CL440-0911-09	1500.000	KG	2026-09-11 09:20:00
70072	JB2609-0117	CL-590	CL590-0912-02	3500.000	KG	2026-09-12 08:05:00
70073	JB2609-0118	CL-590	CL590-0912-05	4900.000	KG	2026-09-12 10:55:00
70074	JB2609-0119	CL-590	CL590-0914-04	6300.000	KG	2026-09-14 13:41:00
70075	JB2609-0120	CL-590	CL590-0915-02	6300.000	KG	2026-09-15 00:06:00
70076	JB2609-0121	CL-440	CL440-0915-05	2700.000	KG	2026-09-15 23:40:00
70077	JB2609-0122	CL-590	CL590-0916-02	1400.000	KG	2026-09-16 23:22:00
70078	JB2609-0123	CL-590	CL590-0917-08	6300.000	KG	2026-09-17 20:06:00
70079	JB2609-0124	CL-590	CL590-0918-07	2800.000	KG	2026-09-18 01:12:00
70080	JB2609-0125	CL-440	CL440-0918-06	3150.000	KG	2026-09-18 03:35:00
70081	JB2609-0125	BT-M8	BTM8-0918-03	7000.000	EA	2026-09-18 23:26:00
70082	JB2609-0126	CL-440	CL440-0918-09	1200.000	KG	2026-09-18 15:26:00
70083	JB2609-0127	CL-440	CL440-0919-07	2700.000	KG	2026-09-19 13:42:00
70084	JB2609-0128	CL-440	CL440-0921-06	2700.000	KG	2026-09-21 09:38:00
70085	JB2609-0129	CL-440	CL440-0921-08	2250.000	KG	2026-09-21 14:09:00
70086	JB2609-0129	BT-M8	BTM8-0922-05	5000.000	EA	2026-09-22 03:54:00
70087	JB2609-0130	CL-440	CL440-0922-09	2700.000	KG	2026-09-22 14:05:00
70088	JB2609-0131	CL-440	CL440-0922-03	1500.000	KG	2026-09-22 21:25:00
70089	JB2609-0132	CL-440	CL440-0923-02	1350.000	KG	2026-09-23 00:18:00
70090	JB2609-0132	BT-M8	BTM8-0923-03	3000.000	EA	2026-09-23 10:27:00
70091	JB2609-0133	CL-590	CL590-0923-03	3500.000	KG	2026-09-23 14:04:00
70092	JB2609-0134	CL-590	CL590-0924-04	5600.000	KG	2026-09-24 04:29:00
70093	JB2609-0135	CL-440	CL440-0924-08	1500.000	KG	2026-09-24 12:23:00
70094	JB2609-0136	CL-440	CL440-0925-06	900.000	KG	2026-09-25 07:44:00
70095	JB2609-0137	CL-440	CL440-0925-01	1800.000	KG	2026-09-25 09:22:00
70096	JB2609-0137	BT-M8	BTM8-0925-07	4000.000	EA	2026-09-25 21:06:00
70097	JB2609-0138	CL-440	CL440-0925-04	1500.000	KG	2026-09-25 22:01:00
70098	JB2609-0139	CL-440	CL440-0926-05	600.000	KG	2026-09-26 00:53:00
70099	JB2609-0140	CL-590	CL590-0928-01	4900.000	KG	2026-09-28 07:30:00
70100	JB2609-0141	CL-590	CL590-0928-09	1400.000	KG	2026-09-28 11:57:00
70101	JB2609-0142	CL-440	CL440-0928-01	1800.000	KG	2026-09-28 21:26:00
70102	JB2609-0143	CL-440	CL440-0929-05	2700.000	KG	2026-09-29 15:09:00
70103	JB2609-0144	CL-440	CL440-0930-02	2100.000	KG	2026-09-30 03:54:00
70104	JB2609-0145	CL-440	CL440-0930-03	900.000	KG	2026-09-30 22:38:00
70105	JB2610-0101	CL-440	CL440-1001-06	1200.000	KG	2026-10-01 09:29:00
70106	JB2610-0102	CL-440	CL440-1001-04	900.000	KG	2026-10-01 11:47:00
70107	JB2610-0103	CL-590	CL590-1001-06	5600.000	KG	2026-10-01 23:35:00
70108	JB2610-0104	CL-590	CL590-1002-04	4900.000	KG	2026-10-02 07:30:00
70109	JB2610-0105	CL-590	CL590-1003-06	2100.000	KG	2026-10-03 08:57:00
70110	JB2610-0106	CL-590	CL590-1003-08	2100.000	KG	2026-10-03 15:16:00
70111	JB2610-0107	CL-440	CL440-1005-04	900.000	KG	2026-10-05 08:11:00
70112	JB2610-0107	BT-M8	BTM8-1005-01	2000.000	EA	2026-10-05 13:11:00
70113	JB2610-0108	CL-440	CL440-1005-05	1800.000	KG	2026-10-05 15:25:00
70114	JB2610-0109	CL-440	CL440-1005-09	2400.000	KG	2026-10-05 21:26:00
70115	JB2610-0110	CL-440	CL440-1006-05	2250.000	KG	2026-10-06 01:25:00
70116	JB2610-0110	BT-M8	BTM8-1006-08	5000.000	EA	2026-10-06 15:02:00
70117	JB2610-0111	CL-440	CL440-1006-02	2100.000	KG	2026-10-06 12:43:00
70118	JB2610-0112	CL-440	CL440-1007-09	1200.000	KG	2026-10-07 11:41:00
70119	JB2610-0113	CL-440	CL440-1007-05	2400.000	KG	2026-10-07 13:46:00
70120	JB2610-0114	CL-440	CL440-1008-02	2100.000	KG	2026-10-08 07:33:00
70121	JB2610-0115	CL-590	CL590-1008-08	6300.000	KG	2026-10-08 20:26:00
70122	JB2610-0116	CL-590	CL590-1009-02	3500.000	KG	2026-10-09 10:09:00
70123	JB2610-0117	CL-440	CL440-1009-02	1800.000	KG	2026-10-09 13:16:00
70124	JB2610-0117	BT-M8	BTM8-1010-07	4000.000	EA	2026-10-10 01:12:00
70125	JB2610-0118	CL-590	CL590-1010-09	2100.000	KG	2026-10-10 04:06:00
70126	JB2610-0119	CL-590	CL590-1010-02	3500.000	KG	2026-10-10 09:10:00
70127	JB2610-0120	CL-440	CL440-1012-02	1500.000	KG	2026-10-12 07:38:00
70128	JB2610-0121	CL-440	CL440-1012-04	2700.000	KG	2026-10-12 23:38:00
70129	JB2610-0122	CL-440	CL440-1013-03	2700.000	KG	2026-10-13 04:15:00
70130	JB2610-0122	BT-M8	BTM8-1013-06	6000.000	EA	2026-10-13 22:25:00
70131	JB2610-0123	CL-590	CL590-1013-06	2100.000	KG	2026-10-13 10:09:00
70132	JB2610-0124	CL-590	CL590-1014-02	2800.000	KG	2026-10-14 10:31:00
70133	JB2610-0125	CL-440	CL440-1014-03	900.000	KG	2026-10-14 14:02:00
70134	JB2610-0126	CL-440	CL440-1015-05	2400.000	KG	2026-10-15 07:30:00
70135	JB2610-0127	CL-590	CL590-1015-02	1400.000	KG	2026-10-15 13:23:00
70136	JB2610-0128	CL-440	CL440-1015-08	2100.000	KG	2026-10-15 23:08:00
70137	JB2610-0129	CL-440	CL440-1016-06	600.000	KG	2026-10-16 11:12:00
70138	JB2610-0130	CL-440	CL440-1017-01	2400.000	KG	2026-10-17 07:30:00
70139	JB2610-0131	CL-440	CL440-1017-03	2400.000	KG	2026-10-17 11:37:00
70140	JB2610-0132	CL-440	CL440-1020-02	2700.000	KG	2026-10-20 03:26:00
70141	JB2610-0133	CL-440	CL440-1020-09	2700.000	KG	2026-10-20 10:53:00
70142	JB2610-0134	CL-440	CL440-1021-07	600.000	KG	2026-10-21 19:30:00
70143	JB2610-0135	CL-590	CL590-1022-08	4200.000	KG	2026-10-22 07:36:00
70144	JB2610-0136	CL-440	CL440-1022-08	1200.000	KG	2026-10-22 11:26:00
70145	JB2610-0137	CL-440	CL440-1023-01	900.000	KG	2026-10-23 03:37:00
70146	JB2610-0139	CL-440	CL440-1023-04	1800.000	KG	2026-10-23 13:23:00
70147	JB2610-0140	CL-440	CL440-1023-02	1500.000	KG	2026-10-23 16:25:00
70148	JB2610-0138	CL-440	CL440-1029-09	3600.000	KG	2026-10-29 07:30:00
70149	JB2610-0141	CL-440	CL440-1028-05	3000.000	KG	2026-10-28 07:30:00
70150	JB2610-0142	CL-440	CL440-1028-08	3600.000	KG	2026-10-28 12:27:00
\.


--
-- Data for Name: production_result; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.production_result (result_id, wo_no, op_seq, equipment_code, worker_id, shift, started_at, ended_at, good_qty, ng_qty) FROM stdin;
200001	JB2608-0101	10	B101	31	주간	2026-08-03 08:00:00	2026-08-03 09:58:00	1500	8
200002	JB2608-0101	20	P101	31	주간	2026-08-03 09:58:00	2026-08-03 14:25:00	1500	15
200003	JB2608-0101	30	P101	31	주간	2026-08-03 14:25:00	2026-08-03 17:00:00	1340	3
200004	JB2608-0101	30	P101	24	야간	2026-08-03 20:00:00	2026-08-03 20:19:00	160	1
200005	JB2608-0102	10	B101	31	주간	2026-08-03 09:58:00	2026-08-03 12:16:00	2000	11
200006	JB2608-0102	20	P103	6	주간	2026-08-03 12:16:00	2026-08-03 17:00:00	1720	4
200007	JB2608-0102	20	P103	57	야간	2026-08-03 20:00:00	2026-08-03 20:46:00	280	1
200008	JB2608-0102	30	P103	57	야간	2026-08-03 20:46:00	2026-08-04 00:45:00	2000	13
200009	JB2608-0103	10	B101	6	주간	2026-08-04 08:00:00	2026-08-04 12:22:00	3500	12
200010	JB2608-0103	20	P103	6	주간	2026-08-04 12:22:00	2026-08-04 17:00:00	1800	13
200011	JB2608-0103	20	P103	48	야간	2026-08-04 20:00:00	2026-08-05 00:24:00	1700	20
200012	JB2608-0103	30	P103	48	야간	2026-08-05 00:24:00	2026-08-05 05:00:00	2630	22
200013	JB2608-0103	30	P103	6	주간	2026-08-05 08:00:00	2026-08-05 09:31:00	870	5
200014	JB2608-0104	10	B101	45	주간	2026-08-04 12:22:00	2026-08-04 15:52:00	3500	21
200015	JB2608-0104	20	P201	44	주간	2026-08-04 15:52:00	2026-08-04 17:00:00	640	1
200016	JB2608-0104	20	P201	55	야간	2026-08-04 20:00:00	2026-08-05 01:05:00	2860	7
200017	JB2608-0104	30	P201	55	야간	2026-08-05 01:05:00	2026-08-05 05:00:00	2710	19
200018	JB2608-0104	30	P201	79	주간	2026-08-05 08:00:00	2026-08-05 09:08:00	790	2
200019	JB2608-0104	40	W201	44	주간	2026-08-05 09:08:00	2026-08-05 17:00:00	2670	12
200020	JB2608-0104	40	W201	55	야간	2026-08-05 20:00:00	2026-08-05 22:26:00	830	2
200021	JB2608-0105	10	B101	31	주간	2026-08-04 15:52:00	2026-08-04 17:00:00	920	4
200022	JB2608-0105	10	B101	57	야간	2026-08-04 20:00:00	2026-08-04 21:20:00	1080	12
200023	JB2608-0105	20	P101	24	야간	2026-08-04 21:20:00	2026-08-05 02:31:00	2000	17
200024	JB2608-0105	30	P101	57	야간	2026-08-05 02:31:00	2026-08-05 05:00:00	1370	14
200025	JB2608-0105	30	P101	31	주간	2026-08-05 10:50:00	2026-08-05 11:58:00	630	5
200026	JB2608-0106	10	B101	6	주간	2026-08-05 09:31:00	2026-08-05 14:13:00	4000	27
200027	JB2608-0106	20	P103	6	주간	2026-08-05 14:13:00	2026-08-05 17:00:00	1110	13
200028	JB2608-0106	20	P103	57	야간	2026-08-05 20:00:00	2026-08-06 03:17:00	2890	10
200029	JB2608-0106	30	P103	38	야간	2026-08-06 03:17:00	2026-08-06 05:00:00	900	10
200030	JB2608-0106	30	P103	72	주간	2026-08-06 08:00:00	2026-08-06 13:57:00	3100	11
200031	JB2608-0107	10	B101	38	야간	2026-08-05 23:58:00	2026-08-06 02:55:00	2500	6
200032	JB2608-0107	20	P101	57	야간	2026-08-06 02:55:00	2026-08-06 05:00:00	800	5
200033	JB2608-0107	20	P101	31	주간	2026-08-06 10:20:00	2026-08-06 14:44:00	1700	4
200034	JB2608-0107	30	P101	45	주간	2026-08-06 14:44:00	2026-08-06 17:00:00	1260	8
200035	JB2608-0107	30	P101	48	야간	2026-08-06 20:00:00	2026-08-06 22:13:00	1240	14
200036	JB2608-0108	10	B101	57	야간	2026-08-06 20:00:00	2026-08-06 21:27:00	1000	3
200037	JB2608-0108	20	P103	24	야간	2026-08-06 21:27:00	2026-08-07 00:45:00	1000	5
200038	JB2608-0108	30	P103	74	야간	2026-08-07 00:45:00	2026-08-07 03:01:00	1000	6
200039	JB2608-0109	10	B101	72	주간	2026-08-07 09:01:00	2026-08-07 10:12:00	1000	6
200040	JB2608-0109	20	P103	6	주간	2026-08-07 10:12:00	2026-08-07 12:47:00	1000	10
200041	JB2608-0109	30	P103	36	주간	2026-08-07 12:47:00	2026-08-07 15:07:00	1000	6
200042	JB2608-0110	10	B101	72	주간	2026-08-07 10:13:00	2026-08-07 11:24:00	1000	10
200043	JB2608-0110	20	P101	31	주간	2026-08-07 11:24:00	2026-08-07 14:13:00	1000	5
200044	JB2608-0110	30	P101	45	주간	2026-08-07 14:13:00	2026-08-07 16:24:00	1000	5
200045	JB2608-0111	10	B101	6	주간	2026-08-07 15:07:00	2026-08-07 17:00:00	1630	15
200046	JB2608-0111	10	B101	24	야간	2026-08-07 20:00:00	2026-08-07 21:35:00	1370	7
200047	JB2608-0111	20	P103	74	야간	2026-08-07 21:35:00	2026-08-08 05:00:00	2820	29
200048	JB2608-0111	20	P103	36	주간	2026-08-08 08:00:00	2026-08-08 08:28:00	180	2
200049	JB2608-0111	30	P103	6	주간	2026-08-08 08:28:00	2026-08-08 14:14:00	3000	9
200050	JB2608-0112	10	B101	74	야간	2026-08-07 21:35:00	2026-08-08 01:38:00	3500	38
200051	JB2608-0112	20	P101	57	야간	2026-08-08 01:38:00	2026-08-08 05:00:00	1360	15
200052	JB2608-0112	20	P101	31	주간	2026-08-08 08:00:00	2026-08-08 13:18:00	2140	8
200053	JB2608-0112	30	P101	72	주간	2026-08-08 13:18:00	2026-08-08 17:00:00	2230	5
200054	JB2608-0112	30	P101	72	주간	2026-08-10 08:00:00	2026-08-10 10:07:00	1270	14
200055	JB2608-0113	10	B101	31	주간	2026-08-08 14:14:00	2026-08-08 17:00:00	2150	5
200056	JB2608-0113	10	B101	31	주간	2026-08-10 08:00:00	2026-08-10 09:06:00	850	5
200057	JB2608-0113	20	P103	6	주간	2026-08-10 09:06:00	2026-08-10 16:46:00	3000	30
200058	JB2608-0113	30	P103	36	주간	2026-08-10 16:46:00	2026-08-10 17:00:00	140	1
200059	JB2608-0113	30	P103	57	야간	2026-08-10 20:00:00	2026-08-11 00:51:00	2860	31
200060	JB2608-0114	10	B101	72	주간	2026-08-10 10:07:00	2026-08-10 12:37:00	2000	14
200061	JB2608-0114	20	P101	45	주간	2026-08-10 12:37:00	2026-08-10 17:00:00	1540	11
200062	JB2608-0114	20	P101	57	야간	2026-08-10 20:00:00	2026-08-10 21:19:00	460	1
200063	JB2608-0114	30	P101	48	야간	2026-08-10 21:19:00	2026-08-11 00:54:00	2000	18
200064	JB2608-0115	10	B101	45	주간	2026-08-11 08:00:00	2026-08-11 09:17:00	1000	8
200065	JB2608-0115	20	P103	36	주간	2026-08-11 09:17:00	2026-08-11 12:26:00	1000	10
200066	JB2608-0115	30	P103	6	주간	2026-08-11 12:26:00	2026-08-11 14:30:00	1000	5
200067	JB2608-0116	10	B101	36	주간	2026-08-11 09:17:00	2026-08-11 13:37:00	3500	27
200068	JB2608-0116	20	P101	36	주간	2026-08-11 13:37:00	2026-08-11 17:00:00	1220	8
200069	JB2608-0116	20	P101	48	야간	2026-08-11 20:00:00	2026-08-12 02:17:00	2280	10
200070	JB2608-0116	30	P101	57	야간	2026-08-12 02:17:00	2026-08-12 05:00:00	1580	18
200071	JB2608-0116	30	P101	45	주간	2026-08-12 08:00:00	2026-08-12 11:18:00	1920	23
200072	JB2608-0117	10	B101	38	야간	2026-08-12 02:30:00	2026-08-12 03:47:00	1000	8
200073	JB2608-0117	20	P103	74	야간	2026-08-12 03:47:00	2026-08-12 05:00:00	380	4
200074	JB2608-0117	20	P103	31	주간	2026-08-12 08:00:00	2026-08-12 09:57:00	620	6
200075	JB2608-0117	30	P103	6	주간	2026-08-12 09:57:00	2026-08-12 12:05:00	1000	3
200076	JB2608-0118	10	B101	45	주간	2026-08-12 12:05:00	2026-08-12 14:15:00	2000	15
200077	JB2608-0118	20	P103	31	주간	2026-08-12 14:15:00	2026-08-12 17:00:00	1120	10
200078	JB2608-0118	20	P103	48	야간	2026-08-12 20:00:00	2026-08-12 22:10:00	880	2
200079	JB2608-0118	30	P103	24	야간	2026-08-12 22:10:00	2026-08-13 02:20:00	2000	23
200080	JB2608-0119	10	B101	74	야간	2026-08-12 20:00:00	2026-08-13 00:27:00	4500	34
200081	JB2608-0119	20	P101	74	야간	2026-08-13 00:27:00	2026-08-13 05:00:00	2140	24
200082	JB2608-0119	20	P101	45	주간	2026-08-13 08:00:00	2026-08-13 13:00:00	2360	7
200083	JB2608-0119	30	P101	36	주간	2026-08-13 13:00:00	2026-08-13 17:00:00	2070	13
200084	JB2608-0119	30	P101	24	야간	2026-08-13 20:00:00	2026-08-14 00:43:00	2430	21
200085	JB2608-0120	10	B101	57	야간	2026-08-13 02:20:00	2026-08-13 05:00:00	2460	7
200086	JB2608-0120	10	B101	72	주간	2026-08-13 08:00:00	2026-08-13 09:08:00	1040	5
200087	JB2608-0120	20	P103	6	주간	2026-08-13 09:08:00	2026-08-13 17:00:00	3500	10
200088	JB2608-0120	30	P103	74	야간	2026-08-13 20:00:00	2026-08-14 02:57:00	3500	36
200089	JB2608-0121	10	B101	57	야간	2026-08-14 00:43:00	2026-08-14 04:02:00	3000	33
200090	JB2608-0121	20	P101	48	야간	2026-08-14 04:02:00	2026-08-14 05:00:00	450	1
200091	JB2608-0121	20	P101	45	주간	2026-08-14 08:00:00	2026-08-14 13:26:00	2550	10
200092	JB2608-0121	30	P101	72	주간	2026-08-14 13:26:00	2026-08-14 17:00:00	1890	21
200093	JB2608-0121	30	P101	74	야간	2026-08-14 20:00:00	2026-08-14 22:05:00	1110	2
200094	JB2608-0122	10	B101	36	주간	2026-08-14 14:57:00	2026-08-14 17:00:00	1710	4
200095	JB2608-0122	10	B101	57	야간	2026-08-14 20:00:00	2026-08-14 20:21:00	290	2
200096	JB2608-0122	20	P103	48	야간	2026-08-14 20:21:00	2026-08-15 01:52:00	2000	20
200097	JB2608-0122	30	P103	74	야간	2026-08-15 01:52:00	2026-08-15 05:00:00	1740	18
200098	JB2608-0122	30	P103	6	주간	2026-08-15 08:00:00	2026-08-15 08:28:00	260	3
200099	JB2608-0123	10	B101	38	야간	2026-08-14 22:05:00	2026-08-15 00:07:00	1500	13
200100	JB2608-0123	20	P101	57	야간	2026-08-15 00:07:00	2026-08-15 04:24:00	1500	6
200101	JB2608-0123	30	P101	74	야간	2026-08-15 04:24:00	2026-08-15 05:00:00	290	2
200102	JB2608-0123	30	P101	31	주간	2026-08-15 08:00:00	2026-08-15 10:31:00	1210	10
200103	JB2608-0124	10	B101	31	주간	2026-08-15 14:28:00	2026-08-15 17:00:00	2350	15
200104	JB2608-0124	10	B101	31	주간	2026-08-17 08:00:00	2026-08-17 08:10:00	150	2
200105	JB2608-0124	20	P103	31	주간	2026-08-17 08:10:00	2026-08-17 13:51:00	2500	10
200106	JB2608-0124	30	P103	6	주간	2026-08-17 13:51:00	2026-08-17 17:00:00	1620	14
200107	JB2608-0124	30	P103	57	야간	2026-08-17 20:00:00	2026-08-17 21:42:00	880	4
200108	JB2608-0125	10	B101	31	주간	2026-08-17 08:10:00	2026-08-17 11:34:00	3000	13
200109	JB2608-0125	20	P101	72	주간	2026-08-17 11:34:00	2026-08-17 17:00:00	2320	11
200110	JB2608-0125	20	P101	74	야간	2026-08-17 20:00:00	2026-08-17 21:36:00	680	6
200111	JB2608-0125	30	P101	74	야간	2026-08-17 21:36:00	2026-08-18 03:54:00	3000	14
200112	JB2608-0126	10	B101	74	야간	2026-08-18 03:42:00	2026-08-18 05:00:00	1010	9
200113	JB2608-0126	10	B101	6	주간	2026-08-18 08:00:00	2026-08-18 09:55:00	1490	7
200114	JB2608-0126	20	P103	72	주간	2026-08-18 09:55:00	2026-08-18 17:00:00	2490	12
200115	JB2608-0126	20	P103	24	야간	2026-08-18 20:00:00	2026-08-18 20:01:00	10	0
200116	JB2608-0126	30	P103	24	야간	2026-08-18 20:01:00	2026-08-19 00:40:00	2500	17
200117	JB2608-0127	10	B101	36	주간	2026-08-18 09:55:00	2026-08-18 12:30:00	2000	7
200118	JB2608-0127	20	P101	31	주간	2026-08-18 12:30:00	2026-08-18 17:00:00	1680	19
200119	JB2608-0127	20	P101	74	야간	2026-08-18 20:00:00	2026-08-18 20:52:00	320	1
200120	JB2608-0127	30	P101	48	야간	2026-08-18 20:52:00	2026-08-19 00:25:00	2000	19
200121	JB2608-0128	10	B101	48	야간	2026-08-19 00:25:00	2026-08-19 02:56:00	2000	23
200122	JB2608-0128	20	P101	74	야간	2026-08-19 02:56:00	2026-08-19 05:00:00	790	6
200123	JB2608-0128	20	P101	72	주간	2026-08-19 08:00:00	2026-08-19 11:11:00	1210	14
200124	JB2608-0128	30	P101	72	주간	2026-08-19 11:11:00	2026-08-19 14:53:00	2000	10
200125	JB2608-0129	10	B101	72	주간	2026-08-19 08:00:00	2026-08-19 13:03:00	4000	23
200126	JB2608-0129	20	P103	31	주간	2026-08-19 13:03:00	2026-08-19 17:00:00	1580	11
200127	JB2608-0129	20	P103	24	야간	2026-08-19 20:00:00	2026-08-20 02:04:00	2420	8
200128	JB2608-0129	30	P103	74	야간	2026-08-20 02:04:00	2026-08-20 05:00:00	1760	8
200129	JB2608-0129	30	P103	45	주간	2026-08-20 08:00:00	2026-08-20 11:44:00	2240	25
200130	JB2608-0130	10	B101	72	주간	2026-08-19 13:03:00	2026-08-19 15:07:00	2000	18
200131	JB2608-0130	20	P201	40	주간	2026-08-19 15:07:00	2026-08-19 17:00:00	990	9
200132	JB2608-0130	20	P201	55	야간	2026-08-19 20:00:00	2026-08-19 21:56:00	1010	5
200133	JB2608-0130	30	P201	55	야간	2026-08-19 21:56:00	2026-08-20 00:56:00	2000	5
200134	JB2608-0130	40	W201	55	야간	2026-08-20 00:56:00	2026-08-20 05:00:00	1360	16
200135	JB2608-0130	40	W201	44	주간	2026-08-20 08:00:00	2026-08-20 09:54:00	640	2
200136	JB2608-0131	10	B101	48	야간	2026-08-20 02:53:00	2026-08-20 05:00:00	1660	18
200137	JB2608-0131	10	B101	31	주간	2026-08-20 08:00:00	2026-08-20 09:43:00	1340	3
200138	JB2608-0131	20	P101	36	주간	2026-08-20 09:43:00	2026-08-20 17:00:00	2910	11
200139	JB2608-0131	20	P101	74	야간	2026-08-20 20:00:00	2026-08-20 20:13:00	90	0
200140	JB2608-0131	30	P101	24	야간	2026-08-20 20:13:00	2026-08-21 01:32:00	3000	24
200141	JB2608-0132	10	B101	38	야간	2026-08-20 23:44:00	2026-08-21 04:51:00	4500	45
200142	JB2608-0132	20	P103	57	야간	2026-08-21 04:51:00	2026-08-21 05:00:00	60	1
200143	JB2608-0132	20	P103	72	주간	2026-08-21 08:00:00	2026-08-21 17:00:00	3790	39
200144	JB2608-0132	20	P103	74	야간	2026-08-21 20:00:00	2026-08-21 21:32:00	650	8
200145	JB2608-0132	30	P103	24	야간	2026-08-21 21:32:00	2026-08-22 05:00:00	4030	14
200146	JB2608-0132	30	P103	31	주간	2026-08-22 08:00:00	2026-08-22 08:52:00	470	5
200147	JB2608-0133	10	B101	57	야간	2026-08-21 04:51:00	2026-08-21 05:00:00	140	1
200148	JB2608-0133	10	B101	31	주간	2026-08-21 08:00:00	2026-08-21 11:01:00	2860	13
200149	JB2608-0133	20	P201	40	주간	2026-08-21 11:01:00	2026-08-21 16:05:00	3000	20
200150	JB2608-0133	30	P201	27	주간	2026-08-21 16:05:00	2026-08-21 17:00:00	620	7
200151	JB2608-0133	30	P201	55	야간	2026-08-21 20:00:00	2026-08-21 23:31:00	2380	11
200152	JB2608-0133	40	W201	55	야간	2026-08-21 23:31:00	2026-08-22 05:00:00	1920	21
200153	JB2608-0133	40	W201	52	주간	2026-08-22 08:00:00	2026-08-22 11:06:00	1080	13
200154	JB2608-0134	10	B101	6	주간	2026-08-21 11:01:00	2026-08-21 13:05:00	1500	5
200155	JB2608-0134	20	P101	6	주간	2026-08-21 13:05:00	2026-08-21 17:00:00	1320	9
200156	JB2608-0134	20	P101	57	야간	2026-08-21 20:00:00	2026-08-21 20:33:00	180	2
200157	JB2608-0134	30	P101	24	야간	2026-08-21 20:33:00	2026-08-21 23:24:00	1500	15
200158	JB2608-0135	10	B101	45	주간	2026-08-22 08:00:00	2026-08-22 10:30:00	2000	9
200159	JB2608-0135	20	P101	31	주간	2026-08-22 10:30:00	2026-08-22 16:13:00	2000	17
200160	JB2608-0135	30	P101	31	주간	2026-08-22 16:13:00	2026-08-22 17:00:00	400	4
200161	JB2608-0135	30	P101	6	주간	2026-08-24 08:00:00	2026-08-24 11:09:00	1600	17
200162	JB2608-0136	10	B101	31	주간	2026-08-22 10:30:00	2026-08-22 11:57:00	1000	5
200163	JB2608-0136	20	P103	31	주간	2026-08-22 11:57:00	2026-08-22 15:05:00	1000	9
200164	JB2608-0136	30	P103	31	주간	2026-08-22 15:05:00	2026-08-22 17:00:00	920	3
200165	JB2608-0136	30	P103	45	주간	2026-08-24 08:00:00	2026-08-24 08:10:00	80	0
200166	JB2608-0137	10	B101	45	주간	2026-08-24 08:10:00	2026-08-24 12:30:00	4000	9
200167	JB2608-0137	20	P103	31	주간	2026-08-24 12:30:00	2026-08-24 17:00:00	1660	17
200168	JB2608-0137	20	P103	48	야간	2026-08-24 20:00:00	2026-08-25 02:20:00	2340	17
200169	JB2608-0137	30	P103	48	야간	2026-08-25 02:20:00	2026-08-25 05:00:00	1610	10
200170	JB2608-0137	30	P103	72	주간	2026-08-25 08:00:00	2026-08-25 11:58:00	2390	27
200171	JB2608-0138	10	B101	38	야간	2026-08-24 23:09:00	2026-08-25 02:24:00	2500	17
200172	JB2608-0138	20	P101	74	야간	2026-08-25 02:24:00	2026-08-25 05:00:00	1060	12
200173	JB2608-0138	20	P101	36	주간	2026-08-25 08:00:00	2026-08-25 11:31:00	1440	6
200174	JB2608-0138	30	P101	45	주간	2026-08-25 11:31:00	2026-08-25 15:51:00	2500	6
200175	JB2608-0139	10	B101	38	야간	2026-08-25 21:51:00	2026-08-26 00:28:00	2500	15
200176	JB2608-0139	20	P101	57	야간	2026-08-26 00:28:00	2026-08-26 05:00:00	1940	21
200177	JB2608-0139	20	P101	31	주간	2026-08-26 08:00:00	2026-08-26 09:19:00	560	5
200178	JB2608-0139	30	P101	6	주간	2026-08-26 09:19:00	2026-08-26 14:02:00	2500	19
200179	JB2608-0140	10	B101	57	야간	2026-08-26 00:28:00	2026-08-26 01:45:00	1000	8
200180	JB2608-0140	20	P103	24	야간	2026-08-26 01:45:00	2026-08-26 04:43:00	1000	3
200181	JB2608-0140	30	P103	48	야간	2026-08-26 04:43:00	2026-08-26 05:00:00	130	1
200182	JB2608-0140	30	P103	36	주간	2026-08-26 08:00:00	2026-08-26 09:51:00	870	5
200183	JB2608-0141	10	B101	48	야간	2026-08-26 20:02:00	2026-08-27 00:09:00	3500	27
200184	JB2608-0141	20	P101	38	야간	2026-08-27 00:09:00	2026-08-27 05:00:00	1540	7
200185	JB2608-0141	20	P101	72	주간	2026-08-27 08:00:00	2026-08-27 13:11:00	1960	18
200186	JB2608-0141	30	P101	72	주간	2026-08-27 13:11:00	2026-08-27 17:00:00	2100	19
200187	JB2608-0141	30	P101	24	야간	2026-08-27 20:00:00	2026-08-27 22:32:00	1400	10
200188	JB2608-0142	10	B101	38	야간	2026-08-27 00:09:00	2026-08-27 03:59:00	3000	22
200189	JB2608-0142	20	P103	48	야간	2026-08-27 03:59:00	2026-08-27 05:00:00	400	2
200190	JB2608-0142	20	P103	45	주간	2026-08-27 08:00:00	2026-08-27 14:41:00	2600	24
200191	JB2608-0142	30	P103	6	주간	2026-08-27 14:41:00	2026-08-27 17:00:00	1270	3
200192	JB2608-0142	30	P103	74	야간	2026-08-27 20:00:00	2026-08-27 23:10:00	1730	4
200193	JB2608-0143	10	B101	48	야간	2026-08-27 03:59:00	2026-08-27 05:00:00	870	3
200194	JB2608-0143	10	B101	45	주간	2026-08-27 08:00:00	2026-08-27 08:09:00	130	1
200195	JB2608-0143	20	P201	40	주간	2026-08-27 08:09:00	2026-08-27 10:11:00	1000	6
200196	JB2608-0143	30	P201	27	주간	2026-08-27 10:11:00	2026-08-27 11:54:00	1000	5
200197	JB2608-0143	40	W201	44	주간	2026-08-27 11:54:00	2026-08-27 15:03:00	1000	6
200198	JB2608-0144	10	B101	48	야간	2026-08-28 04:32:00	2026-08-28 05:00:00	400	4
200199	JB2608-0144	10	B101	31	주간	2026-08-28 08:00:00	2026-08-28 09:53:00	1600	4
200200	JB2608-0144	20	P101	45	주간	2026-08-28 09:53:00	2026-08-28 15:41:00	2000	7
200201	JB2608-0144	30	P101	6	주간	2026-08-28 15:41:00	2026-08-28 17:00:00	710	8
200202	JB2608-0144	30	P101	48	야간	2026-08-28 20:00:00	2026-08-28 22:25:00	1290	4
200203	JB2608-0145	10	B101	36	주간	2026-08-28 09:53:00	2026-08-28 11:17:00	1000	11
200204	JB2608-0145	20	P103	31	주간	2026-08-28 11:17:00	2026-08-28 14:35:00	1000	6
200205	JB2608-0145	30	P103	31	주간	2026-08-28 14:35:00	2026-08-28 16:38:00	1000	4
200206	JB2608-0146	10	B101	24	야간	2026-08-28 22:25:00	2026-08-29 03:07:00	4500	51
200207	JB2608-0146	20	P101	48	야간	2026-08-29 03:07:00	2026-08-29 05:00:00	860	5
200208	JB2608-0146	20	P101	6	주간	2026-08-29 08:00:00	2026-08-29 16:01:00	3640	14
200209	JB2608-0146	30	P101	31	주간	2026-08-29 16:01:00	2026-08-29 17:00:00	500	5
200210	JB2608-0146	30	P101	72	주간	2026-08-31 08:00:00	2026-08-31 15:52:00	4000	25
200211	JB2608-0147	10	B101	24	야간	2026-08-29 04:38:00	2026-08-29 05:00:00	340	2
200212	JB2608-0147	10	B101	6	주간	2026-08-29 08:00:00	2026-08-29 09:48:00	1660	20
200213	JB2608-0147	20	P103	72	주간	2026-08-29 09:48:00	2026-08-29 14:12:00	2000	6
200214	JB2608-0147	30	P103	36	주간	2026-08-29 14:12:00	2026-08-29 17:00:00	1280	8
200215	JB2608-0147	30	P103	6	주간	2026-08-31 09:00:00	2026-08-31 10:35:00	720	8
200216	JB2608-0148	10	B101	36	주간	2026-08-31 15:52:00	2026-08-31 17:00:00	970	5
200217	JB2608-0148	10	B101	24	야간	2026-08-31 20:00:00	2026-08-31 20:37:00	530	2
200218	JB2608-0148	20	P101	24	야간	2026-08-31 20:37:00	2026-09-01 00:07:00	1500	11
200219	JB2608-0148	30	P101	57	야간	2026-09-01 00:07:00	2026-09-01 03:41:00	1500	13
200220	JB2608-0149	10	B101	24	야간	2026-08-31 22:35:00	2026-08-31 23:46:00	1000	10
200221	JB2608-0149	20	P103	57	야간	2026-08-31 23:46:00	2026-09-01 02:29:00	1000	10
200222	JB2608-0149	30	P103	48	야간	2026-09-01 02:29:00	2026-09-01 04:58:00	1000	6
200223	JB2608-0150	10	B101	48	야간	2026-08-31 23:46:00	2026-09-01 02:53:00	3000	26
200224	JB2608-0150	20	P201	55	야간	2026-09-01 02:53:00	2026-09-01 05:00:00	1220	8
200225	JB2608-0150	20	P201	79	주간	2026-09-01 08:00:00	2026-09-01 11:06:00	1780	21
200226	JB2608-0150	30	P201	44	주간	2026-09-01 11:06:00	2026-09-01 15:46:00	3000	15
200227	JB2608-0150	40	W201	79	주간	2026-09-01 15:46:00	2026-09-01 17:00:00	430	1
200228	JB2608-0150	40	W201	55	야간	2026-09-01 20:00:00	2026-09-02 03:18:00	2570	29
200229	JB2609-0101	10	B101	38	야간	2026-09-01 04:58:00	2026-09-01 05:00:00	30	0
200230	JB2609-0101	10	B101	31	주간	2026-09-01 08:00:00	2026-09-01 11:30:00	3470	11
200231	JB2609-0101	20	P103	36	주간	2026-09-01 11:30:00	2026-09-01 17:00:00	2420	24
200232	JB2609-0101	20	P103	74	야간	2026-09-01 20:00:00	2026-09-01 22:28:00	1080	7
200233	JB2609-0101	30	P103	74	야간	2026-09-01 22:28:00	2026-09-02 04:46:00	3500	33
200234	JB2609-0102	10	B101	31	주간	2026-09-01 11:30:00	2026-09-01 13:15:00	1500	16
200235	JB2609-0102	20	P101	72	주간	2026-09-01 13:15:00	2026-09-01 17:00:00	1440	7
200236	JB2609-0102	20	P101	57	야간	2026-09-01 20:00:00	2026-09-01 20:09:00	60	0
200237	JB2609-0102	30	P101	57	야간	2026-09-01 20:09:00	2026-09-01 23:17:00	1500	12
200238	JB2609-0103	10	B101	45	주간	2026-09-02 11:17:00	2026-09-02 14:45:00	3000	30
200239	JB2609-0103	20	P101	72	주간	2026-09-02 14:45:00	2026-09-02 17:00:00	970	5
200240	JB2609-0103	20	P101	38	야간	2026-09-02 20:00:00	2026-09-03 00:44:00	2030	12
200241	JB2609-0103	30	P101	38	야간	2026-09-03 00:44:00	2026-09-03 05:00:00	2330	23
200242	JB2609-0103	30	P101	72	주간	2026-09-03 08:00:00	2026-09-03 09:13:00	670	7
200243	JB2609-0104	10	B101	45	주간	2026-09-02 16:46:00	2026-09-02 17:00:00	200	1
200244	JB2609-0104	10	B101	74	야간	2026-09-02 20:00:00	2026-09-02 22:03:00	1800	12
200245	JB2609-0104	20	P103	38	야간	2026-09-02 22:03:00	2026-09-03 02:36:00	2000	11
200246	JB2609-0104	30	P103	24	야간	2026-09-03 02:36:00	2026-09-03 05:00:00	1100	5
200247	JB2609-0104	30	P103	72	주간	2026-09-03 08:00:00	2026-09-03 09:57:00	900	9
200248	JB2609-0105	10	B101	48	야간	2026-09-03 21:13:00	2026-09-04 00:08:00	2500	8
200249	JB2609-0105	20	P101	74	야간	2026-09-04 00:08:00	2026-09-04 05:00:00	2000	16
200250	JB2609-0105	20	P101	36	주간	2026-09-04 08:00:00	2026-09-04 09:13:00	500	5
200251	JB2609-0105	30	P101	31	주간	2026-09-04 09:13:00	2026-09-04 14:24:00	2500	17
200252	JB2609-0106	10	B101	48	야간	2026-09-04 00:08:00	2026-09-04 02:54:00	2500	28
200253	JB2609-0106	20	P103	74	야간	2026-09-04 02:54:00	2026-09-04 05:00:00	970	7
200254	JB2609-0106	20	P103	31	주간	2026-09-04 08:00:00	2026-09-04 11:20:00	1530	14
200255	JB2609-0106	30	P103	45	주간	2026-09-04 11:20:00	2026-09-04 16:00:00	2500	25
200256	JB2609-0107	10	B101	24	야간	2026-09-04 22:00:00	2026-09-05 02:22:00	4500	41
200257	JB2609-0107	20	P103	24	야간	2026-09-05 02:22:00	2026-09-05 05:00:00	1320	10
200258	JB2609-0107	20	P103	72	주간	2026-09-05 08:00:00	2026-09-05 14:20:00	3180	19
200259	JB2609-0107	30	P103	36	주간	2026-09-05 14:20:00	2026-09-05 17:00:00	1510	17
200260	JB2609-0107	30	P103	45	주간	2026-09-07 08:00:00	2026-09-07 13:17:00	2990	9
200261	JB2609-0108	10	B101	74	야간	2026-09-05 02:24:00	2026-09-05 05:00:00	2420	28
200262	JB2609-0108	10	B101	6	주간	2026-09-05 08:00:00	2026-09-05 09:10:00	1080	4
200263	JB2609-0108	20	P101	72	주간	2026-09-05 09:10:00	2026-09-05 17:00:00	3330	39
200264	JB2609-0108	20	P101	45	주간	2026-09-07 08:00:00	2026-09-07 08:24:00	170	1
200265	JB2609-0108	30	P101	36	주간	2026-09-07 08:24:00	2026-09-07 14:44:00	3500	7
200266	JB2609-0109	10	B101	24	야간	2026-09-07 20:44:00	2026-09-08 01:38:00	4000	23
200267	JB2609-0109	20	P101	24	야간	2026-09-08 01:38:00	2026-09-08 05:00:00	1300	9
200268	JB2609-0109	20	P101	6	주간	2026-09-08 08:00:00	2026-09-08 14:58:00	2700	22
200269	JB2609-0109	30	P101	6	주간	2026-09-08 14:58:00	2026-09-08 17:00:00	1200	10
200270	JB2609-0109	30	P101	38	야간	2026-09-08 20:00:00	2026-09-09 00:44:00	2800	17
200271	JB2609-0110	10	B101	48	야간	2026-09-08 01:38:00	2026-09-08 03:28:00	1500	10
200272	JB2609-0110	20	P103	74	야간	2026-09-08 03:28:00	2026-09-08 05:00:00	500	3
200273	JB2609-0110	20	P103	6	주간	2026-09-08 08:00:00	2026-09-08 11:04:00	1000	5
200274	JB2609-0110	30	P103	36	주간	2026-09-08 11:04:00	2026-09-08 14:16:00	1500	7
200275	JB2609-0111	10	B101	38	야간	2026-09-09 02:16:00	2026-09-09 04:39:00	2000	18
200276	JB2609-0111	20	P103	57	야간	2026-09-09 04:39:00	2026-09-09 05:00:00	150	0
200277	JB2609-0111	20	P103	31	주간	2026-09-09 08:00:00	2026-09-09 12:20:00	1850	6
200278	JB2609-0111	30	P103	31	주간	2026-09-09 12:20:00	2026-09-09 16:31:00	2000	16
200279	JB2609-0112	10	B101	36	주간	2026-09-09 08:00:00	2026-09-09 10:16:00	2000	15
200280	JB2609-0112	20	P101	31	주간	2026-09-09 10:16:00	2026-09-09 15:16:00	2000	15
200281	JB2609-0112	30	P101	31	주간	2026-09-09 15:16:00	2026-09-09 17:00:00	800	5
200282	JB2609-0112	30	P101	57	야간	2026-09-09 20:00:00	2026-09-09 22:35:00	1200	6
200283	JB2609-0113	10	B101	6	주간	2026-09-09 16:31:00	2026-09-09 17:00:00	490	5
200284	JB2609-0113	10	B101	24	야간	2026-09-09 20:00:00	2026-09-09 22:59:00	3010	31
200285	JB2609-0113	20	P103	38	야간	2026-09-09 22:59:00	2026-09-10 05:00:00	2990	7
200286	JB2609-0113	20	P103	36	주간	2026-09-10 08:00:00	2026-09-10 09:02:00	510	6
200287	JB2609-0113	30	P103	72	주간	2026-09-10 09:02:00	2026-09-10 16:22:00	3500	38
200288	JB2609-0114	10	B101	38	야간	2026-09-10 04:35:00	2026-09-10 05:00:00	410	1
200289	JB2609-0114	10	B101	6	주간	2026-09-10 09:00:00	2026-09-10 12:10:00	3090	13
200290	JB2609-0114	20	P101	36	주간	2026-09-10 13:20:00	2026-09-10 17:00:00	1740	4
200291	JB2609-0114	20	P101	57	야간	2026-09-10 20:00:00	2026-09-10 23:43:00	1760	7
200292	JB2609-0114	30	P101	24	야간	2026-09-10 23:43:00	2026-09-11 05:00:00	2600	16
200293	JB2609-0114	30	P101	72	주간	2026-09-11 08:00:00	2026-09-11 09:50:00	900	10
200294	JB2609-0115	10	B101	74	야간	2026-09-10 22:22:00	2026-09-11 02:50:00	4500	25
200295	JB2609-0115	20	P103	24	야간	2026-09-11 02:50:00	2026-09-11 05:00:00	1060	10
200296	JB2609-0115	20	P103	31	주간	2026-09-11 08:00:00	2026-09-11 15:02:00	3440	23
200297	JB2609-0115	30	P103	36	주간	2026-09-11 15:02:00	2026-09-11 17:00:00	990	6
200298	JB2609-0115	30	P103	38	야간	2026-09-11 20:00:00	2026-09-12 02:57:00	3510	39
200299	JB2609-0116	10	B101	31	주간	2026-09-11 09:50:00	2026-09-11 12:44:00	2500	22
200300	JB2609-0116	20	P101	72	주간	2026-09-11 12:44:00	2026-09-11 17:00:00	1920	5
200301	JB2609-0116	20	P101	48	야간	2026-09-11 20:00:00	2026-09-11 21:18:00	580	3
200302	JB2609-0116	30	P101	57	야간	2026-09-11 21:18:00	2026-09-12 02:35:00	2500	13
200303	JB2609-0117	10	B101	45	주간	2026-09-12 08:35:00	2026-09-12 11:25:00	2500	20
200304	JB2609-0117	20	P101	72	주간	2026-09-12 11:25:00	2026-09-12 17:00:00	1970	18
200305	JB2609-0117	20	P101	72	주간	2026-09-14 08:00:00	2026-09-14 09:31:00	530	2
200306	JB2609-0117	30	P101	31	주간	2026-09-14 09:31:00	2026-09-14 14:11:00	2500	23
200307	JB2609-0118	10	B101	45	주간	2026-09-12 11:25:00	2026-09-12 15:20:00	3500	12
200308	JB2609-0118	20	P103	6	주간	2026-09-12 15:20:00	2026-09-12 17:00:00	630	7
200309	JB2609-0118	20	P103	45	주간	2026-09-14 08:00:00	2026-09-14 15:39:00	2870	14
200310	JB2609-0118	30	P103	31	주간	2026-09-14 15:39:00	2026-09-14 17:00:00	790	8
200311	JB2609-0118	30	P103	57	야간	2026-09-14 20:00:00	2026-09-15 00:36:00	2710	26
200312	JB2609-0119	10	B101	31	주간	2026-09-14 14:11:00	2026-09-14 17:00:00	2330	16
200313	JB2609-0119	10	B101	38	야간	2026-09-14 20:00:00	2026-09-14 22:37:00	2170	13
200314	JB2609-0119	20	P101	57	야간	2026-09-14 22:37:00	2026-09-15 05:00:00	2530	16
200315	JB2609-0119	20	P101	72	주간	2026-09-15 08:00:00	2026-09-15 12:59:00	1970	4
200316	JB2609-0119	30	P101	31	주간	2026-09-15 12:59:00	2026-09-15 17:00:00	2210	12
200317	JB2609-0119	30	P101	24	야간	2026-09-15 20:00:00	2026-09-16 00:10:00	2290	5
200318	JB2609-0120	10	B101	24	야간	2026-09-15 00:36:00	2026-09-15 05:00:00	3510	11
200319	JB2609-0120	10	B101	45	주간	2026-09-15 08:00:00	2026-09-15 09:14:00	990	11
200320	JB2609-0120	20	P103	45	주간	2026-09-15 09:14:00	2026-09-15 17:00:00	2900	27
200321	JB2609-0120	20	P103	38	야간	2026-09-15 20:00:00	2026-09-16 00:18:00	1600	7
200322	JB2609-0120	30	P103	57	야간	2026-09-16 00:18:00	2026-09-16 05:00:00	2470	6
200323	JB2609-0120	30	P103	72	주간	2026-09-16 08:00:00	2026-09-16 11:52:00	2030	22
200324	JB2609-0121	10	B101	38	야간	2026-09-16 00:10:00	2026-09-16 05:00:00	4420	45
200325	JB2609-0121	10	B101	31	주간	2026-09-16 08:00:00	2026-09-16 08:05:00	80	0
200326	JB2609-0121	20	P101	72	주간	2026-09-16 08:12:00	2026-09-16 17:00:00	3920	20
200327	JB2609-0121	20	P101	38	야간	2026-09-16 20:00:00	2026-09-16 21:18:00	580	6
200328	JB2609-0121	30	P101	48	야간	2026-09-16 21:18:00	2026-09-17 05:00:00	4170	49
200329	JB2609-0121	30	P101	45	주간	2026-09-17 08:00:00	2026-09-17 08:36:00	330	3
200330	JB2609-0122	10	B101	24	야간	2026-09-16 23:52:00	2026-09-17 01:19:00	1000	5
200331	JB2609-0122	20	P103	38	야간	2026-09-17 01:19:00	2026-09-17 04:29:00	1000	5
200332	JB2609-0122	30	P103	74	야간	2026-09-17 04:29:00	2026-09-17 05:00:00	240	1
200333	JB2609-0122	30	P103	6	주간	2026-09-17 08:00:00	2026-09-17 09:39:00	760	8
200334	JB2609-0123	10	B101	57	야간	2026-09-17 20:36:00	2026-09-18 01:42:00	4500	49
200335	JB2609-0123	20	P101	57	야간	2026-09-18 01:42:00	2026-09-18 05:00:00	1330	10
200336	JB2609-0123	20	P101	36	주간	2026-09-18 08:00:00	2026-09-18 15:52:00	3170	35
200337	JB2609-0123	30	P101	36	주간	2026-09-18 15:52:00	2026-09-18 17:00:00	700	6
200338	JB2609-0123	30	P101	24	야간	2026-09-18 20:00:00	2026-09-19 02:12:00	3800	12
200339	JB2609-0124	10	B101	48	야간	2026-09-18 01:42:00	2026-09-18 04:05:00	2000	12
200340	JB2609-0124	20	P103	24	야간	2026-09-18 04:05:00	2026-09-18 05:00:00	350	1
200341	JB2609-0124	20	P103	72	주간	2026-09-18 08:00:00	2026-09-18 12:22:00	1650	7
200342	JB2609-0124	30	P103	36	주간	2026-09-18 12:22:00	2026-09-18 15:56:00	2000	21
200343	JB2609-0125	10	B101	38	야간	2026-09-18 04:05:00	2026-09-18 05:00:00	960	11
200344	JB2609-0125	10	B101	72	주간	2026-09-18 08:00:00	2026-09-18 10:26:00	2540	11
200345	JB2609-0125	20	P201	52	주간	2026-09-18 10:26:00	2026-09-18 15:52:00	3500	41
200346	JB2609-0125	30	P201	52	주간	2026-09-18 15:52:00	2026-09-18 17:00:00	780	8
200347	JB2609-0125	30	P201	55	야간	2026-09-18 20:00:00	2026-09-18 23:56:00	2720	32
200348	JB2609-0125	40	W201	55	야간	2026-09-18 23:56:00	2026-09-19 05:00:00	1760	6
200349	JB2609-0125	40	W201	40	주간	2026-09-19 08:00:00	2026-09-19 12:59:00	1740	20
200350	JB2609-0126	10	B101	6	주간	2026-09-18 15:56:00	2026-09-18 17:00:00	950	2
200351	JB2609-0126	10	B101	38	야간	2026-09-18 20:00:00	2026-09-18 21:11:00	1050	3
200352	JB2609-0126	20	P103	24	야간	2026-09-18 21:11:00	2026-09-19 02:00:00	2000	19
200353	JB2609-0126	30	P103	48	야간	2026-09-19 02:00:00	2026-09-19 05:00:00	1530	9
200354	JB2609-0126	30	P103	6	주간	2026-09-19 08:00:00	2026-09-19 08:55:00	470	4
200355	JB2609-0127	10	B101	72	주간	2026-09-19 14:12:00	2026-09-19 17:00:00	2550	23
200356	JB2609-0127	10	B101	36	주간	2026-09-21 08:00:00	2026-09-21 10:08:00	1950	15
200357	JB2609-0127	20	P101	72	주간	2026-09-21 10:08:00	2026-09-21 17:00:00	3020	13
200358	JB2609-0127	20	P101	48	야간	2026-09-21 20:00:00	2026-09-21 23:21:00	1480	17
200359	JB2609-0127	30	P101	57	야간	2026-09-21 23:21:00	2026-09-22 05:00:00	2800	21
200360	JB2609-0127	30	P101	36	주간	2026-09-22 08:00:00	2026-09-22 11:26:00	1700	9
200361	JB2609-0128	10	B101	6	주간	2026-09-21 10:08:00	2026-09-21 14:39:00	4500	18
200362	JB2609-0128	20	P103	6	주간	2026-09-21 14:39:00	2026-09-21 17:00:00	1100	9
200363	JB2609-0128	20	P103	74	야간	2026-09-21 20:00:00	2026-09-22 03:16:00	3400	40
200364	JB2609-0128	30	P103	74	야간	2026-09-22 03:16:00	2026-09-22 05:00:00	940	8
200365	JB2609-0128	30	P103	72	주간	2026-09-22 08:00:00	2026-09-22 14:35:00	3560	35
200366	JB2609-0129	10	B101	45	주간	2026-09-21 14:39:00	2026-09-21 17:00:00	2350	19
200367	JB2609-0129	10	B101	48	야간	2026-09-21 20:00:00	2026-09-21 20:09:00	150	1
200368	JB2609-0129	20	P201	55	야간	2026-09-21 20:09:00	2026-09-22 00:26:00	2500	10
200369	JB2609-0129	30	P201	55	야간	2026-09-22 00:26:00	2026-09-22 04:24:00	2500	29
200370	JB2609-0129	40	W201	55	야간	2026-09-22 04:24:00	2026-09-22 05:00:00	230	2
200371	JB2609-0129	40	W201	44	주간	2026-09-22 08:00:00	2026-09-22 13:53:00	2270	16
200372	JB2609-0130	10	B101	36	주간	2026-09-22 14:35:00	2026-09-22 17:00:00	2510	29
200373	JB2609-0130	10	B101	24	야간	2026-09-22 20:00:00	2026-09-22 21:55:00	1990	19
200374	JB2609-0130	20	P103	74	야간	2026-09-22 21:55:00	2026-09-23 05:00:00	3020	10
200375	JB2609-0130	20	P103	6	주간	2026-09-23 08:00:00	2026-09-23 11:12:00	1480	13
200376	JB2609-0130	30	P103	72	주간	2026-09-23 11:12:00	2026-09-23 17:00:00	2970	25
200377	JB2609-0130	30	P103	24	야간	2026-09-23 20:00:00	2026-09-23 22:59:00	1530	4
200378	JB2609-0131	10	B101	48	야간	2026-09-22 21:55:00	2026-09-23 00:48:00	2500	28
200379	JB2609-0131	20	P101	38	야간	2026-09-23 00:48:00	2026-09-23 05:00:00	1960	18
200380	JB2609-0131	20	P101	36	주간	2026-09-23 08:00:00	2026-09-23 09:10:00	540	3
200381	JB2609-0131	30	P101	45	주간	2026-09-23 09:10:00	2026-09-23 14:34:00	2500	12
200382	JB2609-0132	10	B101	57	야간	2026-09-23 00:48:00	2026-09-23 02:29:00	1500	15
200383	JB2609-0132	20	P201	55	야간	2026-09-23 02:29:00	2026-09-23 05:00:00	1280	6
200384	JB2609-0132	20	P201	52	주간	2026-09-23 08:00:00	2026-09-23 08:26:00	220	1
200385	JB2609-0132	30	P201	79	주간	2026-09-23 08:26:00	2026-09-23 10:57:00	1500	16
200386	JB2609-0132	40	W201	52	주간	2026-09-23 10:57:00	2026-09-23 15:13:00	1500	11
200387	JB2609-0133	10	B101	45	주간	2026-09-23 14:34:00	2026-09-23 17:00:00	1930	19
200388	JB2609-0133	10	B101	57	야간	2026-09-23 20:00:00	2026-09-23 20:43:00	570	7
200389	JB2609-0133	20	P101	48	야간	2026-09-23 20:43:00	2026-09-24 03:28:00	2500	14
200390	JB2609-0133	30	P101	24	야간	2026-09-24 03:28:00	2026-09-24 05:00:00	880	2
200391	JB2609-0133	30	P101	45	주간	2026-09-24 08:00:00	2026-09-24 10:50:00	1620	14
200392	JB2609-0134	10	B101	38	야간	2026-09-24 04:59:00	2026-09-24 05:00:00	10	0
200393	JB2609-0134	10	B101	45	주간	2026-09-24 08:00:00	2026-09-24 12:53:00	3990	44
200394	JB2609-0134	20	P103	6	주간	2026-09-24 12:53:00	2026-09-24 17:00:00	1650	4
200395	JB2609-0134	20	P103	74	야간	2026-09-24 20:00:00	2026-09-25 01:50:00	2350	7
200396	JB2609-0134	30	P103	38	야간	2026-09-25 01:50:00	2026-09-25 05:00:00	1750	18
200397	JB2609-0134	30	P103	31	주간	2026-09-25 08:00:00	2026-09-25 12:04:00	2250	12
200398	JB2609-0135	10	B101	36	주간	2026-09-24 12:53:00	2026-09-24 15:42:00	2500	9
200399	JB2609-0135	20	P101	72	주간	2026-09-24 15:42:00	2026-09-24 17:00:00	600	3
200400	JB2609-0135	20	P101	38	야간	2026-09-24 20:00:00	2026-09-25 00:09:00	1900	7
200401	JB2609-0135	30	P101	74	야간	2026-09-25 00:09:00	2026-09-25 05:00:00	2390	9
200402	JB2609-0135	30	P101	36	주간	2026-09-25 08:00:00	2026-09-25 08:14:00	110	1
200403	JB2609-0136	10	B101	6	주간	2026-09-25 08:14:00	2026-09-25 09:52:00	1500	9
200404	JB2609-0136	20	P101	45	주간	2026-09-25 09:52:00	2026-09-25 13:21:00	1500	5
200405	JB2609-0136	30	P101	45	주간	2026-09-25 13:21:00	2026-09-25 16:31:00	1500	7
200406	JB2609-0137	10	B101	36	주간	2026-09-25 09:52:00	2026-09-25 11:53:00	2000	18
200407	JB2609-0137	20	P201	27	주간	2026-09-25 11:53:00	2026-09-25 15:30:00	2000	19
200408	JB2609-0137	30	P201	40	주간	2026-09-25 15:30:00	2026-09-25 17:00:00	970	3
200409	JB2609-0137	30	P201	55	야간	2026-09-25 20:00:00	2026-09-25 21:36:00	1030	7
200410	JB2609-0137	40	W201	55	야간	2026-09-25 21:36:00	2026-09-26 03:23:00	2000	8
200411	JB2609-0138	10	B101	48	야간	2026-09-25 22:31:00	2026-09-26 01:23:00	2500	24
200412	JB2609-0138	20	P101	57	야간	2026-09-26 01:23:00	2026-09-26 05:00:00	1610	16
200413	JB2609-0138	20	P101	72	주간	2026-09-26 08:00:00	2026-09-26 10:00:00	890	9
200414	JB2609-0138	30	P101	72	주간	2026-09-26 10:00:00	2026-09-26 15:15:00	2500	23
200415	JB2609-0139	10	B101	57	야간	2026-09-26 01:23:00	2026-09-26 02:41:00	1000	3
200416	JB2609-0139	20	P103	38	야간	2026-09-26 02:41:00	2026-09-26 05:00:00	930	10
200417	JB2609-0139	20	P103	6	주간	2026-09-26 08:00:00	2026-09-26 08:10:00	70	0
200418	JB2609-0139	30	P103	31	주간	2026-09-26 08:10:00	2026-09-26 10:34:00	1000	7
200419	JB2609-0140	10	B101	31	주간	2026-09-28 08:00:00	2026-09-28 12:27:00	3500	19
200420	JB2609-0140	20	P103	72	주간	2026-09-28 12:27:00	2026-09-28 17:00:00	1860	13
200421	JB2609-0140	20	P103	48	야간	2026-09-28 20:00:00	2026-09-29 00:01:00	1640	16
200422	JB2609-0140	30	P103	24	야간	2026-09-29 00:01:00	2026-09-29 05:00:00	2630	12
200423	JB2609-0140	30	P103	31	주간	2026-09-29 08:00:00	2026-09-29 09:39:00	870	9
200424	JB2609-0141	10	B101	72	주간	2026-09-28 12:27:00	2026-09-28 13:46:00	1000	3
200425	JB2609-0141	20	P101	72	주간	2026-09-28 13:46:00	2026-09-28 16:49:00	1000	8
200426	JB2609-0141	30	P101	72	주간	2026-09-28 16:49:00	2026-09-28 17:00:00	90	0
200427	JB2609-0141	30	P101	24	야간	2026-09-28 20:00:00	2026-09-28 21:56:00	910	7
200428	JB2609-0142	10	B101	38	야간	2026-09-28 21:56:00	2026-09-29 01:22:00	3000	26
200429	JB2609-0142	20	P101	74	야간	2026-09-29 01:22:00	2026-09-29 05:00:00	1730	4
200430	JB2609-0142	20	P101	45	주간	2026-09-29 08:00:00	2026-09-29 10:39:00	1270	8
200431	JB2609-0142	30	P101	6	주간	2026-09-29 10:39:00	2026-09-29 16:24:00	3000	20
200432	JB2609-0143	10	B101	36	주간	2026-09-29 15:39:00	2026-09-29 17:00:00	1260	14
200433	JB2609-0143	10	B101	74	야간	2026-09-29 20:00:00	2026-09-29 23:28:00	3240	29
200434	JB2609-0143	20	P103	24	야간	2026-09-29 23:28:00	2026-09-30 05:00:00	2580	22
200435	JB2609-0143	20	P103	72	주간	2026-09-30 08:00:00	2026-09-30 12:06:00	1920	9
200436	JB2609-0143	30	P103	36	주간	2026-09-30 12:06:00	2026-09-30 17:00:00	2740	10
200437	JB2609-0143	30	P103	48	야간	2026-09-30 20:00:00	2026-09-30 23:08:00	1760	13
200438	JB2609-0144	10	B101	48	야간	2026-09-30 04:24:00	2026-09-30 05:00:00	570	2
200439	JB2609-0144	10	B101	72	주간	2026-09-30 08:00:00	2026-09-30 11:05:00	2930	9
200440	JB2609-0144	20	P101	6	주간	2026-09-30 11:05:00	2026-09-30 17:00:00	2880	11
200441	JB2609-0144	20	P101	48	야간	2026-09-30 20:00:00	2026-09-30 21:16:00	620	3
200442	JB2609-0144	30	P101	74	야간	2026-09-30 21:16:00	2026-10-01 03:59:00	3500	23
200443	JB2609-0145	10	B101	24	야간	2026-09-30 23:08:00	2026-10-01 00:48:00	1500	5
200444	JB2609-0145	20	P103	38	야간	2026-10-01 01:19:00	2026-10-01 05:00:00	1420	11
200445	JB2609-0145	20	P103	36	주간	2026-10-01 08:00:00	2026-10-01 08:12:00	80	1
200446	JB2609-0145	30	P103	45	주간	2026-10-01 08:12:00	2026-10-01 11:27:00	1500	16
200447	JB2610-0101	10	B101	31	주간	2026-10-01 09:59:00	2026-10-01 12:17:00	2000	12
200448	JB2610-0101	20	P101	31	주간	2026-10-01 12:17:00	2026-10-01 17:00:00	1910	5
200449	JB2610-0101	20	P101	24	야간	2026-10-01 20:00:00	2026-10-01 20:13:00	90	1
200450	JB2610-0101	30	P101	38	야간	2026-10-01 20:13:00	2026-10-02 00:28:00	2000	6
200451	JB2610-0102	10	B101	31	주간	2026-10-01 12:17:00	2026-10-01 14:03:00	1500	7
200452	JB2610-0102	20	P103	6	주간	2026-10-01 14:03:00	2026-10-01 17:00:00	1160	12
200453	JB2610-0102	20	P103	38	야간	2026-10-01 20:00:00	2026-10-01 20:52:00	340	1
200454	JB2610-0102	30	P103	24	야간	2026-10-01 20:52:00	2026-10-02 00:05:00	1500	17
200455	JB2610-0103	10	B101	48	야간	2026-10-02 00:05:00	2026-10-02 04:48:00	4000	47
200456	JB2610-0103	20	P103	48	야간	2026-10-02 04:48:00	2026-10-02 05:00:00	80	1
200457	JB2610-0103	20	P103	6	주간	2026-10-02 08:00:00	2026-10-02 17:00:00	3500	34
200458	JB2610-0103	20	P103	57	야간	2026-10-02 20:00:00	2026-10-02 21:05:00	420	1
200459	JB2610-0103	30	P103	38	야간	2026-10-02 21:05:00	2026-10-03 03:46:00	4000	10
200460	JB2610-0104	10	B101	31	주간	2026-10-02 08:00:00	2026-10-02 12:19:00	3500	7
200461	JB2610-0104	20	P101	36	주간	2026-10-02 12:19:00	2026-10-02 17:00:00	1770	17
200462	JB2610-0104	20	P101	38	야간	2026-10-02 20:00:00	2026-10-03 00:35:00	1730	10
200463	JB2610-0104	30	P101	38	야간	2026-10-03 00:35:00	2026-10-03 05:00:00	2630	8
200464	JB2610-0104	30	P101	45	주간	2026-10-03 08:00:00	2026-10-03 09:27:00	870	7
200465	JB2610-0105	10	B101	6	주간	2026-10-03 09:27:00	2026-10-03 11:18:00	1500	13
200466	JB2610-0105	20	P101	36	주간	2026-10-03 11:18:00	2026-10-03 15:32:00	1500	14
200467	JB2610-0105	30	P101	72	주간	2026-10-03 15:32:00	2026-10-03 17:00:00	730	7
200468	JB2610-0105	30	P101	6	주간	2026-10-05 08:00:00	2026-10-05 09:32:00	770	6
200469	JB2610-0106	10	B101	45	주간	2026-10-03 15:46:00	2026-10-03 17:00:00	970	9
200470	JB2610-0106	10	B101	36	주간	2026-10-05 08:00:00	2026-10-05 08:41:00	530	6
200471	JB2610-0106	20	P103	45	주간	2026-10-05 08:41:00	2026-10-05 12:43:00	1500	15
200472	JB2610-0106	30	P103	31	주간	2026-10-05 12:43:00	2026-10-05 15:55:00	1500	15
200473	JB2610-0107	10	B101	31	주간	2026-10-05 08:41:00	2026-10-05 09:52:00	1000	11
200474	JB2610-0107	20	P201	79	주간	2026-10-05 09:52:00	2026-10-05 11:52:00	1000	11
200475	JB2610-0107	30	P201	79	주간	2026-10-05 11:52:00	2026-10-05 13:41:00	1000	9
200476	JB2610-0107	40	W201	79	주간	2026-10-05 13:41:00	2026-10-05 16:50:00	1000	4
200477	JB2610-0108	10	B101	45	주간	2026-10-05 15:55:00	2026-10-05 17:00:00	1080	12
200478	JB2610-0108	10	B101	38	야간	2026-10-05 20:00:00	2026-10-05 21:56:00	1920	19
200479	JB2610-0108	20	P103	48	야간	2026-10-05 21:56:00	2026-10-06 04:16:00	3000	10
200480	JB2610-0108	30	P103	24	야간	2026-10-06 04:16:00	2026-10-06 05:00:00	370	3
200481	JB2610-0108	30	P103	72	주간	2026-10-06 08:00:00	2026-10-06 13:13:00	2630	18
200482	JB2610-0109	10	B101	24	야간	2026-10-05 21:56:00	2026-10-06 01:55:00	4000	19
200483	JB2610-0109	20	P101	38	야간	2026-10-06 01:55:00	2026-10-06 05:00:00	1370	13
200484	JB2610-0109	20	P101	6	주간	2026-10-06 08:00:00	2026-10-06 13:57:00	2630	26
200485	JB2610-0109	30	P101	6	주간	2026-10-06 13:57:00	2026-10-06 17:00:00	1690	15
200486	JB2610-0109	30	P101	57	야간	2026-10-06 20:00:00	2026-10-07 00:11:00	2310	6
200487	JB2610-0110	10	B101	74	야간	2026-10-06 01:55:00	2026-10-06 04:32:00	2500	10
200488	JB2610-0110	20	P201	55	야간	2026-10-06 04:32:00	2026-10-06 05:00:00	270	2
200489	JB2610-0110	20	P201	40	주간	2026-10-06 08:00:00	2026-10-06 11:47:00	2230	22
200490	JB2610-0110	30	P201	52	주간	2026-10-06 11:47:00	2026-10-06 15:32:00	2500	27
200491	JB2610-0110	40	W201	52	주간	2026-10-06 15:32:00	2026-10-06 17:00:00	570	6
200492	JB2610-0110	40	W201	55	야간	2026-10-06 20:00:00	2026-10-07 00:57:00	1930	13
200493	JB2610-0111	10	B101	36	주간	2026-10-06 13:13:00	2026-10-06 16:58:00	3500	31
200494	JB2610-0111	20	P103	72	주간	2026-10-06 16:58:00	2026-10-06 17:00:00	20	0
200495	JB2610-0111	20	P103	38	야간	2026-10-06 20:00:00	2026-10-07 02:57:00	3480	41
200496	JB2610-0111	30	P103	74	야간	2026-10-07 02:57:00	2026-10-07 05:00:00	1000	7
200497	JB2610-0111	30	P103	31	주간	2026-10-07 08:00:00	2026-10-07 13:09:00	2500	19
200498	JB2610-0112	10	B101	31	주간	2026-10-07 12:11:00	2026-10-07 14:16:00	2000	24
200499	JB2610-0112	20	P101	72	주간	2026-10-07 14:16:00	2026-10-07 17:00:00	1210	14
200500	JB2610-0112	20	P101	38	야간	2026-10-07 20:00:00	2026-10-07 21:47:00	790	6
200501	JB2610-0112	30	P101	57	야간	2026-10-07 21:47:00	2026-10-08 02:03:00	2000	23
200502	JB2610-0113	10	B101	72	주간	2026-10-07 14:16:00	2026-10-07 17:00:00	2610	6
200503	JB2610-0113	10	B101	57	야간	2026-10-07 20:00:00	2026-10-07 21:27:00	1390	4
200504	JB2610-0113	20	P103	48	야간	2026-10-07 21:27:00	2026-10-08 05:00:00	3250	14
200505	JB2610-0113	20	P103	31	주간	2026-10-08 08:00:00	2026-10-08 09:45:00	750	9
200506	JB2610-0113	30	P103	31	주간	2026-10-08 09:45:00	2026-10-08 17:00:00	3540	21
200507	JB2610-0113	30	P103	24	야간	2026-10-08 20:00:00	2026-10-08 20:56:00	460	3
200508	JB2610-0114	10	B101	45	주간	2026-10-08 08:03:00	2026-10-08 12:03:00	3500	18
200509	JB2610-0114	20	P101	36	주간	2026-10-08 12:03:00	2026-10-08 17:00:00	2410	26
200510	JB2610-0114	20	P101	74	야간	2026-10-08 20:00:00	2026-10-08 22:15:00	1090	12
200511	JB2610-0114	30	P101	48	야간	2026-10-08 22:15:00	2026-10-09 04:39:00	3500	39
200512	JB2610-0115	10	B101	57	야간	2026-10-08 20:56:00	2026-10-09 02:18:00	4500	42
200513	JB2610-0115	20	P103	57	야간	2026-10-09 02:18:00	2026-10-09 05:00:00	1160	9
200514	JB2610-0115	20	P103	31	주간	2026-10-09 08:00:00	2026-10-09 15:48:00	3340	12
200515	JB2610-0115	30	P103	45	주간	2026-10-09 15:48:00	2026-10-09 17:00:00	730	4
200516	JB2610-0115	30	P103	38	야간	2026-10-09 20:00:00	2026-10-10 02:09:00	3770	36
200517	JB2610-0116	10	B101	6	주간	2026-10-09 10:39:00	2026-10-09 13:46:00	2500	14
200518	JB2610-0116	20	P101	6	주간	2026-10-09 13:46:00	2026-10-09 17:00:00	1140	5
200519	JB2610-0116	20	P101	74	야간	2026-10-09 20:00:00	2026-10-09 23:51:00	1360	11
200520	JB2610-0116	30	P101	38	야간	2026-10-09 23:51:00	2026-10-10 04:36:00	2500	17
200521	JB2610-0117	10	B101	6	주간	2026-10-09 13:46:00	2026-10-09 16:01:00	2000	23
200522	JB2610-0117	20	P201	40	주간	2026-10-09 16:01:00	2026-10-09 17:00:00	520	6
200523	JB2610-0117	20	P201	55	야간	2026-10-09 20:00:00	2026-10-09 22:50:00	1480	3
200524	JB2610-0117	30	P201	55	야간	2026-10-09 22:50:00	2026-10-10 01:42:00	2000	10
200525	JB2610-0117	40	W201	55	야간	2026-10-10 01:42:00	2026-10-10 05:00:00	1220	6
200526	JB2610-0117	40	W201	79	주간	2026-10-10 08:00:00	2026-10-10 10:07:00	780	8
200527	JB2610-0118	10	B101	48	야간	2026-10-10 04:36:00	2026-10-10 05:00:00	290	1
200528	JB2610-0118	10	B101	6	주간	2026-10-10 08:00:00	2026-10-10 09:40:00	1210	3
200529	JB2610-0118	20	P101	45	주간	2026-10-10 09:40:00	2026-10-10 14:04:00	1500	6
200530	JB2610-0118	30	P101	72	주간	2026-10-10 14:04:00	2026-10-10 17:00:00	1430	12
200531	JB2610-0118	30	P101	72	주간	2026-10-12 08:00:00	2026-10-12 08:08:00	70	0
200532	JB2610-0119	10	B101	45	주간	2026-10-10 09:40:00	2026-10-10 12:58:00	2500	25
200533	JB2610-0119	20	P103	31	주간	2026-10-10 12:58:00	2026-10-10 17:00:00	1480	5
200534	JB2610-0119	20	P103	31	주간	2026-10-12 08:00:00	2026-10-12 10:46:00	1020	11
200535	JB2610-0119	30	P103	36	주간	2026-10-12 10:46:00	2026-10-12 15:11:00	2500	8
200536	JB2610-0120	10	B101	72	주간	2026-10-12 08:08:00	2026-10-12 10:48:00	2500	11
200537	JB2610-0120	20	P101	31	주간	2026-10-12 10:48:00	2026-10-12 16:13:00	2500	9
200538	JB2610-0120	30	P101	45	주간	2026-10-12 16:13:00	2026-10-12 17:00:00	400	2
200539	JB2610-0120	30	P101	24	야간	2026-10-12 20:00:00	2026-10-13 00:08:00	2100	19
200540	JB2610-0121	10	B101	74	야간	2026-10-13 00:08:00	2026-10-13 04:45:00	4500	23
200541	JB2610-0121	20	P101	57	야간	2026-10-13 04:45:00	2026-10-13 05:00:00	110	1
200542	JB2610-0121	20	P101	72	주간	2026-10-13 08:00:00	2026-10-13 17:00:00	3990	20
200543	JB2610-0121	20	P101	24	야간	2026-10-13 20:00:00	2026-10-13 20:54:00	400	5
200544	JB2610-0121	30	P101	38	야간	2026-10-13 20:54:00	2026-10-14 05:00:00	4220	34
200545	JB2610-0121	30	P101	6	주간	2026-10-14 08:00:00	2026-10-14 08:32:00	280	1
200546	JB2610-0122	10	B101	38	야간	2026-10-13 04:45:00	2026-10-13 05:00:00	260	2
200547	JB2610-0122	10	B101	6	주간	2026-10-13 08:00:00	2026-10-13 10:39:00	2740	6
200548	JB2610-0122	20	P201	79	주간	2026-10-13 10:39:00	2026-10-13 15:30:00	3000	24
200549	JB2610-0122	30	P201	52	주간	2026-10-13 15:30:00	2026-10-13 17:00:00	1020	12
200550	JB2610-0122	30	P201	55	야간	2026-10-13 20:00:00	2026-10-13 22:55:00	1980	13
200551	JB2610-0122	40	W201	55	야간	2026-10-13 22:55:00	2026-10-14 05:00:00	2230	22
200552	JB2610-0122	40	W201	44	주간	2026-10-14 08:00:00	2026-10-14 10:07:00	770	9
200553	JB2610-0123	10	B101	36	주간	2026-10-13 10:39:00	2026-10-13 12:42:00	1500	8
200554	JB2610-0123	20	P103	72	주간	2026-10-13 12:42:00	2026-10-13 17:00:00	1420	7
200555	JB2610-0123	20	P103	38	야간	2026-10-13 20:00:00	2026-10-13 20:15:00	80	1
200556	JB2610-0123	30	P103	48	야간	2026-10-13 20:15:00	2026-10-13 23:01:00	1500	8
200557	JB2610-0124	10	B101	6	주간	2026-10-14 11:01:00	2026-10-14 13:36:00	2000	11
200558	JB2610-0124	20	P103	31	주간	2026-10-14 13:36:00	2026-10-14 17:00:00	1290	13
200559	JB2610-0124	20	P103	24	야간	2026-10-14 20:00:00	2026-10-14 21:53:00	710	4
200560	JB2610-0124	30	P103	48	야간	2026-10-14 21:53:00	2026-10-15 01:32:00	2000	14
200561	JB2610-0125	10	B101	6	주간	2026-10-14 14:32:00	2026-10-14 16:21:00	1500	15
200562	JB2610-0125	20	P101	36	주간	2026-10-14 16:21:00	2026-10-14 17:00:00	280	1
200563	JB2610-0125	20	P101	57	야간	2026-10-14 20:00:00	2026-10-14 22:53:00	1220	3
200564	JB2610-0125	30	P101	38	야간	2026-10-14 22:53:00	2026-10-15 01:53:00	1500	3
200565	JB2610-0126	10	B101	31	주간	2026-10-15 08:00:00	2026-10-15 12:32:00	4000	27
200566	JB2610-0126	20	P103	72	주간	2026-10-15 12:32:00	2026-10-15 17:00:00	2010	6
200567	JB2610-0126	20	P103	57	야간	2026-10-15 20:00:00	2026-10-16 00:26:00	1990	6
200568	JB2610-0126	30	P103	57	야간	2026-10-16 00:26:00	2026-10-16 05:00:00	2210	17
200569	JB2610-0126	30	P103	6	주간	2026-10-16 08:00:00	2026-10-16 11:42:00	1790	15
200570	JB2610-0127	10	B101	72	주간	2026-10-15 13:53:00	2026-10-15 15:19:00	1000	9
200571	JB2610-0127	20	P101	31	주간	2026-10-15 15:19:00	2026-10-15 17:00:00	520	4
200572	JB2610-0127	20	P101	48	야간	2026-10-15 20:00:00	2026-10-15 21:32:00	480	3
200573	JB2610-0127	30	P101	24	야간	2026-10-15 21:32:00	2026-10-15 23:38:00	1000	11
200574	JB2610-0128	10	B101	74	야간	2026-10-15 23:38:00	2026-10-16 04:22:00	3500	9
200575	JB2610-0128	20	P101	57	야간	2026-10-16 04:22:00	2026-10-16 05:00:00	280	2
200576	JB2610-0128	20	P101	45	주간	2026-10-16 08:00:00	2026-10-16 15:25:00	3220	24
200577	JB2610-0128	30	P101	6	주간	2026-10-16 15:25:00	2026-10-16 17:00:00	860	9
200578	JB2610-0128	30	P101	24	야간	2026-10-16 20:00:00	2026-10-17 00:53:00	2640	22
200579	JB2610-0129	10	B101	36	주간	2026-10-16 11:42:00	2026-10-16 12:54:00	1000	10
200580	JB2610-0129	20	P103	36	주간	2026-10-16 12:54:00	2026-10-16 15:34:00	1000	12
200581	JB2610-0129	30	P103	6	주간	2026-10-16 15:34:00	2026-10-16 17:00:00	640	5
200582	JB2610-0129	30	P103	38	야간	2026-10-16 20:00:00	2026-10-16 20:48:00	360	3
200583	JB2610-0130	10	B101	31	주간	2026-10-17 08:00:00	2026-10-17 12:07:00	4000	46
200584	JB2610-0130	20	P101	72	주간	2026-10-17 12:07:00	2026-10-17 17:00:00	2320	5
200585	JB2610-0130	20	P101	45	주간	2026-10-19 08:00:00	2026-10-19 11:33:00	1680	6
200586	JB2610-0130	30	P101	31	주간	2026-10-19 11:33:00	2026-10-19 17:00:00	2950	21
200587	JB2610-0130	30	P101	24	야간	2026-10-19 20:00:00	2026-10-19 21:56:00	1050	6
200588	JB2610-0131	10	B101	6	주간	2026-10-17 12:07:00	2026-10-17 16:05:00	4000	34
200589	JB2610-0131	20	P103	45	주간	2026-10-17 16:05:00	2026-10-17 17:00:00	410	3
200590	JB2610-0131	20	P103	31	주간	2026-10-19 08:00:00	2026-10-19 15:56:00	3590	43
200591	JB2610-0131	30	P103	36	주간	2026-10-19 15:56:00	2026-10-19 17:00:00	570	6
200592	JB2610-0131	30	P103	38	야간	2026-10-19 20:00:00	2026-10-20 02:25:00	3430	17
200593	JB2610-0132	10	B101	24	야간	2026-10-20 03:56:00	2026-10-20 05:00:00	1080	11
200594	JB2610-0132	10	B101	36	주간	2026-10-20 08:00:00	2026-10-20 11:23:00	3420	24
200595	JB2610-0132	20	P101	72	주간	2026-10-20 11:23:00	2026-10-20 17:00:00	2600	18
200596	JB2610-0132	20	P101	74	야간	2026-10-20 20:00:00	2026-10-21 00:07:00	1900	18
200597	JB2610-0132	30	P101	38	야간	2026-10-21 00:07:00	2026-10-21 05:00:00	2340	8
200598	JB2610-0132	30	P101	45	주간	2026-10-21 08:00:00	2026-10-21 12:30:00	2160	20
200599	JB2610-0133	10	B101	36	주간	2026-10-20 11:23:00	2026-10-20 16:29:00	4500	30
200600	JB2610-0133	20	P103	36	주간	2026-10-20 16:29:00	2026-10-20 17:00:00	230	1
200601	JB2610-0133	20	P103	24	야간	2026-10-20 20:00:00	2026-10-21 05:00:00	3980	31
200602	JB2610-0133	20	P103	72	주간	2026-10-21 08:00:00	2026-10-21 08:40:00	290	2
200603	JB2610-0133	30	P103	31	주간	2026-10-21 08:40:00	2026-10-21 17:00:00	4450	18
200604	JB2610-0133	30	P103	74	야간	2026-10-21 20:00:00	2026-10-21 20:06:00	50	0
200605	JB2610-0134	10	B101	48	야간	2026-10-21 20:00:00	2026-10-21 21:12:00	1000	11
200606	JB2610-0134	20	P101	38	야간	2026-10-21 21:12:00	2026-10-21 23:50:00	1000	7
200607	JB2610-0134	30	P101	74	야간	2026-10-21 23:50:00	2026-10-22 02:11:00	1000	3
200608	JB2610-0135	10	B101	31	주간	2026-10-22 08:06:00	2026-10-22 11:56:00	3000	9
200609	JB2610-0135	20	P103	6	주간	2026-10-22 11:56:00	2026-10-22 17:00:00	1940	21
200610	JB2610-0135	20	P103	57	야간	2026-10-22 20:00:00	2026-10-22 22:47:00	1060	5
200611	JB2610-0135	30	P103	74	야간	2026-10-22 22:47:00	2026-10-23 04:07:00	3000	12
200612	JB2610-0136	10	B101	45	주간	2026-10-22 11:56:00	2026-10-22 14:19:00	2000	14
200613	JB2610-0136	20	P101	36	주간	2026-10-22 14:19:00	2026-10-22 17:00:00	1140	3
200614	JB2610-0136	20	P101	74	야간	2026-10-22 20:00:00	2026-10-22 22:01:00	860	10
200615	JB2610-0136	30	P101	74	야간	2026-10-22 22:01:00	2026-10-23 01:53:00	2000	17
200616	JB2610-0137	10	B101	74	야간	2026-10-23 04:07:00	2026-10-23 05:00:00	760	8
200617	JB2610-0137	10	B101	36	주간	2026-10-23 08:00:00	2026-10-23 08:51:00	740	7
200618	JB2610-0137	20	P103	72	주간	2026-10-23 08:51:00	2026-10-23 12:33:00	1500	5
200619	JB2610-0137	30	P103	72	주간	2026-10-23 12:33:00	2026-10-23 15:47:00	1500	11
200620	JB2610-0139	10	B101	45	주간	2026-10-23 13:53:00	2026-10-23 16:55:00	3000	18
200621	JB2610-0139	20	P101	45	주간	2026-10-23 16:55:00	2026-10-23 17:00:00	40	0
200622	JB2610-0139	20	P101	24	야간	2026-10-23 20:00:00	2026-10-24 02:19:00	2960	23
200623	JB2610-0139	30	P101	48	야간	2026-10-24 02:19:00	2026-10-24 05:00:00	1400	7
200624	JB2610-0139	30	P101	6	주간	2026-10-24 08:00:00	2026-10-24 11:03:00	1600	6
200625	JB2610-0140	10	B101	36	주간	2026-10-23 16:55:00	2026-10-23 17:00:00	80	1
200626	JB2610-0140	10	B101	57	야간	2026-10-23 20:00:00	2026-10-23 22:29:00	2420	13
200627	JB2610-0140	20	P103	74	야간	2026-10-23 22:29:00	2026-10-24 04:33:00	2500	26
200628	JB2610-0140	30	P103	24	야간	2026-10-24 04:33:00	2026-10-24 05:00:00	240	2
200629	JB2610-0140	30	P103	31	주간	2026-10-24 08:00:00	2026-10-24 12:18:00	2260	27
200630	JB2610-0138	10	B101	6	주간	2026-10-29 08:00:00	2026-10-29 13:58:00	6000	12
200631	JB2610-0138	20	P101	36	주간	2026-10-29 09:00:00	2026-10-29 16:52:00	3600	28
200632	JB2610-0138	30	P101	36	주간	2026-10-29 16:52:00	2026-10-29 17:00:00	60	0
200633	JB2610-0138	30	P101	38	야간	2026-10-29 20:00:00	2026-10-30 03:18:00	3540	12
200634	JB2610-0141	10	B101	72	주간	2026-10-28 08:00:00	2026-10-28 12:57:00	5000	45
200635	JB2610-0141	20	P103	45	주간	2026-10-29 08:00:00	2026-10-29 12:14:00	1800	7
200636	JB2610-0141	30	P103	48	야간	2026-10-29 20:00:00	2026-10-30 00:04:00	1800	12
200637	JB2610-0142	10	B101	31	주간	2026-10-28 12:57:00	2026-10-28 16:55:00	4000	18
200638	JB2610-0142	20	P201	27	주간	2026-10-29 08:00:00	2026-10-29 14:17:00	4000	19
200639	JB2610-0142	30	P201	44	주간	2026-10-29 14:17:00	2026-10-29 17:00:00	1850	22
200640	JB2610-0142	30	P201	55	야간	2026-10-29 20:00:00	2026-10-29 23:09:00	2150	24
\.


--
-- Data for Name: routing; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.routing (product_code, op_seq, op_name, equipment_kind, std_cycle_sec, std_setup_min, min_tonnage) FROM stdin;
BRK-210L	10	블랭킹	BLK	3.00	15	200
BRK-210L	20	성형	PRS	6.00	40	400
BRK-210L	30	트리밍	PRS	5.50	30	400
BRK-210L	40	검사	INS	3.50	5	\N
BRK-210R	10	블랭킹	BLK	3.00	15	200
BRK-210R	20	성형	PRS	6.00	40	400
BRK-210R	30	트리밍	PRS	5.50	30	400
BRK-210R	40	검사	INS	3.50	5	\N
RNF-320L	10	블랭킹	BLK	3.40	15	200
RNF-320L	20	성형	PRS	7.00	50	400
RNF-320L	30	피어싱	PRS	5.00	30	400
RNF-320L	40	검사	INS	3.50	5	\N
RNF-320R	10	블랭킹	BLK	3.40	15	200
RNF-320R	20	성형	PRS	7.00	50	400
RNF-320R	30	피어싱	PRS	5.00	30	400
RNF-320R	40	검사	INS	3.50	5	\N
PNL-410	10	블랭킹	BLK	2.80	15	200
PNL-410	20	성형	PRS	4.50	35	300
PNL-410	30	트리밍	PRS	4.00	25	300
PNL-410	40	볼트 용접	WLD	8.00	20	\N
PNL-410	50	검사	INS	3.00	5	\N
\.


--
-- Data for Name: sensor_reading; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.sensor_reading (equipment_code, measured_at, metric, value, threshold_high) FROM stdin;
B101	2026-08-03 09:00:00	temp_c	43.28	60.00
B101	2026-08-03 09:00:00	vibration_mm_s	1.75	4.50
P101	2026-08-03 09:00:00	temp_c	42.31	60.00
P101	2026-08-03 09:00:00	vibration_mm_s	2.02	4.50
P103	2026-08-03 09:00:00	temp_c	39.41	60.00
P103	2026-08-03 09:00:00	vibration_mm_s	2.43	4.50
P201	2026-08-03 09:00:00	temp_c	41.51	60.00
P201	2026-08-03 09:00:00	vibration_mm_s	2.84	4.50
B101	2026-08-04 09:00:00	temp_c	41.97	60.00
B101	2026-08-04 09:00:00	vibration_mm_s	1.59	4.50
P101	2026-08-04 09:00:00	temp_c	44.66	60.00
P101	2026-08-04 09:00:00	vibration_mm_s	3.18	4.50
P103	2026-08-04 09:00:00	temp_c	44.98	60.00
P103	2026-08-04 09:00:00	vibration_mm_s	2.35	4.50
P201	2026-08-04 09:00:00	temp_c	38.78	60.00
P201	2026-08-04 09:00:00	vibration_mm_s	2.65	4.50
B101	2026-08-05 09:00:00	temp_c	45.98	60.00
B101	2026-08-05 09:00:00	vibration_mm_s	2.07	4.50
P101	2026-08-05 09:00:00	temp_c	47.57	60.00
P101	2026-08-05 09:00:00	vibration_mm_s	3.48	4.50
P103	2026-08-05 09:00:00	temp_c	37.17	60.00
P103	2026-08-05 09:00:00	vibration_mm_s	1.94	4.50
P201	2026-08-05 09:00:00	temp_c	44.53	60.00
P201	2026-08-05 09:00:00	vibration_mm_s	2.55	4.50
B101	2026-08-06 09:00:00	temp_c	38.12	60.00
B101	2026-08-06 09:00:00	vibration_mm_s	1.80	4.50
P101	2026-08-06 09:00:00	temp_c	40.30	60.00
P101	2026-08-06 09:00:00	vibration_mm_s	2.09	4.50
P103	2026-08-06 09:00:00	temp_c	42.02	60.00
P103	2026-08-06 09:00:00	vibration_mm_s	2.18	4.50
P201	2026-08-06 09:00:00	temp_c	40.61	60.00
P201	2026-08-06 09:00:00	vibration_mm_s	2.44	4.50
B101	2026-08-07 09:00:00	temp_c	38.73	60.00
B101	2026-08-07 09:00:00	vibration_mm_s	1.89	4.50
P101	2026-08-07 09:00:00	temp_c	41.85	60.00
P101	2026-08-07 09:00:00	vibration_mm_s	2.07	4.50
P103	2026-08-07 09:00:00	temp_c	39.30	60.00
P103	2026-08-07 09:00:00	vibration_mm_s	1.97	4.50
P201	2026-08-07 09:00:00	temp_c	41.32	60.00
P201	2026-08-07 09:00:00	vibration_mm_s	2.88	4.50
B101	2026-08-08 09:00:00	temp_c	40.83	60.00
B101	2026-08-08 09:00:00	vibration_mm_s	1.81	4.50
P101	2026-08-08 09:00:00	temp_c	40.08	60.00
P101	2026-08-08 09:00:00	vibration_mm_s	2.23	4.50
P103	2026-08-08 09:00:00	temp_c	38.54	60.00
P103	2026-08-08 09:00:00	vibration_mm_s	2.23	4.50
P201	2026-08-08 09:00:00	temp_c	43.86	60.00
P201	2026-08-08 09:00:00	vibration_mm_s	2.84	4.50
B101	2026-08-10 09:00:00	temp_c	39.65	60.00
B101	2026-08-10 09:00:00	vibration_mm_s	1.55	4.50
P101	2026-08-10 09:00:00	temp_c	40.67	60.00
P101	2026-08-10 09:00:00	vibration_mm_s	1.94	4.50
P103	2026-08-10 09:00:00	temp_c	40.76	60.00
P103	2026-08-10 09:00:00	vibration_mm_s	2.21	4.50
P201	2026-08-10 09:00:00	temp_c	41.96	60.00
P201	2026-08-10 09:00:00	vibration_mm_s	2.43	4.50
B101	2026-08-11 09:00:00	temp_c	43.31	60.00
B101	2026-08-11 09:00:00	vibration_mm_s	1.91	4.50
P101	2026-08-11 09:00:00	temp_c	37.24	60.00
P101	2026-08-11 09:00:00	vibration_mm_s	2.00	4.50
P103	2026-08-11 09:00:00	temp_c	43.31	60.00
P103	2026-08-11 09:00:00	vibration_mm_s	2.26	4.50
P201	2026-08-11 09:00:00	temp_c	40.76	60.00
P201	2026-08-11 09:00:00	vibration_mm_s	2.56	4.50
B101	2026-08-12 09:00:00	temp_c	39.70	60.00
B101	2026-08-12 09:00:00	vibration_mm_s	1.72	4.50
P101	2026-08-12 09:00:00	temp_c	42.92	60.00
P101	2026-08-12 09:00:00	vibration_mm_s	2.28	4.50
P103	2026-08-12 09:00:00	temp_c	41.09	60.00
P103	2026-08-12 09:00:00	vibration_mm_s	1.90	4.50
P201	2026-08-12 09:00:00	temp_c	39.65	60.00
P201	2026-08-12 09:00:00	vibration_mm_s	2.37	4.50
B101	2026-08-13 09:00:00	temp_c	41.87	60.00
B101	2026-08-13 09:00:00	vibration_mm_s	1.97	4.50
P101	2026-08-13 09:00:00	temp_c	43.65	60.00
P101	2026-08-13 09:00:00	vibration_mm_s	2.46	4.50
P103	2026-08-13 09:00:00	temp_c	42.39	60.00
P103	2026-08-13 09:00:00	vibration_mm_s	2.05	4.50
P201	2026-08-13 09:00:00	temp_c	38.69	60.00
P201	2026-08-13 09:00:00	vibration_mm_s	2.42	4.50
B101	2026-08-14 09:00:00	temp_c	42.74	60.00
B101	2026-08-14 09:00:00	vibration_mm_s	2.09	4.50
P101	2026-08-14 09:00:00	temp_c	39.51	60.00
P101	2026-08-14 09:00:00	vibration_mm_s	2.29	4.50
P103	2026-08-14 09:00:00	temp_c	44.29	60.00
P103	2026-08-14 09:00:00	vibration_mm_s	2.19	4.50
P201	2026-08-14 09:00:00	temp_c	41.47	60.00
P201	2026-08-14 09:00:00	vibration_mm_s	2.81	4.50
B101	2026-08-15 09:00:00	temp_c	40.44	60.00
B101	2026-08-15 09:00:00	vibration_mm_s	1.62	4.50
P101	2026-08-15 09:00:00	temp_c	39.54	60.00
P101	2026-08-15 09:00:00	vibration_mm_s	2.16	4.50
P103	2026-08-15 09:00:00	temp_c	39.60	60.00
P103	2026-08-15 09:00:00	vibration_mm_s	2.16	4.50
P201	2026-08-15 09:00:00	temp_c	37.45	60.00
P201	2026-08-15 09:00:00	vibration_mm_s	2.42	4.50
B101	2026-08-17 09:00:00	temp_c	44.12	60.00
B101	2026-08-17 09:00:00	vibration_mm_s	1.80	4.50
P101	2026-08-17 09:00:00	temp_c	45.56	60.00
P101	2026-08-17 09:00:00	vibration_mm_s	2.47	4.50
P103	2026-08-17 09:00:00	temp_c	39.77	60.00
P103	2026-08-17 09:00:00	vibration_mm_s	2.22	4.50
P201	2026-08-17 09:00:00	temp_c	42.23	60.00
P201	2026-08-17 09:00:00	vibration_mm_s	2.39	4.50
B101	2026-08-18 09:00:00	temp_c	40.98	60.00
B101	2026-08-18 09:00:00	vibration_mm_s	2.01	4.50
P101	2026-08-18 09:00:00	temp_c	43.74	60.00
P101	2026-08-18 09:00:00	vibration_mm_s	2.09	4.50
P103	2026-08-18 09:00:00	temp_c	42.51	60.00
P103	2026-08-18 09:00:00	vibration_mm_s	2.31	4.50
P201	2026-08-18 09:00:00	temp_c	40.63	60.00
P201	2026-08-18 09:00:00	vibration_mm_s	2.60	4.50
B101	2026-08-19 09:00:00	temp_c	43.76	60.00
B101	2026-08-19 09:00:00	vibration_mm_s	2.10	4.50
P101	2026-08-19 09:00:00	temp_c	43.72	60.00
P101	2026-08-19 09:00:00	vibration_mm_s	2.46	4.50
P103	2026-08-19 09:00:00	temp_c	39.69	60.00
P103	2026-08-19 09:00:00	vibration_mm_s	2.15	4.50
P201	2026-08-19 09:00:00	temp_c	39.99	60.00
P201	2026-08-19 09:00:00	vibration_mm_s	2.55	4.50
B101	2026-08-20 09:00:00	temp_c	43.95	60.00
B101	2026-08-20 09:00:00	vibration_mm_s	1.80	4.50
P101	2026-08-20 09:00:00	temp_c	38.55	60.00
P101	2026-08-20 09:00:00	vibration_mm_s	2.32	4.50
P103	2026-08-20 09:00:00	temp_c	41.98	60.00
P103	2026-08-20 09:00:00	vibration_mm_s	2.02	4.50
P201	2026-08-20 09:00:00	temp_c	40.49	60.00
P201	2026-08-20 09:00:00	vibration_mm_s	2.75	4.50
B101	2026-08-21 09:00:00	temp_c	43.63	60.00
B101	2026-08-21 09:00:00	vibration_mm_s	1.98	4.50
P101	2026-08-21 09:00:00	temp_c	38.91	60.00
P101	2026-08-21 09:00:00	vibration_mm_s	2.26	4.50
P103	2026-08-21 09:00:00	temp_c	43.88	60.00
P103	2026-08-21 09:00:00	vibration_mm_s	1.97	4.50
P201	2026-08-21 09:00:00	temp_c	43.05	60.00
P201	2026-08-21 09:00:00	vibration_mm_s	2.31	4.50
B101	2026-08-22 09:00:00	temp_c	39.51	60.00
B101	2026-08-22 09:00:00	vibration_mm_s	1.79	4.50
P101	2026-08-22 09:00:00	temp_c	39.59	60.00
P101	2026-08-22 09:00:00	vibration_mm_s	2.49	4.50
P103	2026-08-22 09:00:00	temp_c	39.19	60.00
P103	2026-08-22 09:00:00	vibration_mm_s	2.18	4.50
P201	2026-08-22 09:00:00	temp_c	43.07	60.00
P201	2026-08-22 09:00:00	vibration_mm_s	2.49	4.50
B101	2026-08-24 09:00:00	temp_c	40.99	60.00
B101	2026-08-24 09:00:00	vibration_mm_s	1.90	4.50
P101	2026-08-24 09:00:00	temp_c	41.63	60.00
P101	2026-08-24 09:00:00	vibration_mm_s	2.18	4.50
P103	2026-08-24 09:00:00	temp_c	38.53	60.00
P103	2026-08-24 09:00:00	vibration_mm_s	2.05	4.50
P201	2026-08-24 09:00:00	temp_c	41.46	60.00
P201	2026-08-24 09:00:00	vibration_mm_s	2.35	4.50
B101	2026-08-25 09:00:00	temp_c	44.41	60.00
B101	2026-08-25 09:00:00	vibration_mm_s	2.09	4.50
P101	2026-08-25 09:00:00	temp_c	43.02	60.00
P101	2026-08-25 09:00:00	vibration_mm_s	2.27	4.50
P103	2026-08-25 09:00:00	temp_c	41.91	60.00
P103	2026-08-25 09:00:00	vibration_mm_s	2.01	4.50
P201	2026-08-25 09:00:00	temp_c	40.69	60.00
P201	2026-08-25 09:00:00	vibration_mm_s	2.58	4.50
B101	2026-08-26 09:00:00	temp_c	43.68	60.00
B101	2026-08-26 09:00:00	vibration_mm_s	2.02	4.50
P101	2026-08-26 09:00:00	temp_c	37.21	60.00
P101	2026-08-26 09:00:00	vibration_mm_s	1.91	4.50
P103	2026-08-26 09:00:00	temp_c	42.60	60.00
P103	2026-08-26 09:00:00	vibration_mm_s	1.90	4.50
P201	2026-08-26 09:00:00	temp_c	45.29	60.00
P201	2026-08-26 09:00:00	vibration_mm_s	2.85	4.50
B101	2026-08-27 09:00:00	temp_c	40.15	60.00
B101	2026-08-27 09:00:00	vibration_mm_s	1.59	4.50
P101	2026-08-27 09:00:00	temp_c	42.73	60.00
P101	2026-08-27 09:00:00	vibration_mm_s	1.99	4.50
P103	2026-08-27 09:00:00	temp_c	40.83	60.00
P103	2026-08-27 09:00:00	vibration_mm_s	2.29	4.50
P201	2026-08-27 09:00:00	temp_c	44.72	60.00
P201	2026-08-27 09:00:00	vibration_mm_s	2.73	4.50
B101	2026-08-28 09:00:00	temp_c	40.67	60.00
B101	2026-08-28 09:00:00	vibration_mm_s	1.66	4.50
P101	2026-08-28 09:00:00	temp_c	44.21	60.00
P101	2026-08-28 09:00:00	vibration_mm_s	2.15	4.50
P103	2026-08-28 09:00:00	temp_c	41.18	60.00
P103	2026-08-28 09:00:00	vibration_mm_s	2.48	4.50
P201	2026-08-28 09:00:00	temp_c	44.03	60.00
P201	2026-08-28 09:00:00	vibration_mm_s	2.89	4.50
B101	2026-08-29 09:00:00	temp_c	43.15	60.00
B101	2026-08-29 09:00:00	vibration_mm_s	1.79	4.50
P101	2026-08-29 09:00:00	temp_c	42.87	60.00
P101	2026-08-29 09:00:00	vibration_mm_s	2.16	4.50
P103	2026-08-29 09:00:00	temp_c	41.83	60.00
P103	2026-08-29 09:00:00	vibration_mm_s	2.45	4.50
P201	2026-08-29 09:00:00	temp_c	43.89	60.00
P201	2026-08-29 09:00:00	vibration_mm_s	2.68	4.50
B101	2026-08-31 09:00:00	temp_c	44.64	60.00
B101	2026-08-31 09:00:00	vibration_mm_s	2.09	4.50
P101	2026-08-31 09:00:00	temp_c	37.85	60.00
P101	2026-08-31 09:00:00	vibration_mm_s	1.93	4.50
P103	2026-08-31 09:00:00	temp_c	45.51	60.00
P103	2026-08-31 09:00:00	vibration_mm_s	3.98	4.50
P201	2026-08-31 09:00:00	temp_c	41.81	60.00
P201	2026-08-31 09:00:00	vibration_mm_s	2.80	4.50
B101	2026-09-01 09:00:00	temp_c	43.18	60.00
B101	2026-09-01 09:00:00	vibration_mm_s	2.07	4.50
P101	2026-09-01 09:00:00	temp_c	41.75	60.00
P101	2026-09-01 09:00:00	vibration_mm_s	2.10	4.50
P103	2026-09-01 09:00:00	temp_c	39.59	60.00
P103	2026-09-01 09:00:00	vibration_mm_s	2.28	4.50
P201	2026-09-01 09:00:00	temp_c	40.36	60.00
P201	2026-09-01 09:00:00	vibration_mm_s	2.77	4.50
B101	2026-09-02 09:00:00	temp_c	42.55	60.00
B101	2026-09-02 09:00:00	vibration_mm_s	1.79	4.50
P101	2026-09-02 09:00:00	temp_c	42.11	60.00
P101	2026-09-02 09:00:00	vibration_mm_s	2.08	4.50
P103	2026-09-02 09:00:00	temp_c	43.31	60.00
P103	2026-09-02 09:00:00	vibration_mm_s	2.31	4.50
P201	2026-09-02 09:00:00	temp_c	42.33	60.00
P201	2026-09-02 09:00:00	vibration_mm_s	2.67	4.50
B101	2026-09-03 09:00:00	temp_c	39.14	60.00
B101	2026-09-03 09:00:00	vibration_mm_s	2.04	4.50
P101	2026-09-03 09:00:00	temp_c	38.95	60.00
P101	2026-09-03 09:00:00	vibration_mm_s	2.24	4.50
P103	2026-09-03 09:00:00	temp_c	42.60	60.00
P103	2026-09-03 09:00:00	vibration_mm_s	2.46	4.50
P201	2026-09-03 09:00:00	temp_c	39.73	60.00
P201	2026-09-03 09:00:00	vibration_mm_s	2.31	4.50
B101	2026-09-04 09:00:00	temp_c	39.59	60.00
B101	2026-09-04 09:00:00	vibration_mm_s	1.71	4.50
P101	2026-09-04 09:00:00	temp_c	38.74	60.00
P101	2026-09-04 09:00:00	vibration_mm_s	2.14	4.50
P103	2026-09-04 09:00:00	temp_c	39.88	60.00
P103	2026-09-04 09:00:00	vibration_mm_s	2.41	4.50
P201	2026-09-04 09:00:00	temp_c	39.92	60.00
P201	2026-09-04 09:00:00	vibration_mm_s	2.88	4.50
B101	2026-09-05 09:00:00	temp_c	42.11	60.00
B101	2026-09-05 09:00:00	vibration_mm_s	1.77	4.50
P101	2026-09-05 09:00:00	temp_c	40.56	60.00
P101	2026-09-05 09:00:00	vibration_mm_s	2.01	4.50
P103	2026-09-05 09:00:00	temp_c	41.77	60.00
P103	2026-09-05 09:00:00	vibration_mm_s	2.11	4.50
P201	2026-09-05 09:00:00	temp_c	43.69	60.00
P201	2026-09-05 09:00:00	vibration_mm_s	2.30	4.50
B101	2026-09-07 09:00:00	temp_c	38.93	60.00
B101	2026-09-07 09:00:00	vibration_mm_s	1.94	4.50
P101	2026-09-07 09:00:00	temp_c	39.43	60.00
P101	2026-09-07 09:00:00	vibration_mm_s	2.04	4.50
P103	2026-09-07 09:00:00	temp_c	42.79	60.00
P103	2026-09-07 09:00:00	vibration_mm_s	2.05	4.50
P201	2026-09-07 09:00:00	temp_c	41.27	60.00
P201	2026-09-07 09:00:00	vibration_mm_s	2.48	4.50
B101	2026-09-08 09:00:00	temp_c	43.14	60.00
B101	2026-09-08 09:00:00	vibration_mm_s	1.90	4.50
P101	2026-09-08 09:00:00	temp_c	40.83	60.00
P101	2026-09-08 09:00:00	vibration_mm_s	2.25	4.50
P103	2026-09-08 09:00:00	temp_c	42.24	60.00
P103	2026-09-08 09:00:00	vibration_mm_s	2.47	4.50
P201	2026-09-08 09:00:00	temp_c	39.80	60.00
P201	2026-09-08 09:00:00	vibration_mm_s	2.55	4.50
B101	2026-09-09 09:00:00	temp_c	39.24	60.00
B101	2026-09-09 09:00:00	vibration_mm_s	1.92	4.50
P101	2026-09-09 09:00:00	temp_c	45.82	60.00
P101	2026-09-09 09:00:00	vibration_mm_s	3.45	4.50
P103	2026-09-09 09:00:00	temp_c	40.85	60.00
P103	2026-09-09 09:00:00	vibration_mm_s	2.02	4.50
P201	2026-09-09 09:00:00	temp_c	41.15	60.00
P201	2026-09-09 09:00:00	vibration_mm_s	2.74	4.50
B101	2026-09-10 09:00:00	temp_c	44.43	60.00
B101	2026-09-10 09:00:00	vibration_mm_s	1.77	4.50
P101	2026-09-10 09:00:00	temp_c	45.71	60.00
P101	2026-09-10 09:00:00	vibration_mm_s	3.50	4.50
P103	2026-09-10 09:00:00	temp_c	45.01	60.00
P103	2026-09-10 09:00:00	vibration_mm_s	2.48	4.50
P201	2026-09-10 09:00:00	temp_c	37.24	60.00
P201	2026-09-10 09:00:00	vibration_mm_s	2.35	4.50
B101	2026-09-11 09:00:00	temp_c	39.85	60.00
B101	2026-09-11 09:00:00	vibration_mm_s	2.02	4.50
P101	2026-09-11 09:00:00	temp_c	43.51	60.00
P101	2026-09-11 09:00:00	vibration_mm_s	2.25	4.50
P103	2026-09-11 09:00:00	temp_c	44.49	60.00
P103	2026-09-11 09:00:00	vibration_mm_s	2.20	4.50
P201	2026-09-11 09:00:00	temp_c	42.55	60.00
P201	2026-09-11 09:00:00	vibration_mm_s	2.88	4.50
B101	2026-09-12 09:00:00	temp_c	39.26	60.00
B101	2026-09-12 09:00:00	vibration_mm_s	1.99	4.50
P101	2026-09-12 09:00:00	temp_c	40.03	60.00
P101	2026-09-12 09:00:00	vibration_mm_s	2.01	4.50
P103	2026-09-12 09:00:00	temp_c	41.95	60.00
P103	2026-09-12 09:00:00	vibration_mm_s	2.43	4.50
P201	2026-09-12 09:00:00	temp_c	42.67	60.00
P201	2026-09-12 09:00:00	vibration_mm_s	2.52	4.50
B101	2026-09-14 09:00:00	temp_c	42.67	60.00
B101	2026-09-14 09:00:00	vibration_mm_s	1.81	4.50
P101	2026-09-14 09:00:00	temp_c	38.89	60.00
P101	2026-09-14 09:00:00	vibration_mm_s	2.26	4.50
P103	2026-09-14 09:00:00	temp_c	44.41	60.00
P103	2026-09-14 09:00:00	vibration_mm_s	2.13	4.50
P201	2026-09-14 09:00:00	temp_c	43.25	60.00
P201	2026-09-14 09:00:00	vibration_mm_s	2.53	4.50
B101	2026-09-15 09:00:00	temp_c	39.39	60.00
B101	2026-09-15 09:00:00	vibration_mm_s	1.61	4.50
P101	2026-09-15 09:00:00	temp_c	43.46	60.00
P101	2026-09-15 09:00:00	vibration_mm_s	2.22	4.50
P103	2026-09-15 09:00:00	temp_c	39.02	60.00
P103	2026-09-15 09:00:00	vibration_mm_s	2.36	4.50
P201	2026-09-15 09:00:00	temp_c	39.34	60.00
P201	2026-09-15 09:00:00	vibration_mm_s	2.32	4.50
B101	2026-09-16 09:00:00	temp_c	43.03	60.00
B101	2026-09-16 09:00:00	vibration_mm_s	1.92	4.50
P101	2026-09-16 09:00:00	temp_c	38.96	60.00
P101	2026-09-16 09:00:00	vibration_mm_s	2.36	4.50
P103	2026-09-16 09:00:00	temp_c	40.86	60.00
P103	2026-09-16 09:00:00	vibration_mm_s	2.40	4.50
P201	2026-09-16 09:00:00	temp_c	37.86	60.00
P201	2026-09-16 09:00:00	vibration_mm_s	2.49	4.50
B101	2026-09-17 09:00:00	temp_c	45.22	60.00
B101	2026-09-17 09:00:00	vibration_mm_s	2.04	4.50
P101	2026-09-17 09:00:00	temp_c	44.17	60.00
P101	2026-09-17 09:00:00	vibration_mm_s	2.13	4.50
P103	2026-09-17 09:00:00	temp_c	44.41	60.00
P103	2026-09-17 09:00:00	vibration_mm_s	2.24	4.50
P201	2026-09-17 09:00:00	temp_c	44.27	60.00
P201	2026-09-17 09:00:00	vibration_mm_s	2.61	4.50
B101	2026-09-18 09:00:00	temp_c	38.82	60.00
B101	2026-09-18 09:00:00	vibration_mm_s	1.68	4.50
P101	2026-09-18 09:00:00	temp_c	40.06	60.00
P101	2026-09-18 09:00:00	vibration_mm_s	2.49	4.50
P103	2026-09-18 09:00:00	temp_c	38.51	60.00
P103	2026-09-18 09:00:00	vibration_mm_s	2.13	4.50
P201	2026-09-18 09:00:00	temp_c	43.81	60.00
P201	2026-09-18 09:00:00	vibration_mm_s	2.64	4.50
B101	2026-09-19 09:00:00	temp_c	42.54	60.00
B101	2026-09-19 09:00:00	vibration_mm_s	1.81	4.50
P101	2026-09-19 09:00:00	temp_c	44.31	60.00
P101	2026-09-19 09:00:00	vibration_mm_s	2.40	4.50
P103	2026-09-19 09:00:00	temp_c	41.36	60.00
P103	2026-09-19 09:00:00	vibration_mm_s	2.27	4.50
P201	2026-09-19 09:00:00	temp_c	41.20	60.00
P201	2026-09-19 09:00:00	vibration_mm_s	2.56	4.50
B101	2026-09-21 09:00:00	temp_c	41.62	60.00
B101	2026-09-21 09:00:00	vibration_mm_s	1.83	4.50
P101	2026-09-21 09:00:00	temp_c	42.51	60.00
P101	2026-09-21 09:00:00	vibration_mm_s	2.16	4.50
P103	2026-09-21 09:00:00	temp_c	38.78	60.00
P103	2026-09-21 09:00:00	vibration_mm_s	2.18	4.50
P201	2026-09-21 09:00:00	temp_c	40.62	60.00
P201	2026-09-21 09:00:00	vibration_mm_s	2.67	4.50
B101	2026-09-22 09:00:00	temp_c	42.64	60.00
B101	2026-09-22 09:00:00	vibration_mm_s	1.58	4.50
P101	2026-09-22 09:00:00	temp_c	43.09	60.00
P101	2026-09-22 09:00:00	vibration_mm_s	2.48	4.50
P103	2026-09-22 09:00:00	temp_c	38.97	60.00
P103	2026-09-22 09:00:00	vibration_mm_s	2.00	4.50
P201	2026-09-22 09:00:00	temp_c	45.16	60.00
P201	2026-09-22 09:00:00	vibration_mm_s	2.83	4.50
B101	2026-09-23 09:00:00	temp_c	42.51	60.00
B101	2026-09-23 09:00:00	vibration_mm_s	1.90	4.50
P101	2026-09-23 09:00:00	temp_c	44.02	60.00
P101	2026-09-23 09:00:00	vibration_mm_s	2.16	4.50
P103	2026-09-23 09:00:00	temp_c	37.64	60.00
P103	2026-09-23 09:00:00	vibration_mm_s	2.00	4.50
P201	2026-09-23 09:00:00	temp_c	38.64	60.00
P201	2026-09-23 09:00:00	vibration_mm_s	2.42	4.50
B101	2026-09-24 09:00:00	temp_c	38.51	60.00
B101	2026-09-24 09:00:00	vibration_mm_s	1.91	4.50
P101	2026-09-24 09:00:00	temp_c	43.77	60.00
P101	2026-09-24 09:00:00	vibration_mm_s	2.01	4.50
P103	2026-09-24 09:00:00	temp_c	42.39	60.00
P103	2026-09-24 09:00:00	vibration_mm_s	2.45	4.50
P201	2026-09-24 09:00:00	temp_c	42.62	60.00
P201	2026-09-24 09:00:00	vibration_mm_s	2.57	4.50
B101	2026-09-25 09:00:00	temp_c	38.20	60.00
B101	2026-09-25 09:00:00	vibration_mm_s	1.60	4.50
P101	2026-09-25 09:00:00	temp_c	41.96	60.00
P101	2026-09-25 09:00:00	vibration_mm_s	2.42	4.50
P103	2026-09-25 09:00:00	temp_c	44.21	60.00
P103	2026-09-25 09:00:00	vibration_mm_s	2.35	4.50
P201	2026-09-25 09:00:00	temp_c	44.32	60.00
P201	2026-09-25 09:00:00	vibration_mm_s	2.62	4.50
B101	2026-09-26 09:00:00	temp_c	39.30	60.00
B101	2026-09-26 09:00:00	vibration_mm_s	1.73	4.50
P101	2026-09-26 09:00:00	temp_c	43.88	60.00
P101	2026-09-26 09:00:00	vibration_mm_s	2.39	4.50
P103	2026-09-26 09:00:00	temp_c	40.00	60.00
P103	2026-09-26 09:00:00	vibration_mm_s	2.21	4.50
P201	2026-09-26 09:00:00	temp_c	41.50	60.00
P201	2026-09-26 09:00:00	vibration_mm_s	2.34	4.50
B101	2026-09-28 09:00:00	temp_c	43.99	60.00
B101	2026-09-28 09:00:00	vibration_mm_s	1.73	4.50
P101	2026-09-28 09:00:00	temp_c	37.75	60.00
P101	2026-09-28 09:00:00	vibration_mm_s	2.08	4.50
P103	2026-09-28 09:00:00	temp_c	39.57	60.00
P103	2026-09-28 09:00:00	vibration_mm_s	2.21	4.50
P201	2026-09-28 09:00:00	temp_c	40.67	60.00
P201	2026-09-28 09:00:00	vibration_mm_s	2.36	4.50
B101	2026-09-29 09:00:00	temp_c	39.30	60.00
B101	2026-09-29 09:00:00	vibration_mm_s	1.86	4.50
P101	2026-09-29 09:00:00	temp_c	37.84	60.00
P101	2026-09-29 09:00:00	vibration_mm_s	2.07	4.50
P103	2026-09-29 09:00:00	temp_c	40.98	60.00
P103	2026-09-29 09:00:00	vibration_mm_s	2.39	4.50
P201	2026-09-29 09:00:00	temp_c	41.59	60.00
P201	2026-09-29 09:00:00	vibration_mm_s	2.79	4.50
B101	2026-09-30 09:00:00	temp_c	41.05	60.00
B101	2026-09-30 09:00:00	vibration_mm_s	1.61	4.50
P101	2026-09-30 09:00:00	temp_c	43.24	60.00
P101	2026-09-30 09:00:00	vibration_mm_s	2.15	4.50
P103	2026-09-30 09:00:00	temp_c	43.46	60.00
P103	2026-09-30 09:00:00	vibration_mm_s	2.47	4.50
P201	2026-09-30 09:00:00	temp_c	39.63	60.00
P201	2026-09-30 09:00:00	vibration_mm_s	2.52	4.50
B101	2026-10-01 09:00:00	temp_c	44.05	60.00
B101	2026-10-01 09:00:00	vibration_mm_s	1.70	4.50
P101	2026-10-01 09:00:00	temp_c	42.28	60.00
P101	2026-10-01 09:00:00	vibration_mm_s	2.24	4.50
P103	2026-10-01 09:00:00	temp_c	38.40	60.00
P103	2026-10-01 09:00:00	vibration_mm_s	2.04	4.50
P201	2026-10-01 09:00:00	temp_c	48.72	60.00
P201	2026-10-01 09:00:00	vibration_mm_s	3.87	4.50
B101	2026-10-02 09:00:00	temp_c	39.72	60.00
B101	2026-10-02 09:00:00	vibration_mm_s	1.50	4.50
P101	2026-10-02 09:00:00	temp_c	40.92	60.00
P101	2026-10-02 09:00:00	vibration_mm_s	2.37	4.50
P103	2026-10-02 09:00:00	temp_c	40.86	60.00
P103	2026-10-02 09:00:00	vibration_mm_s	2.46	4.50
P201	2026-10-02 09:00:00	temp_c	50.75	60.00
P201	2026-10-02 09:00:00	vibration_mm_s	4.12	4.50
B101	2026-10-03 09:00:00	temp_c	43.34	60.00
B101	2026-10-03 09:00:00	vibration_mm_s	1.60	4.50
P101	2026-10-03 09:00:00	temp_c	44.71	60.00
P101	2026-10-03 09:00:00	vibration_mm_s	2.23	4.50
P103	2026-10-03 09:00:00	temp_c	41.36	60.00
P103	2026-10-03 09:00:00	vibration_mm_s	1.93	4.50
P201	2026-10-03 09:00:00	temp_c	41.11	60.00
P201	2026-10-03 09:00:00	vibration_mm_s	2.32	4.50
B101	2026-10-05 09:00:00	temp_c	43.72	60.00
B101	2026-10-05 09:00:00	vibration_mm_s	1.68	4.50
P101	2026-10-05 09:00:00	temp_c	39.71	60.00
P101	2026-10-05 09:00:00	vibration_mm_s	2.39	4.50
P103	2026-10-05 09:00:00	temp_c	41.39	60.00
P103	2026-10-05 09:00:00	vibration_mm_s	2.37	4.50
P201	2026-10-05 09:00:00	temp_c	41.44	60.00
P201	2026-10-05 09:00:00	vibration_mm_s	2.31	4.50
B101	2026-10-06 09:00:00	temp_c	42.85	60.00
B101	2026-10-06 09:00:00	vibration_mm_s	1.78	4.50
P101	2026-10-06 09:00:00	temp_c	43.60	60.00
P101	2026-10-06 09:00:00	vibration_mm_s	2.18	4.50
P103	2026-10-06 09:00:00	temp_c	40.32	60.00
P103	2026-10-06 09:00:00	vibration_mm_s	2.22	4.50
P201	2026-10-06 09:00:00	temp_c	39.06	60.00
P201	2026-10-06 09:00:00	vibration_mm_s	2.48	4.50
B101	2026-10-07 09:00:00	temp_c	43.06	60.00
B101	2026-10-07 09:00:00	vibration_mm_s	1.74	4.50
P101	2026-10-07 09:00:00	temp_c	40.60	60.00
P101	2026-10-07 09:00:00	vibration_mm_s	2.29	4.50
P103	2026-10-07 09:00:00	temp_c	42.28	60.00
P103	2026-10-07 09:00:00	vibration_mm_s	2.04	4.50
P201	2026-10-07 09:00:00	temp_c	41.55	60.00
P201	2026-10-07 09:00:00	vibration_mm_s	2.77	4.50
B101	2026-10-08 09:00:00	temp_c	42.61	60.00
B101	2026-10-08 09:00:00	vibration_mm_s	1.85	4.50
P101	2026-10-08 09:00:00	temp_c	48.08	60.00
P101	2026-10-08 09:00:00	vibration_mm_s	3.49	4.50
P103	2026-10-08 09:00:00	temp_c	43.40	60.00
P103	2026-10-08 09:00:00	vibration_mm_s	2.14	4.50
P201	2026-10-08 09:00:00	temp_c	43.86	60.00
P201	2026-10-08 09:00:00	vibration_mm_s	2.54	4.50
B101	2026-10-09 09:00:00	temp_c	40.80	60.00
B101	2026-10-09 09:00:00	vibration_mm_s	1.95	4.50
P101	2026-10-09 09:00:00	temp_c	48.27	60.00
P101	2026-10-09 09:00:00	vibration_mm_s	3.72	4.50
P103	2026-10-09 09:00:00	temp_c	40.09	60.00
P103	2026-10-09 09:00:00	vibration_mm_s	2.14	4.50
P201	2026-10-09 09:00:00	temp_c	38.13	60.00
P201	2026-10-09 09:00:00	vibration_mm_s	2.34	4.50
B101	2026-10-10 09:00:00	temp_c	40.43	60.00
B101	2026-10-10 09:00:00	vibration_mm_s	1.88	4.50
P101	2026-10-10 09:00:00	temp_c	44.31	60.00
P101	2026-10-10 09:00:00	vibration_mm_s	2.18	4.50
P103	2026-10-10 09:00:00	temp_c	38.36	60.00
P103	2026-10-10 09:00:00	vibration_mm_s	2.25	4.50
P201	2026-10-10 09:00:00	temp_c	43.65	60.00
P201	2026-10-10 09:00:00	vibration_mm_s	2.41	4.50
B101	2026-10-12 09:00:00	temp_c	41.14	60.00
B101	2026-10-12 09:00:00	vibration_mm_s	2.10	4.50
P101	2026-10-12 09:00:00	temp_c	39.59	60.00
P101	2026-10-12 09:00:00	vibration_mm_s	2.22	4.50
P103	2026-10-12 09:00:00	temp_c	38.63	60.00
P103	2026-10-12 09:00:00	vibration_mm_s	2.30	4.50
P201	2026-10-12 09:00:00	temp_c	39.16	60.00
P201	2026-10-12 09:00:00	vibration_mm_s	2.86	4.50
B101	2026-10-13 09:00:00	temp_c	39.43	60.00
B101	2026-10-13 09:00:00	vibration_mm_s	1.55	4.50
P101	2026-10-13 09:00:00	temp_c	37.00	60.00
P101	2026-10-13 09:00:00	vibration_mm_s	1.91	4.50
P103	2026-10-13 09:00:00	temp_c	40.05	60.00
P103	2026-10-13 09:00:00	vibration_mm_s	2.45	4.50
P201	2026-10-13 09:00:00	temp_c	39.88	60.00
P201	2026-10-13 09:00:00	vibration_mm_s	2.81	4.50
B101	2026-10-14 09:00:00	temp_c	38.73	60.00
B101	2026-10-14 09:00:00	vibration_mm_s	1.76	4.50
P101	2026-10-14 09:00:00	temp_c	41.61	60.00
P101	2026-10-14 09:00:00	vibration_mm_s	2.38	4.50
P103	2026-10-14 09:00:00	temp_c	38.77	60.00
P103	2026-10-14 09:00:00	vibration_mm_s	2.13	4.50
P201	2026-10-14 09:00:00	temp_c	39.66	60.00
P201	2026-10-14 09:00:00	vibration_mm_s	2.47	4.50
B101	2026-10-15 09:00:00	temp_c	39.02	60.00
B101	2026-10-15 09:00:00	vibration_mm_s	1.95	4.50
P101	2026-10-15 09:00:00	temp_c	42.38	60.00
P101	2026-10-15 09:00:00	vibration_mm_s	2.43	4.50
P103	2026-10-15 09:00:00	temp_c	38.73	60.00
P103	2026-10-15 09:00:00	vibration_mm_s	2.11	4.50
P201	2026-10-15 09:00:00	temp_c	39.55	60.00
P201	2026-10-15 09:00:00	vibration_mm_s	2.35	4.50
B101	2026-10-16 09:00:00	temp_c	42.25	60.00
B101	2026-10-16 09:00:00	vibration_mm_s	1.93	4.50
P101	2026-10-16 09:00:00	temp_c	40.66	60.00
P101	2026-10-16 09:00:00	vibration_mm_s	2.10	4.50
P103	2026-10-16 09:00:00	temp_c	44.21	60.00
P103	2026-10-16 09:00:00	vibration_mm_s	2.25	4.50
P201	2026-10-16 09:00:00	temp_c	40.64	60.00
P201	2026-10-16 09:00:00	vibration_mm_s	2.36	4.50
B101	2026-10-17 09:00:00	temp_c	39.79	60.00
B101	2026-10-17 09:00:00	vibration_mm_s	1.98	4.50
P101	2026-10-17 09:00:00	temp_c	39.36	60.00
P101	2026-10-17 09:00:00	vibration_mm_s	2.43	4.50
P103	2026-10-17 09:00:00	temp_c	41.29	60.00
P103	2026-10-17 09:00:00	vibration_mm_s	2.24	4.50
P201	2026-10-17 09:00:00	temp_c	41.60	60.00
P201	2026-10-17 09:00:00	vibration_mm_s	2.40	4.50
B101	2026-10-19 09:00:00	temp_c	44.62	60.00
B101	2026-10-19 09:00:00	vibration_mm_s	3.32	4.50
P101	2026-10-19 09:00:00	temp_c	42.43	60.00
P101	2026-10-19 09:00:00	vibration_mm_s	2.17	4.50
P103	2026-10-19 09:00:00	temp_c	44.49	60.00
P103	2026-10-19 09:00:00	vibration_mm_s	2.50	4.50
P201	2026-10-19 09:00:00	temp_c	42.55	60.00
P201	2026-10-19 09:00:00	vibration_mm_s	2.89	4.50
B101	2026-10-20 09:00:00	temp_c	42.12	60.00
B101	2026-10-20 09:00:00	vibration_mm_s	2.07	4.50
P101	2026-10-20 09:00:00	temp_c	42.61	60.00
P101	2026-10-20 09:00:00	vibration_mm_s	2.16	4.50
P103	2026-10-20 09:00:00	temp_c	42.02	60.00
P103	2026-10-20 09:00:00	vibration_mm_s	2.31	4.50
P201	2026-10-20 09:00:00	temp_c	44.48	60.00
P201	2026-10-20 09:00:00	vibration_mm_s	2.59	4.50
B101	2026-10-21 09:00:00	temp_c	44.40	60.00
B101	2026-10-21 09:00:00	vibration_mm_s	2.06	4.50
P101	2026-10-21 09:00:00	temp_c	39.30	60.00
P101	2026-10-21 09:00:00	vibration_mm_s	2.10	4.50
P103	2026-10-21 09:00:00	temp_c	38.52	60.00
P103	2026-10-21 09:00:00	vibration_mm_s	2.20	4.50
P201	2026-10-21 09:00:00	temp_c	40.26	60.00
P201	2026-10-21 09:00:00	vibration_mm_s	2.32	4.50
B101	2026-10-22 09:00:00	temp_c	43.56	60.00
B101	2026-10-22 09:00:00	vibration_mm_s	2.03	4.50
P101	2026-10-22 09:00:00	temp_c	41.56	60.00
P101	2026-10-22 09:00:00	vibration_mm_s	2.20	4.50
P103	2026-10-22 09:00:00	temp_c	42.96	60.00
P103	2026-10-22 09:00:00	vibration_mm_s	2.40	4.50
P201	2026-10-22 09:00:00	temp_c	42.47	60.00
P201	2026-10-22 09:00:00	vibration_mm_s	2.66	4.50
B101	2026-10-23 09:00:00	temp_c	42.23	60.00
B101	2026-10-23 09:00:00	vibration_mm_s	1.68	4.50
P101	2026-10-23 09:00:00	temp_c	39.88	60.00
P101	2026-10-23 09:00:00	vibration_mm_s	2.00	4.50
P103	2026-10-23 09:00:00	temp_c	41.02	60.00
P103	2026-10-23 09:00:00	vibration_mm_s	2.10	4.50
P201	2026-10-23 09:00:00	temp_c	43.68	60.00
P201	2026-10-23 09:00:00	vibration_mm_s	2.42	4.50
B101	2026-10-24 09:00:00	temp_c	45.71	60.00
B101	2026-10-24 09:00:00	vibration_mm_s	2.02	4.50
P101	2026-10-24 09:00:00	temp_c	38.67	60.00
P101	2026-10-24 09:00:00	vibration_mm_s	2.30	4.50
P103	2026-10-24 09:00:00	temp_c	40.48	60.00
P103	2026-10-24 09:00:00	vibration_mm_s	2.30	4.50
P201	2026-10-24 09:00:00	temp_c	43.79	60.00
P201	2026-10-24 09:00:00	vibration_mm_s	2.47	4.50
B101	2026-10-26 09:00:00	temp_c	43.92	60.00
B101	2026-10-26 09:00:00	vibration_mm_s	1.58	4.50
P101	2026-10-26 09:00:00	temp_c	45.72	60.00
P101	2026-10-26 09:00:00	vibration_mm_s	2.40	4.50
P103	2026-10-26 09:00:00	temp_c	47.00	60.00
P103	2026-10-26 09:00:00	vibration_mm_s	3.10	4.50
P201	2026-10-26 09:00:00	temp_c	39.10	60.00
P201	2026-10-26 09:00:00	vibration_mm_s	2.58	4.50
B101	2026-10-27 09:00:00	temp_c	42.76	60.00
B101	2026-10-27 09:00:00	vibration_mm_s	1.86	4.50
P101	2026-10-27 09:00:00	temp_c	41.24	60.00
P101	2026-10-27 09:00:00	vibration_mm_s	2.30	4.50
P103	2026-10-27 09:00:00	temp_c	44.95	60.00
P103	2026-10-27 09:00:00	vibration_mm_s	3.80	4.50
P201	2026-10-27 09:00:00	temp_c	44.71	60.00
P201	2026-10-27 09:00:00	vibration_mm_s	2.64	4.50
B101	2026-10-28 09:00:00	temp_c	41.58	60.00
B101	2026-10-28 09:00:00	vibration_mm_s	1.97	4.50
P101	2026-10-28 09:00:00	temp_c	42.72	60.00
P101	2026-10-28 09:00:00	vibration_mm_s	2.20	4.50
P103	2026-10-28 09:00:00	temp_c	52.07	60.00
P103	2026-10-28 09:00:00	vibration_mm_s	4.90	4.50
P201	2026-10-28 09:00:00	temp_c	43.12	60.00
P201	2026-10-28 09:00:00	vibration_mm_s	2.78	4.50
B101	2026-10-29 09:00:00	temp_c	39.71	60.00
B101	2026-10-29 09:00:00	vibration_mm_s	1.72	4.50
P101	2026-10-29 09:00:00	temp_c	39.02	60.00
P101	2026-10-29 09:00:00	vibration_mm_s	2.40	4.50
P103	2026-10-29 09:00:00	temp_c	55.47	60.00
P103	2026-10-29 09:00:00	vibration_mm_s	5.70	4.50
P201	2026-10-29 09:00:00	temp_c	42.41	60.00
P201	2026-10-29 09:00:00	vibration_mm_s	2.81	4.50
P103	2026-10-30 02:50:00	vibration_mm_s	6.40	4.50
B101	2026-10-30 09:00:00	temp_c	39.73	60.00
B101	2026-10-30 09:00:00	vibration_mm_s	1.84	4.50
P101	2026-10-30 09:00:00	temp_c	42.08	60.00
P101	2026-10-30 09:00:00	vibration_mm_s	2.06	4.50
P201	2026-10-30 09:00:00	temp_c	38.52	60.00
P201	2026-10-30 09:00:00	vibration_mm_s	2.39	4.50
\.


--
-- Data for Name: work_order; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.work_order (wo_no, product_code, die_drawing_code, status, priority, line, planned_qty, produced_qty, defect_qty, planned_start, planned_end, actual_start, actual_end, so_ref) FROM stdin;
JB2608-0101	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1500	1500	27	2026-08-03	2026-08-03	2026-08-03 08:00:00	2026-08-03 20:19:00	2607001
JB2608-0102	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	2000	2000	29	2026-08-03	2026-08-04	2026-08-03 09:58:00	2026-08-04 00:45:00	2607001
JB2608-0103	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	3500	3500	72	2026-08-04	2026-08-05	2026-08-04 08:00:00	2026-08-05 09:31:00	2607002
JB2608-0104	PNL-410	DWG-PNL-410	완료	낮음	2라인	3500	3500	64	2026-08-04	2026-08-05	2026-08-04 12:22:00	2026-08-05 22:26:00	2607003
JB2608-0105	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	2000	2000	52	2026-08-04	2026-08-05	2026-08-04 15:52:00	2026-08-05 11:58:00	2607004
JB2608-0106	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	4000	4000	71	2026-08-05	2026-08-06	2026-08-05 09:31:00	2026-08-06 13:57:00	2607004
JB2608-0107	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	2500	2500	37	2026-08-05	2026-08-06	2026-08-05 23:58:00	2026-08-06 22:13:00	2608001
JB2608-0108	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1000	1000	14	2026-08-06	2026-08-07	2026-08-06 20:00:00	2026-08-07 03:01:00	2608001
JB2608-0109	BRK-210L	DWG-BRK-210L	완료	낮음	1라인	1000	1000	22	2026-08-07	2026-08-07	2026-08-07 09:01:00	2026-08-07 15:07:00	2607005
JB2608-0110	BRK-210R	DWG-BRK-210R	완료	보통	1라인	1000	1000	20	2026-08-07	2026-08-07	2026-08-07 10:13:00	2026-08-07 16:24:00	OD-2608-002
JB2608-0111	RNF-320L	DWG-RNF-320L	완료	보통	1라인	3000	3000	62	2026-08-07	2026-08-08	2026-08-07 15:07:00	2026-08-08 14:14:00	2608003
JB2608-0112	RNF-320R	DWG-RNF-320R	완료	보통	1라인	3500	3500	80	2026-08-07	2026-08-10	2026-08-07 21:35:00	2026-08-10 10:07:00	2608003
JB2608-0113	RNF-320L	DWG-RNF-320L	완료	보통	1라인	3000	3000	72	2026-08-08	2026-08-11	2026-08-08 14:14:00	2026-08-11 00:51:00	OD-2607-006
JB2608-0114	RNF-320L	DWG-RNF-320L	완료	보통	1라인	2000	2000	44	2026-08-10	2026-08-11	2026-08-10 10:07:00	2026-08-11 00:54:00	2608004
JB2608-0115	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1000	1000	23	2026-08-11	2026-08-11	2026-08-11 08:00:00	2026-08-11 14:30:00	2608004
JB2608-0116	RNF-320L	DWG-RNF-320L	완료	보통	1라인	3500	3500	86	2026-08-11	2026-08-12	2026-08-11 09:17:00	2026-08-12 11:18:00	2608005
JB2608-0117	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	1000	1000	21	2026-08-12	2026-08-12	2026-08-12 02:30:00	2026-08-12 12:05:00	2608005
JB2608-0118	BRK-210L	DWG-BRK-210L	완료	보통	1라인	2000	2000	50	2026-08-12	2026-08-13	2026-08-12 12:05:00	2026-08-13 02:20:00	2608006
JB2608-0119	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	4500	4500	99	2026-08-12	2026-08-14	2026-08-12 20:00:00	2026-08-14 00:43:00	2608006
JB2608-0120	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3500	3500	58	2026-08-13	2026-08-14	2026-08-13 02:20:00	2026-08-14 02:57:00	2608007
JB2608-0121	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	3000	3000	67	2026-08-14	2026-08-14	2026-08-14 00:43:00	2026-08-14 22:05:00	2608007
JB2608-0122	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	2000	2000	47	2026-08-14	2026-08-15	2026-08-14 14:57:00	2026-08-15 08:28:00	2608008
JB2608-0123	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1500	1500	31	2026-08-14	2026-08-15	2026-08-14 22:05:00	2026-08-15 10:31:00	2608008
JB2608-0124	BRK-210L	DWG-BRK-210L	완료	보통	1라인	2500	2500	45	2026-08-15	2026-08-17	2026-08-15 14:28:00	2026-08-17 21:42:00	2608009
JB2608-0125	BRK-210R	DWG-BRK-210R	완료	보통	1라인	3000	3000	44	2026-08-17	2026-08-18	2026-08-17 08:10:00	2026-08-18 03:54:00	2608009
JB2608-0126	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	2500	2500	45	2026-08-18	2026-08-19	2026-08-18 03:42:00	2026-08-19 00:40:00	2608010
JB2608-0127	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	2000	2000	46	2026-08-18	2026-08-19	2026-08-18 09:55:00	2026-08-19 00:25:00	2608010
JB2608-0128	RNF-320L	DWG-RNF-320L	완료	보통	1라인	2000	2000	53	2026-08-19	2026-08-19	2026-08-19 00:25:00	2026-08-19 14:53:00	2608011
JB2608-0129	RNF-320R	DWG-RNF-320R	완료	보통	1라인	4000	4000	75	2026-08-19	2026-08-20	2026-08-19 08:00:00	2026-08-20 11:44:00	2608011
JB2608-0130	PNL-410	DWG-PNL-410	완료	낮음	2라인	2000	2000	55	2026-08-19	2026-08-20	2026-08-19 13:03:00	2026-08-20 09:54:00	2608012
JB2608-0131	RNF-320L	DWG-RNF-320L	완료	보통	1라인	3000	3000	56	2026-08-20	2026-08-21	2026-08-20 02:53:00	2026-08-21 01:32:00	2608013
JB2608-0132	RNF-320R	DWG-RNF-320R	완료	보통	1라인	4500	4500	112	2026-08-20	2026-08-22	2026-08-20 23:44:00	2026-08-22 08:52:00	2608013
JB2608-0133	PNL-410	DWG-PNL-410	완료	보통	2라인	3000	3000	86	2026-08-21	2026-08-22	2026-08-21 04:51:00	2026-08-22 11:06:00	OD-2608-014
JB2608-0134	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1500	1500	31	2026-08-21	2026-08-21	2026-08-21 11:01:00	2026-08-21 23:24:00	2608015
JB2608-0135	RNF-320L	DWG-RNF-320L	완료	보통	1라인	2000	2000	47	2026-08-22	2026-08-24	2026-08-22 08:00:00	2026-08-24 11:09:00	2608016
JB2608-0136	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	1000	1000	17	2026-08-22	2026-08-24	2026-08-22 10:30:00	2026-08-24 08:10:00	\N
JB2608-0137	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	4000	4000	80	2026-08-24	2026-08-25	2026-08-24 08:10:00	2026-08-25 11:58:00	OD-2608-017
JB2608-0138	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	2500	2500	41	2026-08-24	2026-08-25	2026-08-24 23:09:00	2026-08-25 15:51:00	2608017
JB2608-0139	BRK-210L	DWG-BRK-210L	완료	낮음	1라인	2500	2500	60	2026-08-25	2026-08-26	2026-08-25 21:51:00	2026-08-26 14:02:00	\N
JB2608-0140	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1000	1000	17	2026-08-26	2026-08-26	2026-08-26 00:28:00	2026-08-26 09:51:00	2608019
JB2608-0141	RNF-320R	DWG-RNF-320R	완료	보통	1라인	3500	3500	81	2026-08-26	2026-08-27	2026-08-26 20:02:00	2026-08-27 22:32:00	2608019
JB2608-0142	RNF-320L	DWG-RNF-320L	완료	보통	1라인	3000	3000	55	2026-08-27	2026-08-27	2026-08-27 00:09:00	2026-08-27 23:10:00	2608020
JB2608-0143	PNL-410	DWG-PNL-410	완료	보통	2라인	1000	1000	21	2026-08-27	2026-08-27	2026-08-27 03:59:00	2026-08-27 15:03:00	2608021
JB2608-0144	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	2000	2000	27	2026-08-28	2026-08-28	2026-08-28 04:32:00	2026-08-28 22:25:00	2608022
JB2608-0145	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1000	1000	31	2026-08-28	2026-08-28	2026-08-28 09:53:00	2026-08-28 16:38:00	2608022
JB2608-0146	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4500	4500	100	2026-08-28	2026-08-31	2026-08-28 22:25:00	2026-08-31 15:52:00	2608023
JB2608-0147	BRK-210R	DWG-BRK-210R	완료	보통	1라인	2000	2000	44	2026-08-29	2026-08-31	2026-08-29 04:38:00	2026-08-31 10:35:00	2608024
JB2608-0148	BRK-210L	DWG-BRK-210L	완료	보통	1라인	1500	1500	31	2026-08-31	2026-09-01	2026-08-31 15:52:00	2026-09-01 03:41:00	2608025
JB2608-0149	BRK-210R	DWG-BRK-210R	완료	보통	1라인	1000	1000	26	2026-08-31	2026-09-01	2026-08-31 22:35:00	2026-09-01 04:58:00	2608025
JB2608-0150	PNL-410	DWG-PNL-410	완료	긴급	2라인	3000	3000	100	2026-08-31	2026-09-02	2026-08-31 23:46:00	2026-09-02 03:18:00	2608026
JB2609-0101	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3500	3500	75	2026-09-01	2026-09-02	2026-09-01 04:58:00	2026-09-02 04:46:00	2608027
JB2609-0102	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	1500	1500	35	2026-09-01	2026-09-01	2026-09-01 11:30:00	2026-09-01 23:17:00	2608027
JB2609-0103	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3000	3000	77	2026-09-02	2026-09-03	2026-09-02 11:17:00	2026-09-03 09:13:00	2608028
JB2609-0104	BRK-210R	DWG-BRK-210R	완료	보통	1라인	2000	2000	38	2026-09-02	2026-09-03	2026-09-02 16:46:00	2026-09-03 09:57:00	2608028
JB2609-0105	BRK-210L	DWG-BRK-210L	완료	보통	1라인	2500	2500	46	2026-09-03	2026-09-04	2026-09-03 21:13:00	2026-09-04 14:24:00	2608029
JB2609-0106	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	2500	2500	74	2026-09-04	2026-09-04	2026-09-04 00:08:00	2026-09-04 16:00:00	\N
JB2609-0107	BRK-210L	DWG-BRK-210L	완료	보통	1라인	4500	4500	96	2026-09-04	2026-09-07	2026-09-04 22:00:00	2026-09-07 13:17:00	2608030
JB2609-0108	BRK-210R	DWG-BRK-210R	완료	보통	1라인	3500	3500	79	2026-09-05	2026-09-07	2026-09-05 02:24:00	2026-09-07 14:44:00	OD-2608-030
JB2609-0109	RNF-320L	DWG-RNF-320L	완료	보통	1라인	4000	4000	81	2026-09-07	2026-09-09	2026-09-07 20:44:00	2026-09-09 00:44:00	2608031
JB2609-0110	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1500	1500	25	2026-09-08	2026-09-08	2026-09-08 01:38:00	2026-09-08 14:16:00	2608031
JB2609-0111	BRK-210L	DWG-BRK-210L	완료	낮음	1라인	2000	2000	40	2026-09-09	2026-09-09	2026-09-09 02:16:00	2026-09-09 16:31:00	OD-2608-032
JB2609-0112	BRK-210R	DWG-BRK-210R	완료	보통	1라인	2000	2000	41	2026-09-09	2026-09-09	2026-09-09 08:00:00	2026-09-09 22:35:00	2608032
JB2609-0113	BRK-210L	DWG-BRK-210L	완료	낮음	1라인	3500	3500	87	2026-09-09	2026-09-10	2026-09-09 16:31:00	2026-09-10 16:22:00	2609001
JB2609-0114	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	3500	3500	51	2026-09-10	2026-09-11	2026-09-10 04:35:00	2026-09-11 09:50:00	OD-2609-001
JB2609-0115	BRK-210L	DWG-BRK-210L	완료	보통	1라인	4500	4500	103	2026-09-10	2026-09-12	2026-09-10 22:22:00	2026-09-12 02:57:00	OD-2609-002
JB2609-0116	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	2500	2500	43	2026-09-11	2026-09-12	2026-09-11 09:50:00	2026-09-12 02:35:00	2609002
JB2609-0117	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	2500	2500	63	2026-09-12	2026-09-14	2026-09-12 08:35:00	2026-09-14 14:11:00	2609003
JB2609-0118	RNF-320R	DWG-RNF-320R	완료	보통	1라인	3500	3500	67	2026-09-12	2026-09-15	2026-09-12 11:25:00	2026-09-15 00:36:00	2609003
JB2609-0119	RNF-320L	DWG-RNF-320L	완료	보통	1라인	4500	4500	66	2026-09-14	2026-09-16	2026-09-14 14:11:00	2026-09-16 00:10:00	2609004
JB2609-0120	RNF-320R	DWG-RNF-320R	완료	보통	1라인	4500	4500	84	2026-09-15	2026-09-16	2026-09-15 00:36:00	2026-09-16 11:52:00	2609004
JB2609-0121	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	4500	4500	123	2026-09-16	2026-09-17	2026-09-16 00:10:00	2026-09-17 08:36:00	2609005
JB2609-0122	RNF-320L	DWG-RNF-320L	완료	보통	1라인	1000	1000	19	2026-09-16	2026-09-17	2026-09-16 23:52:00	2026-09-17 09:39:00	2609006
JB2609-0123	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	4500	4500	112	2026-09-17	2026-09-19	2026-09-17 20:36:00	2026-09-19 02:12:00	2609006
JB2609-0124	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	2000	2000	41	2026-09-18	2026-09-18	2026-09-18 01:42:00	2026-09-18 15:56:00	2609007
JB2609-0125	PNL-410	DWG-PNL-410	완료	낮음	2라인	3500	3500	129	2026-09-18	2026-09-19	2026-09-18 04:05:00	2026-09-19 12:59:00	2609008
JB2609-0126	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	2000	2000	37	2026-09-18	2026-09-19	2026-09-18 15:56:00	2026-09-19 08:55:00	2609009
JB2609-0127	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4500	4500	98	2026-09-19	2026-09-22	2026-09-19 14:12:00	2026-09-22 11:26:00	2609009
JB2609-0128	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4500	4500	110	2026-09-21	2026-09-22	2026-09-21 10:08:00	2026-09-22 14:35:00	2609010
JB2609-0129	PNL-410	DWG-PNL-410	완료	긴급	2라인	2500	2500	77	2026-09-21	2026-09-22	2026-09-21 14:39:00	2026-09-22 13:53:00	2609011
JB2609-0130	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	4500	4500	100	2026-09-22	2026-09-23	2026-09-22 14:35:00	2026-09-23 22:59:00	2609012
JB2609-0131	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	2500	2500	61	2026-09-22	2026-09-23	2026-09-22 21:55:00	2026-09-23 14:34:00	2609012
JB2609-0132	PNL-410	DWG-PNL-410	완료	보통	2라인	1500	1500	49	2026-09-23	2026-09-23	2026-09-23 00:48:00	2026-09-23 15:13:00	2609013
JB2609-0133	RNF-320L	DWG-RNF-320L	완료	보통	1라인	2500	2500	56	2026-09-23	2026-09-24	2026-09-23 14:34:00	2026-09-24 10:50:00	2609014
JB2609-0134	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	4000	4000	85	2026-09-24	2026-09-25	2026-09-24 04:59:00	2026-09-25 12:04:00	2609014
JB2609-0135	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	2500	2500	29	2026-09-24	2026-09-25	2026-09-24 12:53:00	2026-09-25 08:14:00	OD-2609-015
JB2609-0136	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	1500	1500	21	2026-09-25	2026-09-25	2026-09-25 08:14:00	2026-09-25 16:31:00	2609015
JB2609-0137	PNL-410	DWG-PNL-410	완료	보통	2라인	2000	2000	55	2026-09-25	2026-09-26	2026-09-25 09:52:00	2026-09-26 03:23:00	2609016
JB2609-0138	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	2500	2500	72	2026-09-25	2026-09-26	2026-09-25 22:31:00	2026-09-26 15:15:00	2609017
JB2609-0139	BRK-210R	DWG-BRK-210R	완료	보통	1라인	1000	1000	20	2026-09-26	2026-09-26	2026-09-26 01:23:00	2026-09-26 10:34:00	2609017
JB2609-0140	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	3500	3500	69	2026-09-28	2026-09-29	2026-09-28 08:00:00	2026-09-29 09:39:00	2609018
JB2609-0141	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	1000	1000	18	2026-09-28	2026-09-28	2026-09-28 12:27:00	2026-09-28 21:56:00	2609971
JB2609-0142	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3000	3000	58	2026-09-28	2026-09-29	2026-09-28 21:56:00	2026-09-29 16:24:00	2609019
JB2609-0143	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	4500	4500	97	2026-09-29	2026-09-30	2026-09-29 15:39:00	2026-09-30 23:08:00	2609019
JB2609-0144	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3500	3500	48	2026-09-30	2026-10-01	2026-09-30 04:24:00	2026-10-01 03:59:00	OD-2609-020
JB2609-0145	BRK-210R	DWG-BRK-210R	완료	보통	1라인	1500	1500	33	2026-09-30	2026-10-01	2026-09-30 23:08:00	2026-10-01 11:27:00	2609020
JB2610-0101	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	2000	2000	24	2026-10-01	2026-10-02	2026-10-01 09:59:00	2026-10-02 00:28:00	2609021
JB2610-0102	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	1500	1500	37	2026-10-01	2026-10-02	2026-10-01 12:17:00	2026-10-02 00:05:00	2609021
JB2610-0103	RNF-320L	DWG-RNF-320L	완료	보통	1라인	4000	4000	93	2026-10-02	2026-10-03	2026-10-02 00:05:00	2026-10-03 03:46:00	2609970
JB2610-0104	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	3500	3500	49	2026-10-02	2026-10-03	2026-10-02 08:00:00	2026-10-03 09:27:00	2609022
JB2610-0105	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1500	1500	40	2026-10-03	2026-10-05	2026-10-03 09:27:00	2026-10-05 09:32:00	2609023
JB2610-0106	RNF-320R	DWG-RNF-320R	완료	보통	1라인	1500	1500	45	2026-10-03	2026-10-05	2026-10-03 15:46:00	2026-10-05 15:55:00	2609023
JB2610-0107	PNL-410	DWG-PNL-410	완료	보통	2라인	1000	1000	35	2026-10-05	2026-10-05	2026-10-05 08:41:00	2026-10-05 16:50:00	2610001
JB2610-0108	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3000	3000	62	2026-10-05	2026-10-06	2026-10-05 15:55:00	2026-10-06 13:13:00	2610002
JB2610-0109	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4000	4000	79	2026-10-05	2026-10-07	2026-10-05 21:56:00	2026-10-07 00:11:00	2610002
JB2610-0110	PNL-410	DWG-PNL-410	완료	보통	2라인	2500	2500	80	2026-10-06	2026-10-07	2026-10-06 01:55:00	2026-10-07 00:57:00	2609024
JB2610-0111	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3500	3500	98	2026-10-06	2026-10-07	2026-10-06 13:13:00	2026-10-07 13:09:00	2609025
JB2610-0112	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	2000	2000	67	2026-10-07	2026-10-08	2026-10-07 12:11:00	2026-10-08 02:03:00	2609025
JB2610-0113	BRK-210L	DWG-BRK-210L	완료	낮음	1라인	4000	4000	57	2026-10-07	2026-10-08	2026-10-07 14:16:00	2026-10-08 20:56:00	2610003
JB2610-0114	BRK-210R	DWG-BRK-210R	완료	보통	1라인	3500	3500	95	2026-10-08	2026-10-09	2026-10-08 08:03:00	2026-10-09 04:39:00	2610003
JB2610-0115	RNF-320L	DWG-RNF-320L	완료	보통	1라인	4500	4500	103	2026-10-08	2026-10-10	2026-10-08 20:56:00	2026-10-10 02:09:00	2610004
JB2610-0116	RNF-320R	DWG-RNF-320R	완료	긴급	1라인	2500	2500	47	2026-10-09	2026-10-10	2026-10-09 10:39:00	2026-10-10 04:36:00	2610005
JB2610-0117	PNL-410	DWG-PNL-410	완료	보통	2라인	2000	2000	56	2026-10-09	2026-10-10	2026-10-09 13:46:00	2026-10-10 10:07:00	2609026
JB2610-0118	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1500	1500	22	2026-10-10	2026-10-12	2026-10-10 04:36:00	2026-10-12 08:08:00	2609027
JB2610-0119	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	2500	2500	49	2026-10-10	2026-10-12	2026-10-10 09:40:00	2026-10-12 15:11:00	2609027
JB2610-0120	BRK-210L	DWG-BRK-210L	완료	보통	1라인	2500	2500	41	2026-10-12	2026-10-13	2026-10-12 08:08:00	2026-10-13 00:08:00	OD-2610-006
JB2610-0121	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	4500	4500	84	2026-10-13	2026-10-14	2026-10-13 00:08:00	2026-10-14 08:32:00	2610006
JB2610-0122	PNL-410	DWG-PNL-410	완료	보통	2라인	3000	3000	88	2026-10-13	2026-10-14	2026-10-13 04:45:00	2026-10-14 10:07:00	2610007
JB2610-0123	RNF-320L	DWG-RNF-320L	완료	낮음	1라인	1500	1500	24	2026-10-13	2026-10-13	2026-10-13 10:39:00	2026-10-13 23:01:00	OD-2610-008
JB2610-0124	RNF-320R	DWG-RNF-320R	완료	낮음	1라인	2000	2000	42	2026-10-14	2026-10-15	2026-10-14 11:01:00	2026-10-15 01:32:00	2610008
JB2610-0125	BRK-210L	DWG-BRK-210L	완료	보통	1라인	1500	1500	22	2026-10-14	2026-10-15	2026-10-14 14:32:00	2026-10-15 01:53:00	2610009
JB2610-0126	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	4000	4000	71	2026-10-15	2026-10-16	2026-10-15 08:00:00	2026-10-16 11:42:00	2610009
JB2610-0127	RNF-320L	DWG-RNF-320L	완료	긴급	1라인	1000	1000	27	2026-10-15	2026-10-15	2026-10-15 13:53:00	2026-10-15 23:38:00	2610010
JB2610-0128	BRK-210L	DWG-BRK-210L	완료	긴급	1라인	3500	3500	66	2026-10-15	2026-10-17	2026-10-15 23:38:00	2026-10-17 00:53:00	2610011
JB2610-0129	BRK-210L	DWG-BRK-210L	완료	보통	1라인	1000	1000	30	2026-10-16	2026-10-16	2026-10-16 11:42:00	2026-10-16 20:48:00	2610012
JB2610-0130	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4000	4000	84	2026-10-17	2026-10-19	2026-10-17 08:00:00	2026-10-19 21:56:00	2610012
JB2610-0131	BRK-210L	DWG-BRK-210L	완료	보통	1라인	4000	4000	103	2026-10-17	2026-10-20	2026-10-17 12:07:00	2026-10-20 02:25:00	2610013
JB2610-0132	BRK-210R	DWG-BRK-210R	완료	보통	1라인	4500	4500	99	2026-10-20	2026-10-21	2026-10-20 03:56:00	2026-10-21 12:30:00	2610013
JB2610-0133	BRK-210L	DWG-BRK-210L	완료	보통	1라인	4500	4500	82	2026-10-20	2026-10-21	2026-10-20 11:23:00	2026-10-21 20:06:00	2610014
JB2610-0134	BRK-210R	DWG-BRK-210R	완료	긴급	1라인	1000	1000	21	2026-10-21	2026-10-22	2026-10-21 20:00:00	2026-10-22 02:11:00	2610014
JB2610-0135	RNF-320R	DWG-RNF-320R	완료	보통	1라인	3000	3000	47	2026-10-22	2026-10-23	2026-10-22 08:06:00	2026-10-23 04:07:00	2610015
JB2610-0136	BRK-210L	DWG-BRK-210L	완료	보통	1라인	2000	2000	44	2026-10-22	2026-10-23	2026-10-22 11:56:00	2026-10-23 01:53:00	2610016
JB2610-0137	BRK-210R	DWG-BRK-210R	완료	낮음	1라인	1500	1500	31	2026-10-23	2026-10-23	2026-10-23 04:07:00	2026-10-23 15:47:00	2610016
JB2610-0139	BRK-210L	DWG-BRK-210L	완료	보통	1라인	3000	3000	54	2026-10-23	2026-10-24	2026-10-23 13:53:00	2026-10-24 11:03:00	2610017
JB2610-0140	BRK-210R	DWG-BRK-210R	완료	보통	1라인	2500	2500	69	2026-10-23	2026-10-24	2026-10-23 16:55:00	2026-10-24 12:18:00	2610017
JB2610-0138	BRK-210L	DWG-BRK-210L	진행	낮음	1라인	6000	3600	52	2026-10-29	2026-10-31	2026-10-29 08:00:00	\N	2610021
JB2610-0141	BRK-210R	DWG-BRK-210R	진행	보통	1라인	5000	1800	64	2026-10-28	2026-10-31	2026-10-28 08:00:00	\N	2610021
JB2610-0145	BRK-210L	DWG-BRK-210L	대기	보통	1라인	3000	0	0	2026-11-02	2026-11-03	\N	\N	2610019
JB2610-0142	PNL-410	DWG-PNL-410	진행	낮음	2라인	4000	0	83	2026-10-28	2026-10-31	2026-10-28 12:57:00	\N	2610018
JB2610-0143	PNL-410	DWG-PNL-410	대기	보통	2라인	3000	0	0	2026-11-02	2026-11-03	\N	\N	2610020
\.


--
-- Data for Name: work_order_operation; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.work_order_operation (wo_no, op_seq, equipment_code, status, remaining_qty) FROM stdin;
JB2608-0101	10	B101	완료	0
JB2608-0101	20	P101	완료	0
JB2608-0101	30	P101	완료	0
JB2608-0101	40	I101	완료	0
JB2608-0102	10	B101	완료	0
JB2608-0102	20	P103	완료	0
JB2608-0102	30	P103	완료	0
JB2608-0102	40	I101	완료	0
JB2608-0103	10	B101	완료	0
JB2608-0103	20	P103	완료	0
JB2608-0103	30	P103	완료	0
JB2608-0103	40	I101	완료	0
JB2608-0104	10	B101	완료	0
JB2608-0104	20	P201	완료	0
JB2608-0104	30	P201	완료	0
JB2608-0104	40	W201	완료	0
JB2608-0104	50	I101	완료	0
JB2608-0105	10	B101	완료	0
JB2608-0105	20	P101	완료	0
JB2608-0105	30	P101	완료	0
JB2608-0105	40	I101	완료	0
JB2608-0106	10	B101	완료	0
JB2608-0106	20	P103	완료	0
JB2608-0106	30	P103	완료	0
JB2608-0106	40	I101	완료	0
JB2608-0107	10	B101	완료	0
JB2608-0107	20	P101	완료	0
JB2608-0107	30	P101	완료	0
JB2608-0107	40	I101	완료	0
JB2608-0108	10	B101	완료	0
JB2608-0108	20	P103	완료	0
JB2608-0108	30	P103	완료	0
JB2608-0108	40	I101	완료	0
JB2608-0109	10	B101	완료	0
JB2608-0109	20	P103	완료	0
JB2608-0109	30	P103	완료	0
JB2608-0109	40	I101	완료	0
JB2608-0110	10	B101	완료	0
JB2608-0110	20	P101	완료	0
JB2608-0110	30	P101	완료	0
JB2608-0110	40	I101	완료	0
JB2608-0111	10	B101	완료	0
JB2608-0111	20	P103	완료	0
JB2608-0111	30	P103	완료	0
JB2608-0111	40	I101	완료	0
JB2608-0112	10	B101	완료	0
JB2608-0112	20	P101	완료	0
JB2608-0112	30	P101	완료	0
JB2608-0112	40	I101	완료	0
JB2608-0113	10	B101	완료	0
JB2608-0113	20	P103	완료	0
JB2608-0113	30	P103	완료	0
JB2608-0113	40	I101	완료	0
JB2608-0114	10	B101	완료	0
JB2608-0114	20	P101	완료	0
JB2608-0114	30	P101	완료	0
JB2608-0114	40	I101	완료	0
JB2608-0115	10	B101	완료	0
JB2608-0115	20	P103	완료	0
JB2608-0115	30	P103	완료	0
JB2608-0115	40	I101	완료	0
JB2608-0116	10	B101	완료	0
JB2608-0116	20	P101	완료	0
JB2608-0116	30	P101	완료	0
JB2608-0116	40	I101	완료	0
JB2608-0117	10	B101	완료	0
JB2608-0117	20	P103	완료	0
JB2608-0117	30	P103	완료	0
JB2608-0117	40	I101	완료	0
JB2608-0118	10	B101	완료	0
JB2608-0118	20	P103	완료	0
JB2608-0118	30	P103	완료	0
JB2608-0118	40	I101	완료	0
JB2608-0119	10	B101	완료	0
JB2608-0119	20	P101	완료	0
JB2608-0119	30	P101	완료	0
JB2608-0119	40	I101	완료	0
JB2608-0120	10	B101	완료	0
JB2608-0120	20	P103	완료	0
JB2608-0120	30	P103	완료	0
JB2608-0120	40	I101	완료	0
JB2608-0121	10	B101	완료	0
JB2608-0121	20	P101	완료	0
JB2608-0121	30	P101	완료	0
JB2608-0121	40	I101	완료	0
JB2608-0122	10	B101	완료	0
JB2608-0122	20	P103	완료	0
JB2608-0122	30	P103	완료	0
JB2608-0122	40	I101	완료	0
JB2608-0123	10	B101	완료	0
JB2608-0123	20	P101	완료	0
JB2608-0123	30	P101	완료	0
JB2608-0123	40	I101	완료	0
JB2608-0124	10	B101	완료	0
JB2608-0124	20	P103	완료	0
JB2608-0124	30	P103	완료	0
JB2608-0124	40	I101	완료	0
JB2608-0125	10	B101	완료	0
JB2608-0125	20	P101	완료	0
JB2608-0125	30	P101	완료	0
JB2608-0125	40	I101	완료	0
JB2608-0126	10	B101	완료	0
JB2608-0126	20	P103	완료	0
JB2608-0126	30	P103	완료	0
JB2608-0126	40	I101	완료	0
JB2608-0127	10	B101	완료	0
JB2608-0127	20	P101	완료	0
JB2608-0127	30	P101	완료	0
JB2608-0127	40	I101	완료	0
JB2608-0128	10	B101	완료	0
JB2608-0128	20	P101	완료	0
JB2608-0128	30	P101	완료	0
JB2608-0128	40	I101	완료	0
JB2608-0129	10	B101	완료	0
JB2608-0129	20	P103	완료	0
JB2608-0129	30	P103	완료	0
JB2608-0129	40	I101	완료	0
JB2608-0130	10	B101	완료	0
JB2608-0130	20	P201	완료	0
JB2608-0130	30	P201	완료	0
JB2608-0130	40	W201	완료	0
JB2608-0130	50	I101	완료	0
JB2608-0131	10	B101	완료	0
JB2608-0131	20	P101	완료	0
JB2608-0131	30	P101	완료	0
JB2608-0131	40	I101	완료	0
JB2608-0132	10	B101	완료	0
JB2608-0132	20	P103	완료	0
JB2608-0132	30	P103	완료	0
JB2608-0132	40	I101	완료	0
JB2608-0133	10	B101	완료	0
JB2608-0133	20	P201	완료	0
JB2608-0133	30	P201	완료	0
JB2608-0133	40	W201	완료	0
JB2608-0133	50	I101	완료	0
JB2608-0134	10	B101	완료	0
JB2608-0134	20	P101	완료	0
JB2608-0134	30	P101	완료	0
JB2608-0134	40	I101	완료	0
JB2608-0135	10	B101	완료	0
JB2608-0135	20	P101	완료	0
JB2608-0135	30	P101	완료	0
JB2608-0135	40	I101	완료	0
JB2608-0136	10	B101	완료	0
JB2608-0136	20	P103	완료	0
JB2608-0136	30	P103	완료	0
JB2608-0136	40	I101	완료	0
JB2608-0137	10	B101	완료	0
JB2608-0137	20	P103	완료	0
JB2608-0137	30	P103	완료	0
JB2608-0137	40	I101	완료	0
JB2608-0138	10	B101	완료	0
JB2608-0138	20	P101	완료	0
JB2608-0138	30	P101	완료	0
JB2608-0138	40	I101	완료	0
JB2608-0139	10	B101	완료	0
JB2608-0139	20	P101	완료	0
JB2608-0139	30	P101	완료	0
JB2608-0139	40	I101	완료	0
JB2608-0140	10	B101	완료	0
JB2608-0140	20	P103	완료	0
JB2608-0140	30	P103	완료	0
JB2608-0140	40	I101	완료	0
JB2608-0141	10	B101	완료	0
JB2608-0141	20	P101	완료	0
JB2608-0141	30	P101	완료	0
JB2608-0141	40	I101	완료	0
JB2608-0142	10	B101	완료	0
JB2608-0142	20	P103	완료	0
JB2608-0142	30	P103	완료	0
JB2608-0142	40	I101	완료	0
JB2608-0143	10	B101	완료	0
JB2608-0143	20	P201	완료	0
JB2608-0143	30	P201	완료	0
JB2608-0143	40	W201	완료	0
JB2608-0143	50	I101	완료	0
JB2608-0144	10	B101	완료	0
JB2608-0144	20	P101	완료	0
JB2608-0144	30	P101	완료	0
JB2608-0144	40	I101	완료	0
JB2608-0145	10	B101	완료	0
JB2608-0145	20	P103	완료	0
JB2608-0145	30	P103	완료	0
JB2608-0145	40	I101	완료	0
JB2608-0146	10	B101	완료	0
JB2608-0146	20	P101	완료	0
JB2608-0146	30	P101	완료	0
JB2608-0146	40	I101	완료	0
JB2608-0147	10	B101	완료	0
JB2608-0147	20	P103	완료	0
JB2608-0147	30	P103	완료	0
JB2608-0147	40	I101	완료	0
JB2608-0148	10	B101	완료	0
JB2608-0148	20	P101	완료	0
JB2608-0148	30	P101	완료	0
JB2608-0148	40	I101	완료	0
JB2608-0149	10	B101	완료	0
JB2608-0149	20	P103	완료	0
JB2608-0149	30	P103	완료	0
JB2608-0149	40	I101	완료	0
JB2608-0150	10	B101	완료	0
JB2608-0150	20	P201	완료	0
JB2608-0150	30	P201	완료	0
JB2608-0150	40	W201	완료	0
JB2608-0150	50	I101	완료	0
JB2609-0101	10	B101	완료	0
JB2609-0101	20	P103	완료	0
JB2609-0101	30	P103	완료	0
JB2609-0101	40	I101	완료	0
JB2609-0102	10	B101	완료	0
JB2609-0102	20	P101	완료	0
JB2609-0102	30	P101	완료	0
JB2609-0102	40	I101	완료	0
JB2609-0103	10	B101	완료	0
JB2609-0103	20	P101	완료	0
JB2609-0103	30	P101	완료	0
JB2609-0103	40	I101	완료	0
JB2609-0104	10	B101	완료	0
JB2609-0104	20	P103	완료	0
JB2609-0104	30	P103	완료	0
JB2609-0104	40	I101	완료	0
JB2609-0105	10	B101	완료	0
JB2609-0105	20	P101	완료	0
JB2609-0105	30	P101	완료	0
JB2609-0105	40	I101	완료	0
JB2609-0106	10	B101	완료	0
JB2609-0106	20	P103	완료	0
JB2609-0106	30	P103	완료	0
JB2609-0106	40	I101	완료	0
JB2609-0107	10	B101	완료	0
JB2609-0107	20	P103	완료	0
JB2609-0107	30	P103	완료	0
JB2609-0107	40	I101	완료	0
JB2609-0108	10	B101	완료	0
JB2609-0108	20	P101	완료	0
JB2609-0108	30	P101	완료	0
JB2609-0108	40	I101	완료	0
JB2609-0109	10	B101	완료	0
JB2609-0109	20	P101	완료	0
JB2609-0109	30	P101	완료	0
JB2609-0109	40	I101	완료	0
JB2609-0110	10	B101	완료	0
JB2609-0110	20	P103	완료	0
JB2609-0110	30	P103	완료	0
JB2609-0110	40	I101	완료	0
JB2609-0111	10	B101	완료	0
JB2609-0111	20	P103	완료	0
JB2609-0111	30	P103	완료	0
JB2609-0111	40	I101	완료	0
JB2609-0112	10	B101	완료	0
JB2609-0112	20	P101	완료	0
JB2609-0112	30	P101	완료	0
JB2609-0112	40	I101	완료	0
JB2609-0113	10	B101	완료	0
JB2609-0113	20	P103	완료	0
JB2609-0113	30	P103	완료	0
JB2609-0113	40	I101	완료	0
JB2609-0114	10	B101	완료	0
JB2609-0114	20	P101	완료	0
JB2609-0114	30	P101	완료	0
JB2609-0114	40	I101	완료	0
JB2609-0115	10	B101	완료	0
JB2609-0115	20	P103	완료	0
JB2609-0115	30	P103	완료	0
JB2609-0115	40	I101	완료	0
JB2609-0116	10	B101	완료	0
JB2609-0116	20	P101	완료	0
JB2609-0116	30	P101	완료	0
JB2609-0116	40	I101	완료	0
JB2609-0117	10	B101	완료	0
JB2609-0117	20	P101	완료	0
JB2609-0117	30	P101	완료	0
JB2609-0117	40	I101	완료	0
JB2609-0118	10	B101	완료	0
JB2609-0118	20	P103	완료	0
JB2609-0118	30	P103	완료	0
JB2609-0118	40	I101	완료	0
JB2609-0119	10	B101	완료	0
JB2609-0119	20	P101	완료	0
JB2609-0119	30	P101	완료	0
JB2609-0119	40	I101	완료	0
JB2609-0120	10	B101	완료	0
JB2609-0120	20	P103	완료	0
JB2609-0120	30	P103	완료	0
JB2609-0120	40	I101	완료	0
JB2609-0121	10	B101	완료	0
JB2609-0121	20	P101	완료	0
JB2609-0121	30	P101	완료	0
JB2609-0121	40	I101	완료	0
JB2609-0122	10	B101	완료	0
JB2609-0122	20	P103	완료	0
JB2609-0122	30	P103	완료	0
JB2609-0122	40	I101	완료	0
JB2609-0123	10	B101	완료	0
JB2609-0123	20	P101	완료	0
JB2609-0123	30	P101	완료	0
JB2609-0123	40	I101	완료	0
JB2609-0124	10	B101	완료	0
JB2609-0124	20	P103	완료	0
JB2609-0124	30	P103	완료	0
JB2609-0124	40	I101	완료	0
JB2609-0125	10	B101	완료	0
JB2609-0125	20	P201	완료	0
JB2609-0125	30	P201	완료	0
JB2609-0125	40	W201	완료	0
JB2609-0125	50	I101	완료	0
JB2609-0126	10	B101	완료	0
JB2609-0126	20	P103	완료	0
JB2609-0126	30	P103	완료	0
JB2609-0126	40	I101	완료	0
JB2609-0127	10	B101	완료	0
JB2609-0127	20	P101	완료	0
JB2609-0127	30	P101	완료	0
JB2609-0127	40	I101	완료	0
JB2609-0128	10	B101	완료	0
JB2609-0128	20	P103	완료	0
JB2609-0128	30	P103	완료	0
JB2609-0128	40	I101	완료	0
JB2609-0129	10	B101	완료	0
JB2609-0129	20	P201	완료	0
JB2609-0129	30	P201	완료	0
JB2609-0129	40	W201	완료	0
JB2609-0129	50	I101	완료	0
JB2609-0130	10	B101	완료	0
JB2609-0130	20	P103	완료	0
JB2609-0130	30	P103	완료	0
JB2609-0130	40	I101	완료	0
JB2609-0131	10	B101	완료	0
JB2609-0131	20	P101	완료	0
JB2609-0131	30	P101	완료	0
JB2609-0131	40	I101	완료	0
JB2609-0132	10	B101	완료	0
JB2609-0132	20	P201	완료	0
JB2609-0132	30	P201	완료	0
JB2609-0132	40	W201	완료	0
JB2609-0132	50	I101	완료	0
JB2609-0133	10	B101	완료	0
JB2609-0133	20	P101	완료	0
JB2609-0133	30	P101	완료	0
JB2609-0133	40	I101	완료	0
JB2609-0134	10	B101	완료	0
JB2609-0134	20	P103	완료	0
JB2609-0134	30	P103	완료	0
JB2609-0134	40	I101	완료	0
JB2609-0135	10	B101	완료	0
JB2609-0135	20	P101	완료	0
JB2609-0135	30	P101	완료	0
JB2609-0135	40	I101	완료	0
JB2609-0136	10	B101	완료	0
JB2609-0136	20	P101	완료	0
JB2609-0136	30	P101	완료	0
JB2609-0136	40	I101	완료	0
JB2609-0137	10	B101	완료	0
JB2609-0137	20	P201	완료	0
JB2609-0137	30	P201	완료	0
JB2609-0137	40	W201	완료	0
JB2609-0137	50	I101	완료	0
JB2609-0138	10	B101	완료	0
JB2609-0138	20	P101	완료	0
JB2609-0138	30	P101	완료	0
JB2609-0138	40	I101	완료	0
JB2609-0139	10	B101	완료	0
JB2609-0139	20	P103	완료	0
JB2609-0139	30	P103	완료	0
JB2609-0139	40	I101	완료	0
JB2609-0140	10	B101	완료	0
JB2609-0140	20	P103	완료	0
JB2609-0140	30	P103	완료	0
JB2609-0140	40	I101	완료	0
JB2609-0141	10	B101	완료	0
JB2609-0141	20	P101	완료	0
JB2609-0141	30	P101	완료	0
JB2609-0141	40	I101	완료	0
JB2609-0142	10	B101	완료	0
JB2609-0142	20	P101	완료	0
JB2609-0142	30	P101	완료	0
JB2609-0142	40	I101	완료	0
JB2609-0143	10	B101	완료	0
JB2609-0143	20	P103	완료	0
JB2609-0143	30	P103	완료	0
JB2609-0143	40	I101	완료	0
JB2609-0144	10	B101	완료	0
JB2609-0144	20	P101	완료	0
JB2609-0144	30	P101	완료	0
JB2609-0144	40	I101	완료	0
JB2609-0145	10	B101	완료	0
JB2609-0145	20	P103	완료	0
JB2609-0145	30	P103	완료	0
JB2609-0145	40	I101	완료	0
JB2610-0101	10	B101	완료	0
JB2610-0101	20	P101	완료	0
JB2610-0101	30	P101	완료	0
JB2610-0101	40	I101	완료	0
JB2610-0102	10	B101	완료	0
JB2610-0102	20	P103	완료	0
JB2610-0102	30	P103	완료	0
JB2610-0102	40	I101	완료	0
JB2610-0103	10	B101	완료	0
JB2610-0103	20	P103	완료	0
JB2610-0103	30	P103	완료	0
JB2610-0103	40	I101	완료	0
JB2610-0104	10	B101	완료	0
JB2610-0104	20	P101	완료	0
JB2610-0104	30	P101	완료	0
JB2610-0104	40	I101	완료	0
JB2610-0105	10	B101	완료	0
JB2610-0105	20	P101	완료	0
JB2610-0105	30	P101	완료	0
JB2610-0105	40	I101	완료	0
JB2610-0106	10	B101	완료	0
JB2610-0106	20	P103	완료	0
JB2610-0106	30	P103	완료	0
JB2610-0106	40	I101	완료	0
JB2610-0107	10	B101	완료	0
JB2610-0107	20	P201	완료	0
JB2610-0107	30	P201	완료	0
JB2610-0107	40	W201	완료	0
JB2610-0107	50	I101	완료	0
JB2610-0108	10	B101	완료	0
JB2610-0108	20	P103	완료	0
JB2610-0108	30	P103	완료	0
JB2610-0108	40	I101	완료	0
JB2610-0109	10	B101	완료	0
JB2610-0109	20	P101	완료	0
JB2610-0109	30	P101	완료	0
JB2610-0109	40	I101	완료	0
JB2610-0110	10	B101	완료	0
JB2610-0110	20	P201	완료	0
JB2610-0110	30	P201	완료	0
JB2610-0110	40	W201	완료	0
JB2610-0110	50	I101	완료	0
JB2610-0111	10	B101	완료	0
JB2610-0111	20	P103	완료	0
JB2610-0111	30	P103	완료	0
JB2610-0111	40	I101	완료	0
JB2610-0112	10	B101	완료	0
JB2610-0112	20	P101	완료	0
JB2610-0112	30	P101	완료	0
JB2610-0112	40	I101	완료	0
JB2610-0113	10	B101	완료	0
JB2610-0113	20	P103	완료	0
JB2610-0113	30	P103	완료	0
JB2610-0113	40	I101	완료	0
JB2610-0114	10	B101	완료	0
JB2610-0114	20	P101	완료	0
JB2610-0114	30	P101	완료	0
JB2610-0114	40	I101	완료	0
JB2610-0115	10	B101	완료	0
JB2610-0115	20	P103	완료	0
JB2610-0115	30	P103	완료	0
JB2610-0115	40	I101	완료	0
JB2610-0116	10	B101	완료	0
JB2610-0116	20	P101	완료	0
JB2610-0116	30	P101	완료	0
JB2610-0116	40	I101	완료	0
JB2610-0117	10	B101	완료	0
JB2610-0117	20	P201	완료	0
JB2610-0117	30	P201	완료	0
JB2610-0117	40	W201	완료	0
JB2610-0117	50	I101	완료	0
JB2610-0118	10	B101	완료	0
JB2610-0118	20	P101	완료	0
JB2610-0118	30	P101	완료	0
JB2610-0118	40	I101	완료	0
JB2610-0119	10	B101	완료	0
JB2610-0119	20	P103	완료	0
JB2610-0119	30	P103	완료	0
JB2610-0119	40	I101	완료	0
JB2610-0120	10	B101	완료	0
JB2610-0120	20	P101	완료	0
JB2610-0120	30	P101	완료	0
JB2610-0120	40	I101	완료	0
JB2610-0121	10	B101	완료	0
JB2610-0121	20	P101	완료	0
JB2610-0121	30	P101	완료	0
JB2610-0121	40	I101	완료	0
JB2610-0122	10	B101	완료	0
JB2610-0122	20	P201	완료	0
JB2610-0122	30	P201	완료	0
JB2610-0122	40	W201	완료	0
JB2610-0122	50	I101	완료	0
JB2610-0123	10	B101	완료	0
JB2610-0123	20	P103	완료	0
JB2610-0123	30	P103	완료	0
JB2610-0123	40	I101	완료	0
JB2610-0124	10	B101	완료	0
JB2610-0124	20	P103	완료	0
JB2610-0124	30	P103	완료	0
JB2610-0124	40	I101	완료	0
JB2610-0125	10	B101	완료	0
JB2610-0125	20	P101	완료	0
JB2610-0125	30	P101	완료	0
JB2610-0125	40	I101	완료	0
JB2610-0126	10	B101	완료	0
JB2610-0126	20	P103	완료	0
JB2610-0126	30	P103	완료	0
JB2610-0126	40	I101	완료	0
JB2610-0127	10	B101	완료	0
JB2610-0127	20	P101	완료	0
JB2610-0127	30	P101	완료	0
JB2610-0127	40	I101	완료	0
JB2610-0128	10	B101	완료	0
JB2610-0128	20	P101	완료	0
JB2610-0128	30	P101	완료	0
JB2610-0128	40	I101	완료	0
JB2610-0129	10	B101	완료	0
JB2610-0129	20	P103	완료	0
JB2610-0129	30	P103	완료	0
JB2610-0129	40	I101	완료	0
JB2610-0130	10	B101	완료	0
JB2610-0130	20	P101	완료	0
JB2610-0130	30	P101	완료	0
JB2610-0130	40	I101	완료	0
JB2610-0131	10	B101	완료	0
JB2610-0131	20	P103	완료	0
JB2610-0131	30	P103	완료	0
JB2610-0131	40	I101	완료	0
JB2610-0132	10	B101	완료	0
JB2610-0132	20	P101	완료	0
JB2610-0132	30	P101	완료	0
JB2610-0132	40	I101	완료	0
JB2610-0133	10	B101	완료	0
JB2610-0133	20	P103	완료	0
JB2610-0133	30	P103	완료	0
JB2610-0133	40	I101	완료	0
JB2610-0134	10	B101	완료	0
JB2610-0134	20	P101	완료	0
JB2610-0134	30	P101	완료	0
JB2610-0134	40	I101	완료	0
JB2610-0135	10	B101	완료	0
JB2610-0135	20	P103	완료	0
JB2610-0135	30	P103	완료	0
JB2610-0135	40	I101	완료	0
JB2610-0136	10	B101	완료	0
JB2610-0136	20	P101	완료	0
JB2610-0136	30	P101	완료	0
JB2610-0136	40	I101	완료	0
JB2610-0137	10	B101	완료	0
JB2610-0137	20	P103	완료	0
JB2610-0137	30	P103	완료	0
JB2610-0137	40	I101	완료	0
JB2610-0139	10	B101	완료	0
JB2610-0139	20	P101	완료	0
JB2610-0139	30	P101	완료	0
JB2610-0139	40	I101	완료	0
JB2610-0140	10	B101	완료	0
JB2610-0140	20	P103	완료	0
JB2610-0140	30	P103	완료	0
JB2610-0140	40	I101	완료	0
JB2610-0138	10	B101	완료	0
JB2610-0138	20	P101	진행	2400
JB2610-0138	30	P101	진행	2400
JB2610-0138	40	I101	대기	6000
JB2610-0141	10	B101	완료	0
JB2610-0141	20	P103	중단	3200
JB2610-0141	30	P103	중단	3200
JB2610-0141	40	I101	대기	5000
JB2610-0145	10	B101	대기	3000
JB2610-0145	20	P101	대기	3000
JB2610-0145	30	P101	대기	3000
JB2610-0145	40	I101	대기	3000
JB2610-0142	10	B101	완료	0
JB2610-0142	20	P201	완료	0
JB2610-0142	30	P201	완료	0
JB2610-0142	40	W201	진행	4000
JB2610-0142	50	I101	대기	4000
JB2610-0143	10	B101	대기	3000
JB2610-0143	20	P201	대기	3000
JB2610-0143	30	P201	대기	3000
JB2610-0143	40	W201	대기	3000
JB2610-0143	50	I101	대기	3000
\.


--
-- Data for Name: worker; Type: TABLE DATA; Schema: mes; Owner: -
--

COPY mes.worker (id, name, shift, line, skill_level, active_yn) FROM stdin;
6	장보람	주간	1라인	2	Y
24	최서준	야간	1라인	3	Y
27	정가은	주간	2라인	5	Y
31	강민준	주간	1라인	2	Y
36	정동현	주간	1라인	3	Y
38	권수빈	야간	1라인	4	Y
40	박다은	주간	2라인	2	Y
44	조지수	주간	2라인	3	Y
45	권민준	주간	1라인	2	Y
48	류준혁	야간	1라인	3	Y
52	강가은	주간	2라인	3	Y
55	권도윤	야간	2라인	5	Y
72	강은지	주간	1라인	4	Y
74	홍가은	야간	1라인	1	Y
79	최가은	주간	2라인	5	Y
57	한다은	야간	1라인	2	Y
\.


--
-- Name: change_log change_log_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.change_log
    ADD CONSTRAINT change_log_pkey PRIMARY KEY (change_id);


--
-- Name: defect defect_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.defect
    ADD CONSTRAINT defect_pkey PRIMARY KEY (defect_id);


--
-- Name: die die_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.die
    ADD CONSTRAINT die_pkey PRIMARY KEY (die_code);


--
-- Name: downtime downtime_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.downtime
    ADD CONSTRAINT downtime_pkey PRIMARY KEY (downtime_id);


--
-- Name: equipment equipment_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.equipment
    ADD CONSTRAINT equipment_pkey PRIMARY KEY (code);


--
-- Name: handover_note handover_note_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.handover_note
    ADD CONSTRAINT handover_note_pkey PRIMARY KEY (note_id);


--
-- Name: inspection inspection_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.inspection
    ADD CONSTRAINT inspection_pkey PRIMARY KEY (inspection_id);


--
-- Name: maintenance_order maintenance_order_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.maintenance_order
    ADD CONSTRAINT maintenance_order_pkey PRIMARY KEY (mo_no);


--
-- Name: material_issue material_issue_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.material_issue
    ADD CONSTRAINT material_issue_pkey PRIMARY KEY (issue_id);


--
-- Name: production_result production_result_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.production_result
    ADD CONSTRAINT production_result_pkey PRIMARY KEY (result_id);


--
-- Name: routing routing_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.routing
    ADD CONSTRAINT routing_pkey PRIMARY KEY (product_code, op_seq);


--
-- Name: sensor_reading sensor_reading_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.sensor_reading
    ADD CONSTRAINT sensor_reading_pkey PRIMARY KEY (equipment_code, measured_at, metric);


--
-- Name: work_order_operation work_order_operation_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.work_order_operation
    ADD CONSTRAINT work_order_operation_pkey PRIMARY KEY (wo_no, op_seq);


--
-- Name: work_order work_order_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.work_order
    ADD CONSTRAINT work_order_pkey PRIMARY KEY (wo_no);


--
-- Name: worker worker_pkey; Type: CONSTRAINT; Schema: mes; Owner: -
--

ALTER TABLE ONLY mes.worker
    ADD CONSTRAINT worker_pkey PRIMARY KEY (id);


--
-- PostgreSQL database dump complete
--

\unrestrict H0GqoWwa7nRV4tLFICtTix0SHBjTpSZB67JmvIbnsDZYkffRhxUXdSHDjwmQeIL

