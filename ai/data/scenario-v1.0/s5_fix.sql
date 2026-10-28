-- 시나리오 5 원천 수정분. 시연 때 원천 DB에 반영한다(분석 입력 아님).
update erp.fixed_asset set equipment_code = 'P201' where asset_no = 'FA-0001';
insert into erp.item values ('BRK-990T', '브래킷 시제품', '시제품', 'EA', 'FG', 0, 0, 'Y', '2026-10-31 10:00:00');
insert into erp.change_log values (71, 'fixed_asset', 'FA-0001', 'UPDATE', '2026-10-31 10:00:00');
insert into erp.change_log values (72, 'item', 'BRK-990T', 'INSERT', '2026-10-31 10:00:00');
