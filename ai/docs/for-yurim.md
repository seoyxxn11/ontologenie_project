# 유림언니를위한리드미❤️

데이터셋·정답셋과 분석 결과 샘플을 작업에 어떻게 쓰면 되는지 정리했습니다. 경로는 모두 `ai/` 기준입니다.

## 작업별로 볼 파일

| 작업 | 볼 파일 |
| --- | --- |
| #19 연결 규칙 정답·검증 기준 | `data/gold-v1.0/mapping_rules.json` |
| #127·128 시험 적용 판정·근거 | `data/gold-v1.0/id_links.json` |
| #129·132 규칙 승인·개별 보류 | `data/gold-v1.0/id_links.json`, `scenario_answers.json` |
| #134·135 재검증 | `data/scenario-v1.0/s5_fix.sql`, `data/gold-v1.0/integrity_issues.json` |
| #223·123·124 분석 결과 저장·제안 목록 | `results/proposals-v1.0.api.json`, `docs/api-internal.md` |
| #125 규칙 후보 직접 작성 | `app/rules/transforms.py` |
| #224 개인정보 가림 | `data/gold-v1.0/ontology.json` |

## 데이터 개요

- (주)00프레스(가상) 자동차 차체 부품 프레스 회사의 3개월 ERP·MES 데이터입니다. 기업 예시와 같은 29개 표 구조에 시나리오에 필요한 표 4개와 컬럼 2개를 더했습니다.
- `data/data-v1.0`은 원천 DB에 들어가는 데이터이고, `data/gold-v1.0`은 정답입니다. 정답은 원천 DB와 AI 입력에 넣지 않습니다.
- 시험 적용과 정합성 검사가 제대로 잡는지 확인할 수 있도록 현장에서 흔한 오류(오타, 없는 번호, 빈 값)를 일부러 넣어 두었습니다. 각 오류가 어떤 결과로 나와야 하는지는 정답 파일에 적혀 있습니다.

## 1. 연결 규칙 정답 (#19)

#19의 연결 지점 5가지가 `mapping_rules.json`의 정답 규칙 5개입니다.

| 연결 지점 | 출발 → 도착 | 규칙 |
| --- | --- | --- |
| 수주 | `mes.work_order.so_ref` → `erp.sales_order.so_no` | R-SO: 양쪽 숫자만 남겨 비교 (`2610021` = `OD-2610-021`) |
| 사람 | `mes.worker.id` → `erp.employee.emp_no` | R-W: 4자리로 0 채우고 GP 붙임 (`31` → `GP0031`) |
| 설비 자산 | `erp.fixed_asset.equipment_code` → `mes.equipment.code` | R-ASSET: 매핑 컬럼 값 그대로 |
| 품목 | MES 품목 코드 → `erp.item.item_code` | R-ITEM: 값 그대로 |
| 제품 로트 | `erp.shipment.lot_no` → `mes.inspection.lot_no` | R-LOT: 값 그대로 |

이 밖에 메모 속 설비 이름을 설비 코드로 잇는 규칙(R-ALIAS)이 하나 더 있습니다.

변환은 `reference_transforms`에 출발과 도착을 따로 적었습니다. AI 후보의 연결 규칙도 같은 형식이라 바로 비교할 수 있습니다.

```json
"reference_transforms": {
  "source_transforms": [{"op": "PAD_ZEROS", "arg": 4}, {"op": "ADD_PREFIX", "arg": "GP"}],
  "target_transforms": []
}
```

**관계 전체 표 불일치 3건 확인 결과**

| 관계 | 확인 결과 |
| --- | --- |
| `routing.equipment_kind` → 설비 | 특정 설비가 아니라 설비 종류. 정답은 `equipment.kind` (키가 아니라 N:M) |
| `work_order.die_drawing_code` → 금형 | `die_code`가 아니라 `drawing_code`와 같은 값. 정답은 `die.drawing_code` |
| `inspection.lot_no` → 출하 | `ship_no`가 아니라 출하 `lot_no`와 같은 값 (161건 일치). 방향은 출하 → 검사 |

검사 로트 관계를 출하 → 검사 방향으로 둔 것은, 검사 기록이 없는 출하 로트를 미연결로 잡기 위해서입니다.

**AI 입력**: 표 정의(표·컬럼·자료형·기본키), 컬럼 주석, 값을 씁니다. 관계는 AI가 찾아야 하는 대상이라 컬럼 주석에 다른 표 이름을 적지 않고 정답에만 두었습니다.

## 2. 시험 적용 판정과 근거 (#127·128)

`id_links.json`은 규칙을 원천에 적용했을 때 값마다 나와야 하는 결과입니다. 출발 값 하나에 한 줄씩, 모두 304줄입니다.

```json
{"rule_id": "R-W", "from_value": "57", "to_value": null, "status": "unmatched_error", "reason": "직원 표에 GP0057 없음", "row_keys": ["57"]}
```

각 줄의 항목이 판정 근거 화면의 항목과 같아서, 시험 적용 결과를 그대로 대조할 수 있습니다.

| 항목 | 뜻 |
| --- | --- |
| `from_value` | 원본 값 |
| `to_value` | 대응 대상 |
| `reason` | 실패 이유 |
| `row_keys` | 그 값이 나오는 행 |

| status | 뜻 | 수 |
| --- | --- | --- |
| `matched` | 이어짐 | 293 |
| `unmatched_error` | 오류로 안 이어짐 (57 → GP0057 없음, 오타 `P2O1`, 없는 수주 번호 등) | 7 |
| `unmatched_expected` | 원래 안 이어지는 값 (설비가 아닌 자산, 아직 검사 전인 로트) | 2 |
| `missing` | 출발 값 없음 | 1 |
| `ambiguous` | 후보 여럿 (메모의 "1호기" → P101·P201) | 1 |

연결률은 행 기준으로 셉니다. R-SO는 136행 중 134행이 이어집니다(98.5%, 빈 값 3행 제외).

## 3. 규칙 승인과 개별 보류 (#129·132)

- 시나리오 S2의 기대 답(`scenario_answers.json`)은 R-W 규칙을 승인하고, 57 → GP0057은 직원 표에 없어서 개별 보류하는 것입니다.
- `id_links.json`에서 `unmatched_error`나 `ambiguous`인 값이 개별 보류 후보입니다. 보류 이유는 `reason`, 원천 위치는 `row_keys`에 있습니다.

## 4. 정합성 검사와 재검증 (#134·135)

`integrity_checks.json`은 검사 9건의 기대 판정입니다.

| 검사 | 내용 | 판정 |
| --- | --- | --- |
| K1·K2 | 작업자 31 → GP0031 / 57 → GP0057(없음) | 통과 / 실패 |
| K3 | 자산 FA-0003 → 설비 P101 | 통과 |
| K4 | 금형 MD-990T-01의 제품 BRK-990T (품목에 없음) | 실패 |
| K5 | 자재 로트 → 입고 로트 (입고 표에 로트 컬럼 없음) | 확인 필요 |
| A1·A2 | 수주 출하 수량, 발주 입고 수량 | 통과 |
| A3 | 작업지시 JB2608-0145 불량 수량 31 ≠ 21 ≠ 12 | 실패 |
| A4 | 분개 JV-2609-0005 차변 ≠ 대변 | 실패 |

`integrity_issues.json`은 검사로 나와야 할 문제 11건입니다(미연결 9, 조건 위반 2). 한 줄에 `column`(컬럼), `row_key`(행), `raw_value`(원래 값), `rule`(규칙), `reason`(이유)이 들어 있습니다.

**재검증**: `s5_fix.sql`은 시나리오 5에서 원천 DB에 반영하는 수정 2건과 변경 로그 2줄입니다.

| 수정 | 해결되는 문제 |
| --- | --- |
| 자산 FA-0001 설비 코드 `P2O1` → `P201` | G-I11 |
| 품목 `BRK-990T` 추가 | G-I2 (K4) |

- 반영한 뒤 다시 검사하면 해결 2건, 남은 문제 9건, 새 문제 0건이 나와야 합니다.
- 바뀐 행은 `erp.change_log`에 들어가므로, 최신 데이터 재검증 대상을 찾을 때 쓸 수 있습니다.
- 같은 내용을 `s5_fix.json`에 JSON으로도 두었습니다.

## 5. 분석 결과 저장과 제안 목록 (#223·123·124)

`proposals-v1.0.api.json`은 `data-v1.0` 전체를 분석했을 때 105로 보내는 요청 본문 그대로입니다. AI를 돌리지 않아도 저장과 목록·상세 화면을 만들어 볼 수 있습니다.

- 구조: `{status, resultSummary, proposals[]}`
- 후보 656개: 객체 유형·매핑 각 31, 속성·매핑 각 235, 관계 유형·연결 규칙 각 62

| 화면 항목 | 필드 |
| --- | --- |
| 내용 | `itemKind`, `payload` |
| 근거 | `evidence.reason`, `evidence.items` |
| 신뢰도 | `aiConfidence`, `evidence.confidenceItems` |
| 보류 권고 | `evidence.holdRecommended`, `evidence.holdReason` (22개) |
| 대체 후보 | `options[]` |

- 이 샘플에는 `options`가 채워진 후보가 없습니다. 후보가 여럿인 경우는 `id_links.json`의 "1호기"(P101·P201)를 참고해 주세요.
- 명세에 아직 없는 부분이 두 가지 있습니다. 자세한 내용은 `docs/api-internal.md` 2절에 있습니다.
  - 연결 규칙 `conditions[].sourceVia`: 다른 표를 거쳐 잇는 관계 (공정 실적 → 작업지시 → 라우팅). 후보 3개에만 있음
  - 관계 개수 `MANY_TO_MANY`: 키가 아닌 종류 값으로 잇는 관계 (라우팅 설비 종류 → 설비 종류)

## 6. 규칙 후보 직접 작성 (#125)

정답 규칙과 AI 후보가 쓰는 변환 단계는 아래 10개입니다(`app/rules/transforms.py`). 직접 작성한 규칙도 이 목록 안의 단계만 쓰면 실행할 수 있는 형식입니다.

`STRIP_PREFIX` · `ADD_PREFIX` · `PAD_ZEROS` · `UPPER` · `TRIM` · `EXTRACT_NUMBER` · `TO_INTEGER` · `TO_DECIMAL` · `TO_DATE` · `MAP_VALUE`

## 7. 개인정보 가림 (#224)

`ontology.json`에서 `is_personal`이 true인 컬럼은 4개입니다.

| 컬럼 | 값 |
| --- | --- |
| `erp.employee.emp_name`, `mes.worker.name`, `erp.vendor.contact_name` | 사람 이름 (모두 가명) |
| `mes.maintenance_order.technician` | 대부분 부서 이름, 2건은 직원 이름 |

사업자번호(`erp.vendor.biz_no`)는 뒷자리를 가린 값(`144-82-*****`)으로 넣었습니다.

## 정답 확인

정답이 원천과 맞는지는 아래 명령으로 확인할 수 있습니다. 오류 0건이 나와야 합니다.

```powershell
.\.venv\Scripts\python.exe -m app.validators.gold_source_check data/data-v1.0 data/gold-v1.0
```
