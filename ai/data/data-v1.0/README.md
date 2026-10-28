# 00프레스(가상) ERP·MES 원천 데이터 v1.0

자동차 차체 부품 프레스 회사의 3개월(2026-08-03 ~ 10-30) ERP·MES 데이터입니다. 기준 시각은 2026-10-30 14:00이고, 이때 P103은 고장으로 정비 중입니다.
표 33개(ERP 18 · MES 15, 변경 이력 표 포함), 5,231행이며 값은 모두 가상입니다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `erp/schema.sql`, `mes/schema.sql` | 스키마·표 생성 (기본키, 컬럼 주석) |
| `erp/*.csv`, `mes/*.csv` | 데이터. UTF-8, 첫 줄 헤더, 빈 칸 = NULL. 메모 원문은 줄바꿈을 포함함 |
| `load_erp.psql`, `load_mes.psql` | ERP DB·MES DB 적재 |
| `verify_counts.sql` | 적재 후 표별 행 수 확인 |
| `CHECKSUMS.sha256` | 파일 무결성 확인 (`sha256sum -c CHECKSUMS.sha256`) |
| `../scenario-v1.0/s5_fix.sql` | 시나리오 5 시연 때만 적용하는 원천 수정분 |

## 적재

1. 이 폴더로 이동합니다(`\copy`가 상대 경로를 씁니다).
2. ERP DB에 `psql -f load_erp.psql`, MES DB에 `psql -f load_mes.psql`을 실행합니다.
3. `psql -f verify_counts.sql`을 실행해 모든 행의 `ok`가 true인지 확인합니다.

## 적재 후 확인

- [ ] `verify_counts.sql` 결과가 모두 true
- [ ] 한글이 깨지지 않음: `select item_name from erp.item limit 3;`
- [ ] 빈 칸이 NULL로 들어감: `select count(*) from mes.work_order where so_ref is null;` → 3
- [ ] 메모 줄바꿈 유지: `select count(*) from mes.handover_note where content like '%' || chr(10) || '%';` → 60
- [ ] 분석용 읽기 전용 계정으로 두 스키마의 모든 표를 SELECT 할 수 있음

## 지킬 것

- 값을 직접 고치지 않습니다. 일부 값은 정합성 시연을 위해 일부러 어긋나게 넣었습니다. 바꿔야 하면 생성기로 다시 만듭니다.
- 정답셋(`../gold-v1.0`)은 원천 DB와 AI 입력에 넣지 않습니다.
- `s5_fix.sql`은 시나리오 5 시연 때만 적용합니다.

## 알려진 한계

- 발주마다 상세가 1줄뿐입니다. 업무상 발주와 상세는 1:N입니다.
- 표 사이에 외래키 제약이 없습니다. 실제 레거시 원천처럼 관계를 값으로만 확인할 수 있습니다.

## 다시 만들기

`ai/` 폴더에서 `.\.venv\Scripts\python.exe -m app.dataset.build --version v1.0 --force`를 실행합니다. 같은 시드면 같은 파일이 나옵니다(CHECKSUMS 동일).
