-- 적재 후 실행. ok가 모두 true여야 함.
select table_name, actual, expected, actual = expected as ok from (
select 'erp.item' as table_name, count(*) as actual, 10 as expected from erp.item
union all
select 'erp.vendor' as table_name, count(*) as actual, 6 as expected from erp.vendor
union all
select 'erp.purchase_order' as table_name, count(*) as actual, 27 as expected from erp.purchase_order
union all
select 'erp.purchase_order_line' as table_name, count(*) as actual, 27 as expected from erp.purchase_order_line
union all
select 'erp.goods_receipt' as table_name, count(*) as actual, 36 as expected from erp.goods_receipt
union all
select 'erp.stock' as table_name, count(*) as actual, 10 as expected from erp.stock
union all
select 'erp.sales_order' as table_name, count(*) as actual, 88 as expected from erp.sales_order
union all
select 'erp.sales_order_line' as table_name, count(*) as actual, 141 as expected from erp.sales_order_line
union all
select 'erp.shipment' as table_name, count(*) as actual, 162 as expected from erp.shipment
union all
select 'erp.sales_invoice' as table_name, count(*) as actual, 9 as expected from erp.sales_invoice
union all
select 'erp.purchase_invoice' as table_name, count(*) as actual, 9 as expected from erp.purchase_invoice
union all
select 'erp.gl_account' as table_name, count(*) as actual, 7 as expected from erp.gl_account
union all
select 'erp.journal_entry' as table_name, count(*) as actual, 53 as expected from erp.journal_entry
union all
select 'erp.employee' as table_name, count(*) as actual, 25 as expected from erp.employee
union all
select 'erp.department' as table_name, count(*) as actual, 8 as expected from erp.department
union all
select 'erp.attendance' as table_name, count(*) as actual, 1660 as expected from erp.attendance
union all
select 'erp.fixed_asset' as table_name, count(*) as actual, 8 as expected from erp.fixed_asset
union all
select 'mes.equipment' as table_name, count(*) as actual, 6 as expected from mes.equipment
union all
select 'mes.worker' as table_name, count(*) as actual, 16 as expected from mes.worker
union all
select 'mes.routing' as table_name, count(*) as actual, 21 as expected from mes.routing
union all
select 'mes.work_order' as table_name, count(*) as actual, 139 as expected from mes.work_order
union all
select 'mes.work_order_operation' as table_name, count(*) as actual, 571 as expected from mes.work_order_operation
union all
select 'mes.production_result' as table_name, count(*) as actual, 640 as expected from mes.production_result
union all
select 'mes.material_issue' as table_name, count(*) as actual, 150 as expected from mes.material_issue
union all
select 'mes.downtime' as table_name, count(*) as actual, 32 as expected from mes.downtime
union all
select 'mes.sensor_reading' as table_name, count(*) as actual, 615 as expected from mes.sensor_reading
union all
select 'mes.defect' as table_name, count(*) as actual, 424 as expected from mes.defect
union all
select 'mes.inspection' as table_name, count(*) as actual, 161 as expected from mes.inspection
union all
select 'mes.die' as table_name, count(*) as actual, 6 as expected from mes.die
union all
select 'mes.maintenance_order' as table_name, count(*) as actual, 15 as expected from mes.maintenance_order
union all
select 'mes.handover_note' as table_name, count(*) as actual, 60 as expected from mes.handover_note
union all
select 'erp.change_log' as table_name, count(*) as actual, 70 as expected from erp.change_log
union all
select 'mes.change_log' as table_name, count(*) as actual, 19 as expected from mes.change_log
) c
order by ok, table_name;
