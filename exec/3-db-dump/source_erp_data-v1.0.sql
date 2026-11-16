--
-- PostgreSQL database dump
--

\restrict hGADyVz8xMD4HjDcS89w3LRtPAZopghccc1KtRc2rC50P3N64O61Wk2HbO4YhGw

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
-- Name: erp; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA erp;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: attendance; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.attendance (
    emp_no character varying(20) NOT NULL,
    work_date date NOT NULL,
    clock_in time without time zone,
    clock_out time without time zone,
    work_type character varying(10),
    overtime_min integer,
    late_yn character(1)
);


--
-- Name: TABLE attendance; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.attendance IS '근태';


--
-- Name: COLUMN attendance.emp_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.emp_no IS '사번';


--
-- Name: COLUMN attendance.work_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.work_date IS '근무일';


--
-- Name: COLUMN attendance.clock_in; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.clock_in IS '출근 시각';


--
-- Name: COLUMN attendance.clock_out; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.clock_out IS '퇴근 시각';


--
-- Name: COLUMN attendance.work_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.work_type IS '근무 구분(근무 · 외근 · 연차)';


--
-- Name: COLUMN attendance.overtime_min; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.overtime_min IS '연장 근무 시간(분)';


--
-- Name: COLUMN attendance.late_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.attendance.late_yn IS '지각 여부(Y/N)';


--
-- Name: change_log; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.change_log (
    change_id bigint NOT NULL,
    table_name character varying(50),
    row_key character varying(100),
    operation character varying(10),
    changed_at timestamp without time zone
);


--
-- Name: TABLE change_log; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.change_log IS '원천 변경 이력';


--
-- Name: COLUMN change_log.change_id; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.change_log.change_id IS '변경 순번';


--
-- Name: COLUMN change_log.table_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.change_log.table_name IS '변경된 표';


--
-- Name: COLUMN change_log.row_key; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.change_log.row_key IS '변경된 행의 키';


--
-- Name: COLUMN change_log.operation; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.change_log.operation IS 'INSERT · UPDATE · DELETE';


--
-- Name: COLUMN change_log.changed_at; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.change_log.changed_at IS '변경 시각';


--
-- Name: department; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.department (
    dept_code character varying(10) NOT NULL,
    dept_name character varying(50),
    parent_code character varying(10),
    manager_emp_no character varying(20)
);


--
-- Name: TABLE department; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.department IS '부서';


--
-- Name: COLUMN department.dept_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.department.dept_code IS '부서 코드';


--
-- Name: COLUMN department.dept_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.department.dept_name IS '부서명';


--
-- Name: COLUMN department.parent_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.department.parent_code IS '상위 부서 코드';


--
-- Name: COLUMN department.manager_emp_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.department.manager_emp_no IS '부서장 사번';


--
-- Name: employee; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.employee (
    emp_no character varying(20) NOT NULL,
    emp_name character varying(50),
    dept_code character varying(10),
    "position" character varying(20),
    hire_date date,
    employment_type character varying(10),
    status character varying(10)
);


--
-- Name: TABLE employee; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.employee IS '직원';


--
-- Name: COLUMN employee.emp_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.emp_no IS '사번';


--
-- Name: COLUMN employee.emp_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.emp_name IS '이름(가명)';


--
-- Name: COLUMN employee.dept_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.dept_code IS '소속 부서 코드';


--
-- Name: COLUMN employee."position"; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee."position" IS '직급';


--
-- Name: COLUMN employee.hire_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.hire_date IS '입사일';


--
-- Name: COLUMN employee.employment_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.employment_type IS '고용 형태(정규 · 계약)';


--
-- Name: COLUMN employee.status; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.employee.status IS '재직 상태(재직 · 휴직 · 퇴직)';


--
-- Name: fixed_asset; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.fixed_asset (
    asset_no character varying(20) NOT NULL,
    asset_name character varying(100),
    asset_type character varying(20),
    acquired_on date,
    acquisition_cost numeric(16,2),
    book_value numeric(16,2),
    location character varying(50),
    equipment_code character varying(20)
);


--
-- Name: TABLE fixed_asset; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.fixed_asset IS '고정자산';


--
-- Name: COLUMN fixed_asset.asset_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.asset_no IS '자산 번호';


--
-- Name: COLUMN fixed_asset.asset_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.asset_name IS '자산명';


--
-- Name: COLUMN fixed_asset.asset_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.asset_type IS '자산 종류(기계장치 · 차량 · 공구)';


--
-- Name: COLUMN fixed_asset.acquired_on; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.acquired_on IS '취득일';


--
-- Name: COLUMN fixed_asset.acquisition_cost; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.acquisition_cost IS '취득가(원)';


--
-- Name: COLUMN fixed_asset.book_value; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.book_value IS '장부가(원)';


--
-- Name: COLUMN fixed_asset.location; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.location IS '설치·보관 위치';


--
-- Name: COLUMN fixed_asset.equipment_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.fixed_asset.equipment_code IS '생산 설비이면 현장 시스템의 설비 코드';


--
-- Name: gl_account; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.gl_account (
    account_code character varying(10) NOT NULL,
    account_name character varying(50),
    account_type character varying(10),
    parent_code character varying(10),
    use_yn character(1)
);


--
-- Name: TABLE gl_account; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.gl_account IS '회계 계정';


--
-- Name: COLUMN gl_account.account_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.gl_account.account_code IS '계정 코드';


--
-- Name: COLUMN gl_account.account_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.gl_account.account_name IS '계정명';


--
-- Name: COLUMN gl_account.account_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.gl_account.account_type IS '계정 구분(자산 · 부채 · 자본 · 수익 · 비용)';


--
-- Name: COLUMN gl_account.parent_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.gl_account.parent_code IS '상위 계정 코드';


--
-- Name: COLUMN gl_account.use_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.gl_account.use_yn IS '사용 여부(Y/N)';


--
-- Name: goods_receipt; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.goods_receipt (
    gr_no character varying(20) NOT NULL,
    po_no character varying(20),
    item_code character varying(20),
    gr_date date,
    qty numeric(14,3),
    accepted_qty numeric(14,3),
    rejected_qty numeric(14,3),
    warehouse character varying(50),
    inspector character varying(50)
);


--
-- Name: TABLE goods_receipt; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.goods_receipt IS '입고';


--
-- Name: COLUMN goods_receipt.gr_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.gr_no IS '입고 번호';


--
-- Name: COLUMN goods_receipt.po_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.po_no IS '근거 발주 번호';


--
-- Name: COLUMN goods_receipt.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.item_code IS '입고 품번';


--
-- Name: COLUMN goods_receipt.gr_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.gr_date IS '입고일';


--
-- Name: COLUMN goods_receipt.qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.qty IS '입고 수량';


--
-- Name: COLUMN goods_receipt.accepted_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.accepted_qty IS '검수 합격 수량';


--
-- Name: COLUMN goods_receipt.rejected_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.rejected_qty IS '검수 불합격 수량';


--
-- Name: COLUMN goods_receipt.warehouse; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.warehouse IS '입고 창고';


--
-- Name: COLUMN goods_receipt.inspector; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.goods_receipt.inspector IS '검수 담당';


--
-- Name: item; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.item (
    item_code character varying(20) NOT NULL,
    item_name character varying(100),
    spec character varying(100),
    unit character varying(10),
    item_type character varying(2),
    std_price numeric(12,2),
    safety_stock numeric(14,3),
    use_yn character(1),
    updated_at timestamp without time zone
);


--
-- Name: TABLE item; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.item IS '품목';


--
-- Name: COLUMN item.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.item_code IS '품번';


--
-- Name: COLUMN item.item_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.item_name IS '품명';


--
-- Name: COLUMN item.spec; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.spec IS '규격';


--
-- Name: COLUMN item.unit; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.unit IS '단위(EA 개 · KG 킬로그램)';


--
-- Name: COLUMN item.item_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.item_type IS '품목 구분(FG 완제품 · SF 반제품 · RM 원자재)';


--
-- Name: COLUMN item.std_price; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.std_price IS '표준 단가(원)';


--
-- Name: COLUMN item.safety_stock; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.safety_stock IS '안전 재고 수량';


--
-- Name: COLUMN item.use_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.use_yn IS '사용 여부(Y/N)';


--
-- Name: COLUMN item.updated_at; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.item.updated_at IS '마지막 수정 시각';


--
-- Name: journal_entry; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.journal_entry (
    entry_no character varying(20) NOT NULL,
    line_no integer NOT NULL,
    entry_date date,
    account_code character varying(10),
    vendor_code character varying(20),
    debit numeric(16,2),
    credit numeric(16,2),
    description character varying(100)
);


--
-- Name: TABLE journal_entry; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.journal_entry IS '분개';


--
-- Name: COLUMN journal_entry.entry_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.entry_no IS '전표 번호';


--
-- Name: COLUMN journal_entry.line_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.line_no IS '분개 순번';


--
-- Name: COLUMN journal_entry.entry_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.entry_date IS '전표일';


--
-- Name: COLUMN journal_entry.account_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.account_code IS '계정 코드';


--
-- Name: COLUMN journal_entry.vendor_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.vendor_code IS '관련 거래처 코드';


--
-- Name: COLUMN journal_entry.debit; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.debit IS '차변 금액';


--
-- Name: COLUMN journal_entry.credit; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.credit IS '대변 금액';


--
-- Name: COLUMN journal_entry.description; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.journal_entry.description IS '적요';


--
-- Name: purchase_invoice; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.purchase_invoice (
    invoice_no character varying(20) NOT NULL,
    vendor_code character varying(20),
    invoice_date date,
    supply_amount numeric(16,2),
    vat numeric(16,2),
    total_amount numeric(16,2),
    paid_yn character(1),
    paid_date date
);


--
-- Name: TABLE purchase_invoice; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.purchase_invoice IS '매입 전표';


--
-- Name: COLUMN purchase_invoice.invoice_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.invoice_no IS '매입 전표 번호';


--
-- Name: COLUMN purchase_invoice.vendor_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.vendor_code IS '공급 거래처 코드';


--
-- Name: COLUMN purchase_invoice.invoice_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.invoice_date IS '발행일';


--
-- Name: COLUMN purchase_invoice.supply_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.supply_amount IS '공급가액(원)';


--
-- Name: COLUMN purchase_invoice.vat; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.vat IS '부가세(원)';


--
-- Name: COLUMN purchase_invoice.total_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.total_amount IS '합계(원)';


--
-- Name: COLUMN purchase_invoice.paid_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.paid_yn IS '지급 완료 여부(Y/N)';


--
-- Name: COLUMN purchase_invoice.paid_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_invoice.paid_date IS '지급일';


--
-- Name: purchase_order; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.purchase_order (
    po_no character varying(20) NOT NULL,
    vendor_code character varying(20),
    po_date date,
    due_date date,
    status character varying(10),
    total_amount numeric(16,2),
    buyer character varying(50),
    remark character varying(100)
);


--
-- Name: TABLE purchase_order; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.purchase_order IS '구매 발주';


--
-- Name: COLUMN purchase_order.po_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.po_no IS '발주 번호';


--
-- Name: COLUMN purchase_order.vendor_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.vendor_code IS '공급 거래처 코드';


--
-- Name: COLUMN purchase_order.po_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.po_date IS '발주일';


--
-- Name: COLUMN purchase_order.due_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.due_date IS '입고 요청일';


--
-- Name: COLUMN purchase_order.status; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.status IS '진행 상태(발주 · 부분입고 · 입고완료 · 취소)';


--
-- Name: COLUMN purchase_order.total_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.total_amount IS '발주 금액(원, 부가세 별도)';


--
-- Name: COLUMN purchase_order.buyer; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.buyer IS '구매 담당';


--
-- Name: COLUMN purchase_order.remark; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order.remark IS '비고';


--
-- Name: purchase_order_line; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.purchase_order_line (
    po_no character varying(20) NOT NULL,
    line_no integer NOT NULL,
    item_code character varying(20),
    qty numeric(14,3),
    unit_price numeric(12,2),
    received_qty numeric(14,3),
    due_date date
);


--
-- Name: TABLE purchase_order_line; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.purchase_order_line IS '구매 발주 상세';


--
-- Name: COLUMN purchase_order_line.po_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.po_no IS '발주 번호';


--
-- Name: COLUMN purchase_order_line.line_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.line_no IS '상세 순번';


--
-- Name: COLUMN purchase_order_line.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.item_code IS '발주 품번';


--
-- Name: COLUMN purchase_order_line.qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.qty IS '발주 수량';


--
-- Name: COLUMN purchase_order_line.unit_price; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.unit_price IS '발주 단가(원)';


--
-- Name: COLUMN purchase_order_line.received_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.received_qty IS '누적 입고 수량';


--
-- Name: COLUMN purchase_order_line.due_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.purchase_order_line.due_date IS '상세별 입고 요청일';


--
-- Name: sales_invoice; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.sales_invoice (
    invoice_no character varying(20) NOT NULL,
    customer_code character varying(20),
    invoice_date date,
    supply_amount numeric(16,2),
    vat numeric(16,2),
    total_amount numeric(16,2),
    collected_yn character(1),
    collected_date date
);


--
-- Name: TABLE sales_invoice; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.sales_invoice IS '매출 전표';


--
-- Name: COLUMN sales_invoice.invoice_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.invoice_no IS '매출 전표 번호';


--
-- Name: COLUMN sales_invoice.customer_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.customer_code IS '청구 고객 코드';


--
-- Name: COLUMN sales_invoice.invoice_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.invoice_date IS '발행일';


--
-- Name: COLUMN sales_invoice.supply_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.supply_amount IS '공급가액(원)';


--
-- Name: COLUMN sales_invoice.vat; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.vat IS '부가세(원)';


--
-- Name: COLUMN sales_invoice.total_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.total_amount IS '합계(원)';


--
-- Name: COLUMN sales_invoice.collected_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.collected_yn IS '수금 완료 여부(Y/N)';


--
-- Name: COLUMN sales_invoice.collected_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_invoice.collected_date IS '수금일';


--
-- Name: sales_order; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.sales_order (
    so_no character varying(20) NOT NULL,
    customer_code character varying(20),
    so_date date,
    due_date date,
    status character varying(10),
    total_amount numeric(16,2),
    sales_rep character varying(50)
);


--
-- Name: TABLE sales_order; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.sales_order IS '수주';


--
-- Name: COLUMN sales_order.so_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.so_no IS '수주 번호';


--
-- Name: COLUMN sales_order.customer_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.customer_code IS '주문 고객 코드';


--
-- Name: COLUMN sales_order.so_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.so_date IS '수주일';


--
-- Name: COLUMN sales_order.due_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.due_date IS '납기일';


--
-- Name: COLUMN sales_order.status; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.status IS '진행 상태(접수 · 생산중 · 출하완료 · 취소)';


--
-- Name: COLUMN sales_order.total_amount; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.total_amount IS '수주 금액(원)';


--
-- Name: COLUMN sales_order.sales_rep; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order.sales_rep IS '영업 담당';


--
-- Name: sales_order_line; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.sales_order_line (
    so_no character varying(20) NOT NULL,
    line_no integer NOT NULL,
    item_code character varying(20),
    qty numeric(14,3),
    unit_price numeric(12,2),
    shipped_qty numeric(14,3)
);


--
-- Name: TABLE sales_order_line; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.sales_order_line IS '수주 상세';


--
-- Name: COLUMN sales_order_line.so_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.so_no IS '수주 번호';


--
-- Name: COLUMN sales_order_line.line_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.line_no IS '상세 순번';


--
-- Name: COLUMN sales_order_line.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.item_code IS '주문 품번';


--
-- Name: COLUMN sales_order_line.qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.qty IS '주문 수량';


--
-- Name: COLUMN sales_order_line.unit_price; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.unit_price IS '판매 단가(원)';


--
-- Name: COLUMN sales_order_line.shipped_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.sales_order_line.shipped_qty IS '누적 출하 수량';


--
-- Name: shipment; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.shipment (
    ship_no character varying(20) NOT NULL,
    so_no character varying(20),
    item_code character varying(20),
    ship_date date,
    qty numeric(14,3),
    carrier character varying(50),
    lot_no character varying(20)
);


--
-- Name: TABLE shipment; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.shipment IS '출하';


--
-- Name: COLUMN shipment.ship_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.ship_no IS '출하 번호';


--
-- Name: COLUMN shipment.so_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.so_no IS '근거 수주 번호';


--
-- Name: COLUMN shipment.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.item_code IS '출하 품번';


--
-- Name: COLUMN shipment.ship_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.ship_date IS '출하일';


--
-- Name: COLUMN shipment.qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.qty IS '출하 수량';


--
-- Name: COLUMN shipment.carrier; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.carrier IS '운송사';


--
-- Name: COLUMN shipment.lot_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.shipment.lot_no IS '출하 제품 로트';


--
-- Name: stock; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.stock (
    warehouse character varying(50) NOT NULL,
    item_code character varying(20) NOT NULL,
    qty numeric(14,3),
    reserved_qty numeric(14,3),
    available_qty numeric(14,3),
    last_in_date date,
    last_out_date date
);


--
-- Name: TABLE stock; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.stock IS '창고별 재고';


--
-- Name: COLUMN stock.warehouse; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.warehouse IS '창고';


--
-- Name: COLUMN stock.item_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.item_code IS '품번';


--
-- Name: COLUMN stock.qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.qty IS '현재 재고 수량';


--
-- Name: COLUMN stock.reserved_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.reserved_qty IS '출고 예약 수량';


--
-- Name: COLUMN stock.available_qty; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.available_qty IS '가용 수량(현재 − 예약)';


--
-- Name: COLUMN stock.last_in_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.last_in_date IS '최근 입고일';


--
-- Name: COLUMN stock.last_out_date; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.stock.last_out_date IS '최근 출고일';


--
-- Name: vendor; Type: TABLE; Schema: erp; Owner: -
--

CREATE TABLE erp.vendor (
    vendor_code character varying(20) NOT NULL,
    vendor_name character varying(100),
    biz_no character varying(20),
    vendor_type character(1),
    contact_name character varying(50),
    payment_terms character varying(50),
    use_yn character(1)
);


--
-- Name: TABLE vendor; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON TABLE erp.vendor IS '거래처';


--
-- Name: COLUMN vendor.vendor_code; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.vendor_code IS '거래처 코드';


--
-- Name: COLUMN vendor.vendor_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.vendor_name IS '거래처 상호';


--
-- Name: COLUMN vendor.biz_no; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.biz_no IS '사업자등록번호(뒷자리 가림)';


--
-- Name: COLUMN vendor.vendor_type; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.vendor_type IS '거래 구분(P 매입처 · S 매출처 · B 매입·매출)';


--
-- Name: COLUMN vendor.contact_name; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.contact_name IS '담당자 이름(가명)';


--
-- Name: COLUMN vendor.payment_terms; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.payment_terms IS '결제 조건';


--
-- Name: COLUMN vendor.use_yn; Type: COMMENT; Schema: erp; Owner: -
--

COMMENT ON COLUMN erp.vendor.use_yn IS '사용 여부(Y/N)';


--
-- Data for Name: attendance; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.attendance (emp_no, work_date, clock_in, clock_out, work_type, overtime_min, late_yn) FROM stdin;
GP0006	2026-08-03	07:47:00	18:07:00	근무	60	N
GP0024	2026-08-03	19:45:00	05:25:00	근무	0	N
GP0027	2026-08-03	07:45:00	17:25:00	근무	0	N
GP0031	2026-08-03	07:54:00	17:50:00	근무	30	N
GP0036	2026-08-03	07:57:00	18:38:00	근무	90	N
GP0038	2026-08-03	19:47:00	05:22:00	근무	0	N
GP0040	2026-08-03	08:21:00	18:25:00	근무	60	Y
GP0044	2026-08-03	07:51:00	18:16:00	근무	60	N
GP0045	2026-08-03	07:48:00	17:38:00	근무	30	N
GP0048	2026-08-03	19:48:00	05:20:00	근무	0	N
GP0052	2026-08-03	07:58:00	17:41:00	근무	30	N
GP0055	2026-08-03	19:54:00	06:23:00	근무	60	N
GP0072	2026-08-03	07:50:00	18:14:00	근무	60	N
GP0074	2026-08-03	19:55:00	06:16:00	근무	60	N
GP0079	2026-08-03	07:46:00	18:51:00	근무	90	N
GP0101	2026-08-03	07:47:00	18:20:00	근무	60	N
GP0102	2026-08-03	07:49:00	17:08:00	근무	0	N
GP0110	2026-08-03	08:23:00	18:43:00	근무	90	Y
GP0111	2026-08-03	07:48:00	17:14:00	근무	0	N
GP0120	2026-08-03	07:46:00	18:08:00	근무	60	N
GP0130	2026-08-03	08:25:00	17:20:00	근무	0	Y
GP0131	2026-08-03	07:53:00	17:51:00	근무	30	N
GP0140	2026-08-03	07:50:00	18:43:00	근무	90	N
GP0150	2026-08-03	\N	\N	연차	0	N
GP0160	2026-08-03	07:58:00	18:21:00	근무	60	N
GP0006	2026-08-04	07:51:00	17:16:00	근무	0	N
GP0024	2026-08-04	19:51:00	06:54:00	근무	90	N
GP0027	2026-08-04	07:51:00	18:44:00	근무	90	N
GP0031	2026-08-04	07:57:00	17:13:00	근무	0	N
GP0036	2026-08-04	08:19:00	17:45:00	근무	30	Y
GP0038	2026-08-04	19:47:00	05:22:00	근무	0	N
GP0040	2026-08-04	07:53:00	17:18:00	근무	0	N
GP0044	2026-08-04	07:57:00	18:13:00	근무	60	N
GP0045	2026-08-04	07:51:00	18:54:00	근무	90	N
GP0048	2026-08-04	19:53:00	06:08:00	근무	60	N
GP0052	2026-08-04	08:12:00	17:16:00	근무	0	Y
GP0055	2026-08-04	19:47:00	06:41:00	근무	90	N
GP0072	2026-08-04	07:48:00	17:16:00	근무	0	N
GP0074	2026-08-04	19:52:00	06:36:00	근무	90	N
GP0079	2026-08-04	07:54:00	18:18:00	근무	60	N
GP0101	2026-08-04	07:55:00	17:24:00	근무	0	N
GP0102	2026-08-04	07:58:00	17:25:00	근무	0	N
GP0110	2026-08-04	07:50:00	17:41:00	근무	30	N
GP0111	2026-08-04	07:52:00	17:48:00	근무	30	N
GP0120	2026-08-04	07:48:00	18:53:00	근무	90	N
GP0130	2026-08-04	07:47:00	18:12:00	근무	60	N
GP0131	2026-08-04	07:58:00	18:09:00	근무	60	N
GP0140	2026-08-04	07:56:00	17:36:00	근무	30	N
GP0150	2026-08-04	07:47:00	18:12:00	근무	60	N
GP0160	2026-08-04	07:56:00	18:05:00	근무	60	N
GP0006	2026-08-05	07:50:00	17:09:00	근무	0	N
GP0024	2026-08-05	19:53:00	05:43:00	근무	30	N
GP0027	2026-08-05	07:59:00	18:50:00	근무	90	N
GP0031	2026-08-05	07:45:00	17:17:00	근무	0	N
GP0036	2026-08-05	07:53:00	17:41:00	근무	30	N
GP0038	2026-08-05	19:49:00	05:37:00	근무	30	N
GP0040	2026-08-05	07:50:00	18:51:00	근무	90	N
GP0044	2026-08-05	07:59:00	17:39:00	근무	30	N
GP0045	2026-08-05	07:59:00	17:45:00	근무	30	N
GP0048	2026-08-05	19:45:00	05:06:00	근무	0	N
GP0052	2026-08-05	07:53:00	18:35:00	근무	90	N
GP0055	2026-08-05	19:50:00	06:48:00	근무	90	N
GP0072	2026-08-05	07:49:00	18:39:00	근무	90	N
GP0074	2026-08-05	19:50:00	06:18:00	근무	60	N
GP0079	2026-08-05	07:52:00	17:20:00	근무	0	N
GP0101	2026-08-05	07:59:00	18:23:00	근무	60	N
GP0102	2026-08-05	07:51:00	17:06:00	근무	0	N
GP0110	2026-08-05	07:50:00	17:40:00	근무	30	N
GP0111	2026-08-05	07:50:00	18:55:00	근무	90	N
GP0120	2026-08-05	07:47:00	18:35:00	근무	90	N
GP0130	2026-08-05	07:45:00	18:52:00	근무	90	N
GP0131	2026-08-05	07:46:00	18:06:00	근무	60	N
GP0140	2026-08-05	07:50:00	17:19:00	근무	0	N
GP0150	2026-08-05	07:59:00	17:54:00	근무	30	N
GP0160	2026-08-05	07:54:00	17:05:00	근무	0	N
GP0006	2026-08-06	07:56:00	18:45:00	근무	90	N
GP0024	2026-08-06	19:58:00	05:55:00	근무	30	N
GP0027	2026-08-06	07:55:00	17:24:00	근무	0	N
GP0031	2026-08-06	07:52:00	17:21:00	근무	0	N
GP0036	2026-08-06	07:54:00	17:21:00	근무	0	N
GP0038	2026-08-06	19:52:00	05:24:00	근무	0	N
GP0040	2026-08-06	07:48:00	18:05:00	근무	60	N
GP0044	2026-08-06	07:48:00	17:40:00	근무	30	N
GP0045	2026-08-06	07:53:00	17:13:00	근무	0	N
GP0048	2026-08-06	19:57:00	05:17:00	근무	0	N
GP0052	2026-08-06	07:57:00	18:51:00	근무	90	N
GP0055	2026-08-06	19:54:00	05:53:00	근무	30	N
GP0072	2026-08-06	07:59:00	18:09:00	근무	60	N
GP0074	2026-08-06	19:50:00	06:42:00	근무	90	N
GP0079	2026-08-06	07:51:00	18:19:00	근무	60	N
GP0101	2026-08-06	07:57:00	17:39:00	근무	30	N
GP0102	2026-08-06	07:53:00	18:15:00	근무	60	N
GP0110	2026-08-06	07:47:00	17:37:00	근무	30	N
GP0111	2026-08-06	\N	\N	연차	0	N
GP0120	2026-08-06	\N	\N	연차	0	N
GP0130	2026-08-06	07:51:00	18:35:00	근무	90	N
GP0131	2026-08-06	07:54:00	18:43:00	근무	90	N
GP0140	2026-08-06	07:50:00	17:48:00	근무	30	N
GP0150	2026-08-06	07:49:00	17:11:00	근무	0	N
GP0160	2026-08-06	07:57:00	18:42:00	근무	90	N
GP0006	2026-08-07	07:51:00	17:22:00	근무	0	N
GP0024	2026-08-07	19:59:00	06:40:00	근무	90	N
GP0027	2026-08-07	07:48:00	18:19:00	근무	60	N
GP0031	2026-08-07	07:57:00	17:18:00	근무	0	N
GP0036	2026-08-07	07:48:00	18:47:00	근무	90	N
GP0038	2026-08-07	19:50:00	05:24:00	근무	0	N
GP0040	2026-08-07	07:59:00	17:05:00	근무	0	N
GP0044	2026-08-07	07:54:00	17:38:00	근무	30	N
GP0045	2026-08-07	07:46:00	18:15:00	근무	60	N
GP0048	2026-08-07	20:17:00	05:55:00	근무	30	Y
GP0052	2026-08-07	07:47:00	17:09:00	근무	0	N
GP0055	2026-08-07	19:50:00	05:42:00	근무	30	N
GP0072	2026-08-07	07:48:00	18:21:00	근무	60	N
GP0074	2026-08-07	19:45:00	06:48:00	근무	90	N
GP0079	2026-08-07	07:48:00	18:07:00	근무	60	N
GP0101	2026-08-07	07:57:00	17:20:00	근무	0	N
GP0102	2026-08-07	07:57:00	18:36:00	근무	90	N
GP0110	2026-08-07	07:58:00	17:25:00	근무	0	N
GP0111	2026-08-07	07:46:00	18:07:00	근무	60	N
GP0120	2026-08-07	07:54:00	17:25:00	근무	0	N
GP0130	2026-08-07	07:45:00	18:40:00	근무	90	N
GP0131	2026-08-07	07:49:00	18:36:00	근무	90	N
GP0140	2026-08-07	07:53:00	17:17:00	근무	0	N
GP0150	2026-08-07	07:58:00	18:36:00	근무	90	N
GP0160	2026-08-07	07:50:00	18:25:00	근무	60	N
GP0006	2026-08-08	07:54:00	17:14:00	근무	0	N
GP0031	2026-08-08	07:56:00	17:07:00	근무	0	N
GP0036	2026-08-08	08:15:00	18:50:00	근무	90	Y
GP0072	2026-08-08	07:48:00	17:51:00	근무	30	N
GP0006	2026-08-10	07:47:00	17:05:00	근무	0	N
GP0024	2026-08-10	19:59:00	05:10:00	근무	0	N
GP0027	2026-08-10	08:25:00	17:10:00	근무	0	Y
GP0031	2026-08-10	07:52:00	18:06:00	근무	60	N
GP0036	2026-08-10	07:45:00	17:06:00	근무	0	N
GP0038	2026-08-10	19:46:00	06:37:00	근무	90	N
GP0040	2026-08-10	07:52:00	17:50:00	근무	30	N
GP0044	2026-08-10	07:47:00	18:50:00	근무	90	N
GP0045	2026-08-10	07:52:00	17:23:00	근무	0	N
GP0048	2026-08-10	19:50:00	06:07:00	근무	60	N
GP0052	2026-08-10	07:58:00	17:11:00	근무	0	N
GP0055	2026-08-10	19:47:00	05:51:00	근무	30	N
GP0072	2026-08-10	07:45:00	18:43:00	근무	90	N
GP0074	2026-08-10	\N	\N	연차	0	N
GP0079	2026-08-10	07:53:00	17:48:00	근무	30	N
GP0101	2026-08-10	07:45:00	18:51:00	근무	90	N
GP0102	2026-08-10	07:49:00	17:43:00	근무	30	N
GP0110	2026-08-10	\N	\N	연차	0	N
GP0111	2026-08-10	07:53:00	17:18:00	근무	0	N
GP0120	2026-08-10	07:45:00	17:09:00	근무	0	N
GP0130	2026-08-10	07:58:00	18:08:00	근무	60	N
GP0131	2026-08-10	08:23:00	17:17:00	근무	0	Y
GP0140	2026-08-10	07:56:00	17:24:00	근무	0	N
GP0150	2026-08-10	\N	\N	연차	0	N
GP0160	2026-08-10	07:55:00	18:42:00	근무	90	N
GP0006	2026-08-11	07:59:00	17:18:00	근무	0	N
GP0024	2026-08-11	19:51:00	06:10:00	근무	60	N
GP0027	2026-08-11	07:46:00	17:52:00	근무	30	N
GP0031	2026-08-11	07:54:00	17:19:00	근무	0	N
GP0036	2026-08-11	07:56:00	18:16:00	근무	60	N
GP0038	2026-08-11	19:49:00	06:44:00	근무	90	N
GP0040	2026-08-11	\N	\N	연차	0	N
GP0044	2026-08-11	07:45:00	17:08:00	근무	0	N
GP0045	2026-08-11	07:48:00	17:55:00	근무	30	N
GP0048	2026-08-11	19:52:00	05:25:00	근무	0	N
GP0052	2026-08-11	07:50:00	18:48:00	근무	90	N
GP0055	2026-08-11	19:49:00	06:41:00	근무	90	N
GP0072	2026-08-11	07:53:00	18:42:00	근무	90	N
GP0074	2026-08-11	19:47:00	05:22:00	근무	0	N
GP0079	2026-08-11	07:48:00	17:24:00	근무	0	N
GP0101	2026-08-11	07:58:00	18:12:00	근무	60	N
GP0102	2026-08-11	07:49:00	18:05:00	근무	60	N
GP0110	2026-08-11	07:49:00	18:23:00	근무	60	N
GP0111	2026-08-11	07:58:00	17:48:00	근무	30	N
GP0120	2026-08-11	07:47:00	17:47:00	근무	30	N
GP0130	2026-08-11	07:50:00	17:22:00	근무	0	N
GP0131	2026-08-11	07:54:00	18:48:00	근무	90	N
GP0140	2026-08-11	07:54:00	17:08:00	근무	0	N
GP0150	2026-08-11	\N	\N	연차	0	N
GP0160	2026-08-11	07:55:00	17:09:00	근무	0	N
GP0006	2026-08-12	07:50:00	17:13:00	근무	0	N
GP0024	2026-08-12	19:52:00	06:21:00	근무	60	N
GP0027	2026-08-12	07:50:00	17:05:00	근무	0	N
GP0031	2026-08-12	07:46:00	17:25:00	근무	0	N
GP0036	2026-08-12	07:48:00	17:42:00	근무	30	N
GP0038	2026-08-12	19:53:00	05:21:00	근무	0	N
GP0040	2026-08-12	07:50:00	18:40:00	근무	90	N
GP0044	2026-08-12	\N	\N	연차	0	N
GP0045	2026-08-12	07:50:00	17:09:00	근무	0	N
GP0048	2026-08-12	19:49:00	05:05:00	근무	0	N
GP0052	2026-08-12	07:47:00	18:44:00	근무	90	N
GP0055	2026-08-12	19:50:00	05:52:00	근무	30	N
GP0072	2026-08-12	07:55:00	17:18:00	근무	0	N
GP0074	2026-08-12	19:45:00	05:19:00	근무	0	N
GP0079	2026-08-12	07:53:00	17:10:00	근무	0	N
GP0101	2026-08-12	07:57:00	18:38:00	근무	90	N
GP0102	2026-08-12	07:56:00	18:36:00	근무	90	N
GP0110	2026-08-12	07:48:00	17:05:00	근무	0	N
GP0111	2026-08-12	07:46:00	17:12:00	근무	0	N
GP0120	2026-08-12	07:53:00	18:14:00	근무	60	N
GP0130	2026-08-12	07:52:00	18:38:00	근무	90	N
GP0131	2026-08-12	07:54:00	18:11:00	근무	60	N
GP0140	2026-08-12	07:49:00	17:22:00	근무	0	N
GP0150	2026-08-12	07:51:00	17:47:00	근무	30	N
GP0160	2026-08-12	07:52:00	18:23:00	근무	60	N
GP0006	2026-08-13	07:53:00	18:17:00	근무	60	N
GP0024	2026-08-13	19:52:00	06:37:00	근무	90	N
GP0027	2026-08-13	07:50:00	17:23:00	근무	0	N
GP0031	2026-08-13	07:58:00	17:52:00	근무	30	N
GP0036	2026-08-13	07:49:00	18:52:00	근무	90	N
GP0038	2026-08-13	19:57:00	05:23:00	근무	0	N
GP0040	2026-08-13	07:45:00	17:40:00	근무	30	N
GP0044	2026-08-13	07:58:00	17:48:00	근무	30	N
GP0045	2026-08-13	07:55:00	18:53:00	근무	90	N
GP0048	2026-08-13	19:53:00	05:18:00	근무	0	N
GP0052	2026-08-13	07:54:00	18:54:00	근무	90	N
GP0055	2026-08-13	19:57:00	05:20:00	근무	0	N
GP0072	2026-08-13	07:46:00	18:23:00	근무	60	N
GP0074	2026-08-13	19:47:00	05:46:00	근무	30	N
GP0079	2026-08-13	07:57:00	17:06:00	근무	0	N
GP0101	2026-08-13	07:55:00	17:42:00	근무	30	N
GP0102	2026-08-13	07:57:00	18:22:00	근무	60	N
GP0110	2026-08-13	08:17:00	17:41:00	근무	30	Y
GP0111	2026-08-13	07:57:00	17:49:00	근무	30	N
GP0120	2026-08-13	07:59:00	17:47:00	근무	30	N
GP0130	2026-08-13	07:53:00	18:36:00	근무	90	N
GP0131	2026-08-13	07:49:00	18:18:00	근무	60	N
GP0140	2026-08-13	07:51:00	17:54:00	근무	30	N
GP0150	2026-08-13	07:49:00	17:22:00	근무	0	N
GP0160	2026-08-13	07:58:00	18:08:00	근무	60	N
GP0006	2026-08-14	07:52:00	17:43:00	근무	30	N
GP0024	2026-08-14	19:51:00	05:15:00	근무	0	N
GP0027	2026-08-14	\N	\N	연차	0	N
GP0031	2026-08-14	07:54:00	17:17:00	근무	0	N
GP0036	2026-08-14	07:48:00	17:51:00	근무	30	N
GP0038	2026-08-14	20:17:00	05:53:00	근무	30	Y
GP0040	2026-08-14	07:48:00	17:16:00	근무	0	N
GP0044	2026-08-14	07:51:00	17:20:00	근무	0	N
GP0045	2026-08-14	07:58:00	17:14:00	근무	0	N
GP0048	2026-08-14	19:57:00	05:36:00	근무	30	N
GP0052	2026-08-14	07:56:00	17:44:00	근무	30	N
GP0055	2026-08-14	19:48:00	06:07:00	근무	60	N
GP0072	2026-08-14	07:48:00	17:37:00	근무	30	N
GP0074	2026-08-14	19:51:00	06:11:00	근무	60	N
GP0079	2026-08-14	07:49:00	17:07:00	근무	0	N
GP0101	2026-08-14	07:53:00	17:09:00	근무	0	N
GP0102	2026-08-14	07:48:00	18:18:00	근무	60	N
GP0110	2026-08-14	08:18:00	17:05:00	근무	0	Y
GP0111	2026-08-14	07:47:00	17:20:00	근무	0	N
GP0120	2026-08-14	\N	\N	연차	0	N
GP0130	2026-08-14	07:49:00	17:50:00	근무	30	N
GP0131	2026-08-14	07:47:00	17:06:00	근무	0	N
GP0140	2026-08-14	07:50:00	18:24:00	근무	60	N
GP0150	2026-08-14	07:47:00	18:52:00	근무	90	N
GP0160	2026-08-14	07:47:00	18:21:00	근무	60	N
GP0006	2026-08-15	07:51:00	18:54:00	근무	90	N
GP0031	2026-08-15	07:55:00	17:55:00	근무	30	N
GP0006	2026-08-17	07:54:00	18:09:00	근무	60	N
GP0024	2026-08-17	20:14:00	06:45:00	근무	90	Y
GP0027	2026-08-17	07:59:00	18:08:00	근무	60	N
GP0031	2026-08-17	07:55:00	17:16:00	근무	0	N
GP0036	2026-08-17	07:45:00	18:24:00	근무	60	N
GP0038	2026-08-17	19:53:00	05:15:00	근무	0	N
GP0040	2026-08-17	07:56:00	17:50:00	근무	30	N
GP0044	2026-08-17	07:49:00	17:24:00	근무	0	N
GP0045	2026-08-17	08:25:00	17:08:00	근무	0	Y
GP0048	2026-08-17	19:56:00	05:05:00	근무	0	N
GP0052	2026-08-17	07:56:00	18:16:00	근무	60	N
GP0055	2026-08-17	19:51:00	05:45:00	근무	30	N
GP0072	2026-08-17	07:58:00	17:24:00	근무	0	N
GP0074	2026-08-17	20:18:00	06:06:00	근무	60	Y
GP0079	2026-08-17	07:58:00	18:40:00	근무	90	N
GP0101	2026-08-17	07:46:00	17:14:00	근무	0	N
GP0102	2026-08-17	07:46:00	17:13:00	근무	0	N
GP0110	2026-08-17	07:57:00	18:54:00	근무	90	N
GP0111	2026-08-17	07:55:00	17:18:00	근무	0	N
GP0120	2026-08-17	07:54:00	18:36:00	근무	90	N
GP0130	2026-08-17	07:51:00	18:51:00	근무	90	N
GP0131	2026-08-17	07:46:00	17:21:00	근무	0	N
GP0140	2026-08-17	07:50:00	17:51:00	근무	30	N
GP0150	2026-08-17	07:57:00	17:15:00	근무	0	N
GP0160	2026-08-17	07:48:00	17:23:00	근무	0	N
GP0006	2026-08-18	07:50:00	18:53:00	근무	90	N
GP0024	2026-08-18	19:56:00	05:05:00	근무	0	N
GP0027	2026-08-18	07:50:00	18:54:00	근무	90	N
GP0031	2026-08-18	07:54:00	18:42:00	근무	90	N
GP0036	2026-08-18	08:12:00	17:54:00	근무	30	Y
GP0038	2026-08-18	19:46:00	05:24:00	근무	0	N
GP0040	2026-08-18	07:51:00	18:22:00	근무	60	N
GP0044	2026-08-18	07:49:00	17:44:00	근무	30	N
GP0045	2026-08-18	07:48:00	17:18:00	근무	0	N
GP0048	2026-08-18	19:52:00	06:42:00	근무	90	N
GP0052	2026-08-18	\N	\N	연차	0	N
GP0055	2026-08-18	19:45:00	05:52:00	근무	30	N
GP0072	2026-08-18	07:54:00	17:18:00	근무	0	N
GP0074	2026-08-18	19:51:00	06:35:00	근무	90	N
GP0079	2026-08-18	07:49:00	17:25:00	근무	0	N
GP0101	2026-08-18	07:53:00	17:17:00	근무	0	N
GP0102	2026-08-18	07:51:00	17:38:00	근무	30	N
GP0110	2026-08-18	07:49:00	17:22:00	근무	0	N
GP0111	2026-08-18	07:52:00	18:37:00	근무	90	N
GP0120	2026-08-18	07:58:00	17:09:00	근무	0	N
GP0130	2026-08-18	07:58:00	17:07:00	근무	0	N
GP0131	2026-08-18	07:58:00	18:18:00	근무	60	N
GP0140	2026-08-18	07:58:00	18:45:00	근무	90	N
GP0150	2026-08-18	07:51:00	17:25:00	근무	0	N
GP0160	2026-08-18	07:45:00	17:23:00	근무	0	N
GP0006	2026-08-19	07:59:00	17:52:00	근무	30	N
GP0024	2026-08-19	19:52:00	06:05:00	근무	60	N
GP0027	2026-08-19	07:47:00	17:06:00	근무	0	N
GP0031	2026-08-19	07:46:00	17:38:00	근무	30	N
GP0036	2026-08-19	07:46:00	17:16:00	근무	0	N
GP0038	2026-08-19	19:52:00	06:19:00	근무	60	N
GP0040	2026-08-19	07:56:00	18:12:00	근무	60	N
GP0044	2026-08-19	07:55:00	17:15:00	근무	0	N
GP0045	2026-08-19	07:52:00	17:07:00	근무	0	N
GP0048	2026-08-19	19:47:00	06:18:00	근무	60	N
GP0052	2026-08-19	07:53:00	17:17:00	근무	0	N
GP0055	2026-08-19	19:47:00	06:46:00	근무	90	N
GP0072	2026-08-19	07:47:00	17:10:00	근무	0	N
GP0074	2026-08-19	19:59:00	06:22:00	근무	60	N
GP0079	2026-08-19	07:48:00	17:44:00	근무	30	N
GP0101	2026-08-19	07:46:00	17:12:00	근무	0	N
GP0102	2026-08-19	07:58:00	18:14:00	근무	60	N
GP0110	2026-08-19	07:55:00	18:40:00	근무	90	N
GP0111	2026-08-19	07:54:00	17:21:00	근무	0	N
GP0120	2026-08-19	08:19:00	17:50:00	근무	30	Y
GP0130	2026-08-19	07:53:00	17:18:00	근무	0	N
GP0131	2026-08-19	07:51:00	17:18:00	근무	0	N
GP0140	2026-08-19	07:46:00	17:16:00	근무	0	N
GP0150	2026-08-19	07:54:00	18:49:00	근무	90	N
GP0160	2026-08-19	07:48:00	18:23:00	근무	60	N
GP0006	2026-08-20	07:50:00	17:41:00	근무	30	N
GP0024	2026-08-20	19:45:00	05:13:00	근무	0	N
GP0027	2026-08-20	07:56:00	18:35:00	근무	90	N
GP0031	2026-08-20	07:55:00	17:47:00	근무	30	N
GP0036	2026-08-20	07:59:00	17:15:00	근무	0	N
GP0038	2026-08-20	19:54:00	06:13:00	근무	60	N
GP0040	2026-08-20	07:51:00	17:54:00	근무	30	N
GP0044	2026-08-20	08:12:00	17:20:00	근무	0	Y
GP0045	2026-08-20	07:46:00	17:05:00	근무	0	N
GP0048	2026-08-20	19:47:00	06:08:00	근무	60	N
GP0052	2026-08-20	\N	\N	연차	0	N
GP0055	2026-08-20	19:47:00	05:09:00	근무	0	N
GP0072	2026-08-20	07:49:00	17:22:00	근무	0	N
GP0074	2026-08-20	20:20:00	06:07:00	근무	60	Y
GP0079	2026-08-20	07:48:00	17:12:00	근무	0	N
GP0101	2026-08-20	08:18:00	17:20:00	근무	0	Y
GP0102	2026-08-20	07:45:00	17:23:00	근무	0	N
GP0110	2026-08-20	07:56:00	18:23:00	근무	60	N
GP0111	2026-08-20	07:57:00	17:50:00	근무	30	N
GP0120	2026-08-20	07:52:00	17:46:00	근무	30	N
GP0130	2026-08-20	07:50:00	18:09:00	근무	60	N
GP0131	2026-08-20	07:47:00	17:49:00	근무	30	N
GP0140	2026-08-20	07:54:00	17:47:00	근무	30	N
GP0150	2026-08-20	07:54:00	17:05:00	근무	0	N
GP0160	2026-08-20	07:53:00	17:08:00	근무	0	N
GP0006	2026-08-21	07:53:00	18:40:00	근무	90	N
GP0024	2026-08-21	19:46:00	05:15:00	근무	0	N
GP0027	2026-08-21	07:50:00	17:38:00	근무	30	N
GP0031	2026-08-21	07:56:00	17:11:00	근무	0	N
GP0036	2026-08-21	07:49:00	17:15:00	근무	0	N
GP0038	2026-08-21	19:47:00	05:44:00	근무	30	N
GP0040	2026-08-21	07:47:00	17:06:00	근무	0	N
GP0044	2026-08-21	07:46:00	17:51:00	근무	30	N
GP0045	2026-08-21	07:56:00	17:13:00	근무	0	N
GP0048	2026-08-21	19:59:00	05:20:00	근무	0	N
GP0052	2026-08-21	07:59:00	17:19:00	근무	0	N
GP0055	2026-08-21	19:51:00	06:43:00	근무	90	N
GP0072	2026-08-21	07:52:00	17:52:00	근무	30	N
GP0074	2026-08-21	19:46:00	06:55:00	근무	90	N
GP0079	2026-08-21	07:58:00	18:49:00	근무	90	N
GP0101	2026-08-21	07:53:00	17:39:00	근무	30	N
GP0102	2026-08-21	07:46:00	18:25:00	근무	60	N
GP0110	2026-08-21	07:47:00	17:11:00	근무	0	N
GP0111	2026-08-21	07:51:00	17:25:00	근무	0	N
GP0120	2026-08-21	07:45:00	18:05:00	근무	60	N
GP0130	2026-08-21	07:51:00	17:05:00	근무	0	N
GP0131	2026-08-21	07:46:00	17:16:00	근무	0	N
GP0140	2026-08-21	08:13:00	18:55:00	근무	90	Y
GP0150	2026-08-21	07:47:00	17:43:00	근무	30	N
GP0160	2026-08-21	07:58:00	17:51:00	근무	30	N
GP0031	2026-08-22	07:48:00	17:06:00	근무	0	N
GP0045	2026-08-22	07:45:00	17:21:00	근무	0	N
GP0052	2026-08-22	07:48:00	18:15:00	근무	60	N
GP0006	2026-08-24	07:48:00	17:12:00	근무	0	N
GP0024	2026-08-24	\N	\N	연차	0	N
GP0027	2026-08-24	07:46:00	18:39:00	근무	90	N
GP0031	2026-08-24	07:51:00	17:22:00	근무	0	N
GP0036	2026-08-24	07:55:00	18:37:00	근무	90	N
GP0038	2026-08-24	19:52:00	06:54:00	근무	90	N
GP0040	2026-08-24	07:51:00	17:24:00	근무	0	N
GP0044	2026-08-24	07:47:00	17:51:00	근무	30	N
GP0045	2026-08-24	07:59:00	17:52:00	근무	30	N
GP0048	2026-08-24	19:56:00	06:44:00	근무	90	N
GP0052	2026-08-24	\N	\N	연차	0	N
GP0055	2026-08-24	19:59:00	05:25:00	근무	0	N
GP0072	2026-08-24	07:57:00	18:46:00	근무	90	N
GP0074	2026-08-24	19:54:00	05:35:00	근무	30	N
GP0079	2026-08-24	07:47:00	18:23:00	근무	60	N
GP0101	2026-08-24	07:58:00	18:38:00	근무	90	N
GP0102	2026-08-24	07:54:00	18:40:00	근무	90	N
GP0110	2026-08-24	07:59:00	17:15:00	근무	0	N
GP0111	2026-08-24	07:57:00	17:21:00	근무	0	N
GP0120	2026-08-24	07:53:00	18:52:00	근무	90	N
GP0130	2026-08-24	07:55:00	17:14:00	근무	0	N
GP0131	2026-08-24	07:49:00	17:37:00	근무	30	N
GP0140	2026-08-24	07:49:00	17:36:00	근무	30	N
GP0150	2026-08-24	07:51:00	18:11:00	근무	60	N
GP0160	2026-08-24	07:45:00	17:24:00	근무	0	N
GP0006	2026-08-25	07:56:00	17:35:00	근무	30	N
GP0024	2026-08-25	19:56:00	06:39:00	근무	90	N
GP0027	2026-08-25	\N	\N	연차	0	N
GP0031	2026-08-25	07:46:00	18:12:00	근무	60	N
GP0036	2026-08-25	07:46:00	17:24:00	근무	0	N
GP0038	2026-08-25	19:55:00	05:08:00	근무	0	N
GP0040	2026-08-25	07:54:00	18:43:00	근무	90	N
GP0044	2026-08-25	07:50:00	17:18:00	근무	0	N
GP0045	2026-08-25	07:58:00	18:07:00	근무	60	N
GP0048	2026-08-25	19:51:00	05:08:00	근무	0	N
GP0052	2026-08-25	07:46:00	18:15:00	근무	60	N
GP0055	2026-08-25	19:59:00	06:14:00	근무	60	N
GP0072	2026-08-25	07:52:00	17:15:00	근무	0	N
GP0074	2026-08-25	19:53:00	06:50:00	근무	90	N
GP0079	2026-08-25	07:52:00	18:08:00	근무	60	N
GP0101	2026-08-25	07:47:00	17:15:00	근무	0	N
GP0102	2026-08-25	07:47:00	17:54:00	근무	30	N
GP0110	2026-08-25	07:55:00	17:09:00	근무	0	N
GP0111	2026-08-25	07:50:00	18:35:00	근무	90	N
GP0120	2026-08-25	07:54:00	17:38:00	근무	30	N
GP0130	2026-08-25	07:46:00	17:17:00	근무	0	N
GP0131	2026-08-25	07:51:00	17:05:00	근무	0	N
GP0140	2026-08-25	07:51:00	17:49:00	근무	30	N
GP0150	2026-08-25	07:50:00	18:06:00	근무	60	N
GP0160	2026-08-25	07:51:00	17:53:00	근무	30	N
GP0006	2026-08-26	07:49:00	17:19:00	근무	0	N
GP0024	2026-08-26	19:49:00	06:06:00	근무	60	N
GP0027	2026-08-26	07:45:00	17:42:00	근무	30	N
GP0031	2026-08-26	07:53:00	17:13:00	근무	0	N
GP0036	2026-08-26	07:59:00	18:46:00	근무	90	N
GP0038	2026-08-26	19:53:00	06:45:00	근무	90	N
GP0040	2026-08-26	07:51:00	17:08:00	근무	0	N
GP0044	2026-08-26	07:57:00	17:55:00	근무	30	N
GP0045	2026-08-26	07:55:00	17:17:00	근무	0	N
GP0048	2026-08-26	19:51:00	05:05:00	근무	0	N
GP0052	2026-08-26	07:52:00	17:07:00	근무	0	N
GP0055	2026-08-26	19:56:00	06:54:00	근무	90	N
GP0072	2026-08-26	07:54:00	17:51:00	근무	30	N
GP0074	2026-08-26	19:50:00	05:47:00	근무	30	N
GP0079	2026-08-26	07:55:00	17:09:00	근무	0	N
GP0101	2026-08-26	08:15:00	17:21:00	근무	0	Y
GP0102	2026-08-26	07:49:00	17:18:00	근무	0	N
GP0110	2026-08-26	07:55:00	17:44:00	근무	30	N
GP0111	2026-08-26	07:49:00	18:14:00	근무	60	N
GP0120	2026-08-26	07:47:00	17:19:00	근무	0	N
GP0130	2026-08-26	07:54:00	18:05:00	근무	60	N
GP0131	2026-08-26	07:56:00	18:10:00	근무	60	N
GP0140	2026-08-26	07:46:00	17:40:00	근무	30	N
GP0150	2026-08-26	\N	\N	연차	0	N
GP0160	2026-08-26	07:46:00	17:35:00	근무	30	N
GP0006	2026-08-27	07:54:00	18:36:00	근무	90	N
GP0024	2026-08-27	19:50:00	06:49:00	근무	90	N
GP0027	2026-08-27	08:22:00	17:35:00	근무	30	Y
GP0031	2026-08-27	07:58:00	17:23:00	근무	0	N
GP0036	2026-08-27	07:53:00	17:13:00	근무	0	N
GP0038	2026-08-27	19:49:00	06:16:00	근무	60	N
GP0040	2026-08-27	07:52:00	17:16:00	근무	0	N
GP0044	2026-08-27	07:49:00	18:23:00	근무	60	N
GP0045	2026-08-27	07:59:00	18:23:00	근무	60	N
GP0048	2026-08-27	19:47:00	05:16:00	근무	0	N
GP0052	2026-08-27	07:56:00	17:10:00	근무	0	N
GP0055	2026-08-27	20:25:00	05:40:00	근무	30	Y
GP0072	2026-08-27	07:53:00	17:24:00	근무	0	N
GP0074	2026-08-27	19:52:00	05:10:00	근무	0	N
GP0079	2026-08-27	\N	\N	연차	0	N
GP0101	2026-08-27	07:48:00	18:37:00	근무	90	N
GP0102	2026-08-27	07:48:00	17:50:00	근무	30	N
GP0110	2026-08-27	07:47:00	18:51:00	근무	90	N
GP0111	2026-08-27	07:49:00	17:15:00	근무	0	N
GP0120	2026-08-27	07:56:00	17:24:00	근무	0	N
GP0130	2026-08-27	07:48:00	17:45:00	근무	30	N
GP0131	2026-08-27	07:54:00	18:16:00	근무	60	N
GP0140	2026-08-27	07:47:00	18:54:00	근무	90	N
GP0150	2026-08-27	07:56:00	18:55:00	근무	90	N
GP0160	2026-08-27	07:55:00	18:37:00	근무	90	N
GP0006	2026-08-28	07:55:00	18:10:00	근무	60	N
GP0024	2026-08-28	19:50:00	05:11:00	근무	0	N
GP0027	2026-08-28	07:51:00	17:11:00	근무	0	N
GP0031	2026-08-28	07:45:00	17:25:00	근무	0	N
GP0036	2026-08-28	07:48:00	18:25:00	근무	60	N
GP0038	2026-08-28	19:54:00	05:17:00	근무	0	N
GP0040	2026-08-28	07:57:00	18:44:00	근무	90	N
GP0044	2026-08-28	07:47:00	18:47:00	근무	90	N
GP0045	2026-08-28	07:57:00	18:08:00	근무	60	N
GP0048	2026-08-28	19:47:00	05:12:00	근무	0	N
GP0052	2026-08-28	07:46:00	17:21:00	근무	0	N
GP0055	2026-08-28	19:50:00	05:20:00	근무	0	N
GP0072	2026-08-28	07:46:00	17:08:00	근무	0	N
GP0074	2026-08-28	19:50:00	05:09:00	근무	0	N
GP0079	2026-08-28	07:54:00	18:39:00	근무	90	N
GP0101	2026-08-28	08:20:00	17:45:00	근무	30	Y
GP0102	2026-08-28	07:52:00	18:39:00	근무	90	N
GP0110	2026-08-28	07:46:00	17:10:00	근무	0	N
GP0111	2026-08-28	07:49:00	17:15:00	근무	0	N
GP0120	2026-08-28	07:55:00	17:12:00	근무	0	N
GP0130	2026-08-28	07:54:00	18:52:00	근무	90	N
GP0131	2026-08-28	07:54:00	18:10:00	근무	60	N
GP0140	2026-08-28	07:45:00	18:55:00	근무	90	N
GP0150	2026-08-28	07:58:00	17:16:00	근무	0	N
GP0160	2026-08-28	07:54:00	17:11:00	근무	0	N
GP0006	2026-08-29	07:59:00	17:08:00	근무	0	N
GP0031	2026-08-29	07:59:00	18:08:00	근무	60	N
GP0036	2026-08-29	07:55:00	17:35:00	근무	30	N
GP0072	2026-08-29	08:18:00	18:15:00	근무	60	Y
GP0006	2026-08-31	07:51:00	17:24:00	근무	0	N
GP0024	2026-08-31	19:51:00	05:37:00	근무	30	N
GP0027	2026-08-31	07:55:00	17:53:00	근무	30	N
GP0031	2026-08-31	07:51:00	18:08:00	근무	60	N
GP0036	2026-08-31	07:48:00	18:49:00	근무	90	N
GP0038	2026-08-31	19:51:00	06:55:00	근무	90	N
GP0040	2026-08-31	07:48:00	18:44:00	근무	90	N
GP0044	2026-08-31	07:57:00	17:53:00	근무	30	N
GP0045	2026-08-31	07:58:00	18:35:00	근무	90	N
GP0048	2026-08-31	19:50:00	06:53:00	근무	90	N
GP0052	2026-08-31	07:55:00	17:15:00	근무	0	N
GP0055	2026-08-31	19:57:00	06:20:00	근무	60	N
GP0072	2026-08-31	07:53:00	18:53:00	근무	90	N
GP0074	2026-08-31	19:53:00	05:42:00	근무	30	N
GP0079	2026-08-31	08:24:00	17:40:00	근무	30	Y
GP0101	2026-08-31	07:55:00	17:15:00	근무	0	N
GP0102	2026-08-31	07:48:00	17:52:00	근무	30	N
GP0110	2026-08-31	07:55:00	18:42:00	근무	90	N
GP0111	2026-08-31	07:46:00	17:10:00	근무	0	N
GP0120	2026-08-31	07:46:00	18:18:00	근무	60	N
GP0130	2026-08-31	07:54:00	17:36:00	근무	30	N
GP0131	2026-08-31	07:59:00	18:51:00	근무	90	N
GP0140	2026-08-31	07:47:00	18:14:00	근무	60	N
GP0150	2026-08-31	07:46:00	18:21:00	근무	60	N
GP0160	2026-08-31	07:56:00	17:41:00	근무	30	N
GP0006	2026-09-01	07:56:00	17:09:00	근무	0	N
GP0024	2026-09-01	19:47:00	05:22:00	근무	0	N
GP0027	2026-09-01	07:48:00	18:18:00	근무	60	N
GP0031	2026-09-01	07:51:00	17:43:00	근무	30	N
GP0036	2026-09-01	07:57:00	17:36:00	근무	30	N
GP0038	2026-09-01	19:57:00	05:42:00	근무	30	N
GP0040	2026-09-01	07:50:00	17:19:00	근무	0	N
GP0044	2026-09-01	07:57:00	17:15:00	근무	0	N
GP0045	2026-09-01	07:49:00	17:38:00	근무	30	N
GP0048	2026-09-01	19:46:00	06:39:00	근무	90	N
GP0052	2026-09-01	07:52:00	18:47:00	근무	90	N
GP0055	2026-09-01	19:46:00	06:40:00	근무	90	N
GP0072	2026-09-01	07:51:00	18:41:00	근무	90	N
GP0074	2026-09-01	19:55:00	06:15:00	근무	60	N
GP0079	2026-09-01	07:49:00	17:48:00	근무	30	N
GP0101	2026-09-01	07:51:00	18:49:00	근무	90	N
GP0102	2026-09-01	07:48:00	17:24:00	근무	0	N
GP0110	2026-09-01	07:45:00	18:38:00	근무	90	N
GP0111	2026-09-01	07:51:00	17:21:00	근무	0	N
GP0120	2026-09-01	07:50:00	18:41:00	근무	90	N
GP0130	2026-09-01	07:57:00	17:25:00	근무	0	N
GP0131	2026-09-01	07:50:00	18:46:00	근무	90	N
GP0140	2026-09-01	07:47:00	17:12:00	근무	0	N
GP0150	2026-09-01	07:47:00	17:14:00	근무	0	N
GP0160	2026-09-01	07:47:00	18:43:00	근무	90	N
GP0006	2026-09-02	07:59:00	18:18:00	근무	60	N
GP0024	2026-09-02	19:58:00	05:08:00	근무	0	N
GP0027	2026-09-02	07:59:00	17:52:00	근무	30	N
GP0031	2026-09-02	07:47:00	17:14:00	근무	0	N
GP0036	2026-09-02	07:46:00	17:22:00	근무	0	N
GP0038	2026-09-02	19:47:00	05:16:00	근무	0	N
GP0040	2026-09-02	07:59:00	18:16:00	근무	60	N
GP0044	2026-09-02	07:50:00	18:36:00	근무	90	N
GP0045	2026-09-02	07:56:00	18:51:00	근무	90	N
GP0048	2026-09-02	19:49:00	05:49:00	근무	30	N
GP0052	2026-09-02	07:48:00	18:55:00	근무	90	N
GP0055	2026-09-02	19:59:00	06:23:00	근무	60	N
GP0072	2026-09-02	07:55:00	18:55:00	근무	90	N
GP0074	2026-09-02	19:50:00	05:13:00	근무	0	N
GP0079	2026-09-02	07:54:00	17:51:00	근무	30	N
GP0101	2026-09-02	07:57:00	17:16:00	근무	0	N
GP0102	2026-09-02	07:52:00	18:43:00	근무	90	N
GP0110	2026-09-02	07:50:00	18:47:00	근무	90	N
GP0111	2026-09-02	07:51:00	17:07:00	근무	0	N
GP0120	2026-09-02	07:46:00	18:11:00	근무	60	N
GP0130	2026-09-02	07:50:00	17:13:00	근무	0	N
GP0131	2026-09-02	07:58:00	18:18:00	근무	60	N
GP0140	2026-09-02	07:54:00	18:06:00	근무	60	N
GP0150	2026-09-02	07:53:00	17:20:00	근무	0	N
GP0160	2026-09-02	07:50:00	17:52:00	근무	30	N
GP0006	2026-09-03	07:50:00	18:43:00	근무	90	N
GP0024	2026-09-03	19:48:00	06:36:00	근무	90	N
GP0027	2026-09-03	07:53:00	17:55:00	근무	30	N
GP0031	2026-09-03	07:55:00	17:19:00	근무	0	N
GP0036	2026-09-03	\N	\N	연차	0	N
GP0038	2026-09-03	19:54:00	05:12:00	근무	0	N
GP0040	2026-09-03	07:46:00	18:48:00	근무	90	N
GP0044	2026-09-03	07:58:00	17:22:00	근무	0	N
GP0045	2026-09-03	07:59:00	18:46:00	근무	90	N
GP0048	2026-09-03	19:45:00	06:53:00	근무	90	N
GP0052	2026-09-03	07:55:00	18:06:00	근무	60	N
GP0055	2026-09-03	19:53:00	05:15:00	근무	0	N
GP0072	2026-09-03	07:56:00	18:52:00	근무	90	N
GP0074	2026-09-03	19:51:00	05:37:00	근무	30	N
GP0079	2026-09-03	07:50:00	17:47:00	근무	30	N
GP0101	2026-09-03	07:58:00	18:18:00	근무	60	N
GP0102	2026-09-03	07:51:00	18:14:00	근무	60	N
GP0110	2026-09-03	07:50:00	18:24:00	근무	60	N
GP0111	2026-09-03	07:59:00	17:15:00	근무	0	N
GP0120	2026-09-03	07:58:00	17:06:00	근무	0	N
GP0130	2026-09-03	07:55:00	18:50:00	근무	90	N
GP0131	2026-09-03	07:59:00	18:44:00	근무	90	N
GP0140	2026-09-03	08:19:00	17:46:00	근무	30	Y
GP0150	2026-09-03	07:53:00	17:19:00	근무	0	N
GP0160	2026-09-03	07:58:00	17:19:00	근무	0	N
GP0006	2026-09-04	07:55:00	17:54:00	근무	30	N
GP0024	2026-09-04	19:45:00	06:16:00	근무	60	N
GP0027	2026-09-04	08:24:00	17:19:00	근무	0	Y
GP0031	2026-09-04	07:49:00	18:51:00	근무	90	N
GP0036	2026-09-04	07:50:00	17:09:00	근무	0	N
GP0038	2026-09-04	19:54:00	05:11:00	근무	0	N
GP0040	2026-09-04	07:59:00	17:22:00	근무	0	N
GP0044	2026-09-04	07:48:00	17:23:00	근무	0	N
GP0045	2026-09-04	08:15:00	17:39:00	근무	30	Y
GP0048	2026-09-04	\N	\N	연차	0	N
GP0052	2026-09-04	\N	\N	연차	0	N
GP0055	2026-09-04	20:19:00	06:54:00	근무	90	Y
GP0072	2026-09-04	\N	\N	연차	0	N
GP0074	2026-09-04	19:51:00	05:47:00	근무	30	N
GP0079	2026-09-04	07:45:00	17:48:00	근무	30	N
GP0101	2026-09-04	07:49:00	17:36:00	근무	30	N
GP0102	2026-09-04	08:12:00	18:40:00	근무	90	Y
GP0110	2026-09-04	07:52:00	17:47:00	근무	30	N
GP0111	2026-09-04	07:48:00	17:46:00	근무	30	N
GP0120	2026-09-04	07:56:00	17:11:00	근무	0	N
GP0130	2026-09-04	07:45:00	18:52:00	근무	90	N
GP0131	2026-09-04	07:57:00	17:49:00	근무	30	N
GP0140	2026-09-04	07:49:00	17:08:00	근무	0	N
GP0150	2026-09-04	07:55:00	18:54:00	근무	90	N
GP0160	2026-09-04	07:47:00	17:39:00	근무	30	N
GP0006	2026-09-05	07:45:00	17:10:00	근무	0	N
GP0036	2026-09-05	07:45:00	17:38:00	근무	30	N
GP0072	2026-09-05	07:58:00	17:14:00	근무	0	N
GP0006	2026-09-07	07:51:00	18:42:00	근무	90	N
GP0024	2026-09-07	19:46:00	05:35:00	근무	30	N
GP0027	2026-09-07	07:46:00	17:48:00	근무	30	N
GP0031	2026-09-07	07:52:00	17:13:00	근무	0	N
GP0036	2026-09-07	07:57:00	18:41:00	근무	90	N
GP0038	2026-09-07	19:51:00	05:51:00	근무	30	N
GP0040	2026-09-07	08:19:00	17:52:00	근무	30	Y
GP0044	2026-09-07	07:52:00	18:18:00	근무	60	N
GP0045	2026-09-07	07:47:00	17:07:00	근무	0	N
GP0048	2026-09-07	19:49:00	05:12:00	근무	0	N
GP0052	2026-09-07	07:46:00	17:20:00	근무	0	N
GP0055	2026-09-07	19:51:00	05:40:00	근무	30	N
GP0072	2026-09-07	07:54:00	18:42:00	근무	90	N
GP0074	2026-09-07	19:46:00	05:15:00	근무	0	N
GP0079	2026-09-07	08:19:00	17:35:00	근무	30	Y
GP0101	2026-09-07	07:46:00	17:12:00	근무	0	N
GP0102	2026-09-07	07:57:00	17:43:00	근무	30	N
GP0110	2026-09-07	07:59:00	18:49:00	근무	90	N
GP0111	2026-09-07	07:59:00	18:53:00	근무	90	N
GP0120	2026-09-07	07:45:00	17:09:00	근무	0	N
GP0130	2026-09-07	07:51:00	17:16:00	근무	0	N
GP0131	2026-09-07	07:48:00	18:52:00	근무	90	N
GP0140	2026-09-07	07:54:00	17:25:00	근무	0	N
GP0150	2026-09-07	07:51:00	17:24:00	근무	0	N
GP0160	2026-09-07	07:48:00	17:52:00	근무	30	N
GP0006	2026-09-08	07:55:00	17:21:00	근무	0	N
GP0024	2026-09-08	19:49:00	05:19:00	근무	0	N
GP0027	2026-09-08	07:55:00	18:41:00	근무	90	N
GP0031	2026-09-08	07:46:00	17:51:00	근무	30	N
GP0036	2026-09-08	07:47:00	17:21:00	근무	0	N
GP0038	2026-09-08	19:51:00	05:38:00	근무	30	N
GP0040	2026-09-08	07:55:00	18:22:00	근무	60	N
GP0044	2026-09-08	07:56:00	18:51:00	근무	90	N
GP0045	2026-09-08	07:57:00	17:14:00	근무	0	N
GP0048	2026-09-08	19:47:00	05:11:00	근무	0	N
GP0052	2026-09-08	07:58:00	18:09:00	근무	60	N
GP0055	2026-09-08	19:48:00	06:23:00	근무	60	N
GP0072	2026-09-08	07:54:00	17:46:00	근무	30	N
GP0074	2026-09-08	19:53:00	06:45:00	근무	90	N
GP0079	2026-09-08	08:25:00	17:18:00	근무	0	Y
GP0101	2026-09-08	07:53:00	17:24:00	근무	0	N
GP0102	2026-09-08	07:59:00	18:13:00	근무	60	N
GP0110	2026-09-08	07:49:00	18:13:00	근무	60	N
GP0111	2026-09-08	07:51:00	18:12:00	근무	60	N
GP0120	2026-09-08	07:59:00	17:13:00	근무	0	N
GP0130	2026-09-08	07:48:00	17:19:00	근무	0	N
GP0131	2026-09-08	07:46:00	17:12:00	근무	0	N
GP0140	2026-09-08	07:53:00	17:25:00	근무	0	N
GP0150	2026-09-08	\N	\N	연차	0	N
GP0160	2026-09-08	07:55:00	17:43:00	근무	30	N
GP0006	2026-09-09	07:46:00	17:15:00	근무	0	N
GP0024	2026-09-09	19:59:00	05:13:00	근무	0	N
GP0027	2026-09-09	07:49:00	18:25:00	근무	60	N
GP0031	2026-09-09	07:54:00	18:54:00	근무	90	N
GP0036	2026-09-09	07:55:00	17:19:00	근무	0	N
GP0038	2026-09-09	19:56:00	05:46:00	근무	30	N
GP0040	2026-09-09	07:45:00	17:17:00	근무	0	N
GP0044	2026-09-09	08:20:00	17:25:00	근무	0	Y
GP0045	2026-09-09	07:57:00	18:37:00	근무	90	N
GP0048	2026-09-09	19:56:00	06:11:00	근무	60	N
GP0052	2026-09-09	07:47:00	17:54:00	근무	30	N
GP0055	2026-09-09	19:51:00	05:17:00	근무	0	N
GP0072	2026-09-09	07:56:00	17:16:00	근무	0	N
GP0074	2026-09-09	19:46:00	05:07:00	근무	0	N
GP0079	2026-09-09	07:51:00	17:14:00	근무	0	N
GP0101	2026-09-09	07:59:00	17:20:00	근무	0	N
GP0102	2026-09-09	07:45:00	18:11:00	근무	60	N
GP0110	2026-09-09	07:57:00	18:07:00	근무	60	N
GP0111	2026-09-09	07:45:00	18:25:00	근무	60	N
GP0120	2026-09-09	07:51:00	17:08:00	근무	0	N
GP0130	2026-09-09	07:56:00	17:23:00	근무	0	N
GP0131	2026-09-09	07:57:00	17:17:00	근무	0	N
GP0140	2026-09-09	07:50:00	17:35:00	근무	30	N
GP0150	2026-09-09	07:59:00	18:44:00	근무	90	N
GP0160	2026-09-09	08:21:00	17:13:00	근무	0	Y
GP0006	2026-09-10	08:17:00	18:54:00	근무	90	Y
GP0024	2026-09-10	19:56:00	06:45:00	근무	90	N
GP0027	2026-09-10	07:56:00	18:44:00	근무	90	N
GP0031	2026-09-10	07:52:00	17:18:00	근무	0	N
GP0036	2026-09-10	07:51:00	17:24:00	근무	0	N
GP0038	2026-09-10	19:51:00	05:18:00	근무	0	N
GP0040	2026-09-10	07:56:00	17:45:00	근무	30	N
GP0044	2026-09-10	07:47:00	17:35:00	근무	30	N
GP0045	2026-09-10	07:46:00	17:20:00	근무	0	N
GP0048	2026-09-10	19:52:00	05:40:00	근무	30	N
GP0052	2026-09-10	07:57:00	18:10:00	근무	60	N
GP0055	2026-09-10	19:56:00	05:41:00	근무	30	N
GP0072	2026-09-10	07:56:00	17:52:00	근무	30	N
GP0074	2026-09-10	19:50:00	06:12:00	근무	60	N
GP0079	2026-09-10	07:57:00	17:10:00	근무	0	N
GP0101	2026-09-10	07:56:00	17:42:00	근무	30	N
GP0102	2026-09-10	07:49:00	17:36:00	근무	30	N
GP0110	2026-09-10	07:53:00	18:09:00	근무	60	N
GP0111	2026-09-10	07:48:00	17:53:00	근무	30	N
GP0120	2026-09-10	07:57:00	17:40:00	근무	30	N
GP0130	2026-09-10	07:53:00	17:13:00	근무	0	N
GP0131	2026-09-10	07:56:00	18:11:00	근무	60	N
GP0140	2026-09-10	07:56:00	18:09:00	근무	60	N
GP0150	2026-09-10	07:47:00	18:55:00	근무	90	N
GP0160	2026-09-10	07:50:00	17:40:00	근무	30	N
GP0006	2026-09-11	07:57:00	18:10:00	근무	60	N
GP0024	2026-09-11	19:58:00	06:24:00	근무	60	N
GP0027	2026-09-11	07:54:00	18:55:00	근무	90	N
GP0031	2026-09-11	07:49:00	17:10:00	근무	0	N
GP0036	2026-09-11	07:45:00	17:48:00	근무	30	N
GP0038	2026-09-11	19:48:00	06:05:00	근무	60	N
GP0040	2026-09-11	07:47:00	17:09:00	근무	0	N
GP0044	2026-09-11	07:47:00	18:47:00	근무	90	N
GP0045	2026-09-11	07:59:00	17:12:00	근무	0	N
GP0048	2026-09-11	19:46:00	05:22:00	근무	0	N
GP0052	2026-09-11	07:50:00	17:05:00	근무	0	N
GP0055	2026-09-11	19:46:00	05:21:00	근무	0	N
GP0072	2026-09-11	07:57:00	17:12:00	근무	0	N
GP0074	2026-09-11	19:52:00	05:52:00	근무	30	N
GP0079	2026-09-11	07:47:00	17:05:00	근무	0	N
GP0101	2026-09-11	07:58:00	17:37:00	근무	30	N
GP0102	2026-09-11	07:45:00	18:52:00	근무	90	N
GP0110	2026-09-11	08:15:00	17:08:00	근무	0	Y
GP0111	2026-09-11	07:53:00	18:23:00	근무	60	N
GP0120	2026-09-11	07:58:00	18:45:00	근무	90	N
GP0130	2026-09-11	07:54:00	18:44:00	근무	90	N
GP0131	2026-09-11	07:56:00	18:23:00	근무	60	N
GP0140	2026-09-11	07:53:00	18:40:00	근무	90	N
GP0150	2026-09-11	07:59:00	17:36:00	근무	30	N
GP0160	2026-09-11	07:46:00	17:10:00	근무	0	N
GP0006	2026-09-12	07:51:00	17:25:00	근무	0	N
GP0045	2026-09-12	07:59:00	18:16:00	근무	60	N
GP0072	2026-09-12	07:48:00	17:08:00	근무	0	N
GP0006	2026-09-14	07:48:00	18:25:00	근무	60	N
GP0024	2026-09-14	19:52:00	05:24:00	근무	0	N
GP0027	2026-09-14	07:50:00	17:39:00	근무	30	N
GP0031	2026-09-14	07:45:00	17:55:00	근무	30	N
GP0036	2026-09-14	07:47:00	18:45:00	근무	90	N
GP0038	2026-09-14	19:46:00	05:06:00	근무	0	N
GP0040	2026-09-14	07:57:00	17:12:00	근무	0	N
GP0044	2026-09-14	07:47:00	17:07:00	근무	0	N
GP0045	2026-09-14	07:48:00	17:12:00	근무	0	N
GP0048	2026-09-14	19:50:00	05:47:00	근무	30	N
GP0052	2026-09-14	07:55:00	18:12:00	근무	60	N
GP0055	2026-09-14	19:54:00	06:49:00	근무	90	N
GP0072	2026-09-14	07:56:00	17:13:00	근무	0	N
GP0074	2026-09-14	19:45:00	05:08:00	근무	0	N
GP0079	2026-09-14	07:45:00	17:43:00	근무	30	N
GP0101	2026-09-14	07:51:00	17:20:00	근무	0	N
GP0102	2026-09-14	07:45:00	18:48:00	근무	90	N
GP0110	2026-09-14	07:58:00	17:50:00	근무	30	N
GP0111	2026-09-14	07:52:00	17:25:00	근무	0	N
GP0120	2026-09-14	07:47:00	18:16:00	근무	60	N
GP0130	2026-09-14	07:59:00	17:23:00	근무	0	N
GP0131	2026-09-14	07:59:00	18:16:00	근무	60	N
GP0140	2026-09-14	07:45:00	17:14:00	근무	0	N
GP0150	2026-09-14	\N	\N	연차	0	N
GP0160	2026-09-14	07:48:00	17:07:00	근무	0	N
GP0006	2026-09-15	07:51:00	17:47:00	근무	30	N
GP0024	2026-09-15	19:57:00	06:53:00	근무	90	N
GP0027	2026-09-15	07:51:00	18:22:00	근무	60	N
GP0031	2026-09-15	07:46:00	17:16:00	근무	0	N
GP0036	2026-09-15	07:51:00	17:18:00	근무	0	N
GP0038	2026-09-15	19:55:00	05:44:00	근무	30	N
GP0040	2026-09-15	07:51:00	17:16:00	근무	0	N
GP0044	2026-09-15	07:47:00	17:24:00	근무	0	N
GP0045	2026-09-15	07:59:00	18:39:00	근무	90	N
GP0048	2026-09-15	19:56:00	06:22:00	근무	60	N
GP0052	2026-09-15	07:56:00	18:15:00	근무	60	N
GP0055	2026-09-15	19:56:00	05:20:00	근무	0	N
GP0072	2026-09-15	07:54:00	17:09:00	근무	0	N
GP0074	2026-09-15	19:59:00	05:10:00	근무	0	N
GP0079	2026-09-15	07:57:00	17:15:00	근무	0	N
GP0101	2026-09-15	07:45:00	17:54:00	근무	30	N
GP0102	2026-09-15	07:45:00	17:17:00	근무	0	N
GP0110	2026-09-15	07:50:00	17:11:00	근무	0	N
GP0111	2026-09-15	07:52:00	18:39:00	근무	90	N
GP0120	2026-09-15	07:58:00	17:44:00	근무	30	N
GP0130	2026-09-15	07:46:00	17:05:00	근무	0	N
GP0131	2026-09-15	07:50:00	17:25:00	근무	0	N
GP0140	2026-09-15	07:48:00	17:17:00	근무	0	N
GP0150	2026-09-15	07:55:00	17:19:00	근무	0	N
GP0160	2026-09-15	07:58:00	18:55:00	근무	90	N
GP0006	2026-09-16	07:54:00	17:20:00	근무	0	N
GP0024	2026-09-16	19:51:00	06:09:00	근무	60	N
GP0027	2026-09-16	07:58:00	17:21:00	근무	0	N
GP0031	2026-09-16	07:50:00	18:13:00	근무	60	N
GP0036	2026-09-16	07:56:00	17:18:00	근무	0	N
GP0038	2026-09-16	20:15:00	06:43:00	근무	90	Y
GP0040	2026-09-16	07:59:00	18:16:00	근무	60	N
GP0044	2026-09-16	07:51:00	17:13:00	근무	0	N
GP0045	2026-09-16	07:47:00	18:18:00	근무	60	N
GP0048	2026-09-16	19:51:00	05:36:00	근무	30	N
GP0052	2026-09-16	07:57:00	17:38:00	근무	30	N
GP0055	2026-09-16	19:58:00	05:22:00	근무	0	N
GP0072	2026-09-16	07:46:00	18:10:00	근무	60	N
GP0074	2026-09-16	19:45:00	06:05:00	근무	60	N
GP0079	2026-09-16	07:45:00	17:05:00	근무	0	N
GP0101	2026-09-16	07:53:00	17:07:00	근무	0	N
GP0102	2026-09-16	07:51:00	18:40:00	근무	90	N
GP0110	2026-09-16	07:46:00	18:13:00	근무	60	N
GP0111	2026-09-16	07:52:00	17:48:00	근무	30	N
GP0120	2026-09-16	07:59:00	17:53:00	근무	30	N
GP0130	2026-09-16	07:54:00	17:45:00	근무	30	N
GP0131	2026-09-16	07:49:00	17:08:00	근무	0	N
GP0140	2026-09-16	07:46:00	18:36:00	근무	90	N
GP0150	2026-09-16	07:47:00	17:25:00	근무	0	N
GP0160	2026-09-16	07:50:00	18:54:00	근무	90	N
GP0006	2026-09-17	07:57:00	17:39:00	근무	30	N
GP0024	2026-09-17	19:53:00	05:12:00	근무	0	N
GP0027	2026-09-17	07:53:00	18:20:00	근무	60	N
GP0031	2026-09-17	07:56:00	17:13:00	근무	0	N
GP0036	2026-09-17	07:49:00	17:50:00	근무	30	N
GP0038	2026-09-17	19:46:00	05:42:00	근무	30	N
GP0040	2026-09-17	07:48:00	17:15:00	근무	0	N
GP0044	2026-09-17	07:45:00	18:11:00	근무	60	N
GP0045	2026-09-17	07:48:00	18:15:00	근무	60	N
GP0048	2026-09-17	19:51:00	05:22:00	근무	0	N
GP0052	2026-09-17	07:51:00	17:23:00	근무	0	N
GP0055	2026-09-17	19:46:00	05:13:00	근무	0	N
GP0072	2026-09-17	07:47:00	18:15:00	근무	60	N
GP0074	2026-09-17	19:47:00	05:10:00	근무	0	N
GP0079	2026-09-17	07:57:00	18:15:00	근무	60	N
GP0101	2026-09-17	07:56:00	18:06:00	근무	60	N
GP0102	2026-09-17	07:48:00	17:25:00	근무	0	N
GP0110	2026-09-17	07:47:00	18:19:00	근무	60	N
GP0111	2026-09-17	07:58:00	18:44:00	근무	90	N
GP0120	2026-09-17	07:47:00	18:35:00	근무	90	N
GP0130	2026-09-17	07:49:00	17:16:00	근무	0	N
GP0131	2026-09-17	07:50:00	17:18:00	근무	0	N
GP0140	2026-09-17	07:46:00	18:37:00	근무	90	N
GP0150	2026-09-17	07:50:00	17:39:00	근무	30	N
GP0160	2026-09-17	08:15:00	18:52:00	근무	90	Y
GP0006	2026-09-18	07:45:00	18:46:00	근무	90	N
GP0024	2026-09-18	19:54:00	05:17:00	근무	0	N
GP0027	2026-09-18	07:55:00	17:22:00	근무	0	N
GP0031	2026-09-18	07:49:00	18:09:00	근무	60	N
GP0036	2026-09-18	07:51:00	17:16:00	근무	0	N
GP0038	2026-09-18	19:45:00	05:13:00	근무	0	N
GP0040	2026-09-18	07:53:00	18:17:00	근무	60	N
GP0044	2026-09-18	07:45:00	18:35:00	근무	90	N
GP0045	2026-09-18	07:46:00	17:12:00	근무	0	N
GP0048	2026-09-18	19:51:00	05:06:00	근무	0	N
GP0052	2026-09-18	07:55:00	17:45:00	근무	30	N
GP0055	2026-09-18	19:56:00	06:21:00	근무	60	N
GP0072	2026-09-18	07:56:00	18:21:00	근무	60	N
GP0074	2026-09-18	20:18:00	05:14:00	근무	0	Y
GP0079	2026-09-18	07:55:00	18:36:00	근무	90	N
GP0101	2026-09-18	07:45:00	17:21:00	근무	0	N
GP0102	2026-09-18	07:46:00	18:42:00	근무	90	N
GP0110	2026-09-18	07:59:00	17:20:00	근무	0	N
GP0111	2026-09-18	07:58:00	18:50:00	근무	90	N
GP0120	2026-09-18	08:17:00	17:11:00	근무	0	Y
GP0130	2026-09-18	07:52:00	17:43:00	근무	30	N
GP0131	2026-09-18	07:58:00	17:17:00	근무	0	N
GP0140	2026-09-18	07:52:00	17:11:00	근무	0	N
GP0150	2026-09-18	07:53:00	17:35:00	근무	30	N
GP0160	2026-09-18	07:49:00	18:43:00	근무	90	N
GP0006	2026-09-19	07:59:00	17:37:00	근무	30	N
GP0040	2026-09-19	07:51:00	17:23:00	근무	0	N
GP0072	2026-09-19	07:47:00	17:11:00	근무	0	N
GP0006	2026-09-21	07:55:00	17:23:00	근무	0	N
GP0024	2026-09-21	19:49:00	05:09:00	근무	0	N
GP0027	2026-09-21	07:48:00	17:35:00	근무	30	N
GP0031	2026-09-21	07:59:00	18:13:00	근무	60	N
GP0036	2026-09-21	07:57:00	17:08:00	근무	0	N
GP0038	2026-09-21	19:58:00	06:36:00	근무	90	N
GP0040	2026-09-21	07:57:00	18:49:00	근무	90	N
GP0044	2026-09-21	\N	\N	연차	0	N
GP0045	2026-09-21	07:58:00	17:14:00	근무	0	N
GP0048	2026-09-21	19:54:00	05:16:00	근무	0	N
GP0052	2026-09-21	07:53:00	17:49:00	근무	30	N
GP0055	2026-09-21	19:58:00	05:38:00	근무	30	N
GP0072	2026-09-21	08:12:00	17:20:00	근무	0	Y
GP0074	2026-09-21	19:48:00	06:13:00	근무	60	N
GP0079	2026-09-21	07:48:00	17:44:00	근무	30	N
GP0101	2026-09-21	\N	\N	연차	0	N
GP0102	2026-09-21	07:52:00	17:10:00	근무	0	N
GP0110	2026-09-21	07:52:00	17:47:00	근무	30	N
GP0111	2026-09-21	07:58:00	17:16:00	근무	0	N
GP0120	2026-09-21	07:58:00	17:36:00	근무	30	N
GP0130	2026-09-21	07:58:00	17:49:00	근무	30	N
GP0131	2026-09-21	08:19:00	18:52:00	근무	90	Y
GP0140	2026-09-21	07:45:00	18:21:00	근무	60	N
GP0150	2026-09-21	07:47:00	17:23:00	근무	0	N
GP0160	2026-09-21	07:53:00	17:55:00	근무	30	N
GP0006	2026-09-22	07:48:00	17:18:00	근무	0	N
GP0024	2026-09-22	19:50:00	05:38:00	근무	30	N
GP0027	2026-09-22	07:48:00	18:51:00	근무	90	N
GP0031	2026-09-22	07:54:00	17:20:00	근무	0	N
GP0036	2026-09-22	07:52:00	17:05:00	근무	0	N
GP0038	2026-09-22	20:15:00	05:07:00	근무	0	Y
GP0040	2026-09-22	07:53:00	17:14:00	근무	0	N
GP0044	2026-09-22	07:45:00	18:46:00	근무	90	N
GP0045	2026-09-22	07:52:00	18:08:00	근무	60	N
GP0048	2026-09-22	19:51:00	05:16:00	근무	0	N
GP0052	2026-09-22	07:50:00	18:11:00	근무	60	N
GP0055	2026-09-22	19:47:00	06:49:00	근무	90	N
GP0072	2026-09-22	07:49:00	18:55:00	근무	90	N
GP0074	2026-09-22	19:46:00	05:10:00	근무	0	N
GP0079	2026-09-22	08:16:00	17:12:00	근무	0	Y
GP0101	2026-09-22	07:56:00	17:12:00	근무	0	N
GP0102	2026-09-22	07:58:00	17:42:00	근무	30	N
GP0110	2026-09-22	07:52:00	18:54:00	근무	90	N
GP0111	2026-09-22	07:58:00	17:45:00	근무	30	N
GP0120	2026-09-22	07:53:00	18:20:00	근무	60	N
GP0130	2026-09-22	07:56:00	17:18:00	근무	0	N
GP0131	2026-09-22	07:53:00	18:38:00	근무	90	N
GP0140	2026-09-22	08:13:00	18:21:00	근무	60	Y
GP0150	2026-09-22	07:52:00	18:18:00	근무	60	N
GP0160	2026-09-22	07:56:00	17:35:00	근무	30	N
GP0006	2026-09-23	07:59:00	17:42:00	근무	30	N
GP0024	2026-09-23	20:23:00	05:11:00	근무	0	Y
GP0027	2026-09-23	07:46:00	17:24:00	근무	0	N
GP0031	2026-09-23	07:46:00	17:46:00	근무	30	N
GP0036	2026-09-23	07:48:00	17:14:00	근무	0	N
GP0038	2026-09-23	19:49:00	05:16:00	근무	0	N
GP0040	2026-09-23	07:49:00	17:13:00	근무	0	N
GP0044	2026-09-23	07:58:00	18:50:00	근무	90	N
GP0045	2026-09-23	07:52:00	17:05:00	근무	0	N
GP0048	2026-09-23	19:56:00	06:36:00	근무	90	N
GP0052	2026-09-23	07:46:00	18:41:00	근무	90	N
GP0055	2026-09-23	19:51:00	05:17:00	근무	0	N
GP0072	2026-09-23	08:12:00	17:24:00	근무	0	Y
GP0074	2026-09-23	19:58:00	05:44:00	근무	30	N
GP0079	2026-09-23	07:53:00	18:15:00	근무	60	N
GP0101	2026-09-23	07:47:00	18:38:00	근무	90	N
GP0102	2026-09-23	07:47:00	17:23:00	근무	0	N
GP0110	2026-09-23	08:25:00	17:11:00	근무	0	Y
GP0111	2026-09-23	07:53:00	18:39:00	근무	90	N
GP0120	2026-09-23	07:47:00	17:53:00	근무	30	N
GP0130	2026-09-23	07:50:00	18:46:00	근무	90	N
GP0131	2026-09-23	07:49:00	17:55:00	근무	30	N
GP0140	2026-09-23	07:48:00	18:20:00	근무	60	N
GP0150	2026-09-23	07:47:00	17:21:00	근무	0	N
GP0160	2026-09-23	07:53:00	17:23:00	근무	0	N
GP0006	2026-09-24	07:50:00	17:08:00	근무	0	N
GP0024	2026-09-24	19:51:00	05:24:00	근무	0	N
GP0027	2026-09-24	07:55:00	18:11:00	근무	60	N
GP0031	2026-09-24	07:48:00	17:25:00	근무	0	N
GP0036	2026-09-24	07:53:00	17:50:00	근무	30	N
GP0038	2026-09-24	19:47:00	05:36:00	근무	30	N
GP0040	2026-09-24	07:57:00	17:37:00	근무	30	N
GP0044	2026-09-24	07:58:00	17:40:00	근무	30	N
GP0045	2026-09-24	07:51:00	18:55:00	근무	90	N
GP0048	2026-09-24	19:48:00	05:24:00	근무	0	N
GP0052	2026-09-24	07:46:00	17:25:00	근무	0	N
GP0055	2026-09-24	19:48:00	06:14:00	근무	60	N
GP0072	2026-09-24	07:56:00	18:05:00	근무	60	N
GP0074	2026-09-24	19:54:00	06:18:00	근무	60	N
GP0079	2026-09-24	\N	\N	연차	0	N
GP0101	2026-09-24	07:58:00	17:08:00	근무	0	N
GP0102	2026-09-24	07:47:00	18:11:00	근무	60	N
GP0110	2026-09-24	07:56:00	18:39:00	근무	90	N
GP0111	2026-09-24	07:57:00	17:05:00	근무	0	N
GP0120	2026-09-24	08:22:00	18:46:00	근무	90	Y
GP0130	2026-09-24	07:50:00	17:07:00	근무	0	N
GP0131	2026-09-24	07:52:00	17:22:00	근무	0	N
GP0140	2026-09-24	07:45:00	17:17:00	근무	0	N
GP0150	2026-09-24	07:59:00	18:54:00	근무	90	N
GP0160	2026-09-24	07:58:00	18:25:00	근무	60	N
GP0006	2026-09-25	07:55:00	17:11:00	근무	0	N
GP0024	2026-09-25	19:46:00	06:10:00	근무	60	N
GP0027	2026-09-25	07:46:00	17:43:00	근무	30	N
GP0031	2026-09-25	07:46:00	18:42:00	근무	90	N
GP0036	2026-09-25	07:45:00	17:06:00	근무	0	N
GP0038	2026-09-25	19:51:00	06:25:00	근무	60	N
GP0040	2026-09-25	07:58:00	17:54:00	근무	30	N
GP0044	2026-09-25	07:48:00	17:20:00	근무	0	N
GP0045	2026-09-25	07:49:00	17:22:00	근무	0	N
GP0048	2026-09-25	19:55:00	05:40:00	근무	30	N
GP0052	2026-09-25	07:49:00	17:06:00	근무	0	N
GP0055	2026-09-25	19:47:00	06:50:00	근무	90	N
GP0072	2026-09-25	07:52:00	18:17:00	근무	60	N
GP0074	2026-09-25	19:57:00	05:12:00	근무	0	N
GP0079	2026-09-25	07:57:00	17:35:00	근무	30	N
GP0101	2026-09-25	07:57:00	18:11:00	근무	60	N
GP0102	2026-09-25	07:46:00	17:48:00	근무	30	N
GP0110	2026-09-25	\N	\N	연차	0	N
GP0111	2026-09-25	07:45:00	17:43:00	근무	30	N
GP0120	2026-09-25	07:52:00	18:18:00	근무	60	N
GP0130	2026-09-25	07:50:00	18:47:00	근무	90	N
GP0131	2026-09-25	07:49:00	18:22:00	근무	60	N
GP0140	2026-09-25	07:48:00	18:12:00	근무	60	N
GP0150	2026-09-25	07:45:00	18:13:00	근무	60	N
GP0160	2026-09-25	07:52:00	18:49:00	근무	90	N
GP0006	2026-09-26	07:49:00	18:38:00	근무	90	N
GP0031	2026-09-26	07:46:00	18:46:00	근무	90	N
GP0072	2026-09-26	07:55:00	17:08:00	근무	0	N
GP0006	2026-09-28	07:48:00	17:51:00	근무	30	N
GP0024	2026-09-28	19:46:00	05:12:00	근무	0	N
GP0027	2026-09-28	07:47:00	18:46:00	근무	90	N
GP0031	2026-09-28	07:54:00	17:42:00	근무	30	N
GP0036	2026-09-28	07:48:00	17:21:00	근무	0	N
GP0038	2026-09-28	19:48:00	05:54:00	근무	30	N
GP0040	2026-09-28	07:51:00	17:53:00	근무	30	N
GP0044	2026-09-28	07:59:00	18:13:00	근무	60	N
GP0045	2026-09-28	07:50:00	18:41:00	근무	90	N
GP0048	2026-09-28	19:48:00	05:36:00	근무	30	N
GP0052	2026-09-28	07:52:00	17:46:00	근무	30	N
GP0055	2026-09-28	20:13:00	06:35:00	근무	90	Y
GP0072	2026-09-28	07:49:00	17:13:00	근무	0	N
GP0074	2026-09-28	19:54:00	05:10:00	근무	0	N
GP0079	2026-09-28	07:57:00	17:14:00	근무	0	N
GP0101	2026-09-28	07:50:00	17:36:00	근무	30	N
GP0102	2026-09-28	07:53:00	17:41:00	근무	30	N
GP0110	2026-09-28	07:47:00	17:53:00	근무	30	N
GP0111	2026-09-28	07:55:00	18:46:00	근무	90	N
GP0120	2026-09-28	07:54:00	17:20:00	근무	0	N
GP0130	2026-09-28	07:45:00	17:22:00	근무	0	N
GP0131	2026-09-28	08:22:00	17:06:00	근무	0	Y
GP0140	2026-09-28	07:55:00	17:37:00	근무	30	N
GP0150	2026-09-28	07:56:00	18:54:00	근무	90	N
GP0160	2026-09-28	07:51:00	17:15:00	근무	0	N
GP0006	2026-09-29	07:47:00	18:42:00	근무	90	N
GP0024	2026-09-29	19:50:00	05:24:00	근무	0	N
GP0027	2026-09-29	07:54:00	18:43:00	근무	90	N
GP0031	2026-09-29	07:55:00	18:50:00	근무	90	N
GP0036	2026-09-29	07:49:00	17:42:00	근무	30	N
GP0038	2026-09-29	19:56:00	06:45:00	근무	90	N
GP0040	2026-09-29	07:50:00	17:07:00	근무	0	N
GP0044	2026-09-29	07:54:00	17:24:00	근무	0	N
GP0045	2026-09-29	07:58:00	17:16:00	근무	0	N
GP0048	2026-09-29	19:52:00	05:08:00	근무	0	N
GP0052	2026-09-29	07:57:00	18:23:00	근무	60	N
GP0055	2026-09-29	19:55:00	06:44:00	근무	90	N
GP0072	2026-09-29	07:58:00	17:40:00	근무	30	N
GP0074	2026-09-29	19:59:00	06:46:00	근무	90	N
GP0079	2026-09-29	07:49:00	18:42:00	근무	90	N
GP0101	2026-09-29	07:54:00	18:43:00	근무	90	N
GP0102	2026-09-29	\N	\N	연차	0	N
GP0110	2026-09-29	\N	\N	연차	0	N
GP0111	2026-09-29	07:56:00	17:11:00	근무	0	N
GP0120	2026-09-29	07:46:00	17:54:00	근무	30	N
GP0130	2026-09-29	07:52:00	18:49:00	근무	90	N
GP0131	2026-09-29	07:55:00	17:07:00	근무	0	N
GP0140	2026-09-29	07:45:00	17:20:00	근무	0	N
GP0150	2026-09-29	07:53:00	18:48:00	근무	90	N
GP0160	2026-09-29	07:45:00	17:11:00	근무	0	N
GP0006	2026-09-30	07:49:00	18:55:00	근무	90	N
GP0024	2026-09-30	19:46:00	06:16:00	근무	60	N
GP0027	2026-09-30	07:47:00	18:22:00	근무	60	N
GP0031	2026-09-30	07:49:00	18:16:00	근무	60	N
GP0036	2026-09-30	07:50:00	18:11:00	근무	60	N
GP0038	2026-09-30	19:56:00	06:45:00	근무	90	N
GP0040	2026-09-30	07:53:00	18:48:00	근무	90	N
GP0044	2026-09-30	07:48:00	17:49:00	근무	30	N
GP0045	2026-09-30	08:18:00	18:41:00	근무	90	Y
GP0048	2026-09-30	19:46:00	05:21:00	근무	0	N
GP0052	2026-09-30	07:53:00	17:22:00	근무	0	N
GP0055	2026-09-30	19:57:00	05:24:00	근무	0	N
GP0072	2026-09-30	07:53:00	18:13:00	근무	60	N
GP0074	2026-09-30	19:56:00	06:45:00	근무	90	N
GP0079	2026-09-30	07:53:00	17:44:00	근무	30	N
GP0101	2026-09-30	07:52:00	18:53:00	근무	90	N
GP0102	2026-09-30	07:52:00	18:19:00	근무	60	N
GP0110	2026-09-30	07:59:00	17:25:00	근무	0	N
GP0111	2026-09-30	07:57:00	17:17:00	근무	0	N
GP0120	2026-09-30	07:53:00	17:16:00	근무	0	N
GP0130	2026-09-30	07:53:00	17:22:00	근무	0	N
GP0131	2026-09-30	07:47:00	18:14:00	근무	60	N
GP0140	2026-09-30	08:19:00	17:17:00	근무	0	Y
GP0150	2026-09-30	07:56:00	18:11:00	근무	60	N
GP0160	2026-09-30	07:52:00	18:18:00	근무	60	N
GP0006	2026-10-01	07:51:00	18:09:00	근무	60	N
GP0024	2026-10-01	19:59:00	05:08:00	근무	0	N
GP0027	2026-10-01	07:53:00	17:39:00	근무	30	N
GP0031	2026-10-01	07:45:00	17:17:00	근무	0	N
GP0036	2026-10-01	07:51:00	17:13:00	근무	0	N
GP0038	2026-10-01	19:49:00	06:09:00	근무	60	N
GP0040	2026-10-01	07:49:00	17:09:00	근무	0	N
GP0044	2026-10-01	07:56:00	18:05:00	근무	60	N
GP0045	2026-10-01	07:56:00	17:52:00	근무	30	N
GP0048	2026-10-01	20:19:00	05:25:00	근무	0	Y
GP0052	2026-10-01	07:57:00	17:24:00	근무	0	N
GP0055	2026-10-01	19:56:00	05:47:00	근무	30	N
GP0072	2026-10-01	07:47:00	17:43:00	근무	30	N
GP0074	2026-10-01	19:58:00	05:12:00	근무	0	N
GP0079	2026-10-01	07:54:00	17:54:00	근무	30	N
GP0101	2026-10-01	07:46:00	18:21:00	근무	60	N
GP0102	2026-10-01	07:53:00	17:10:00	근무	0	N
GP0110	2026-10-01	07:53:00	17:12:00	근무	0	N
GP0111	2026-10-01	\N	\N	연차	0	N
GP0120	2026-10-01	07:51:00	17:20:00	근무	0	N
GP0130	2026-10-01	07:59:00	17:16:00	근무	0	N
GP0131	2026-10-01	07:51:00	17:11:00	근무	0	N
GP0140	2026-10-01	07:54:00	18:36:00	근무	90	N
GP0150	2026-10-01	07:48:00	17:14:00	근무	0	N
GP0160	2026-10-01	07:56:00	18:37:00	근무	90	N
GP0006	2026-10-02	07:51:00	17:22:00	근무	0	N
GP0024	2026-10-02	19:51:00	06:16:00	근무	60	N
GP0027	2026-10-02	07:49:00	18:36:00	근무	90	N
GP0031	2026-10-02	07:55:00	17:44:00	근무	30	N
GP0036	2026-10-02	07:50:00	18:15:00	근무	60	N
GP0038	2026-10-02	19:50:00	05:16:00	근무	0	N
GP0040	2026-10-02	07:49:00	18:47:00	근무	90	N
GP0044	2026-10-02	07:47:00	18:54:00	근무	90	N
GP0045	2026-10-02	07:45:00	17:12:00	근무	0	N
GP0048	2026-10-02	19:47:00	05:18:00	근무	0	N
GP0052	2026-10-02	\N	\N	연차	0	N
GP0055	2026-10-02	19:50:00	05:20:00	근무	0	N
GP0072	2026-10-02	07:54:00	18:52:00	근무	90	N
GP0074	2026-10-02	19:47:00	06:50:00	근무	90	N
GP0079	2026-10-02	07:58:00	18:11:00	근무	60	N
GP0101	2026-10-02	07:45:00	17:24:00	근무	0	N
GP0102	2026-10-02	08:25:00	18:47:00	근무	90	Y
GP0110	2026-10-02	07:55:00	17:47:00	근무	30	N
GP0111	2026-10-02	07:47:00	18:52:00	근무	90	N
GP0120	2026-10-02	07:46:00	18:20:00	근무	60	N
GP0130	2026-10-02	07:50:00	17:09:00	근무	0	N
GP0131	2026-10-02	07:59:00	17:13:00	근무	0	N
GP0140	2026-10-02	07:54:00	18:42:00	근무	90	N
GP0150	2026-10-02	07:52:00	17:05:00	근무	0	N
GP0160	2026-10-02	07:47:00	17:43:00	근무	30	N
GP0006	2026-10-03	07:59:00	17:09:00	근무	0	N
GP0036	2026-10-03	07:56:00	17:41:00	근무	30	N
GP0045	2026-10-03	07:55:00	18:07:00	근무	60	N
GP0072	2026-10-03	07:48:00	17:10:00	근무	0	N
GP0006	2026-10-05	07:46:00	18:09:00	근무	60	N
GP0024	2026-10-05	19:59:00	05:07:00	근무	0	N
GP0027	2026-10-05	07:48:00	17:18:00	근무	0	N
GP0031	2026-10-05	07:49:00	18:37:00	근무	90	N
GP0036	2026-10-05	07:46:00	17:17:00	근무	0	N
GP0038	2026-10-05	19:54:00	06:06:00	근무	60	N
GP0040	2026-10-05	07:56:00	18:42:00	근무	90	N
GP0044	2026-10-05	07:53:00	18:37:00	근무	90	N
GP0045	2026-10-05	07:48:00	17:17:00	근무	0	N
GP0048	2026-10-05	19:52:00	06:36:00	근무	90	N
GP0052	2026-10-05	07:53:00	17:22:00	근무	0	N
GP0055	2026-10-05	19:53:00	05:48:00	근무	30	N
GP0072	2026-10-05	07:51:00	17:08:00	근무	0	N
GP0074	2026-10-05	19:59:00	06:48:00	근무	90	N
GP0079	2026-10-05	07:55:00	17:16:00	근무	0	N
GP0101	2026-10-05	07:51:00	18:21:00	근무	60	N
GP0102	2026-10-05	07:52:00	18:46:00	근무	90	N
GP0110	2026-10-05	07:57:00	18:23:00	근무	60	N
GP0111	2026-10-05	07:49:00	17:22:00	근무	0	N
GP0120	2026-10-05	07:59:00	18:12:00	근무	60	N
GP0130	2026-10-05	07:57:00	17:19:00	근무	0	N
GP0131	2026-10-05	08:17:00	17:15:00	근무	0	Y
GP0140	2026-10-05	07:54:00	17:07:00	근무	0	N
GP0150	2026-10-05	07:57:00	18:07:00	근무	60	N
GP0160	2026-10-05	07:48:00	17:17:00	근무	0	N
GP0006	2026-10-06	07:58:00	18:41:00	근무	90	N
GP0024	2026-10-06	19:52:00	05:05:00	근무	0	N
GP0027	2026-10-06	07:57:00	17:51:00	근무	30	N
GP0031	2026-10-06	07:59:00	17:53:00	근무	30	N
GP0036	2026-10-06	07:48:00	17:06:00	근무	0	N
GP0038	2026-10-06	19:49:00	05:11:00	근무	0	N
GP0040	2026-10-06	07:48:00	18:49:00	근무	90	N
GP0044	2026-10-06	07:54:00	17:13:00	근무	0	N
GP0045	2026-10-06	07:56:00	17:54:00	근무	30	N
GP0048	2026-10-06	19:55:00	06:52:00	근무	90	N
GP0052	2026-10-06	07:53:00	17:45:00	근무	30	N
GP0055	2026-10-06	19:57:00	05:36:00	근무	30	N
GP0072	2026-10-06	07:57:00	18:20:00	근무	60	N
GP0074	2026-10-06	19:45:00	06:24:00	근무	60	N
GP0079	2026-10-06	07:59:00	17:35:00	근무	30	N
GP0101	2026-10-06	07:50:00	17:48:00	근무	30	N
GP0102	2026-10-06	07:59:00	18:08:00	근무	60	N
GP0110	2026-10-06	07:56:00	18:06:00	근무	60	N
GP0111	2026-10-06	07:47:00	17:09:00	근무	0	N
GP0120	2026-10-06	07:59:00	18:24:00	근무	60	N
GP0130	2026-10-06	08:23:00	17:05:00	근무	0	Y
GP0131	2026-10-06	07:54:00	18:10:00	근무	60	N
GP0140	2026-10-06	07:48:00	17:42:00	근무	30	N
GP0150	2026-10-06	07:57:00	17:41:00	근무	30	N
GP0160	2026-10-06	07:47:00	17:16:00	근무	0	N
GP0006	2026-10-07	07:53:00	18:43:00	근무	90	N
GP0024	2026-10-07	19:51:00	05:16:00	근무	0	N
GP0027	2026-10-07	07:56:00	17:55:00	근무	30	N
GP0031	2026-10-07	07:49:00	17:42:00	근무	30	N
GP0036	2026-10-07	07:48:00	17:22:00	근무	0	N
GP0038	2026-10-07	19:47:00	05:47:00	근무	30	N
GP0040	2026-10-07	07:47:00	17:22:00	근무	0	N
GP0044	2026-10-07	07:50:00	17:52:00	근무	30	N
GP0045	2026-10-07	07:53:00	17:49:00	근무	30	N
GP0048	2026-10-07	19:50:00	06:43:00	근무	90	N
GP0052	2026-10-07	07:57:00	17:48:00	근무	30	N
GP0055	2026-10-07	19:49:00	05:13:00	근무	0	N
GP0072	2026-10-07	07:56:00	18:45:00	근무	90	N
GP0074	2026-10-07	19:47:00	05:07:00	근무	0	N
GP0079	2026-10-07	07:59:00	17:17:00	근무	0	N
GP0101	2026-10-07	07:58:00	17:45:00	근무	30	N
GP0102	2026-10-07	07:58:00	18:10:00	근무	60	N
GP0110	2026-10-07	07:52:00	18:47:00	근무	90	N
GP0111	2026-10-07	07:56:00	17:43:00	근무	30	N
GP0120	2026-10-07	\N	\N	연차	0	N
GP0130	2026-10-07	07:58:00	17:39:00	근무	30	N
GP0131	2026-10-07	\N	\N	연차	0	N
GP0140	2026-10-07	07:52:00	18:14:00	근무	60	N
GP0150	2026-10-07	07:47:00	17:08:00	근무	0	N
GP0160	2026-10-07	07:57:00	18:51:00	근무	90	N
GP0006	2026-10-08	07:58:00	17:07:00	근무	0	N
GP0024	2026-10-08	19:51:00	05:16:00	근무	0	N
GP0027	2026-10-08	07:47:00	18:43:00	근무	90	N
GP0031	2026-10-08	07:45:00	17:24:00	근무	0	N
GP0036	2026-10-08	07:47:00	18:22:00	근무	60	N
GP0038	2026-10-08	19:52:00	05:22:00	근무	0	N
GP0040	2026-10-08	07:59:00	18:42:00	근무	90	N
GP0044	2026-10-08	07:48:00	17:47:00	근무	30	N
GP0045	2026-10-08	07:45:00	17:39:00	근무	30	N
GP0048	2026-10-08	19:49:00	05:17:00	근무	0	N
GP0052	2026-10-08	07:57:00	17:12:00	근무	0	N
GP0055	2026-10-08	19:49:00	06:46:00	근무	90	N
GP0072	2026-10-08	07:49:00	18:48:00	근무	90	N
GP0074	2026-10-08	19:59:00	05:08:00	근무	0	N
GP0079	2026-10-08	07:45:00	17:51:00	근무	30	N
GP0101	2026-10-08	08:25:00	17:51:00	근무	30	Y
GP0102	2026-10-08	07:49:00	17:15:00	근무	0	N
GP0110	2026-10-08	07:47:00	17:09:00	근무	0	N
GP0111	2026-10-08	07:50:00	18:06:00	근무	60	N
GP0120	2026-10-08	07:56:00	17:12:00	근무	0	N
GP0130	2026-10-08	07:51:00	18:54:00	근무	90	N
GP0131	2026-10-08	07:54:00	17:25:00	근무	0	N
GP0140	2026-10-08	07:56:00	18:22:00	근무	60	N
GP0150	2026-10-08	07:55:00	18:40:00	근무	90	N
GP0160	2026-10-08	07:50:00	18:49:00	근무	90	N
GP0006	2026-10-09	08:20:00	18:49:00	근무	90	Y
GP0024	2026-10-09	19:50:00	05:17:00	근무	0	N
GP0027	2026-10-09	07:56:00	18:25:00	근무	60	N
GP0031	2026-10-09	07:51:00	18:14:00	근무	60	N
GP0036	2026-10-09	07:53:00	17:20:00	근무	0	N
GP0038	2026-10-09	19:50:00	06:22:00	근무	60	N
GP0040	2026-10-09	08:19:00	17:06:00	근무	0	Y
GP0044	2026-10-09	07:50:00	17:22:00	근무	0	N
GP0045	2026-10-09	07:56:00	17:05:00	근무	0	N
GP0048	2026-10-09	19:53:00	06:40:00	근무	90	N
GP0052	2026-10-09	07:50:00	17:25:00	근무	0	N
GP0055	2026-10-09	19:59:00	05:21:00	근무	0	N
GP0072	2026-10-09	07:45:00	18:22:00	근무	60	N
GP0074	2026-10-09	19:54:00	05:45:00	근무	30	N
GP0079	2026-10-09	07:46:00	17:20:00	근무	0	N
GP0101	2026-10-09	07:49:00	17:07:00	근무	0	N
GP0102	2026-10-09	07:47:00	18:40:00	근무	90	N
GP0110	2026-10-09	07:45:00	17:06:00	근무	0	N
GP0111	2026-10-09	07:58:00	18:35:00	근무	90	N
GP0120	2026-10-09	07:51:00	18:40:00	근무	90	N
GP0130	2026-10-09	08:18:00	17:15:00	근무	0	Y
GP0131	2026-10-09	07:48:00	17:18:00	근무	0	N
GP0140	2026-10-09	07:46:00	17:10:00	근무	0	N
GP0150	2026-10-09	07:47:00	17:18:00	근무	0	N
GP0160	2026-10-09	07:52:00	17:17:00	근무	0	N
GP0006	2026-10-10	07:49:00	17:19:00	근무	0	N
GP0031	2026-10-10	07:58:00	18:48:00	근무	90	N
GP0045	2026-10-10	07:50:00	17:41:00	근무	30	N
GP0072	2026-10-10	08:25:00	17:50:00	근무	30	Y
GP0079	2026-10-10	07:53:00	18:05:00	근무	60	N
GP0006	2026-10-12	07:46:00	17:06:00	근무	0	N
GP0024	2026-10-12	19:57:00	05:18:00	근무	0	N
GP0027	2026-10-12	07:48:00	17:37:00	근무	30	N
GP0031	2026-10-12	07:51:00	17:09:00	근무	0	N
GP0036	2026-10-12	07:45:00	17:20:00	근무	0	N
GP0038	2026-10-12	19:51:00	06:13:00	근무	60	N
GP0040	2026-10-12	07:55:00	18:13:00	근무	60	N
GP0044	2026-10-12	07:57:00	17:41:00	근무	30	N
GP0045	2026-10-12	07:47:00	18:47:00	근무	90	N
GP0048	2026-10-12	19:50:00	05:19:00	근무	0	N
GP0052	2026-10-12	08:21:00	18:23:00	근무	60	Y
GP0055	2026-10-12	19:50:00	05:06:00	근무	0	N
GP0072	2026-10-12	07:54:00	17:46:00	근무	30	N
GP0074	2026-10-12	19:55:00	05:25:00	근무	0	N
GP0079	2026-10-12	07:51:00	18:42:00	근무	90	N
GP0101	2026-10-12	07:54:00	17:12:00	근무	0	N
GP0102	2026-10-12	07:46:00	18:19:00	근무	60	N
GP0110	2026-10-12	07:48:00	18:50:00	근무	90	N
GP0111	2026-10-12	07:50:00	18:08:00	근무	60	N
GP0120	2026-10-12	07:46:00	17:06:00	근무	0	N
GP0130	2026-10-12	07:58:00	17:53:00	근무	30	N
GP0131	2026-10-12	07:50:00	18:09:00	근무	60	N
GP0140	2026-10-12	07:54:00	17:17:00	근무	0	N
GP0150	2026-10-12	07:52:00	18:19:00	근무	60	N
GP0160	2026-10-12	07:50:00	17:38:00	근무	30	N
GP0006	2026-10-13	07:46:00	18:38:00	근무	90	N
GP0024	2026-10-13	19:57:00	06:50:00	근무	90	N
GP0027	2026-10-13	07:58:00	18:42:00	근무	90	N
GP0031	2026-10-13	07:50:00	17:23:00	근무	0	N
GP0036	2026-10-13	07:47:00	17:20:00	근무	0	N
GP0038	2026-10-13	19:53:00	05:43:00	근무	30	N
GP0040	2026-10-13	07:52:00	17:16:00	근무	0	N
GP0044	2026-10-13	\N	\N	연차	0	N
GP0045	2026-10-13	07:47:00	17:12:00	근무	0	N
GP0048	2026-10-13	19:55:00	06:53:00	근무	90	N
GP0052	2026-10-13	07:52:00	17:25:00	근무	0	N
GP0055	2026-10-13	20:20:00	06:08:00	근무	60	Y
GP0072	2026-10-13	07:49:00	17:07:00	근무	0	N
GP0074	2026-10-13	19:45:00	05:41:00	근무	30	N
GP0079	2026-10-13	07:53:00	18:20:00	근무	60	N
GP0101	2026-10-13	07:47:00	18:37:00	근무	90	N
GP0102	2026-10-13	07:48:00	18:50:00	근무	90	N
GP0110	2026-10-13	07:48:00	17:44:00	근무	30	N
GP0111	2026-10-13	08:18:00	17:41:00	근무	30	Y
GP0120	2026-10-13	07:45:00	18:16:00	근무	60	N
GP0130	2026-10-13	07:54:00	18:20:00	근무	60	N
GP0131	2026-10-13	07:58:00	17:43:00	근무	30	N
GP0140	2026-10-13	07:56:00	17:16:00	근무	0	N
GP0150	2026-10-13	07:59:00	17:17:00	근무	0	N
GP0160	2026-10-13	07:55:00	17:45:00	근무	30	N
GP0006	2026-10-14	07:57:00	17:42:00	근무	30	N
GP0024	2026-10-14	19:56:00	05:17:00	근무	0	N
GP0027	2026-10-14	07:50:00	17:35:00	근무	30	N
GP0031	2026-10-14	07:46:00	17:24:00	근무	0	N
GP0036	2026-10-14	07:55:00	17:08:00	근무	0	N
GP0038	2026-10-14	19:57:00	05:13:00	근무	0	N
GP0040	2026-10-14	07:56:00	17:52:00	근무	30	N
GP0044	2026-10-14	07:49:00	18:45:00	근무	90	N
GP0045	2026-10-14	07:48:00	17:53:00	근무	30	N
GP0048	2026-10-14	19:55:00	06:41:00	근무	90	N
GP0052	2026-10-14	07:55:00	17:50:00	근무	30	N
GP0055	2026-10-14	19:57:00	06:50:00	근무	90	N
GP0072	2026-10-14	07:53:00	17:24:00	근무	0	N
GP0074	2026-10-14	19:49:00	06:08:00	근무	60	N
GP0079	2026-10-14	07:54:00	18:17:00	근무	60	N
GP0101	2026-10-14	07:52:00	17:49:00	근무	30	N
GP0102	2026-10-14	07:45:00	18:25:00	근무	60	N
GP0110	2026-10-14	07:48:00	17:24:00	근무	0	N
GP0111	2026-10-14	07:51:00	17:20:00	근무	0	N
GP0120	2026-10-14	07:50:00	17:12:00	근무	0	N
GP0130	2026-10-14	07:54:00	18:21:00	근무	60	N
GP0131	2026-10-14	07:55:00	17:09:00	근무	0	N
GP0140	2026-10-14	07:50:00	18:51:00	근무	90	N
GP0150	2026-10-14	07:48:00	17:22:00	근무	0	N
GP0160	2026-10-14	07:53:00	18:25:00	근무	60	N
GP0006	2026-10-15	07:51:00	17:19:00	근무	0	N
GP0024	2026-10-15	19:58:00	05:05:00	근무	0	N
GP0027	2026-10-15	07:57:00	18:17:00	근무	60	N
GP0031	2026-10-15	07:58:00	17:18:00	근무	0	N
GP0036	2026-10-15	08:12:00	18:36:00	근무	90	Y
GP0038	2026-10-15	19:54:00	06:53:00	근무	90	N
GP0040	2026-10-15	07:59:00	18:50:00	근무	90	N
GP0044	2026-10-15	\N	\N	연차	0	N
GP0045	2026-10-15	07:47:00	17:47:00	근무	30	N
GP0048	2026-10-15	19:46:00	06:48:00	근무	90	N
GP0052	2026-10-15	07:54:00	17:15:00	근무	0	N
GP0055	2026-10-15	19:58:00	05:16:00	근무	0	N
GP0072	2026-10-15	07:45:00	18:49:00	근무	90	N
GP0074	2026-10-15	19:56:00	06:51:00	근무	90	N
GP0079	2026-10-15	07:58:00	17:15:00	근무	0	N
GP0101	2026-10-15	07:54:00	17:12:00	근무	0	N
GP0102	2026-10-15	07:57:00	18:06:00	근무	60	N
GP0110	2026-10-15	07:53:00	17:10:00	근무	0	N
GP0111	2026-10-15	07:56:00	18:43:00	근무	90	N
GP0120	2026-10-15	07:54:00	17:14:00	근무	0	N
GP0130	2026-10-15	07:55:00	17:39:00	근무	30	N
GP0131	2026-10-15	07:53:00	17:18:00	근무	0	N
GP0140	2026-10-15	07:56:00	17:46:00	근무	30	N
GP0150	2026-10-15	08:16:00	18:20:00	근무	60	Y
GP0160	2026-10-15	08:20:00	18:41:00	근무	90	Y
GP0006	2026-10-16	07:58:00	17:41:00	근무	30	N
GP0024	2026-10-16	19:54:00	06:19:00	근무	60	N
GP0027	2026-10-16	07:51:00	18:53:00	근무	90	N
GP0031	2026-10-16	07:57:00	18:51:00	근무	90	N
GP0036	2026-10-16	07:45:00	17:24:00	근무	0	N
GP0038	2026-10-16	19:51:00	05:43:00	근무	30	N
GP0040	2026-10-16	07:57:00	18:25:00	근무	60	N
GP0044	2026-10-16	07:50:00	17:42:00	근무	30	N
GP0045	2026-10-16	07:47:00	17:38:00	근무	30	N
GP0048	2026-10-16	19:53:00	06:08:00	근무	60	N
GP0052	2026-10-16	07:58:00	17:18:00	근무	0	N
GP0055	2026-10-16	\N	\N	연차	0	N
GP0072	2026-10-16	07:47:00	18:25:00	근무	60	N
GP0074	2026-10-16	19:46:00	05:41:00	근무	30	N
GP0079	2026-10-16	07:54:00	17:19:00	근무	0	N
GP0101	2026-10-16	07:57:00	17:38:00	근무	30	N
GP0102	2026-10-16	07:50:00	17:16:00	근무	0	N
GP0110	2026-10-16	07:56:00	17:43:00	근무	30	N
GP0111	2026-10-16	07:45:00	18:13:00	근무	60	N
GP0120	2026-10-16	07:56:00	17:46:00	근무	30	N
GP0130	2026-10-16	07:51:00	17:06:00	근무	0	N
GP0131	2026-10-16	08:19:00	18:07:00	근무	60	Y
GP0140	2026-10-16	07:46:00	17:14:00	근무	0	N
GP0150	2026-10-16	07:58:00	18:18:00	근무	60	N
GP0160	2026-10-16	07:58:00	17:37:00	근무	30	N
GP0006	2026-10-17	07:53:00	18:12:00	근무	60	N
GP0031	2026-10-17	07:57:00	17:22:00	근무	0	N
GP0045	2026-10-17	07:58:00	17:47:00	근무	30	N
GP0072	2026-10-17	07:53:00	18:54:00	근무	90	N
GP0006	2026-10-19	07:54:00	17:42:00	근무	30	N
GP0024	2026-10-19	19:45:00	05:53:00	근무	30	N
GP0027	2026-10-19	07:45:00	18:16:00	근무	60	N
GP0031	2026-10-19	07:58:00	17:19:00	근무	0	N
GP0036	2026-10-19	07:56:00	17:20:00	근무	0	N
GP0038	2026-10-19	19:55:00	05:21:00	근무	0	N
GP0040	2026-10-19	07:48:00	17:23:00	근무	0	N
GP0044	2026-10-19	07:58:00	18:35:00	근무	90	N
GP0045	2026-10-19	08:16:00	18:21:00	근무	60	Y
GP0048	2026-10-19	\N	\N	연차	0	N
GP0052	2026-10-19	07:57:00	17:22:00	근무	0	N
GP0055	2026-10-19	19:57:00	06:41:00	근무	90	N
GP0072	2026-10-19	07:52:00	17:18:00	근무	0	N
GP0074	2026-10-19	19:46:00	05:21:00	근무	0	N
GP0079	2026-10-19	07:58:00	18:37:00	근무	90	N
GP0101	2026-10-19	07:47:00	18:46:00	근무	90	N
GP0102	2026-10-19	07:48:00	18:24:00	근무	60	N
GP0110	2026-10-19	07:53:00	17:22:00	근무	0	N
GP0111	2026-10-19	07:59:00	17:51:00	근무	30	N
GP0120	2026-10-19	07:59:00	17:43:00	근무	30	N
GP0130	2026-10-19	07:59:00	17:35:00	근무	30	N
GP0131	2026-10-19	08:12:00	17:09:00	근무	0	Y
GP0140	2026-10-19	07:56:00	17:35:00	근무	30	N
GP0150	2026-10-19	07:59:00	17:18:00	근무	0	N
GP0160	2026-10-19	07:48:00	18:39:00	근무	90	N
GP0006	2026-10-20	07:54:00	17:48:00	근무	30	N
GP0024	2026-10-20	19:49:00	06:52:00	근무	90	N
GP0027	2026-10-20	07:52:00	17:06:00	근무	0	N
GP0031	2026-10-20	07:59:00	17:20:00	근무	0	N
GP0036	2026-10-20	07:45:00	17:38:00	근무	30	N
GP0038	2026-10-20	19:59:00	05:22:00	근무	0	N
GP0040	2026-10-20	07:45:00	17:50:00	근무	30	N
GP0044	2026-10-20	07:46:00	17:22:00	근무	0	N
GP0045	2026-10-20	07:52:00	17:05:00	근무	0	N
GP0048	2026-10-20	19:56:00	05:10:00	근무	0	N
GP0052	2026-10-20	07:58:00	17:40:00	근무	30	N
GP0055	2026-10-20	19:54:00	05:45:00	근무	30	N
GP0072	2026-10-20	07:52:00	18:22:00	근무	60	N
GP0074	2026-10-20	19:57:00	06:20:00	근무	60	N
GP0079	2026-10-20	07:52:00	18:11:00	근무	60	N
GP0101	2026-10-20	07:53:00	17:21:00	근무	0	N
GP0102	2026-10-20	07:49:00	18:55:00	근무	90	N
GP0110	2026-10-20	07:46:00	17:09:00	근무	0	N
GP0111	2026-10-20	07:46:00	17:07:00	근무	0	N
GP0120	2026-10-20	07:59:00	18:08:00	근무	60	N
GP0130	2026-10-20	07:55:00	18:38:00	근무	90	N
GP0131	2026-10-20	07:55:00	18:09:00	근무	60	N
GP0140	2026-10-20	07:49:00	18:38:00	근무	90	N
GP0150	2026-10-20	07:49:00	17:49:00	근무	30	N
GP0160	2026-10-20	07:59:00	18:45:00	근무	90	N
GP0006	2026-10-21	07:45:00	18:51:00	근무	90	N
GP0024	2026-10-21	19:46:00	05:24:00	근무	0	N
GP0027	2026-10-21	07:59:00	17:12:00	근무	0	N
GP0031	2026-10-21	07:54:00	18:22:00	근무	60	N
GP0036	2026-10-21	07:59:00	17:10:00	근무	0	N
GP0038	2026-10-21	19:53:00	06:07:00	근무	60	N
GP0040	2026-10-21	\N	\N	연차	0	N
GP0044	2026-10-21	07:54:00	17:20:00	근무	0	N
GP0045	2026-10-21	07:49:00	18:23:00	근무	60	N
GP0048	2026-10-21	19:56:00	05:15:00	근무	0	N
GP0052	2026-10-21	\N	\N	연차	0	N
GP0055	2026-10-21	20:19:00	06:44:00	근무	90	Y
GP0072	2026-10-21	07:53:00	17:49:00	근무	30	N
GP0074	2026-10-21	19:59:00	05:55:00	근무	30	N
GP0079	2026-10-21	07:51:00	18:38:00	근무	90	N
GP0101	2026-10-21	07:50:00	17:15:00	근무	0	N
GP0102	2026-10-21	08:16:00	17:05:00	근무	0	Y
GP0110	2026-10-21	08:23:00	18:49:00	근무	90	Y
GP0111	2026-10-21	07:46:00	17:37:00	근무	30	N
GP0120	2026-10-21	07:51:00	18:51:00	근무	90	N
GP0130	2026-10-21	07:55:00	17:17:00	근무	0	N
GP0131	2026-10-21	\N	\N	연차	0	N
GP0140	2026-10-21	07:50:00	18:07:00	근무	60	N
GP0150	2026-10-21	07:58:00	17:15:00	근무	0	N
GP0160	2026-10-21	07:52:00	17:09:00	근무	0	N
GP0006	2026-10-22	07:45:00	17:15:00	근무	0	N
GP0024	2026-10-22	19:50:00	06:44:00	근무	90	N
GP0027	2026-10-22	07:45:00	17:35:00	근무	30	N
GP0031	2026-10-22	07:50:00	17:15:00	근무	0	N
GP0036	2026-10-22	07:53:00	18:54:00	근무	90	N
GP0038	2026-10-22	19:53:00	05:05:00	근무	0	N
GP0040	2026-10-22	07:56:00	18:07:00	근무	60	N
GP0044	2026-10-22	07:52:00	17:24:00	근무	0	N
GP0045	2026-10-22	07:56:00	17:52:00	근무	30	N
GP0048	2026-10-22	19:46:00	05:08:00	근무	0	N
GP0052	2026-10-22	07:47:00	18:50:00	근무	90	N
GP0055	2026-10-22	19:56:00	06:09:00	근무	60	N
GP0072	2026-10-22	07:49:00	18:15:00	근무	60	N
GP0074	2026-10-22	19:53:00	06:46:00	근무	90	N
GP0079	2026-10-22	07:50:00	18:11:00	근무	60	N
GP0101	2026-10-22	08:25:00	17:20:00	근무	0	Y
GP0102	2026-10-22	07:51:00	17:14:00	근무	0	N
GP0110	2026-10-22	07:47:00	18:36:00	근무	90	N
GP0111	2026-10-22	07:49:00	17:12:00	근무	0	N
GP0120	2026-10-22	07:46:00	17:14:00	근무	0	N
GP0130	2026-10-22	07:50:00	18:11:00	근무	60	N
GP0131	2026-10-22	07:45:00	17:41:00	근무	30	N
GP0140	2026-10-22	07:48:00	17:07:00	근무	0	N
GP0150	2026-10-22	07:51:00	18:40:00	근무	90	N
GP0160	2026-10-22	07:48:00	17:41:00	근무	30	N
GP0006	2026-10-23	07:56:00	18:40:00	근무	90	N
GP0024	2026-10-23	19:59:00	06:17:00	근무	60	N
GP0027	2026-10-23	07:50:00	17:06:00	근무	0	N
GP0031	2026-10-23	07:59:00	17:18:00	근무	0	N
GP0036	2026-10-23	07:48:00	17:35:00	근무	30	N
GP0038	2026-10-23	19:50:00	05:19:00	근무	0	N
GP0040	2026-10-23	07:51:00	17:06:00	근무	0	N
GP0044	2026-10-23	07:50:00	17:15:00	근무	0	N
GP0045	2026-10-23	07:58:00	17:54:00	근무	30	N
GP0048	2026-10-23	19:47:00	06:42:00	근무	90	N
GP0052	2026-10-23	07:51:00	18:23:00	근무	60	N
GP0055	2026-10-23	19:52:00	05:52:00	근무	30	N
GP0072	2026-10-23	07:58:00	17:42:00	근무	30	N
GP0074	2026-10-23	19:55:00	05:52:00	근무	30	N
GP0079	2026-10-23	07:52:00	17:54:00	근무	30	N
GP0101	2026-10-23	07:51:00	17:19:00	근무	0	N
GP0102	2026-10-23	07:49:00	18:38:00	근무	90	N
GP0110	2026-10-23	07:47:00	17:25:00	근무	0	N
GP0111	2026-10-23	07:57:00	18:06:00	근무	60	N
GP0120	2026-10-23	07:49:00	17:23:00	근무	0	N
GP0130	2026-10-23	07:59:00	18:54:00	근무	90	N
GP0131	2026-10-23	07:49:00	17:54:00	근무	30	N
GP0140	2026-10-23	07:59:00	18:15:00	근무	60	N
GP0150	2026-10-23	07:49:00	17:50:00	근무	30	N
GP0160	2026-10-23	07:47:00	18:05:00	근무	60	N
GP0006	2026-10-24	07:51:00	17:16:00	근무	0	N
GP0031	2026-10-24	07:51:00	17:09:00	근무	0	N
GP0006	2026-10-26	07:47:00	17:23:00	근무	0	N
GP0024	2026-10-26	19:48:00	05:13:00	근무	0	N
GP0027	2026-10-26	07:53:00	18:12:00	근무	60	N
GP0031	2026-10-26	07:58:00	17:19:00	근무	0	N
GP0036	2026-10-26	07:51:00	17:11:00	근무	0	N
GP0038	2026-10-26	19:55:00	06:42:00	근무	90	N
GP0040	2026-10-26	07:55:00	17:16:00	근무	0	N
GP0044	2026-10-26	07:56:00	17:17:00	근무	0	N
GP0045	2026-10-26	\N	\N	연차	0	N
GP0048	2026-10-26	19:52:00	05:43:00	근무	30	N
GP0052	2026-10-26	07:54:00	18:18:00	근무	60	N
GP0055	2026-10-26	19:45:00	06:42:00	근무	90	N
GP0072	2026-10-26	07:45:00	17:46:00	근무	30	N
GP0074	2026-10-26	19:45:00	05:09:00	근무	0	N
GP0079	2026-10-26	07:50:00	17:17:00	근무	0	N
GP0101	2026-10-26	07:53:00	17:06:00	근무	0	N
GP0102	2026-10-26	07:55:00	18:17:00	근무	60	N
GP0110	2026-10-26	07:50:00	17:14:00	근무	0	N
GP0111	2026-10-26	07:46:00	17:06:00	근무	0	N
GP0120	2026-10-26	07:59:00	17:40:00	근무	30	N
GP0130	2026-10-26	07:52:00	17:40:00	근무	30	N
GP0131	2026-10-26	07:46:00	17:53:00	근무	30	N
GP0140	2026-10-26	07:45:00	17:48:00	근무	30	N
GP0150	2026-10-26	07:50:00	18:13:00	근무	60	N
GP0160	2026-10-26	07:59:00	17:38:00	근무	30	N
GP0006	2026-10-27	08:15:00	17:36:00	근무	30	Y
GP0024	2026-10-27	20:25:00	05:19:00	근무	0	Y
GP0027	2026-10-27	07:49:00	18:52:00	근무	90	N
GP0031	2026-10-27	07:58:00	18:38:00	근무	90	N
GP0036	2026-10-27	07:55:00	17:14:00	근무	0	N
GP0038	2026-10-27	19:52:00	05:51:00	근무	30	N
GP0040	2026-10-27	07:51:00	17:07:00	근무	0	N
GP0044	2026-10-27	07:49:00	18:18:00	근무	60	N
GP0045	2026-10-27	07:45:00	17:39:00	근무	30	N
GP0048	2026-10-27	19:58:00	05:11:00	근무	0	N
GP0052	2026-10-27	07:58:00	17:17:00	근무	0	N
GP0055	2026-10-27	19:55:00	05:42:00	근무	30	N
GP0072	2026-10-27	07:56:00	17:22:00	근무	0	N
GP0074	2026-10-27	19:50:00	06:08:00	근무	60	N
GP0079	2026-10-27	07:58:00	17:11:00	근무	0	N
GP0101	2026-10-27	07:54:00	17:20:00	근무	0	N
GP0102	2026-10-27	07:57:00	18:53:00	근무	90	N
GP0110	2026-10-27	07:53:00	17:36:00	근무	30	N
GP0111	2026-10-27	07:49:00	18:46:00	근무	90	N
GP0120	2026-10-27	07:46:00	18:37:00	근무	90	N
GP0130	2026-10-27	07:52:00	17:20:00	근무	0	N
GP0131	2026-10-27	07:50:00	17:55:00	근무	30	N
GP0140	2026-10-27	07:55:00	17:17:00	근무	0	N
GP0150	2026-10-27	07:59:00	17:23:00	근무	0	N
GP0160	2026-10-27	07:46:00	18:08:00	근무	60	N
GP0006	2026-10-28	07:53:00	18:41:00	근무	90	N
GP0024	2026-10-28	20:12:00	05:21:00	근무	0	Y
GP0027	2026-10-28	07:58:00	17:05:00	근무	0	N
GP0031	2026-10-28	07:48:00	18:10:00	근무	60	N
GP0036	2026-10-28	07:56:00	17:54:00	근무	30	N
GP0038	2026-10-28	19:49:00	06:47:00	근무	90	N
GP0040	2026-10-28	07:59:00	17:47:00	근무	30	N
GP0044	2026-10-28	07:59:00	17:25:00	근무	0	N
GP0045	2026-10-28	07:53:00	18:09:00	근무	60	N
GP0048	2026-10-28	19:45:00	05:12:00	근무	0	N
GP0052	2026-10-28	07:59:00	17:11:00	근무	0	N
GP0055	2026-10-28	19:48:00	06:11:00	근무	60	N
GP0072	2026-10-28	07:57:00	17:18:00	근무	0	N
GP0074	2026-10-28	19:47:00	06:54:00	근무	90	N
GP0079	2026-10-28	08:13:00	18:50:00	근무	90	Y
GP0101	2026-10-28	07:54:00	17:19:00	근무	0	N
GP0102	2026-10-28	07:57:00	18:07:00	근무	60	N
GP0110	2026-10-28	07:59:00	18:11:00	근무	60	N
GP0111	2026-10-28	07:45:00	17:10:00	근무	0	N
GP0120	2026-10-28	07:49:00	17:07:00	근무	0	N
GP0130	2026-10-28	07:45:00	17:06:00	근무	0	N
GP0131	2026-10-28	07:53:00	17:14:00	근무	0	N
GP0140	2026-10-28	07:54:00	18:49:00	근무	90	N
GP0150	2026-10-28	07:57:00	17:09:00	근무	0	N
GP0160	2026-10-28	08:24:00	17:21:00	근무	0	Y
GP0006	2026-10-29	07:50:00	17:52:00	근무	30	N
GP0024	2026-10-29	19:50:00	06:05:00	근무	60	N
GP0027	2026-10-29	07:49:00	17:14:00	근무	0	N
GP0031	2026-10-29	\N	\N	연차	0	N
GP0036	2026-10-29	07:55:00	18:05:00	근무	60	N
GP0038	2026-10-29	19:59:00	05:50:00	근무	30	N
GP0040	2026-10-29	07:56:00	17:48:00	근무	30	N
GP0044	2026-10-29	07:58:00	17:39:00	근무	30	N
GP0045	2026-10-29	07:50:00	18:45:00	근무	90	N
GP0048	2026-10-29	19:57:00	06:42:00	근무	90	N
GP0052	2026-10-29	07:58:00	17:23:00	근무	0	N
GP0055	2026-10-29	19:49:00	05:20:00	근무	0	N
GP0072	2026-10-29	07:45:00	18:25:00	근무	60	N
GP0074	2026-10-29	19:54:00	06:41:00	근무	90	N
GP0079	2026-10-29	07:45:00	17:42:00	근무	30	N
GP0101	2026-10-29	07:50:00	17:35:00	근무	30	N
GP0102	2026-10-29	07:54:00	18:09:00	근무	60	N
GP0110	2026-10-29	07:50:00	18:50:00	근무	90	N
GP0111	2026-10-29	07:50:00	18:15:00	근무	60	N
GP0120	2026-10-29	07:49:00	18:55:00	근무	90	N
GP0130	2026-10-29	07:52:00	17:25:00	근무	0	N
GP0131	2026-10-29	07:53:00	17:11:00	근무	0	N
GP0140	2026-10-29	08:24:00	18:10:00	근무	60	Y
GP0150	2026-10-29	07:48:00	17:16:00	근무	0	N
GP0160	2026-10-29	07:47:00	18:41:00	근무	90	N
GP0006	2026-10-30	07:58:00	\N	근무	0	N
GP0027	2026-10-30	07:58:00	\N	근무	0	N
GP0031	2026-10-30	07:51:00	\N	근무	0	N
GP0036	2026-10-30	07:53:00	\N	근무	0	N
GP0040	2026-10-30	07:59:00	\N	근무	0	N
GP0044	2026-10-30	07:45:00	\N	근무	0	N
GP0045	2026-10-30	07:48:00	\N	근무	0	N
GP0052	2026-10-30	07:59:00	\N	근무	0	N
GP0072	2026-10-30	07:45:00	\N	근무	0	N
GP0079	2026-10-30	07:47:00	\N	근무	0	N
GP0101	2026-10-30	\N	\N	연차	0	N
GP0102	2026-10-30	07:52:00	\N	근무	0	N
GP0110	2026-10-30	08:25:00	\N	근무	0	Y
GP0111	2026-10-30	08:18:00	\N	근무	0	Y
GP0120	2026-10-30	07:51:00	\N	근무	0	N
GP0130	2026-10-30	07:49:00	\N	근무	0	N
GP0131	2026-10-30	07:58:00	\N	근무	0	N
GP0140	2026-10-30	07:54:00	\N	근무	0	N
GP0150	2026-10-30	07:47:00	\N	근무	0	N
GP0160	2026-10-30	\N	\N	연차	0	N
\.


--
-- Data for Name: change_log; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.change_log (change_id, table_name, row_key, operation, changed_at) FROM stdin;
1	attendance	GP0006|2026-10-28	INSERT	2026-10-28 09:00:00
2	attendance	GP0024|2026-10-28	INSERT	2026-10-28 09:00:00
3	attendance	GP0027|2026-10-28	INSERT	2026-10-28 09:00:00
4	attendance	GP0031|2026-10-28	INSERT	2026-10-28 09:00:00
5	attendance	GP0036|2026-10-28	INSERT	2026-10-28 09:00:00
6	attendance	GP0038|2026-10-28	INSERT	2026-10-28 09:00:00
7	attendance	GP0040|2026-10-28	INSERT	2026-10-28 09:00:00
8	attendance	GP0044|2026-10-28	INSERT	2026-10-28 09:00:00
9	attendance	GP0045|2026-10-28	INSERT	2026-10-28 09:00:00
10	attendance	GP0048|2026-10-28	INSERT	2026-10-28 09:00:00
11	attendance	GP0052|2026-10-28	INSERT	2026-10-28 09:00:00
12	attendance	GP0055|2026-10-28	INSERT	2026-10-28 09:00:00
13	attendance	GP0072|2026-10-28	INSERT	2026-10-28 09:00:00
14	attendance	GP0074|2026-10-28	INSERT	2026-10-28 09:00:00
15	attendance	GP0079|2026-10-28	INSERT	2026-10-28 09:00:00
16	attendance	GP0101|2026-10-28	INSERT	2026-10-28 09:00:00
17	attendance	GP0102|2026-10-28	INSERT	2026-10-28 09:00:00
18	attendance	GP0110|2026-10-28	INSERT	2026-10-28 09:00:00
19	attendance	GP0111|2026-10-28	INSERT	2026-10-28 09:00:00
20	attendance	GP0120|2026-10-28	INSERT	2026-10-28 09:00:00
21	attendance	GP0130|2026-10-28	INSERT	2026-10-28 09:00:00
22	attendance	GP0131|2026-10-28	INSERT	2026-10-28 09:00:00
23	attendance	GP0140|2026-10-28	INSERT	2026-10-28 09:00:00
24	attendance	GP0150|2026-10-28	INSERT	2026-10-28 09:00:00
25	attendance	GP0160|2026-10-28	INSERT	2026-10-28 09:00:00
26	attendance	GP0006|2026-10-29	INSERT	2026-10-29 09:00:00
27	attendance	GP0024|2026-10-29	INSERT	2026-10-29 09:00:00
28	attendance	GP0027|2026-10-29	INSERT	2026-10-29 09:00:00
29	attendance	GP0031|2026-10-29	INSERT	2026-10-29 09:00:00
30	attendance	GP0036|2026-10-29	INSERT	2026-10-29 09:00:00
31	attendance	GP0038|2026-10-29	INSERT	2026-10-29 09:00:00
32	attendance	GP0040|2026-10-29	INSERT	2026-10-29 09:00:00
33	attendance	GP0044|2026-10-29	INSERT	2026-10-29 09:00:00
34	attendance	GP0045|2026-10-29	INSERT	2026-10-29 09:00:00
35	attendance	GP0048|2026-10-29	INSERT	2026-10-29 09:00:00
36	attendance	GP0052|2026-10-29	INSERT	2026-10-29 09:00:00
37	attendance	GP0055|2026-10-29	INSERT	2026-10-29 09:00:00
38	attendance	GP0072|2026-10-29	INSERT	2026-10-29 09:00:00
39	attendance	GP0074|2026-10-29	INSERT	2026-10-29 09:00:00
40	attendance	GP0079|2026-10-29	INSERT	2026-10-29 09:00:00
41	attendance	GP0101|2026-10-29	INSERT	2026-10-29 09:00:00
42	attendance	GP0102|2026-10-29	INSERT	2026-10-29 09:00:00
43	attendance	GP0110|2026-10-29	INSERT	2026-10-29 09:00:00
44	attendance	GP0111|2026-10-29	INSERT	2026-10-29 09:00:00
45	attendance	GP0120|2026-10-29	INSERT	2026-10-29 09:00:00
46	attendance	GP0130|2026-10-29	INSERT	2026-10-29 09:00:00
47	attendance	GP0131|2026-10-29	INSERT	2026-10-29 09:00:00
48	attendance	GP0140|2026-10-29	INSERT	2026-10-29 09:00:00
49	attendance	GP0150|2026-10-29	INSERT	2026-10-29 09:00:00
50	attendance	GP0160|2026-10-29	INSERT	2026-10-29 09:00:00
51	attendance	GP0006|2026-10-30	INSERT	2026-10-30 09:00:00
52	attendance	GP0027|2026-10-30	INSERT	2026-10-30 09:00:00
53	attendance	GP0031|2026-10-30	INSERT	2026-10-30 09:00:00
54	attendance	GP0036|2026-10-30	INSERT	2026-10-30 09:00:00
55	attendance	GP0040|2026-10-30	INSERT	2026-10-30 09:00:00
56	attendance	GP0044|2026-10-30	INSERT	2026-10-30 09:00:00
57	attendance	GP0045|2026-10-30	INSERT	2026-10-30 09:00:00
58	attendance	GP0052|2026-10-30	INSERT	2026-10-30 09:00:00
59	attendance	GP0072|2026-10-30	INSERT	2026-10-30 09:00:00
60	attendance	GP0079|2026-10-30	INSERT	2026-10-30 09:00:00
61	attendance	GP0101|2026-10-30	INSERT	2026-10-30 09:00:00
62	attendance	GP0102|2026-10-30	INSERT	2026-10-30 09:00:00
63	attendance	GP0110|2026-10-30	INSERT	2026-10-30 09:00:00
64	attendance	GP0111|2026-10-30	INSERT	2026-10-30 09:00:00
65	attendance	GP0120|2026-10-30	INSERT	2026-10-30 09:00:00
66	attendance	GP0130|2026-10-30	INSERT	2026-10-30 09:00:00
67	attendance	GP0131|2026-10-30	INSERT	2026-10-30 09:00:00
68	attendance	GP0140|2026-10-30	INSERT	2026-10-30 09:00:00
69	attendance	GP0150|2026-10-30	INSERT	2026-10-30 09:00:00
70	attendance	GP0160|2026-10-30	INSERT	2026-10-30 09:00:00
\.


--
-- Data for Name: department; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.department (dept_code, dept_name, parent_code, manager_emp_no) FROM stdin;
DP10	생산관리팀	\N	GP0101
DP20	프레스1팀	DP10	GP0110
DP21	프레스2팀	DP10	GP0111
DP30	품질팀	\N	GP0120
DP40	보전팀	DP10	GP0130
DP50	영업팀	\N	GP0140
DP60	구매팀	\N	GP0150
DP70	정보시스템팀	\N	GP0160
\.


--
-- Data for Name: employee; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.employee (emp_no, emp_name, dept_code, "position", hire_date, employment_type, status) FROM stdin;
GP0006	장보람	DP20	주임	2017-04-01	정규	재직
GP0024	최서준	DP20	반장	2023-12-01	정규	재직
GP0027	정가은	DP21	사원	2025-09-01	정규	재직
GP0031	강민준	DP20	반장	2024-02-01	정규	재직
GP0036	정동현	DP20	반장	2018-01-01	정규	재직
GP0038	권수빈	DP20	주임	2022-07-01	정규	재직
GP0040	박다은	DP21	반장	2019-03-01	정규	재직
GP0044	조지수	DP21	주임	2022-09-01	정규	재직
GP0045	권민준	DP20	사원	2022-08-01	정규	재직
GP0048	류준혁	DP20	사원	2017-10-01	정규	재직
GP0052	강가은	DP21	주임	2016-06-01	정규	재직
GP0055	권도윤	DP21	반장	2017-06-01	정규	재직
GP0072	강은지	DP20	반장	2014-11-01	정규	재직
GP0074	홍가은	DP20	주임	2021-01-01	정규	재직
GP0079	최가은	DP21	사원	2018-07-01	정규	재직
GP0101	이재원	DP10	과장	2019-04-01	정규	재직
GP0102	류지호	DP10	대리	2017-05-01	정규	재직
GP0110	최재원	DP20	과장	2015-02-01	정규	재직
GP0111	박동현	DP21	과장	2021-03-01	정규	재직
GP0120	강성민	DP30	대리	2024-10-01	정규	재직
GP0130	오준혁	DP40	주임	2023-12-01	정규	재직
GP0131	홍보람	DP40	사원	2011-03-01	정규	재직
GP0140	박지훈	DP50	대리	2021-05-01	정규	재직
GP0150	최승민	DP60	주임	2011-07-01	정규	재직
GP0160	이다은	DP70	사원	2020-03-01	정규	재직
\.


--
-- Data for Name: fixed_asset; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.fixed_asset (asset_no, asset_name, asset_type, acquired_on, acquisition_cost, book_value, location, equipment_code) FROM stdin;
FA-0001	프레스 300톤 (2라인 1호)	기계장치	2015-09-07	540000000.00	84000000.00	1공장 2라인	P2O1
FA-0002	블랭킹 프레스 250톤	기계장치	2017-04-10	360000000.00	128000000.00	1공장 1라인	B101
FA-0003	프레스 400톤 (1라인 1호)	기계장치	2017-04-10	790000000.00	268000000.00	1공장 1라인	P101
FA-0004	지게차 2.5톤	차량	2020-03-02	36000000.00	12000000.00	자재창고	\N
FA-0005	스폿 용접기	기계장치	2021-05-17	115000000.00	61000000.00	1공장 2라인	W201
FA-0006	프레스 600톤 (1라인 3호)	기계장치	2022-02-21	1240000000.00	830000000.00	1공장 1라인	P103
FA-0007	3차원 검사대	기계장치	2023-06-12	92000000.00	64000000.00	1공장 1라인	I101
FA-0008	브래킷 금형 세트	공구	2024-01-15	140000000.00	70000000.00	금형창고	\N
\.


--
-- Data for Name: gl_account; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.gl_account (account_code, account_name, account_type, parent_code, use_yn) FROM stdin;
1080	외상매출금	자산	\N	Y
1350	부가세대급금	자산	\N	Y
1460	원재료	자산	\N	Y
2510	외상매입금	부채	\N	Y
2550	부가세예수금	부채	\N	Y
4010	제품매출	수익	\N	Y
8110	복리후생비	비용	\N	Y
\.


--
-- Data for Name: goods_receipt; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.goods_receipt (gr_no, po_no, item_code, gr_date, qty, accepted_qty, rejected_qty, warehouse, inspector) FROM stdin;
RC2608-0001	PO2607-001	CL-590	2026-08-01	38000.000	38000.000	0.000	원자재창고	품질팀
RC2608-0002	PO2607-002	CL-440	2026-08-02	2500.000	2500.000	0.000	원자재창고	품질팀
RC2608-0003	PO2607-002	CL-440	2026-08-04	2500.000	2500.000	0.000	원자재창고	품질팀
RC2608-0004	PO2608-001	CL-440	2026-08-08	11000.000	11000.000	0.000	원자재창고	품질팀
RC2608-0005	PO2608-002	BT-M8	2026-08-09	20000.000	20000.000	0.000	부자재창고	품질팀
RC2608-0006	PO2608-003	CL-590	2026-08-10	8500.000	8500.000	0.000	원자재창고	품질팀
RC2608-0007	PO2608-003	CL-590	2026-08-12	8500.000	8450.000	50.000	원자재창고	품질팀
RC2608-0008	PO2608-004	CL-590	2026-08-15	17500.000	17500.000	0.000	원자재창고	품질팀
RC2608-0009	PO2608-004	CL-590	2026-08-17	17500.000	17500.000	0.000	원자재창고	품질팀
RC2608-0010	PO2608-005	CL-440	2026-08-17	7000.000	7000.000	0.000	원자재창고	품질팀
RC2608-0011	PO2608-006	CL-590	2026-08-23	12000.000	12000.000	0.000	원자재창고	품질팀
RC2608-0012	PO2608-006	CL-590	2026-08-25	12000.000	12000.000	0.000	원자재창고	품질팀
RC2608-0013	PO2608-007	CL-440	2026-08-24	7000.000	7000.000	0.000	원자재창고	품질팀
RC2608-0014	PO2608-008	CL-440	2026-08-30	21000.000	21000.000	0.000	원자재창고	품질팀
RC2609-0001	PO2608-009	CL-440	2026-09-05	12000.000	11980.000	20.000	원자재창고	품질팀
RC2609-0002	PO2609-001	CL-590	2026-09-07	17000.000	17000.000	0.000	원자재창고	품질팀
RC2609-0003	PO2609-002	BT-M8	2026-09-12	34000.000	34000.000	0.000	부자재창고	품질팀
RC2609-0004	PO2609-003	CL-440	2026-09-12	10000.000	9950.000	50.000	원자재창고	품질팀
RC2609-0005	PO2609-004	CL-590	2026-09-12	26000.000	26000.000	0.000	원자재창고	품질팀
RC2609-0006	PO2609-005	CL-440	2026-09-20	8500.000	8500.000	0.000	원자재창고	품질팀
RC2609-0007	PO2609-005	CL-440	2026-09-22	8500.000	8500.000	0.000	원자재창고	품질팀
RC2609-0008	PO2609-006	CL-590	2026-09-21	5000.000	4980.000	20.000	원자재창고	품질팀
RC2609-0009	PO2609-006	CL-590	2026-09-23	5000.000	4980.000	20.000	원자재창고	품질팀
RC2609-0010	PO2609-007	CL-440	2026-09-27	5500.000	5450.000	50.000	원자재창고	품질팀
RC2609-0011	PO2609-007	CL-440	2026-09-29	5500.000	5480.000	20.000	원자재창고	품질팀
RC2609-0012	PO2609-008	CL-590	2026-09-28	24000.000	23980.000	20.000	원자재창고	품질팀
RC2610-0001	PO2609-009	CL-440	2026-10-05	9000.000	9000.000	0.000	원자재창고	품질팀
RC2610-0002	PO2609-009	CL-440	2026-10-07	9000.000	9000.000	0.000	원자재창고	품질팀
RC2610-0003	PO2609-010	CL-590	2026-10-05	16000.000	16000.000	0.000	원자재창고	품질팀
RC2610-0004	PO2610-001	BT-M8	2026-10-10	8000.000	8000.000	0.000	부자재창고	품질팀
RC2610-0005	PO2610-002	CL-440	2026-10-10	20000.000	19980.000	20.000	원자재창고	품질팀
RC2610-0006	PO2610-003	CL-590	2026-10-10	8000.000	8000.000	0.000	원자재창고	품질팀
RC2610-0007	PO2610-004	CL-440	2026-10-18	13000.000	12950.000	50.000	원자재창고	품질팀
RC2610-0008	PO2610-005	CL-590	2026-10-18	2500.000	2500.000	0.000	원자재창고	품질팀
RC2610-0009	PO2610-005	CL-590	2026-10-20	2500.000	2500.000	0.000	원자재창고	품질팀
RC2610-0010	PO2610-006	CL-440	2026-10-25	12000.000	12000.000	0.000	원자재창고	품질팀
\.


--
-- Data for Name: item; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.item (item_code, item_name, spec, unit, item_type, std_price, safety_stock, use_yn, updated_at) FROM stdin;
BRK-210L	프론트 브래킷 좌	프레스 성형품	EA	FG	4300.00	300.000	Y	2026-07-20 09:00:00
BRK-210R	프론트 브래킷 우	프레스 성형품	EA	FG	4300.00	300.000	Y	2026-07-20 09:00:00
RNF-320L	사이드 보강재 좌	프레스 성형품	EA	FG	5600.00	200.000	Y	2026-07-20 09:00:00
RNF-320R	사이드 보강재 우	프레스 성형품	EA	FG	5600.00	200.000	Y	2026-07-20 09:00:00
PNL-410	휠하우스 패널	프레스 성형품	EA	FG	2900.00	400.000	Y	2026-07-20 09:00:00
BLK-210	브래킷 블랭크	블랭킹 반제품	EA	SF	0.00	0.000	Y	2026-07-20 09:00:00
BLK-320	보강재 블랭크	블랭킹 반제품	EA	SF	0.00	0.000	Y	2026-07-20 09:00:00
CL-440	냉연 코일 SPH440	1.2t x 1250	KG	RM	1250.00	8000.000	Y	2026-07-20 09:00:00
CL-590	고장력 코일 SGC590	2.0t x 1250	KG	RM	1480.00	5000.000	Y	2026-07-20 09:00:00
BT-M8	육각 볼트 M8	M8 x 20	EA	RM	90.00	3000.000	Y	2026-07-20 09:00:00
\.


--
-- Data for Name: journal_entry; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.journal_entry (entry_no, line_no, entry_date, account_code, vendor_code, debit, credit, description) FROM stdin;
JV-2608-0001	1	2026-08-28	1080	CU0001	129470000.00	0.00	2026-08 제품 매출
JV-2608-0001	2	2026-08-28	4010	CU0001	0.00	117700000.00	2026-08 제품 매출
JV-2608-0001	3	2026-08-28	2550	CU0001	0.00	11770000.00	2026-08 제품 매출
JV-2609-0001	1	2026-09-28	1080	CU0001	197780000.00	0.00	2026-09 제품 매출
JV-2609-0001	2	2026-09-28	4010	CU0001	0.00	179800000.00	2026-09 제품 매출
JV-2609-0001	3	2026-09-28	2550	CU0001	0.00	17980000.00	2026-09 제품 매출
JV-2610-0001	1	2026-10-28	1080	CU0001	138160000.00	0.00	2026-10 제품 매출
JV-2610-0001	2	2026-10-28	4010	CU0001	0.00	125600000.00	2026-10 제품 매출
JV-2610-0001	3	2026-10-28	2550	CU0001	0.00	12560000.00	2026-10 제품 매출
JV-2608-0002	1	2026-08-28	1080	CU0002	268070000.00	0.00	2026-08 제품 매출
JV-2608-0002	2	2026-08-28	4010	CU0002	0.00	243700000.00	2026-08 제품 매출
JV-2608-0002	3	2026-08-28	2550	CU0002	0.00	24370000.00	2026-08 제품 매출
JV-2609-0002	1	2026-09-28	1080	CU0002	241120000.00	0.00	2026-09 제품 매출
JV-2609-0002	2	2026-09-28	4010	CU0002	0.00	219200000.00	2026-09 제품 매출
JV-2609-0002	3	2026-09-28	2550	CU0002	0.00	21920000.00	2026-09 제품 매출
JV-2610-0002	1	2026-10-28	1080	CU0002	194370000.00	0.00	2026-10 제품 매출
JV-2610-0002	2	2026-10-28	4010	CU0002	0.00	176700000.00	2026-10 제품 매출
JV-2610-0002	3	2026-10-28	2550	CU0002	0.00	17670000.00	2026-10 제품 매출
JV-2608-0003	1	2026-08-28	1080	CU0003	212795000.00	0.00	2026-08 제품 매출
JV-2608-0003	2	2026-08-28	4010	CU0003	0.00	193450000.00	2026-08 제품 매출
JV-2608-0003	3	2026-08-28	2550	CU0003	0.00	19345000.00	2026-08 제품 매출
JV-2609-0003	1	2026-09-28	1080	CU0003	223025000.00	0.00	2026-09 제품 매출
JV-2609-0003	2	2026-09-28	4010	CU0003	0.00	202750000.00	2026-09 제품 매출
JV-2609-0003	3	2026-09-28	2550	CU0003	0.00	20275000.00	2026-09 제품 매출
JV-2610-0003	1	2026-10-28	1080	CU0003	248215000.00	0.00	2026-10 제품 매출
JV-2610-0003	2	2026-10-28	4010	CU0003	0.00	225650000.00	2026-10 제품 매출
JV-2610-0003	3	2026-10-28	2550	CU0003	0.00	22565000.00	2026-10 제품 매출
JV-2608-0004	1	2026-08-27	1460	SP0001	63750000.00	0.00	2026-08 원재료 매입
JV-2608-0004	2	2026-08-27	1350	SP0001	6375000.00	0.00	2026-08 원재료 매입
JV-2608-0004	3	2026-08-27	2510	SP0001	0.00	70125000.00	2026-08 원재료 매입
JV-2609-0004	1	2026-09-27	1460	SP0001	62325000.00	0.00	2026-09 원재료 매입
JV-2609-0004	2	2026-09-27	1350	SP0001	6232500.00	0.00	2026-09 원재료 매입
JV-2609-0004	3	2026-09-27	2510	SP0001	0.00	68557500.00	2026-09 원재료 매입
JV-2610-0004	1	2026-10-27	1460	SP0001	78662500.00	0.00	2026-10 원재료 매입
JV-2610-0004	2	2026-10-27	1350	SP0001	7866250.00	0.00	2026-10 원재료 매입
JV-2610-0004	3	2026-10-27	2510	SP0001	0.00	86528750.00	2026-10 원재료 매입
JV-2608-0005	1	2026-08-27	1460	SP0002	1800000.00	0.00	2026-08 원재료 매입
JV-2608-0005	2	2026-08-27	1350	SP0002	180000.00	0.00	2026-08 원재료 매입
JV-2608-0005	3	2026-08-27	2510	SP0002	0.00	1980000.00	2026-08 원재료 매입
JV-2609-0005	1	2026-09-27	1460	SP0002	3060000.00	0.00	2026-09 원재료 매입
JV-2609-0005	3	2026-09-27	2510	SP0002	0.00	3366000.00	2026-09 원재료 매입
JV-2610-0005	1	2026-10-27	1460	SP0002	720000.00	0.00	2026-10 원재료 매입
JV-2610-0005	2	2026-10-27	1350	SP0002	72000.00	0.00	2026-10 원재료 매입
JV-2610-0005	3	2026-10-27	2510	SP0002	0.00	792000.00	2026-10 원재료 매입
JV-2608-0006	1	2026-08-27	1460	SP0003	168646000.00	0.00	2026-08 원재료 매입
JV-2608-0006	2	2026-08-27	1350	SP0003	16864600.00	0.00	2026-08 원재료 매입
JV-2608-0006	3	2026-08-27	2510	SP0003	0.00	185510600.00	2026-08 원재료 매입
JV-2609-0006	1	2026-09-27	1460	SP0003	113871200.00	0.00	2026-09 원재료 매입
JV-2609-0006	2	2026-09-27	1350	SP0003	11387120.00	0.00	2026-09 원재료 매입
JV-2609-0006	3	2026-09-27	2510	SP0003	0.00	125258320.00	2026-09 원재료 매입
JV-2610-0006	1	2026-10-27	1460	SP0003	42920000.00	0.00	2026-10 원재료 매입
JV-2610-0006	2	2026-10-27	1350	SP0003	4292000.00	0.00	2026-10 원재료 매입
JV-2610-0006	3	2026-10-27	2510	SP0003	0.00	47212000.00	2026-10 원재료 매입
\.


--
-- Data for Name: purchase_invoice; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.purchase_invoice (invoice_no, vendor_code, invoice_date, supply_amount, vat, total_amount, paid_yn, paid_date) FROM stdin;
AP-2608-001	SP0001	2026-08-27	63750000.00	6375000.00	70125000.00	Y	2026-09-21
AP-2609-001	SP0001	2026-09-27	62325000.00	6232500.00	68557500.00	Y	2026-10-22
AP-2610-001	SP0001	2026-10-27	78662500.00	7866250.00	86528750.00	N	\N
AP-2608-002	SP0002	2026-08-27	1800000.00	180000.00	1980000.00	Y	2026-09-21
AP-2609-002	SP0002	2026-09-27	3060000.00	306000.00	3366000.00	Y	2026-10-22
AP-2610-002	SP0002	2026-10-27	720000.00	72000.00	792000.00	N	\N
AP-2608-003	SP0003	2026-08-27	168646000.00	16864600.00	185510600.00	Y	2026-09-21
AP-2609-003	SP0003	2026-09-27	113871200.00	11387120.00	125258320.00	Y	2026-10-22
AP-2610-003	SP0003	2026-10-27	42920000.00	4292000.00	47212000.00	N	\N
\.


--
-- Data for Name: purchase_order; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.purchase_order (po_no, vendor_code, po_date, due_date, status, total_amount, buyer, remark) FROM stdin;
PO2607-001	SP0003	2026-07-27	2026-08-03	입고완료	56240000.00	구매팀	\N
PO2607-002	SP0001	2026-07-28	2026-08-04	입고완료	6250000.00	구매팀	\N
PO2608-001	SP0001	2026-08-03	2026-08-10	입고완료	13750000.00	구매팀	\N
PO2608-002	SP0002	2026-08-04	2026-08-11	입고완료	1800000.00	구매팀	\N
PO2608-003	SP0003	2026-08-05	2026-08-12	입고완료	25160000.00	구매팀	\N
PO2608-004	SP0003	2026-08-10	2026-08-17	입고완료	51800000.00	구매팀	\N
PO2608-005	SP0001	2026-08-12	2026-08-19	입고완료	8750000.00	구매팀	\N
PO2608-006	SP0003	2026-08-18	2026-08-25	입고완료	35520000.00	구매팀	\N
PO2608-007	SP0001	2026-08-19	2026-08-26	입고완료	8750000.00	구매팀	\N
PO2608-008	SP0001	2026-08-25	2026-09-01	입고완료	26250000.00	구매팀	\N
PO2608-009	SP0001	2026-08-31	2026-09-07	입고완료	15000000.00	구매팀	\N
PO2609-001	SP0003	2026-09-02	2026-09-09	입고완료	25160000.00	구매팀	\N
PO2609-002	SP0002	2026-09-07	2026-09-14	입고완료	3060000.00	구매팀	\N
PO2609-003	SP0001	2026-09-07	2026-09-14	입고완료	12500000.00	구매팀	\N
PO2609-004	SP0003	2026-09-07	2026-09-14	입고완료	38480000.00	구매팀	\N
PO2609-005	SP0001	2026-09-15	2026-09-22	입고완료	21250000.00	구매팀	납기 조정
PO2609-006	SP0003	2026-09-16	2026-09-23	입고완료	14800000.00	구매팀	\N
PO2609-007	SP0001	2026-09-22	2026-09-29	입고완료	13750000.00	구매팀	\N
PO2609-008	SP0003	2026-09-23	2026-09-30	입고완료	35520000.00	구매팀	\N
PO2609-009	SP0001	2026-09-30	2026-10-07	입고완료	22500000.00	구매팀	\N
PO2609-010	SP0003	2026-09-30	2026-10-07	입고완료	23680000.00	구매팀	\N
PO2610-001	SP0002	2026-10-05	2026-10-12	입고완료	720000.00	구매팀	\N
PO2610-002	SP0001	2026-10-05	2026-10-12	입고완료	25000000.00	구매팀	\N
PO2610-003	SP0003	2026-10-05	2026-10-12	입고완료	11840000.00	구매팀	납기 조정
PO2610-004	SP0001	2026-10-13	2026-10-20	입고완료	16250000.00	구매팀	\N
PO2610-005	SP0003	2026-10-13	2026-10-20	입고완료	7400000.00	구매팀	\N
PO2610-006	SP0001	2026-10-20	2026-10-27	입고완료	15000000.00	구매팀	\N
\.


--
-- Data for Name: purchase_order_line; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.purchase_order_line (po_no, line_no, item_code, qty, unit_price, received_qty, due_date) FROM stdin;
PO2607-001	1	CL-590	38000.000	1480.00	38000.000	2026-08-03
PO2607-002	1	CL-440	5000.000	1250.00	5000.000	2026-08-04
PO2608-001	1	CL-440	11000.000	1250.00	11000.000	2026-08-10
PO2608-002	1	BT-M8	20000.000	90.00	20000.000	2026-08-11
PO2608-003	1	CL-590	17000.000	1480.00	17000.000	2026-08-12
PO2608-004	1	CL-590	35000.000	1480.00	35000.000	2026-08-17
PO2608-005	1	CL-440	7000.000	1250.00	7000.000	2026-08-19
PO2608-006	1	CL-590	24000.000	1480.00	24000.000	2026-08-25
PO2608-007	1	CL-440	7000.000	1250.00	7000.000	2026-08-26
PO2608-008	1	CL-440	21000.000	1250.00	21000.000	2026-09-01
PO2608-009	1	CL-440	12000.000	1250.00	12000.000	2026-09-07
PO2609-001	1	CL-590	17000.000	1480.00	17000.000	2026-09-09
PO2609-002	1	BT-M8	34000.000	90.00	34000.000	2026-09-14
PO2609-003	1	CL-440	10000.000	1250.00	10000.000	2026-09-14
PO2609-004	1	CL-590	26000.000	1480.00	26000.000	2026-09-14
PO2609-005	1	CL-440	17000.000	1250.00	17000.000	2026-09-22
PO2609-006	1	CL-590	10000.000	1480.00	10000.000	2026-09-23
PO2609-007	1	CL-440	11000.000	1250.00	11000.000	2026-09-29
PO2609-008	1	CL-590	24000.000	1480.00	24000.000	2026-09-30
PO2609-009	1	CL-440	18000.000	1250.00	18000.000	2026-10-07
PO2609-010	1	CL-590	16000.000	1480.00	16000.000	2026-10-07
PO2610-001	1	BT-M8	8000.000	90.00	8000.000	2026-10-12
PO2610-002	1	CL-440	20000.000	1250.00	20000.000	2026-10-12
PO2610-003	1	CL-590	8000.000	1480.00	8000.000	2026-10-12
PO2610-004	1	CL-440	13000.000	1250.00	13000.000	2026-10-20
PO2610-005	1	CL-590	5000.000	1480.00	5000.000	2026-10-20
PO2610-006	1	CL-440	12000.000	1250.00	12000.000	2026-10-27
\.


--
-- Data for Name: sales_invoice; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.sales_invoice (invoice_no, customer_code, invoice_date, supply_amount, vat, total_amount, collected_yn, collected_date) FROM stdin;
AR-2608-001	CU0001	2026-08-28	117700000.00	11770000.00	129470000.00	Y	2026-09-17
AR-2609-001	CU0001	2026-09-28	179800000.00	17980000.00	197780000.00	Y	2026-10-18
AR-2610-001	CU0001	2026-10-28	125600000.00	12560000.00	138160000.00	N	\N
AR-2608-002	CU0002	2026-08-28	243700000.00	24370000.00	268070000.00	Y	2026-09-17
AR-2609-002	CU0002	2026-09-28	219200000.00	21920000.00	241120000.00	Y	2026-10-18
AR-2610-002	CU0002	2026-10-28	176700000.00	17670000.00	194370000.00	N	\N
AR-2608-003	CU0003	2026-08-28	193450000.00	19345000.00	212795000.00	Y	2026-09-17
AR-2609-003	CU0003	2026-09-28	202750000.00	20275000.00	223025000.00	Y	2026-10-18
AR-2610-003	CU0003	2026-10-28	225650000.00	22565000.00	248215000.00	N	\N
\.


--
-- Data for Name: sales_order; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.sales_order (so_no, customer_code, so_date, due_date, status, total_amount, sales_rep) FROM stdin;
OD-2607-001	CU0003	2026-07-24	2026-08-07	출하완료	19600000.00	영업팀
OD-2607-002	CU0003	2026-07-26	2026-08-12	출하완료	19600000.00	영업팀
OD-2607-003	CU0003	2026-07-26	2026-08-12	출하완료	10150000.00	영업팀
OD-2607-004	CU0003	2026-07-28	2026-08-13	출하완료	33600000.00	영업팀
OD-2608-001	CU0001	2026-08-01	2026-08-13	출하완료	19600000.00	영업팀
OD-2607-005	CU0003	2026-07-30	2026-08-14	출하완료	4300000.00	영업팀
OD-2608-002	CU0003	2026-08-03	2026-08-14	출하완료	4300000.00	영업팀
OD-2608-003	CU0001	2026-08-02	2026-08-12	출하완료	36400000.00	영업팀
OD-2607-006	CU0001	2026-07-30	2026-08-15	출하완료	16800000.00	영업팀
OD-2608-004	CU0002	2026-08-07	2026-08-16	출하완료	16800000.00	영업팀
OD-2608-005	CU0002	2026-08-01	2026-08-18	출하완료	25200000.00	영업팀
OD-2608-006	CU0002	2026-08-04	2026-08-18	출하완료	27950000.00	영업팀
OD-2608-007	CU0002	2026-08-08	2026-08-16	출하완료	27950000.00	영업팀
OD-2608-008	CU0002	2026-08-09	2026-08-19	출하완료	19600000.00	영업팀
OD-2608-009	CU0003	2026-08-05	2026-08-21	출하완료	23650000.00	영업팀
OD-2608-010	CU0001	2026-08-10	2026-08-22	출하완료	25200000.00	영업팀
OD-2608-011	CU0003	2026-08-15	2026-08-23	출하완료	33600000.00	영업팀
OD-2608-012	CU0002	2026-08-15	2026-08-22	출하완료	5800000.00	영업팀
OD-2608-013	CU0002	2026-08-12	2026-08-25	출하완료	42000000.00	영업팀
OD-2608-014	CU0003	2026-08-17	2026-08-28	출하완료	8700000.00	영업팀
OD-2608-015	CU0003	2026-08-15	2026-08-23	출하완료	8400000.00	영업팀
OD-2608-016	CU0001	2026-08-18	2026-08-26	출하완료	16800000.00	영업팀
OD-2608-017	CU0002	2026-08-17	2026-08-28	출하완료	36400000.00	영업팀
OD-2608-018	CU0003	2026-08-17	2026-08-28	출하완료	10750000.00	영업팀
OD-2608-019	CU0002	2026-08-23	2026-08-31	출하완료	25200000.00	영업팀
OD-2608-020	CU0003	2026-08-21	2026-09-01	출하완료	16800000.00	영업팀
OD-2608-021	CU0001	2026-08-20	2026-09-03	출하완료	2900000.00	영업팀
OD-2608-022	CU0002	2026-08-22	2026-09-03	출하완료	16800000.00	영업팀
OD-2608-023	CU0002	2026-08-19	2026-09-02	출하완료	19350000.00	영업팀
OD-2608-024	CU0002	2026-08-20	2026-09-05	출하완료	8600000.00	영업팀
OD-2608-025	CU0001	2026-08-24	2026-09-08	출하완료	10750000.00	영업팀
OD-2608-026	CU0003	2026-08-22	2026-09-05	출하완료	8700000.00	영업팀
OD-2608-027	CU0003	2026-08-27	2026-09-04	출하완료	21500000.00	영업팀
OD-2608-028	CU0001	2026-08-23	2026-09-10	출하완료	21500000.00	영업팀
OD-2608-029	CU0001	2026-08-31	2026-09-07	출하완료	21500000.00	영업팀
OD-2608-030	CU0001	2026-08-30	2026-09-09	출하완료	34400000.00	영업팀
OD-2608-031	CU0003	2026-08-30	2026-09-15	출하완료	30800000.00	영업팀
OD-2608-032	CU0002	2026-08-30	2026-09-15	출하완료	17200000.00	영업팀
OD-2609-001	CU0002	2026-09-06	2026-09-15	출하완료	30100000.00	영업팀
OD-2609-002	CU0001	2026-09-04	2026-09-17	출하완료	30100000.00	영업팀
OD-2609-003	CU0001	2026-09-04	2026-09-17	출하완료	33600000.00	영업팀
OD-2609-004	CU0002	2026-09-08	2026-09-19	출하완료	50400000.00	영업팀
OD-2609-005	CU0002	2026-09-12	2026-09-21	출하완료	19350000.00	영업팀
OD-2609-006	CU0003	2026-09-11	2026-09-24	출하완료	30800000.00	영업팀
OD-2609-007	CU0002	2026-09-14	2026-09-25	출하완료	11200000.00	영업팀
OD-2609-008	CU0003	2026-09-09	2026-09-22	출하완료	10150000.00	영업팀
OD-2609-009	CU0003	2026-09-12	2026-09-26	출하완료	27950000.00	영업팀
OD-2609-010	CU0002	2026-09-18	2026-09-24	출하완료	19350000.00	영업팀
OD-2609-011	CU0002	2026-09-17	2026-09-28	출하완료	7250000.00	영업팀
OD-2609-012	CU0003	2026-09-14	2026-09-28	출하완료	30100000.00	영업팀
OD-2609-013	CU0003	2026-09-15	2026-09-30	출하완료	4350000.00	영업팀
OD-2609-014	CU0002	2026-09-19	2026-09-29	출하완료	36400000.00	영업팀
OD-2609-015	CU0003	2026-09-14	2026-09-29	출하완료	17200000.00	영업팀
OD-2609-016	CU0003	2026-09-18	2026-10-02	출하완료	5800000.00	영업팀
OD-2609-017	CU0001	2026-09-22	2026-09-30	출하완료	15050000.00	영업팀
OD-2609-018	CU0003	2026-09-20	2026-10-04	출하완료	25200000.00	영업팀
OD-2609-019	CU0001	2026-09-25	2026-10-05	출하완료	32250000.00	영업팀
OD-2609-020	CU0002	2026-09-25	2026-10-06	출하완료	21500000.00	영업팀
OD-2609-021	CU0003	2026-09-26	2026-10-08	출하완료	15050000.00	영업팀
OD-2609-022	CU0002	2026-09-26	2026-10-05	출하완료	42000000.00	영업팀
OD-2609-023	CU0002	2026-09-29	2026-10-12	출하완료	16800000.00	영업팀
OD-2610-001	CU0002	2026-10-01	2026-10-11	출하완료	2900000.00	영업팀
OD-2610-002	CU0002	2026-10-01	2026-10-12	출하완료	30100000.00	영업팀
OD-2609-024	CU0002	2026-09-26	2026-10-10	출하완료	7250000.00	영업팀
OD-2609-025	CU0003	2026-09-27	2026-10-14	출하완료	23650000.00	영업팀
OD-2610-003	CU0003	2026-10-02	2026-10-13	출하완료	32250000.00	영업팀
OD-2610-004	CU0003	2026-10-05	2026-10-16	출하완료	25200000.00	영업팀
OD-2610-005	CU0002	2026-10-05	2026-10-14	출하완료	14000000.00	영업팀
OD-2609-026	CU0003	2026-09-30	2026-10-12	출하완료	5800000.00	영업팀
OD-2609-027	CU0001	2026-09-30	2026-10-18	출하완료	22400000.00	영업팀
OD-2610-006	CU0003	2026-10-08	2026-10-17	출하완료	30100000.00	영업팀
OD-2610-007	CU0003	2026-10-07	2026-10-21	출하완료	8700000.00	영업팀
OD-2610-008	CU0003	2026-10-08	2026-10-18	출하완료	19600000.00	영업팀
OD-2610-009	CU0001	2026-10-07	2026-10-21	출하완료	23650000.00	영업팀
OD-2610-010	CU0002	2026-10-05	2026-10-19	출하완료	5600000.00	영업팀
OD-2610-011	CU0002	2026-10-09	2026-10-19	출하완료	15050000.00	영업팀
OD-2610-012	CU0002	2026-10-08	2026-10-23	출하완료	21500000.00	영업팀
OD-2610-013	CU0001	2026-10-11	2026-10-28	출하완료	36550000.00	영업팀
OD-2610-014	CU0003	2026-10-16	2026-10-24	출하완료	23650000.00	영업팀
OD-2610-015	CU0003	2026-10-17	2026-10-25	출하완료	16800000.00	영업팀
OD-2610-016	CU0003	2026-10-13	2026-10-25	출하완료	15050000.00	영업팀
OD-2610-017	CU0001	2026-10-19	2026-10-31	출하완료	23650000.00	영업팀
OD-2608-033	CU0003	2026-08-18	2026-09-07	취소	8600000.00	영업팀
OD-2609-028	CU0003	2026-09-22	2026-10-12	취소	8600000.00	영업팀
OD-2610-021	CU0001	2026-10-20	2026-11-04	생산중	47300000.00	영업팀
OD-2610-019	CU0002	2026-10-18	2026-10-31	접수	12900000.00	영업팀
OD-2610-018	CU0003	2026-10-23	2026-11-05	생산중	11600000.00	영업팀
OD-2610-020	CU0003	2026-10-27	2026-11-10	접수	8700000.00	영업팀
\.


--
-- Data for Name: sales_order_line; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.sales_order_line (so_no, line_no, item_code, qty, unit_price, shipped_qty) FROM stdin;
OD-2607-001	1	RNF-320L	1500.000	5600.00	1500.000
OD-2607-001	2	RNF-320R	2000.000	5600.00	2000.000
OD-2607-002	1	RNF-320R	3500.000	5600.00	3500.000
OD-2607-003	1	PNL-410	3500.000	2900.00	3500.000
OD-2607-004	1	RNF-320L	2000.000	5600.00	2000.000
OD-2607-004	2	RNF-320R	4000.000	5600.00	4000.000
OD-2608-001	1	RNF-320L	2500.000	5600.00	2500.000
OD-2608-001	2	RNF-320R	1000.000	5600.00	1000.000
OD-2607-005	1	BRK-210L	1000.000	4300.00	1000.000
OD-2608-002	1	BRK-210R	1000.000	4300.00	1000.000
OD-2608-003	1	RNF-320L	3000.000	5600.00	3000.000
OD-2608-003	2	RNF-320R	3500.000	5600.00	3500.000
OD-2607-006	1	RNF-320L	3000.000	5600.00	3000.000
OD-2608-004	1	RNF-320L	2000.000	5600.00	2000.000
OD-2608-004	2	RNF-320R	1000.000	5600.00	1000.000
OD-2608-005	1	RNF-320L	3500.000	5600.00	3500.000
OD-2608-005	2	RNF-320R	1000.000	5600.00	1000.000
OD-2608-006	1	BRK-210L	2000.000	4300.00	2000.000
OD-2608-006	2	BRK-210R	4500.000	4300.00	4500.000
OD-2608-007	1	BRK-210L	3500.000	4300.00	3500.000
OD-2608-007	2	BRK-210R	3000.000	4300.00	3000.000
OD-2608-008	1	RNF-320L	2000.000	5600.00	2000.000
OD-2608-008	2	RNF-320R	1500.000	5600.00	1500.000
OD-2608-009	1	BRK-210L	2500.000	4300.00	2500.000
OD-2608-009	2	BRK-210R	3000.000	4300.00	3000.000
OD-2608-010	1	RNF-320L	2500.000	5600.00	2500.000
OD-2608-010	2	RNF-320R	2000.000	5600.00	2000.000
OD-2608-011	1	RNF-320L	2000.000	5600.00	2000.000
OD-2608-011	2	RNF-320R	4000.000	5600.00	4000.000
OD-2608-012	1	PNL-410	2000.000	2900.00	2000.000
OD-2608-013	1	RNF-320L	3000.000	5600.00	3000.000
OD-2608-013	2	RNF-320R	4500.000	5600.00	4500.000
OD-2608-014	1	PNL-410	3000.000	2900.00	3000.000
OD-2608-015	1	RNF-320L	1500.000	5600.00	1500.000
OD-2608-016	1	RNF-320L	2000.000	5600.00	2000.000
OD-2608-016	2	RNF-320R	1000.000	5600.00	1000.000
OD-2608-017	1	RNF-320L	4000.000	5600.00	4000.000
OD-2608-017	2	RNF-320R	2500.000	5600.00	2500.000
OD-2608-018	1	BRK-210L	2500.000	4300.00	2500.000
OD-2608-019	1	RNF-320L	1000.000	5600.00	1000.000
OD-2608-019	2	RNF-320R	3500.000	5600.00	3500.000
OD-2608-020	1	RNF-320L	3000.000	5600.00	3000.000
OD-2608-021	1	PNL-410	1000.000	2900.00	1000.000
OD-2608-022	1	RNF-320L	2000.000	5600.00	2000.000
OD-2608-022	2	RNF-320R	1000.000	5600.00	1000.000
OD-2608-023	1	BRK-210R	4500.000	4300.00	4500.000
OD-2608-024	1	BRK-210R	2000.000	4300.00	2000.000
OD-2608-025	1	BRK-210L	1500.000	4300.00	1500.000
OD-2608-025	2	BRK-210R	1000.000	4300.00	1000.000
OD-2608-026	1	PNL-410	3000.000	2900.00	3000.000
OD-2608-027	1	BRK-210L	3500.000	4300.00	3500.000
OD-2608-027	2	BRK-210R	1500.000	4300.00	1500.000
OD-2608-028	1	BRK-210L	3000.000	4300.00	3000.000
OD-2608-028	2	BRK-210R	2000.000	4300.00	2000.000
OD-2608-029	1	BRK-210L	2500.000	4300.00	2500.000
OD-2608-029	2	BRK-210R	2500.000	4300.00	2500.000
OD-2608-030	1	BRK-210L	4500.000	4300.00	4500.000
OD-2608-030	2	BRK-210R	3500.000	4300.00	3500.000
OD-2608-031	1	RNF-320L	4000.000	5600.00	4000.000
OD-2608-031	2	RNF-320R	1500.000	5600.00	1500.000
OD-2608-032	1	BRK-210L	2000.000	4300.00	2000.000
OD-2608-032	2	BRK-210R	2000.000	4300.00	2000.000
OD-2609-001	1	BRK-210L	3500.000	4300.00	3500.000
OD-2609-001	2	BRK-210R	3500.000	4300.00	3500.000
OD-2609-002	1	BRK-210L	4500.000	4300.00	4500.000
OD-2609-002	2	BRK-210R	2500.000	4300.00	2500.000
OD-2609-003	1	RNF-320L	2500.000	5600.00	2500.000
OD-2609-003	2	RNF-320R	3500.000	5600.00	3500.000
OD-2609-004	1	RNF-320L	4500.000	5600.00	4500.000
OD-2609-004	2	RNF-320R	4500.000	5600.00	4500.000
OD-2609-005	1	BRK-210R	4500.000	4300.00	4500.000
OD-2609-006	1	RNF-320L	1000.000	5600.00	1000.000
OD-2609-006	2	RNF-320R	4500.000	5600.00	4500.000
OD-2609-007	1	RNF-320L	2000.000	5600.00	2000.000
OD-2609-008	1	PNL-410	3500.000	2900.00	3500.000
OD-2609-009	1	BRK-210L	2000.000	4300.00	2000.000
OD-2609-009	2	BRK-210R	4500.000	4300.00	4500.000
OD-2609-010	1	BRK-210R	4500.000	4300.00	4500.000
OD-2609-011	1	PNL-410	2500.000	2900.00	2500.000
OD-2609-012	1	BRK-210L	4500.000	4300.00	4500.000
OD-2609-012	2	BRK-210R	2500.000	4300.00	2500.000
OD-2609-013	1	PNL-410	1500.000	2900.00	1500.000
OD-2609-014	1	RNF-320L	2500.000	5600.00	2500.000
OD-2609-014	2	RNF-320R	4000.000	5600.00	4000.000
OD-2609-015	1	BRK-210L	2500.000	4300.00	2500.000
OD-2609-015	2	BRK-210R	1500.000	4300.00	1500.000
OD-2609-016	1	PNL-410	2000.000	2900.00	2000.000
OD-2609-017	1	BRK-210L	2500.000	4300.00	2500.000
OD-2609-017	2	BRK-210R	1000.000	4300.00	1000.000
OD-2609-018	1	RNF-320L	3500.000	5600.00	3500.000
OD-2609-018	2	RNF-320R	1000.000	5600.00	1000.000
OD-2609-019	1	BRK-210L	3000.000	4300.00	3000.000
OD-2609-019	2	BRK-210R	4500.000	4300.00	4500.000
OD-2609-020	1	BRK-210L	3500.000	4300.00	3500.000
OD-2609-020	2	BRK-210R	1500.000	4300.00	1500.000
OD-2609-021	1	BRK-210L	2000.000	4300.00	2000.000
OD-2609-021	2	BRK-210R	1500.000	4300.00	1500.000
OD-2609-022	1	RNF-320L	4000.000	5600.00	4000.000
OD-2609-022	2	RNF-320R	3500.000	5600.00	3500.000
OD-2609-023	1	RNF-320L	1500.000	5600.00	1500.000
OD-2609-023	2	RNF-320R	1500.000	5600.00	1500.000
OD-2610-001	1	PNL-410	1000.000	2900.00	1000.000
OD-2610-002	1	BRK-210L	3000.000	4300.00	3000.000
OD-2610-002	2	BRK-210R	4000.000	4300.00	4000.000
OD-2609-024	1	PNL-410	2500.000	2900.00	2500.000
OD-2609-025	1	BRK-210L	3500.000	4300.00	3500.000
OD-2609-025	2	BRK-210R	2000.000	4300.00	2000.000
OD-2610-003	1	BRK-210L	4000.000	4300.00	4000.000
OD-2610-003	2	BRK-210R	3500.000	4300.00	3500.000
OD-2610-004	1	RNF-320L	4500.000	5600.00	4500.000
OD-2610-005	1	RNF-320R	2500.000	5600.00	2500.000
OD-2609-026	1	PNL-410	2000.000	2900.00	2000.000
OD-2609-027	1	RNF-320L	1500.000	5600.00	1500.000
OD-2609-027	2	RNF-320R	2500.000	5600.00	2500.000
OD-2610-006	1	BRK-210L	2500.000	4300.00	2500.000
OD-2610-006	2	BRK-210R	4500.000	4300.00	4500.000
OD-2610-007	1	PNL-410	3000.000	2900.00	3000.000
OD-2610-008	1	RNF-320L	1500.000	5600.00	1500.000
OD-2610-008	2	RNF-320R	2000.000	5600.00	2000.000
OD-2610-009	1	BRK-210L	1500.000	4300.00	1500.000
OD-2610-009	2	BRK-210R	4000.000	4300.00	4000.000
OD-2610-010	1	RNF-320L	1000.000	5600.00	1000.000
OD-2610-011	1	BRK-210L	3500.000	4300.00	3500.000
OD-2610-012	1	BRK-210L	1000.000	4300.00	1000.000
OD-2610-012	2	BRK-210R	4000.000	4300.00	4000.000
OD-2610-013	1	BRK-210L	4000.000	4300.00	4000.000
OD-2610-013	2	BRK-210R	4500.000	4300.00	4500.000
OD-2610-014	1	BRK-210L	4500.000	4300.00	4500.000
OD-2610-014	2	BRK-210R	1000.000	4300.00	1000.000
OD-2610-015	1	RNF-320R	3000.000	5600.00	3000.000
OD-2610-016	1	BRK-210L	2000.000	4300.00	2000.000
OD-2610-016	2	BRK-210R	1500.000	4300.00	1500.000
OD-2610-017	1	BRK-210L	3000.000	4300.00	3000.000
OD-2610-017	2	BRK-210R	2500.000	4300.00	2500.000
OD-2608-033	1	BRK-210L	2000.000	4300.00	0.000
OD-2609-028	1	BRK-210L	2000.000	4300.00	0.000
OD-2610-021	1	BRK-210L	6000.000	4300.00	0.000
OD-2610-021	2	BRK-210R	5000.000	4300.00	0.000
OD-2610-019	1	BRK-210L	3000.000	4300.00	0.000
OD-2610-018	1	PNL-410	4000.000	2900.00	0.000
OD-2610-020	1	PNL-410	3000.000	2900.00	0.000
\.


--
-- Data for Name: shipment; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.shipment (ship_no, so_no, item_code, ship_date, qty, carrier, lot_no) FROM stdin;
DL2608-0001	OD-2607-001	RNF-320L	2026-08-04	1500.000	한진택배	LT260804-1
DL2608-0002	OD-2607-001	RNF-320R	2026-08-05	2000.000	대한통운	LT260805-1
DL2608-0003	OD-2607-002	RNF-320R	2026-08-06	3500.000	대한통운	LT260806-1
DL2608-0004	OD-2607-003	PNL-410	2026-08-06	3500.000	대한통운	LT260806-2
DL2608-0005	OD-2607-004	RNF-320L	2026-08-06	2000.000	한진택배	LT260806-3
DL2608-0006	OD-2607-004	RNF-320R	2026-08-07	4000.000	한진택배	LT260807-1
DL2608-0007	OD-2608-001	RNF-320L	2026-08-07	2500.000	대한통운	LT260807-2
DL2608-0008	OD-2608-001	RNF-320R	2026-08-08	1000.000	한진택배	LT260808-1
DL2608-0009	OD-2607-005	BRK-210L	2026-08-08	1000.000	대한통운	LT260808-2
DL2608-0010	OD-2608-002	BRK-210R	2026-08-08	1000.000	대한통운	LT260808-3
DL2608-0011	OD-2608-003	RNF-320L	2026-08-09	3000.000	한진택배	LT260809-1
DL2608-0012	OD-2608-003	RNF-320R	2026-08-11	3500.000	대한통운	LT260811-1
DL2608-0013	OD-2607-006	RNF-320L	2026-08-12	3000.000	대한통운	LT260812-1
DL2608-0014	OD-2608-004	RNF-320L	2026-08-12	2000.000	한진택배	LT260812-2
DL2608-0015	OD-2608-004	RNF-320R	2026-08-12	1000.000	대한통운	LT260812-3
DL2608-0016	OD-2608-005	RNF-320L	2026-08-13	3500.000	대한통운	LT260813-1
DL2608-0017	OD-2608-005	RNF-320R	2026-08-13	1000.000	대한통운	LT260813-2
DL2608-0018	OD-2608-006	BRK-210L	2026-08-14	2000.000	한진택배	LT260814-1
DL2608-0019	OD-2608-006	BRK-210R	2026-08-15	4500.000	대한통운	LT260815-1
DL2608-0020	OD-2608-007	BRK-210L	2026-08-15	1750.000	대한통운	LT260815-2
DL2608-0021	OD-2608-007	BRK-210L	2026-08-16	1750.000	대한통운	LT260816-1
DL2608-0022	OD-2608-007	BRK-210R	2026-08-15	3000.000	대한통운	LT260815-3
DL2608-0023	OD-2608-008	RNF-320L	2026-08-16	2000.000	한진택배	LT260816-2
DL2608-0024	OD-2608-008	RNF-320R	2026-08-16	1500.000	대한통운	LT260816-3
DL2608-0025	OD-2608-009	BRK-210L	2026-08-18	2500.000	대한통운	LT260818-1
DL2608-0026	OD-2608-009	BRK-210R	2026-08-19	3000.000	한진택배	LT260819-1
DL2608-0027	OD-2608-010	RNF-320L	2026-08-20	2500.000	한진택배	LT260820-1
DL2608-0028	OD-2608-010	RNF-320R	2026-08-20	2000.000	한진택배	LT260820-2
DL2608-0029	OD-2608-011	RNF-320L	2026-08-20	2000.000	대한통운	LT260820-3
DL2608-0030	OD-2608-011	RNF-320R	2026-08-21	2000.000	대한통운	LT260821-1
DL2608-0031	OD-2608-011	RNF-320R	2026-08-22	2000.000	대한통운	LT260822-1
DL2608-0032	OD-2608-012	PNL-410	2026-08-21	2000.000	대한통운	LT260821-2
DL2608-0033	OD-2608-013	RNF-320L	2026-08-22	3000.000	대한통운	LT260822-2
DL2608-0034	OD-2608-013	RNF-320R	2026-08-23	4500.000	대한통운	LT260823-1
DL2608-0035	OD-2608-014	PNL-410	2026-08-23	3000.000	한진택배	LT260823-2
DL2608-0036	OD-2608-015	RNF-320L	2026-08-22	1500.000	한진택배	LT260822-3
DL2608-0037	OD-2608-016	RNF-320L	2026-08-25	2000.000	한진택배	LT260825-1
DL2608-0038	OD-2608-016	RNF-320R	2026-08-25	1000.000	대한통운	LT260825-2
DL2608-0039	OD-2608-017	RNF-320L	2026-08-26	2000.000	한진택배	LT260826-1
DL2608-0040	OD-2608-017	RNF-320L	2026-08-27	2000.000	대한통운	LT260827-1
DL2608-0041	OD-2608-017	RNF-320R	2026-08-26	2500.000	대한통운	LT260826-2
DL2608-0042	OD-2608-018	BRK-210L	2026-08-27	2500.000	대한통운	LT260827-2
DL2608-0043	OD-2608-019	RNF-320L	2026-08-27	1000.000	한진택배	LT260827-3
DL2608-0044	OD-2608-019	RNF-320R	2026-08-28	1750.000	한진택배	LT260828-1
DL2608-0045	OD-2608-019	RNF-320R	2026-08-29	1750.000	대한통운	LT260829-1
DL2608-0046	OD-2608-020	RNF-320L	2026-08-28	3000.000	대한통운	LT260828-2
DL2608-0047	OD-2608-021	PNL-410	2026-08-28	1000.000	대한통운	LT260828-3
DL2608-0048	OD-2608-022	RNF-320L	2026-08-29	2000.000	한진택배	LT260829-2
DL2608-0049	OD-2608-022	RNF-320R	2026-08-29	1000.000	대한통운	LT260829-3
DL2609-0001	OD-2608-023	BRK-210R	2026-09-01	2250.000	대한통운	LT260901-1
DL2609-0002	OD-2608-023	BRK-210R	2026-09-02	2250.000	대한통운	LT260902-1
DL2609-0003	OD-2608-024	BRK-210R	2026-09-01	2000.000	대한통운	LT260901-2
DL2609-0004	OD-2608-025	BRK-210L	2026-09-02	1500.000	한진택배	LT260902-2
DL2609-0005	OD-2608-025	BRK-210R	2026-09-02	1000.000	한진택배	LT260902-3
DL2609-0006	OD-2608-026	PNL-410	2026-09-03	3000.000	한진택배	LT260903-1
DL2609-0007	OD-2608-027	BRK-210L	2026-09-03	3500.000	대한통운	LT260903-2
DL2609-0008	OD-2608-027	BRK-210R	2026-09-02	1500.000	한진택배	LT260902-4
DL2609-0009	OD-2608-028	BRK-210L	2026-09-04	3000.000	한진택배	LT260904-1
DL2609-0010	OD-2608-028	BRK-210R	2026-09-04	2000.000	한진택배	LT260904-2
DL2609-0011	OD-2608-029	BRK-210L	2026-09-05	2500.000	한진택배	LT260905-1
DL2609-0012	OD-2608-029	BRK-210R	2026-09-05	2500.000	한진택배	LT260905-2
DL2609-0013	OD-2608-030	BRK-210L	2026-09-08	2250.000	대한통운	LT260908-1
DL2609-0014	OD-2608-030	BRK-210L	2026-09-09	2250.000	대한통운	LT260909-1
DL2609-0015	OD-2608-030	BRK-210R	2026-09-08	3500.000	대한통운	LT260908-2
DL2609-0016	OD-2608-031	RNF-320L	2026-09-10	2000.000	대한통운	LT260910-1
DL2609-0017	OD-2608-031	RNF-320L	2026-09-11	2000.000	한진택배	LT260911-1
DL2609-0018	OD-2608-031	RNF-320R	2026-09-09	1500.000	한진택배	LT260909-2
DL2609-0019	OD-2608-032	BRK-210L	2026-09-10	2000.000	한진택배	LT260910-2
DL2609-0020	OD-2608-032	BRK-210R	2026-09-10	2000.000	대한통운	LT260910-3
DL2609-0021	OD-2609-001	BRK-210L	2026-09-11	1750.000	대한통운	LT260911-2
DL2609-0022	OD-2609-001	BRK-210L	2026-09-12	1750.000	대한통운	LT260912-1
DL2609-0023	OD-2609-001	BRK-210R	2026-09-12	3500.000	한진택배	LT260912-2
DL2609-0024	OD-2609-002	BRK-210L	2026-09-13	2250.000	한진택배	LT260913-1
DL2609-0025	OD-2609-002	BRK-210L	2026-09-14	2250.000	대한통운	LT260914-1
DL2609-0026	OD-2609-002	BRK-210R	2026-09-13	2500.000	한진택배	LT260913-2
DL2609-0027	OD-2609-003	RNF-320L	2026-09-15	2500.000	한진택배	LT260915-1
DL2609-0028	OD-2609-003	RNF-320R	2026-09-16	1750.000	한진택배	LT260916-1
DL2609-0029	OD-2609-003	RNF-320R	2026-09-17	1750.000	한진택배	LT260917-1
DL2609-0030	OD-2609-004	RNF-320L	2026-09-17	2250.000	한진택배	LT260917-2
DL2609-0031	OD-2609-004	RNF-320L	2026-09-18	2250.000	한진택배	LT260918-1
DL2609-0032	OD-2609-004	RNF-320R	2026-09-17	2250.000	대한통운	LT260917-3
DL2609-0033	OD-2609-004	RNF-320R	2026-09-18	2250.000	한진택배	LT260918-2
DL2609-0034	OD-2609-005	BRK-210R	2026-09-18	4500.000	한진택배	LT260918-3
DL2609-0035	OD-2609-006	RNF-320L	2026-09-18	1000.000	한진택배	LT260918-4
DL2609-0036	OD-2609-006	RNF-320R	2026-09-20	2250.000	한진택배	LT260920-1
DL2609-0037	OD-2609-006	RNF-320R	2026-09-21	2250.000	대한통운	LT260921-1
DL2609-0038	OD-2609-007	RNF-320L	2026-09-19	2000.000	대한통운	LT260919-1
DL2609-0039	OD-2609-008	PNL-410	2026-09-20	1750.000	대한통운	LT260920-2
DL2609-0040	OD-2609-008	PNL-410	2026-09-21	1750.000	대한통운	LT260921-2
DL2609-0041	OD-2609-009	BRK-210L	2026-09-20	2000.000	대한통운	LT260920-3
DL2609-0042	OD-2609-009	BRK-210R	2026-09-23	4500.000	한진택배	LT260923-1
DL2609-0043	OD-2609-010	BRK-210R	2026-09-23	2250.000	대한통운	LT260923-2
DL2609-0044	OD-2609-010	BRK-210R	2026-09-24	2250.000	한진택배	LT260924-1
DL2609-0045	OD-2609-011	PNL-410	2026-09-23	2500.000	대한통운	LT260923-3
DL2609-0046	OD-2609-012	BRK-210L	2026-09-24	2250.000	한진택배	LT260924-2
DL2609-0047	OD-2609-012	BRK-210L	2026-09-25	2250.000	한진택배	LT260925-1
DL2609-0048	OD-2609-012	BRK-210R	2026-09-24	2500.000	대한통운	LT260924-3
DL2609-0049	OD-2609-013	PNL-410	2026-09-24	1500.000	대한통운	LT260924-4
DL2609-0050	OD-2609-014	RNF-320L	2026-09-25	2500.000	대한통운	LT260925-2
DL2609-0051	OD-2609-014	RNF-320R	2026-09-26	4000.000	한진택배	LT260926-1
DL2609-0052	OD-2609-015	BRK-210L	2026-09-26	2500.000	대한통운	LT260926-2
DL2609-0053	OD-2609-015	BRK-210R	2026-09-26	1500.000	대한통운	LT260926-3
DL2609-0054	OD-2609-016	PNL-410	2026-09-27	2000.000	한진택배	LT260927-1
DL2609-0055	OD-2609-017	BRK-210L	2026-09-27	2500.000	대한통운	LT260927-2
DL2609-0056	OD-2609-017	BRK-210R	2026-09-27	1000.000	대한통운	LT260927-3
DL2609-0057	OD-2609-018	RNF-320L	2026-09-30	1750.000	한진택배	LT260930-1
DL2610-0001	OD-2609-018	RNF-320L	2026-10-01	1750.000	한진택배	LT261001-1
DL2609-0058	OD-2609-018	RNF-320R	2026-09-29	1000.000	한진택배	LT260929-1
DL2609-0059	OD-2609-019	BRK-210L	2026-09-30	3000.000	대한통운	LT260930-2
DL2610-0002	OD-2609-019	BRK-210R	2026-10-01	2250.000	한진택배	LT261001-2
DL2610-0003	OD-2609-019	BRK-210R	2026-10-02	2250.000	한진택배	LT261002-1
DL2610-0004	OD-2609-020	BRK-210L	2026-10-02	1750.000	대한통운	LT261002-2
DL2610-0005	OD-2609-020	BRK-210L	2026-10-03	1750.000	대한통운	LT261003-1
DL2610-0006	OD-2609-020	BRK-210R	2026-10-02	1500.000	한진택배	LT261002-3
DL2610-0007	OD-2609-021	BRK-210L	2026-10-03	2000.000	대한통운	LT261003-2
DL2610-0008	OD-2609-021	BRK-210R	2026-10-03	1500.000	대한통운	LT261003-3
DL2610-0009	OD-2609-022	RNF-320L	2026-10-04	2000.000	한진택배	LT261004-1
DL2610-0010	OD-2609-022	RNF-320L	2026-10-05	2000.000	한진택배	LT261005-1
DL2610-0011	OD-2609-022	RNF-320R	2026-10-04	3500.000	대한통운	LT261004-2
DL2610-0012	OD-2609-023	RNF-320L	2026-10-06	1500.000	한진택배	LT261006-1
DL2610-0013	OD-2609-023	RNF-320R	2026-10-06	1500.000	한진택배	LT261006-2
DL2610-0014	OD-2610-001	PNL-410	2026-10-06	1000.000	대한통운	LT261006-3
DL2610-0015	OD-2610-002	BRK-210L	2026-10-07	3000.000	한진택배	LT261007-1
DL2610-0016	OD-2610-002	BRK-210R	2026-10-08	2000.000	대한통운	LT261008-1
DL2610-0017	OD-2610-002	BRK-210R	2026-10-09	2000.000	한진택배	LT261009-1
DL2610-0018	OD-2609-024	PNL-410	2026-10-08	2500.000	대한통운	LT261008-2
DL2610-0019	OD-2609-025	BRK-210L	2026-10-08	3500.000	대한통운	LT261008-3
DL2610-0020	OD-2609-025	BRK-210R	2026-10-09	2000.000	대한통운	LT261009-2
DL2610-0021	OD-2610-003	BRK-210L	2026-10-09	2000.000	한진택배	LT261009-3
DL2610-0022	OD-2610-003	BRK-210L	2026-10-10	2000.000	대한통운	LT261010-1
DL2610-0023	OD-2610-003	BRK-210R	2026-10-10	3500.000	대한통운	LT261010-2
DL2610-0024	OD-2610-004	RNF-320L	2026-10-11	2250.000	대한통운	LT261011-1
DL2610-0025	OD-2610-004	RNF-320L	2026-10-12	2250.000	대한통운	LT261012-1
DL2610-0026	OD-2610-005	RNF-320R	2026-10-11	2500.000	대한통운	LT261011-2
DL2610-0027	OD-2609-026	PNL-410	2026-10-11	2000.000	대한통운	LT261011-3
DL2610-0028	OD-2609-027	RNF-320L	2026-10-13	1500.000	한진택배	LT261013-1
DL2610-0029	OD-2609-027	RNF-320R	2026-10-13	2500.000	한진택배	LT261013-2
DL2610-0030	OD-2610-006	BRK-210L	2026-10-14	2500.000	한진택배	LT261014-1
DL2610-0031	OD-2610-006	BRK-210R	2026-10-15	4500.000	대한통운	LT261015-1
DL2610-0032	OD-2610-007	PNL-410	2026-10-15	3000.000	대한통운	LT261015-2
DL2610-0033	OD-2610-008	RNF-320L	2026-10-14	1500.000	한진택배	LT261014-2
DL2610-0034	OD-2610-008	RNF-320R	2026-10-16	2000.000	한진택배	LT261016-1
DL2610-0035	OD-2610-009	BRK-210L	2026-10-16	1500.000	대한통운	LT261016-2
DL2610-0036	OD-2610-009	BRK-210R	2026-10-17	2000.000	대한통운	LT261017-1
DL2610-0037	OD-2610-009	BRK-210R	2026-10-18	2000.000	한진택배	LT261018-1
DL2610-0038	OD-2610-010	RNF-320L	2026-10-16	1000.000	한진택배	LT261016-3
DL2610-0039	OD-2610-011	BRK-210L	2026-10-18	1750.000	한진택배	LT261018-2
DL2610-0040	OD-2610-011	BRK-210L	2026-10-19	1750.000	대한통운	LT261019-1
DL2610-0041	OD-2610-012	BRK-210L	2026-10-17	1000.000	대한통운	LT261017-2
DL2610-0042	OD-2610-012	BRK-210R	2026-10-20	2000.000	대한통운	LT261020-1
DL2610-0043	OD-2610-012	BRK-210R	2026-10-21	2000.000	한진택배	LT261021-1
DL2610-0044	OD-2610-013	BRK-210L	2026-10-21	4000.000	대한통운	LT261021-2
DL2610-0045	OD-2610-013	BRK-210R	2026-10-22	2250.000	대한통운	LT261022-1
DL2610-0046	OD-2610-013	BRK-210R	2026-10-23	2250.000	대한통운	LT261023-1
DL2610-0047	OD-2610-014	BRK-210L	2026-10-22	2250.000	한진택배	LT261022-2
DL2610-0048	OD-2610-014	BRK-210L	2026-10-23	2250.000	한진택배	LT261023-2
DL2610-0049	OD-2610-014	BRK-210R	2026-10-23	1000.000	한진택배	LT261023-3
DL2610-0050	OD-2610-015	RNF-320R	2026-10-24	3000.000	대한통운	LT261024-1
DL2610-0051	OD-2610-016	BRK-210L	2026-10-24	2000.000	한진택배	LT261024-2
DL2610-0052	OD-2610-016	BRK-210R	2026-10-24	1500.000	한진택배	LT261024-3
DL2610-0053	OD-2610-017	BRK-210L	2026-10-25	3000.000	한진택배	LT261025-1
DL2610-0054	OD-2610-017	BRK-210R	2026-10-25	2500.000	대한통운	LT261025-2
\.


--
-- Data for Name: stock; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.stock (warehouse, item_code, qty, reserved_qty, available_qty, last_in_date, last_out_date) FROM stdin;
원자재창고	CL-440	26790.000	4500.000	22290.000	2026-10-25	2026-10-29
원자재창고	CL-590	25090.000	0.000	25090.000	2026-10-20	2026-10-22
부자재창고	BT-M8	11500.000	6000.000	5500.000	2026-10-10	2026-10-13
재공품창고	BLK-210	5600.000	0.000	5600.000	2026-10-29	2026-10-29
재공품창고	BLK-320	0.000	0.000	0.000	2026-10-22	2026-10-22
완제품창고	BRK-210L	4600.000	4600.000	0.000	2026-10-30	2026-10-25
완제품창고	BRK-210R	2100.000	2100.000	0.000	2026-10-30	2026-10-25
완제품창고	RNF-320L	1100.000	0.000	1100.000	2026-10-15	2026-10-16
완제품창고	RNF-320R	500.000	0.000	500.000	2026-10-23	2026-10-24
완제품창고	PNL-410	1100.000	1100.000	0.000	2026-10-14	2026-10-15
\.


--
-- Data for Name: vendor; Type: TABLE DATA; Schema: erp; Owner: -
--

COPY erp.vendor (vendor_code, vendor_name, biz_no, vendor_type, contact_name, payment_terms, use_yn) FROM stdin;
CU0001	한결모터스	359-43-*****	S	조민서	익월 말 현금	Y
CU0002	새벽오토	346-83-*****	S	오민준	익월 말 현금	Y
CU0003	다온차체	178-61-*****	S	조재원	익월 말 현금	Y
SP0001	대한강판	403-85-*****	P	장다은	익월 25일	Y
SP0002	성진볼트	144-82-*****	P	서민준	월말 현금	Y
SP0003	미래코일	567-86-*****	P	서현우	익월 25일	Y
\.


--
-- Name: attendance attendance_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.attendance
    ADD CONSTRAINT attendance_pkey PRIMARY KEY (emp_no, work_date);


--
-- Name: change_log change_log_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.change_log
    ADD CONSTRAINT change_log_pkey PRIMARY KEY (change_id);


--
-- Name: department department_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.department
    ADD CONSTRAINT department_pkey PRIMARY KEY (dept_code);


--
-- Name: employee employee_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.employee
    ADD CONSTRAINT employee_pkey PRIMARY KEY (emp_no);


--
-- Name: fixed_asset fixed_asset_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.fixed_asset
    ADD CONSTRAINT fixed_asset_pkey PRIMARY KEY (asset_no);


--
-- Name: gl_account gl_account_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.gl_account
    ADD CONSTRAINT gl_account_pkey PRIMARY KEY (account_code);


--
-- Name: goods_receipt goods_receipt_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.goods_receipt
    ADD CONSTRAINT goods_receipt_pkey PRIMARY KEY (gr_no);


--
-- Name: item item_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.item
    ADD CONSTRAINT item_pkey PRIMARY KEY (item_code);


--
-- Name: journal_entry journal_entry_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.journal_entry
    ADD CONSTRAINT journal_entry_pkey PRIMARY KEY (entry_no, line_no);


--
-- Name: purchase_invoice purchase_invoice_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.purchase_invoice
    ADD CONSTRAINT purchase_invoice_pkey PRIMARY KEY (invoice_no);


--
-- Name: purchase_order_line purchase_order_line_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.purchase_order_line
    ADD CONSTRAINT purchase_order_line_pkey PRIMARY KEY (po_no, line_no);


--
-- Name: purchase_order purchase_order_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.purchase_order
    ADD CONSTRAINT purchase_order_pkey PRIMARY KEY (po_no);


--
-- Name: sales_invoice sales_invoice_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.sales_invoice
    ADD CONSTRAINT sales_invoice_pkey PRIMARY KEY (invoice_no);


--
-- Name: sales_order_line sales_order_line_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.sales_order_line
    ADD CONSTRAINT sales_order_line_pkey PRIMARY KEY (so_no, line_no);


--
-- Name: sales_order sales_order_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.sales_order
    ADD CONSTRAINT sales_order_pkey PRIMARY KEY (so_no);


--
-- Name: shipment shipment_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.shipment
    ADD CONSTRAINT shipment_pkey PRIMARY KEY (ship_no);


--
-- Name: stock stock_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.stock
    ADD CONSTRAINT stock_pkey PRIMARY KEY (warehouse, item_code);


--
-- Name: vendor vendor_pkey; Type: CONSTRAINT; Schema: erp; Owner: -
--

ALTER TABLE ONLY erp.vendor
    ADD CONSTRAINT vendor_pkey PRIMARY KEY (vendor_code);


--
-- PostgreSQL database dump complete
--

\unrestrict hGADyVz8xMD4HjDcS89w3LRtPAZopghccc1KtRc2rC50P3N64O61Wk2HbO4YhGw

