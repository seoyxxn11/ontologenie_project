# ERP·MES 연결 규칙 및 검증 기준

- 관련 이슈: S15P31C106-19
- 담당: 정유림
- 검토: 김서연
- 기준일: 2026-10-30
- 기준 데이터: data-v1.0 / gold-v1.0

## 1. 목적과 기준 자료

ERP·MES 간 연결 규칙을 AI가 발견·제안하는 범위와,
제안을 검증할 정답 규칙 및 판정 기준을 정의한다.

기준 자료는 다음과 같다. 경로는 모두 `ai/` 기준이다.

- `docs/for-yurim.md`: 작업 안내
- `docs/api-internal.md`: 분석 연동 및 제안 형식
- `data/gold-v1.0/mapping_rules.json`: 정답 규칙
- `data/gold-v1.0/id_links.json`: 값 단위 기대 결과
- `results/proposals-v1.0.api.json`: AI 결과 샘플

정답셋은 검증·평가에만 사용하며 원천 DB와 AI 분석 입력에 포함하지 않는다.

## 2. 연결 규칙 5종

| 규칙 | 출발 → 도착 | 비교 방법·예시 |
|---|---|---|
| 수주 R-SO | `mes.work_order.so_ref` → `erp.sales_order.so_no` | 양쪽 숫자 추출: `2610021` ↔ `OD-2610-021` |
| 사람 R-W | `mes.worker.id` → `erp.employee.emp_no` | 4자리 0 채우기 후 GP 추가: `31` → `GP0031` |
| 설비 자산 R-ASSET | `erp.fixed_asset.equipment_code` → `mes.equipment.code` | 값 그대로: `P103` ↔ `P103` |
| 품목 R-ITEM | MES 제품·자재 코드 → `erp.item.item_code` | 값 그대로: `BRK-210L` ↔ `BRK-210L` |
| 제품 로트 R-LOT | `erp.shipment.lot_no` → `mes.inspection.lot_no` | 값 그대로: `LT260804-1` ↔ `LT260804-1` |

품목 연결의 출발 컬럼은 다음과 같다.

- `mes.routing.product_code`
- `mes.work_order.product_code`
- `mes.material_issue.item_code`
- `mes.die.product_code`

변환은 비교에 사용할 값에 적용하며 원천 데이터는 수정하지 않는다.
메모 속 설비 별칭을 연결하는 R-ALIAS는 추가 검증 사례로 사용한다.

## 3. AI 입력 및 제안 형식

### 3.1 입력

AI는 표·컬럼·자료형·기본키, 컬럼 주석과 원천 값을 분석한다.

원천 설명·동의어·메모는 `source_resource.source_metadata`에 저장한다.
Spring은 분석 시작 요청의 `targets[].sourceMetadata`에 해당 JSON을 담아 FastAPI로 전달한다.
Spring 전달 연동은 #223의 구현 범위다.

AI가 사용하는 메타데이터 키는 다음과 같다.

| 키 | 사용 방법 |
|---|---|
| `description` | 원천 주석이 없을 때 표·컬럼 설명으로 사용 |
| `synonyms` | 동의어 참고 정보 |
| `memo` | 업무 설명·메모 참고 정보 |
| `columns` | 컬럼별 description·synonyms·memo |

메모 키는 `memo`를 사용하며 원천 주석을 덮어쓰지 않는다.

### 3.2 제안

| 필드 | 내용 |
|---|---|
| `itemKind` | 제안 종류 |
| `payload` | 제안 내용, 출발·도착 속성, 비교 조건과 변환 |
| `evidence` | 제안 근거와 원천 위치 |
| `aiConfidence` | AI 신뢰도 |
| `options[]` | 대체 후보 |

AI 신뢰도는 시험 적용 매칭률과 구분한다.
`evidence.holdRecommended`는 보류 권고이며 실제 검토 상태와 구분한다.

중간 표를 거쳐야 하는 관계는 LINK_RULE을 생성하지 않고,
LINK_TYPE만 제안하며 보류를 권고한다. `sourceVia`는 사용하지 않는다.

## 4. 검증·보류·집계 기준

### 4.1 검증 방법

- 출발·도착 컬럼과 연결 방향을 정답 규칙에 대조한다.
- 양쪽 변환 단계와 적용 순서를 확인한다.
- 실제 적용 결과를 `id_links.json`의 기대 결과와 비교한다.
- 오류 미연결·정상 미연결·결측·모호한 후보를 구분한다.
- 원본 값, 실패 사유와 원천 행 위치를 추적할 수 있어야 한다.

### 4.2 판정 및 집계

| 항목 | 기준 |
|---|---|
| 판정 단위 | 출발 원천 행 기준 |
| 정상 | 관계 수 제약과 규칙 조건을 충족한 행 |
| 도착이 ONE | 대상 1개를 정상으로 판정 |
| 도착이 MANY | 대상 1개 이상을 정상으로 판정 |
| 미연결 | 대응 대상이 없는 행 |
| 중복 | 허용 범위를 초과한 복수 대상 또는 원천 키 중복 |
| 조건 위반 | 규칙 조건을 위반한 행 |
| 매칭률 | `matched_count / total_count` — 행 기준 |

원천 키 중복과 조건 위반은 도착이 MANY여도 별도로 검사한다.
한 원천 행은 하나의 최종 분류에 포함한다.
total_count가 0이면 매칭률을 산출하지 않는다.

연결 수는 별도 저장하지 않는다.
반영된 규칙의 현재 연결 수가 필요하면,
해당 `link_mapping_id`의 `object_link` ACTIVE 행을 집계한다.
승인 전 후보는 실제 연결을 만들지 않으므로 연결 수를 표시하지 않는다.

### 4.3 보류 처리

AI는 규칙 단위로 보류를 권고하고,
개별 값의 연결 판정은 백엔드 시험 적용에서 수행한다.

오류 미연결과 모호한 복수 후보는 사유·후보·원천 위치를 제공하고
담당자가 연결 대상을 선택하거나 처리 방향을 판단하도록 한다.

규칙 전체 승인과 개별 값 보류는 구분한다.
예를 들어 R-W 규칙을 승인하더라도,
직원 표에 없는 `57 → GP0057`은 개별 보류할 수 있다.
정상 미연결과 결측은 오류 미연결과 구분해 기록한다.

## 5. 관계 표 불일치 3건의 확인 결과

| 항목 | 확인된 연결 | 의미 |
|---|---|---|
| 설비 종류 | `mes.routing.equipment_kind` → `mes.equipment.kind` | 특정 설비가 아닌 같은 설비 종류로 연결 |
| 금형 도면 | `mes.work_order.die_drawing_code` → `mes.die.drawing_code` | 금형 코드가 아닌 도면 번호로 연결 |
| 제품 로트 | `erp.shipment.lot_no` → `mes.inspection.lot_no` | 출하 번호가 아닌 로트 번호로 연결 |

설비 종류 관계는 MANY_TO_MANY이며 사용 가능 여부나 실제 배정을 포함하지 않는다.

- 톤수: `routing.min_tonnage`와 `equipment.capacity_ton` 비교
- 가동 상태: `equipment.status`와 미종료 `mes.downtime` 확인
- 실제 배정: `work_order_operation.equipment_code → equipment.code`의 MANY_TO_ONE 관계

로트는 검사 기록이 없는 출하를 확인하기 위해 출하 → 검사 방향으로 연결한다.

## 6. 원천 검증 및 검토 기록

정답셋과 원천 데이터의 일치 여부는 `ai/`에서 다음 명령으로 확인한다.

    .\.venv\Scripts\python.exe -m app.validators.gold_source_check data/data-v1.0 data/gold-v1.0

검증 기대 결과는 오류 0건, 설명되지 않은 미연결 0건이다.
실행 일시·기준 커밋·명령·결과를 검증 기록에 남긴다.
이 검증은 AI의 규칙 발견 성능 평가와 구분한다.

김서연은 정답 규칙과 합성·보강 데이터의 일치 여부를 검토하고
Jira #19에 승인 코멘트를 남긴다.
정유림은 최종 문서와 검증 기록의 링크를 Jira #19에 첨부한다.