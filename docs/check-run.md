# 검사 실행 요청·조회·실행과 규칙 입력 변환 — S15P31C106-191·190

## 범위와 권한

FN-CHK-01·02의 검사 실행 목록·상태 조회, 수동 검사·재검증 요청 접수, 접수한 검사를 돌리는 배치 실행기와, 승인 규칙을 판정 엔진 입력으로 바꾸는 변환기를 제공한다. 모든 API는 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 059 | `GET /api/v1/checks/runs` | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 060 | `POST /api/v1/checks/runs` | 새 접수·합류 202, 성공 결과 재사용 200. `data`는 검사 실행에 `joined`·`reused`를 더한 것 |
| 061 | `GET /api/v1/checks/runs/{id}` | `data` 객체(059 항목과 같은 형식) |

062 결과 조회는 정유림 담당(`CheckResultController`)이다.

**실행기(191-2):** 접수된 MANUAL·REVERIFY·SYNC 검사는 batch의 `CheckRunJob`이 돌린다(아래 "실행기"). 원천이 아니라 수집한 플랫폼 객체(`object_instance`)를 읽어 판정한다(결정 문서 `2026-11-05-cdc-initial-load.md` 9.1: 수집·연결 계산과 같은 데이터를 본다). 원천을 읽는 것은 승인 전 후보 시험(CANDIDATE)뿐이고, 이 실행기는 CANDIDATE를 가져가지 않는다.

**판정(191-3):** SYNC·MANUAL 검사는 결과를 저장한 같은 트랜잭션에서 issue를 등록하고, 원천 사이 규칙의 미연결은 확인 대기(`link_check_pending`)로 보낸다(아래 "판정"). 재검증(REVERIFY)은 issue를 만들지 않는다.

**SYNC 요청(191-4):** batch가 주기(기본 5분)마다 반영 중인 규칙 전부를 검사하는 SYNC를 넣는다(아래 "SYNC 요청").

**후보 시험 대상(338 후속):** 후보 검사는 연결 규칙(LINK_RULE) 후보만 받는다(`CandidateCheckTarget`). 객체 유형·속성·관계 유형·매핑·문서 연결 후보는 원천 행으로 시험할 비교가 없으므로 AI 결과 저장(105)·관리자 수정(049) 때 CANDIDATE를 접수하지 않는다. 049 응답의 `checkRunId`는 `null`이고, 제안 조회 응답은 `checkRequired=false`·`latestCheck=null`이며, 승인할 때 검사 결과를 요구하지 않는다(`basisCheckRunId=null`). 이 정책 전에 접수돼 미지원으로 실패한 검사가 남아 있어도 응답과 승인에 쓰지 않는다.

**후보 시험(338):** 저장·수정으로 접수된 CANDIDATE는 같은 배치 폴러의 `CandidateCheckJob`이 실행한다. 선택지가 없는 LINK_RULE의 접수된 revision을 읽고, SYNC 계정이 실행 시점에 조회한 원천 행에 연결 규칙과 매핑 변환을 적용한다. `data_basis`의 마지막 성공 수집 ID는 접수 당시의 기록으로 남기고, 그 뒤의 원천 변경도 실제 조회 결과에 들어오면 검사한다. 규칙의 출발 행 조건을 통과한 검사 건수와 정상 일치 건수는 같은 후보 ID·revision의 `check_result`에 저장한다. 행 수가 조회 한도를 넘으면 시험을 실패로 처리한다.

선택지가 있는 LINK_RULE처럼 지원 범위에 해당하지 않으면 `FAILED`와 미지원 사유를 기록하고 결과 행은 만들지 않는다. 제안 조회 응답의 `latestCheck.unsupported`는 이 경우에만 `true`로 제공한다. 검토 화면에는 “이 후보는 아직 자동 시험을 지원하지 않음”으로 표시한다.

출발과 도착의 객체 유형이 같으면 자기 자신을 잇는 쌍을 먼저 제외한 뒤 관계 수를 센다. 자기 자신만 일치하면 미연결로 기록하고, 다른 객체도 하나 일치하면 그 객체만 관계 수에 반영한다. 후보 시험, 승인 반영, SYNC에 같은 기준을 적용한다.

**미연결 사유(348):** 미연결(UNMATCHED)은 분류·우선순위·문제 중복 키는 그대로 두고 사유 문장만 원인별로 나눈다. 엔진(`RuleEvaluationEngine`)이 만들므로 후보 시험·MANUAL·SYNC·재검증·문제 등록(`issue.failure_reason`, 결과 표본 `reason`)이 모두 같다. 원천 값은 넣지 않고 조건 번호와 출발 속성 코드만 쓴다.

| 원인 | 사유 |
| --- | --- |
| 출발 비교값이 비어 키를 만들지 않음 | `출발 비교값이 비어 있어 비교할 수 없습니다: 조건 2(so_ref).` |
| 같은 키의 도착이 자기 자신뿐(자기 연결 제외) | `자기 자신 말고는 모든 비교 조건을 충족하는 도착 대상이 없습니다.` |
| 조건이 둘 이상이고 일부 조건만 같은 도착이 있음 | `일부 조건만 같은 도착 대상이 있습니다. 같음: 조건 1(so_ref), 다름: 조건 2(product_code).` 가장 많은 조건이 같은 도착 하나 기준, 같은 개수면 먼저 읽은 도착 |
| 어떤 조건도 같은 도착이 없음 | `모든 비교 조건을 충족하는 도착 대상이 없습니다.` |

일부 일치 사유를 위한 조건별 색인은 그 검사에서 미연결 행이 처음 나올 때 한 번 만든다. 미연결이 없으면 만들지 않는다.

**재검증 결과(135):** 문제별 판정은 `issue_verification`에 남고 [API 070](issue-query.md#재검증-기록-api-070)에서 조회한다. 실행 간 비교는 `GET /api/v1/checks/runs/{id}/comparison`으로 조회한다.

**수동 검사의 역할:** 평소 판정은 수집 뒤 배치가 주기적으로 넣는 SYNC가 한다. 수동 검사(MANUAL)는 장애 복구 뒤·문제가 의심될 때 반영 중인 규칙을 다시 점검하는 데 쓴다. 060은 승인 전 후보 시험(CANDIDATE)을 접수하지 않는다. 후보 시험은 제안 저장·수정 때 자동으로 접수한다. issue는 SYNC·MANUAL 판정에서 만들고, 원천 사이 규칙의 미연결은 확인 대기(`link_check_pending`)를 거친다(191-3).

## 060 요청

| 필드 | 값 |
| --- | --- |
| `triggerKind` | `MANUAL` 또는 `REVERIFY`. SYNC·CANDIDATE는 수집·후보가 넣으므로 이 API로 받지 않는다 |
| `scope.kind` | `ALL`, `LINK_MAPPINGS`, `ISSUES` |
| `scope.linkMappingIds` | LINK_MAPPINGS일 때만. 1~100개 |
| `scope.issueIds` | ISSUES일 때만(REVERIFY만). 1~100개 |
| `comparedToRunId` | REVERIFY일 때만 필수 |

명세 예시에는 `{"kind": "ALL"}`만 있어 나머지 범위는 구현에서 정했다.

| 범위 | 검사하는 규칙 |
| --- | --- |
| ALL | 반영 중(`link_mapping.status=ACTIVE`)인 모든 규칙 개정본 |
| LINK_MAPPINGS | 지정한 규칙 개정본. 모두 ACTIVE여야 한다 |
| ISSUES | 지정한 문제가 나온 규칙 **코드**의 지금 반영 중인(ACTIVE) 개정본(246). 문제는 열려 있어야 하고(RESOLVED 아님) 반영된 규칙에서 나왔어야 한다. 문제는 규칙 코드로 묶여 개정 뒤에도 옛 개정본을 가리키므로, 옛 개정본이 아니라 같은 코드의 현재 개정본으로 재검증한다. 실행기(135)는 문제의 규칙 코드로 이 목록에서 검사할 개정본을 찾는다(코드마다 ACTIVE는 하나) |

### 저장되는 범위와 데이터 기준

- `check_run.scope`에는 요청한 `kind`와 함께 **접수 시점에 정한 규칙 개정본 목록**(`linkMappingIds`, ID 오름차순·중복 제거)을 넣는다. ISSUES면 `issueIds`도 넣는다. 실행기는 이 목록만 검사한다. ALL로 요청해도 그 뒤 반영된 규칙은 들어가지 않는다.
- `check_run.data_basis`는 검사할 규칙들이 읽는 원천 자원(출발·도착 객체 유형에 활성 매핑된 자원)마다 마지막으로 성공한 수집 실행이다. 후보 검사(CANDIDATE)와 같은 형식(`CheckDataBasis`)이다.
- 재검증은 접수 시점의 수집 기준을 먼저 기록한다. 배치가 재검증을 시작할 때 같은 읽기 스냅샷에서 자원별 마지막 성공 수집 ID와 객체를 읽는다. 성공 완료 후 061의 `dataBasis`는 실제 검사에 사용한 기준이다.
- 응답의 `dataBasis.resources[]`는 `sourceResourceId`, `lastSyncRunId`다. V5(266) 반영본 명세의 이름을 따랐다. 저장 칸 이름은 `lastSuccessfulSyncRunId`라서 응답으로 바꿀 때 이름을 바꾼다. Notion `api_fixed` 사본의 `lastAppliedChangeSeq`는 V5 전 이름이다.

### 같은 검사 판단

`work_key` = `종류:비교대상(없으면 -):SHA-256(저장 범위 + 데이터 기준 + 연결 상태)`. REVERIFY의 해시 입력에는 이력 형식 버전도 포함한다. 문제별 이력을 저장하기 전에 성공한 실행을 같은 요청에서 재사용하지 않는다.

**연결 상태(367):** 범위 규칙들의 관계 유형에 걸린 `object_link` 행 수와 마지막 변경 시각(`CheckRunRepository.linkState`)이다. 수동 연결을 만들거나 끄면 수집 실행이 없어 데이터 기준은 그대로지만 연결 상태가 바뀌므로, 이전 검사(예: 재검증 FAILED)를 재사용하거나 그 전에 접수된 실행에 합류하지 않고 새로 실행한다. 규칙 연결은 수집·승인과 같은 트랜잭션에서 바뀌어 데이터 기준과 함께 움직인다. 이 값은 `work_key`에만 들어가고 `data_basis`·응답에는 없다.

1. 같은 키로 성공한 검사가 있으면 그 검사를 돌려준다. HTTP 200, `reused=true`.
2. 재사용할 결과가 없으면 대기·실행 중인 검사에 합류한다. HTTP 202, `joined=true`.
   - MANUAL은 같은 키(같은 수집 기준)의 대기·실행 중 검사에 합류한다.
   - REVERIFY는 같은 저장 범위·비교 실행의 **대기 중(QUEUED)** 작업에는 수집 기준과 관계없이 합류한다. 대기 중인 작업은 시작할 때 최신 수집 기준을 읽기 때문이다.
   - **실행 중(RUNNING)** 인 재검증에는 같은 키(같은 수집 기준)일 때만 합류한다. 실행 중인 작업은 시작할 때 이미 데이터를 읽어서 그 뒤의 수집을 보지 못하므로, 그 사이 새 수집이 끝났으면 새로 넣는다(134 후속).
3. 합류할 검사가 없으면 QUEUED로 넣는다. HTTP 202, `joined=false`.

- 실패한 검사는 다시 쓰지 않는다. 같은 요청이면 새로 넣는다.
- 완료 결과보다 새 수집이 반영된 뒤 같은 요청을 하면 새 수집 기준의 작업을 접수하거나 진행 중인 작업에 합류한다.
- MANUAL과 REVERIFY는 규칙과 기준이 같아도 다른 검사다.
- 부분 유일 인덱스와 `on conflict do nothing`으로 같은 키의 작업은 하나만 남긴다. REVERIFY는 동일 범위·비교 실행의 접수와 완료를 DB 트랜잭션 잠금으로 직렬화해, 수집 기준이 다른 요청이 동시에 들어와도 대기 중인 작업 하나로 모인다.

**감사 기록(접수 줄):** 새로 넣었든 합류했든 결과를 다시 썼든 요청마다 한 줄을 남긴다(개발 가이드 14.2). 요청자(USER)·WEB·`SUCCEEDED`·`event_kind=CHECK_RUN`, `target_type=check_run`·`target_id`=검사 ID. 입력은 `triggerKind`·`scopeKind`·`linkMappingCount`(재검증이면 `issueCount`·`comparedToRunId`), 결과는 `joined`·`reused`·`status`다. ID 목록은 넣지 않는다. 결과 줄은 배치 실행 틀이 남기고 같은 검사 ID로 묶인다.

### 거절

| 경우 | 응답 |
| --- | --- |
| 필수 누락, 허용 값 밖, 종류·범위·비교 대상의 조합이 틀림(REVERIFY인데 `comparedToRunId` 없음, MANUAL인데 있음, ALL에 ID 목록, ISSUES를 MANUAL로 등) | 400 `VALIDATION_FAILED` |
| 반영 중인 규칙이 하나도 없음(ALL) | 422 `BUSINESS_RULE_VIOLATION`, `details.field=scope` |
| 없거나 ACTIVE가 아닌 규칙 | 422, `details.linkMappingIds` |
| 없는 문제, 해결된 문제, 승인 전 후보에서 나온 문제 | 422, `details.issueIds` |
| 규칙 코드에 반영 중인 개정본이 없는 문제(폐기만 됨) | 422, `details.issueIds`·`linkMappingCodes` |
| 검사 입력으로 바꿀 수 없는 규칙(비교 조건 없음, 저장된 변환을 읽을 수 없음) | 422, `details.linkMappingId`·`reason` |
| 비교 대상이 없음, 후보 시험(CANDIDATE)임, 성공(SUCCEEDED)이 아님 | 422, `details.field=comparedToRunId` |

거절된 요청은 검사를 만들지 않는다.

REVERIFY 요청은 수정 자료의 수집 완료를 확인한 뒤 보낸다. 재검증이 FAILED여도, 데이터 기준의 FULL_COMPARE 표가 조치완료 표시 전에 시작한 전체 읽기의 자료면 문제를 확인중으로 되돌리지 않는다(376, 수동 다시 읽기가 이미 읽고 저장 중인 실행에 합류한 경우). 067은 조치완료 표시 뒤에 시작한 재검증의 통과 기록을 해결 조건으로 사용한다. 비교 대상 실행은 규칙 일치 여부와 관계없이, 존재하는 MANUAL·SYNC·REVERIFY 실행이며 상태가 SUCCEEDED이면 유효하다.

## 059 목록 조건

| 조건 | 검증 | 생략 시 |
| --- | --- | --- |
| `triggerKind` | `MANUAL`, `SYNC`, `REVERIFY`, `CANDIDATE` | 모든 종류 |
| `status` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` | 모든 상태 |
| `from`, `to` | 시간대가 붙은 ISO 8601. `from`이 `to`보다 늦으면 400 | 기간 제한 없음 |
| `page` | 0 이상 | 0 |
| `size` | 1 이상 | 20 |

- `from`·`to`는 요청 접수 시각(`requestedAt`) 기준이고 양 끝을 포함한다. `+09:00`은 `%2B09:00`으로 보낸다.
- 정렬은 접수 시각 내림차순, 같으면 ID 내림차순이다. 페이지 기본값과 같은 시각의 정렬은 구현 기본값이다.
- 061은 없는 ID면 404(`resource=check_run`)다.

## 실행기 (191-2, `CheckRunJob`)

batch 프로필의 작업 표 처리기(`QueuedJobHandler`, `CHECK_RUN`)다. `trigger_kind <> CANDIDATE`인 QUEUED 행만 가져간다(작업 표 처리기에 `claimCondition`을 더했다. 기본은 모든 행).

**run**: 읽기 전용 REPEATABLE READ 트랜잭션 하나에서 규칙·객체를 한 스냅샷으로 읽는다. 쓰지 않으므로 끝나면 되돌린다.

1. 접수 때 저장한 `scope.linkMappingIds`와 `data_basis`를 읽는다.
   - 재검증(REVERIFY, 134)은 같은 읽기 스냅샷에서 `data_basis`의 자원별 마지막 성공 수집 ID를 다시 읽고 이 기준으로 검사한다.
2. 규칙마다 190 변환기로 엔진 입력을 만든다.
3. 출발·도착 유형의 객체를 읽는다. 대상은 연결 대상 객체(387, `LinkableObjects`: `status = ACTIVE`이고 꺼진 매핑에서 오지 않음, 연결 재계산과 같은 정의) 가운데 규칙의 원천 자원에서 수집한 것이다. 원천에서 지운 객체, 비활성 매핑에서 온 객체, 플랫폼에서 만든 객체(원천 키 없음)는 빠진다.
4. 행 조건(`source_filter`·`target_filter`)을 객체 속성에 적용한다(`ObjectRowFilter`). 연결 재계산(181)과 같은 두 형식(`{}`, `{"property", "equals"}`)만 읽는다.
5. 속성을 `유형코드.속성코드` 이름으로 `CheckRow`에 넣고(원천 자원 = 매핑의 원천 자원, 원천 키 = `source_key`, 객체 ID), 판정 엔진으로 판정한다.
6. **수동 연결(366):** 규칙으로는 미연결(UNMATCHED)인 출발에 같은 관계 유형의 활성 수동 연결(`object_link.origin = MANUAL`, 양 끝이 연결 대상)이 있으면 그 행을 정상(MATCHED)으로 바꾼다(`CheckObjectReader.manualLinkTargets`, 같은 읽기 스냅샷). 이유는 `관리자가 수동으로 연결함`, 대응 도착은 그 연결의 도착 중 이번 도착 행에 있는 것이다. 중복·조건 위반은 규칙이 가리킨 대상의 문제라 바꾸지 않는다.
   - 수동 연결은 승인 당시 값이 그대로인 동안만 활성이다. 값이 바뀌거나 끝이 지워지거나 규칙이 그 출발을 이으면 연결 재계산이 끈다(`docs/link-recompute.md`). 그래서 검사는 활성 수동 연결을 그대로 믿는다.
   - 결과: 그 행은 문제·확인 대기로 가지 않고, 열린 확인 대기는 연결됨으로 닫히며, 그 행의 문제는 재검증 PASSED로 해결할 수 있다(246).

**규칙 건너뛰기:** 아래 경우에는 그 규칙만 판정하지 않고 감사 결과(`skippedRules[]`: `linkMappingId`, `reason`)에 남긴다. 모든 규칙을 건너뛰면 검사는 FAILED("검사할 수 있는 규칙이 없습니다. …")이다. 단 SYNC는 결과 없는 성공으로 끝낸다. 적재·복구 중에는 SYNC가 모든 규칙을 건너뛰는데, 실패로 두면 다시 쓰이지 않아 주기마다 실패가 쌓이기 때문이다. DB 오류는 검사 전체 실패다.

- 규칙이 더 이상 ACTIVE가 아님(접수 뒤 폐기·교체)
- 비교 조건·변환·행 조건을 읽을 수 없음
- 데이터 기준에 규칙이 읽는 자원이 빠짐(접수 뒤 매핑이 추가됨)
- 도착 값 변환 실패(엔진이 거절)

**complete**: 검사 행을 잠가(`for update`) RUNNING인지 본 뒤, 판정한 규칙마다 `check_result` 한 행을 JPA로 저장하고(`CheckResult.of`) SUCCEEDED로 바꾼다.

재검증은 완료 트랜잭션에서 실제 사용한 수집 기준과 이에 해당하는 `work_key`를 결과와 함께 저장한다. 같은 실행 ID로 061 상태와 062 결과를 계속 조회할 수 있다.

| 칸 | 값 |
| --- | --- |
| 대상·건수 | 판정 결과에서 그대로(`ck_check_result_target`·`ck_check_result_counts`) |
| `previous_result_id` | 같은 규칙 개정본의 가장 최근 결과. 결과는 성공하는 트랜잭션에서만 저장되므로 성공한 검사의 결과다 |
| `sample_rows` | 정상이 아닌 행 최대 20개, 객체 ID 순. `objectInstanceId`, `sourceResourceId`, `sourceKey`, `classification`, `rawValue`, `transformedValue`, `reason`, `targetObjectIds`. 조건이 여럿이면 값을 조건 순서대로 ` / `로 잇는다 |

**감사 결과 줄:** `triggerKind`, `checkedRuleCount`, `manualMatchedCount`(수동 연결로 정상 처리한 출발 수, 366), `skippedRules[]`. 문제별 재검증이면 `reverification`(PASSED·FAILED·NOT_EVALUATED 건수)도 넣는다. 원천 값은 넣지 않는다.

### 문제별 재검증 판정 (246, 135 확장 지점)

범위가 ISSUES인 재검증(REVERIFY)이면 실행기가 `scope.issueIds`의 문제마다 판정(`IssueReverification`)을 만든다.

1. 문제가 나온 개정본의 규칙 코드로, 이 검사가 판정한 같은 코드의 개정본을 찾는다. 접수 때 같은 코드의 반영 중인 개정본을 범위에 넣었기 때문이다(위 "ISSUES" 범위).
2. 그 판정에서 문제 객체(`issue.object_instance_id`)의 출발 행 결과를 본다.

| 판정 | 조건 | `detail` |
|---|---|---|
| PASSED | 출발 행이 정상(MATCHED) | `rawValue`, `transformedValue`, `targetFound`, `classification`, `reason`, `matchedBy`(RULE·MANUAL_LINK). 수동 연결로 통과했으면 `manualTargetObjectIds`(실제로 이은 도착)도 넣고, 도착이 규칙의 도착 행 밖이어도 `targetFound=true`다(367). 070 `detail`에 쓸 값 |
| FAILED | 출발 행이 미연결·중복·조건 위반 | 같음 |
| NOT_EVALUATED | 문제에 객체가 없음, 같은 코드의 규칙을 판정하지 못함(실행 때 반영 중 아님·건너뜀), 객체가 판정 대상에 없음(원천 삭제·행 조건 밖) | 비어 있음. `reason`에 이유 |

- 결과를 저장하는 complete 트랜잭션에서, 검사 상태와 실제로 읽은 데이터 기준(`data_basis`)을 저장한 뒤(376) `IssueReverificationRecorder.record(checkRunId, 판정 목록, 시각)`을 부른다. 구현이 예외를 던지면 결과 저장도 함께 롤백되고 검사는 실패한다.
- `IssueVerificationRecorder`가 `issue_verification`에 PASSED·FAILED를 기록한다. 새 FAILED를 저장하면 `IssueStatusService.reopenAfterFailedReverification`을 부른다. NOT_EVALUATED는 검사 감사 집계에 남는다.

### 이전 검사와의 비교 (135)

`GET /api/v1/checks/runs/{id}/comparison?page=0&size=20`은 관리자 전용이다. 재검증 실행에 저장된 `comparedToRunId`가 비교 기준이다. `page`는 0 이상, `size`는 1~100이며, 행은 규칙 코드와 출발 객체 ID 오름차순이다.

- `data`에는 `comparedToRunId`, `coverage`, `hasBaselinePassEvidence`, `rows`가 있다. `hasBaselinePassEvidence`는 비교 결과를 조회할 때 이전 실행에 정상 판정 근거가 하나라도 남아 있는지 나타낸다. 진행 중이거나 실패한 실행은 `false`를 반환한다. `coverage`는 확인 가능한 행이 있는 `PARTIAL`, 또는 확인 가능한 행이 없는 `UNAVAILABLE`이다. 진행 중이거나 실패한 실행도 `UNAVAILABLE`과 빈 목록을 반환한다. `meta`는 페이지 정보다.
- 행마다 `ruleCode`, `objectInstanceId`, `category`, `before`, `after`를 반환한다. 근거에는 각 실행에서 사용한 규칙 개정본 ID(`linkMappingId`), 판정(`classification`), 저장된 원본·변환 값과 사유가 포함된다.
- 두 실행에 저장된 이상 행 표본과 문제별 판정을 같은 규칙 코드 및 출발 객체 ID로 대조한다. 이상→정상은 `RESOLVED`, 이상→이상은 `REMAINING`, 정상→이상은 `NEW`다. 이전 정상 판정은 저장된 통과 기록으로 확인해야 한다. 이전 실행에 정상 판정이 저장되지 않은 경우 화면에 신규 문제 확인 불가를 표시한다.
- 삭제, 행 조건 제외, 표본 상한 또는 검증 대상 차이로 한쪽 판정이 없으면 그 행은 분류하지 않는다. 비교의 `RESOLVED`는 두 실행에서 관찰한 결과다. 문제 상태의 해결 처리는 API 067이 통과 이력과 상태를 다시 확인해 별도로 처리한다.
- 구현이 없으면 부르지 않고 규칙별 결과만 저장한다. 범위가 ALL·LINK_MAPPINGS인 재검증도 부르지 않는다.

**알려 둘 것**

- 건너뛴 규칙이 있어도 성공한 검사는 같은 `work_key` 요청에 다시 쓰인다(060 성공 재사용). 데이터 기준이 바뀌면 새 검사가 된다.
- 비용: 규칙마다 양 끝 유형의 활성 객체를 모두 읽는다(181과 같은 MVP 한계, Jira 335와 같이 본다).

## 판정 (191-3, `CheckIssueJudge`)

SYNC·MANUAL 검사에만 한다. 결정 문서 `2026-11-05-cdc-initial-load.md` 8장과 191 설계안 4장, 2026-11-10 결정(원천 사이 중복·위반은 바로 issue, 건너뛴 규칙은 감사 결과에만, FULL_COMPARE 표는 마지막 성공 수집 기준)을 따른다.

**규칙 분류**(run 스냅샷): 출발·도착 유형에 활성 매핑된 원천 테이블이 모두 같은 원천이고 모두 CDC면 같은 원천, 그 밖은 원천 사이다.

| 분류 | 미연결 | 중복·위반 |
| --- | --- | --- |
| 같은 원천 | 바로 issue | 바로 issue |
| 원천 사이 | 확인 대기 | 바로 issue |

**건너뛰기**(규칙 단위, run 스냅샷): 아래면 판정하지 않고 결과도 남기지 않는다. 이유는 감사 결과 `skippedRules`에만 남는다.

- CDC 표가 전체 적재 전(`loaded_lsn IS NULL`)
- CDC 표의 전체 읽기(FULL)가 대기·실행 중
- CDC 표의 원천에 복구가 필요(`cdc_offset.recovery_reason`)
- FULL_COMPARE 표가 한 번도 수집되지 않음

**미연결 보류**: 도착 테이블에 도착 유형의 열린 수집 실패(`source_load_failure`)가 있으면 그 규칙의 미연결은 issue·확인 대기로 보내지 않는다. 키 실패(객체를 만들지 못한 행)이거나 비교 조건의 도착 속성 값 실패인 경우다(설계 검토 R5). 어느 출발 행이 그 행과 맞았을지 알 수 없어 규칙 단위로 보류한다. 그동안 그 규칙의 확인 대기도 건드리지 않는다.

**issue 등록**: 194 `IssueRegistrationService`를 쓴다.

- `dedupeKey = 종류:rule:규칙코드:object:출발객체ID`. 같은 열린 문제가 있으면 새로 만들지 않는다. 규칙을 개정해도 같은 문제로 묶인다.
- 원본값·변환값은 표본과 같은 모양이다.
- `evidence`: `ruleCode`, `revision`, `reason`, `transformedValues`, `targetObjectIds`, `crossSource`, `causeConfirmed=false`. 승격이면 `pendingId`, `pendingFirstSeenAt`이 더 붙는다.

**확인 대기**(원천 사이 규칙의 미연결, 검사마다)

1. 이번에 미연결인 출발 객체: 대기가 없으면 WAITING으로 넣고, 있으면 `last_check_run_id`만 바꾼다.
2. 이 규칙의 WAITING 중 이번에 미연결이 아닌 것은 일시 불일치(TRANSIENT)로 닫는다. 출발 객체를 원천에서 지웠으면 SOURCE_DELETED, 아니면 LINKED다(중복·위반으로 바뀐 경우도 LINKED).
3. 아직 미연결인 WAITING은 아래를 모두 만족하면 issue(UNMATCHED)로 올리고 PROMOTED로 닫는다. 같은 트랜잭션이다.
   - 규칙의 CDC 원천이 모두 지금 정상(`healthy_since`가 있고 복구 필요 없음)이다.
   - 규칙의 FULL_COMPARE 표마다 처음 본 시각 뒤에 끝난 성공 수집이 있다.
   - `max(처음 본 시각, CDC 원천들의 healthy_since)`부터 대기 시간이 지났다. 대기 시간은 `axcore.check.pending-wait`이고 기본 10분이며 246에서 확정한다.
4. 반영 중이 아닌 규칙 개정본의 WAITING은 RULE_RETIRED로 닫는다(모든 규칙 대상).

FULL_COMPARE 표의 기준을 "마지막 성공 수집 시각부터 대기"로 하면, 수집 주기가 대기 시간보다 짧을 때 시작 시각이 계속 밀려 승격되지 않는다. 그래서 "처음 본 뒤 성공 수집이 한 번 더 있었고 대기 시간이 지남"으로 구현했다.

**감사 결과 줄**: `judgment` 아래에 `issuesCreated`, `issuesExisting`, `heldUnmatched`, `pendingAdded`, `pendingClosed`, `pendingPromoted`를 남긴다.

**한계**

- 원천 사이에서 같은 원천으로 분류가 바뀐 규칙의 WAITING은 닫지 않는다(닫을 이유 코드가 없다).
- 보류는 규칙 단위라, 수집 실패가 열려 있는 동안 그 규칙의 미연결은 하나도 issue가 되지 않는다.

## SYNC 요청 (191-4, `SyncCheckJob`·`SyncCheckRequester`)

batch의 `@Scheduled`(247)가 `axcore.check.sync.interval`(기본 5분)마다 SYNC 하나를 넣는다. 넣기만 하고 실행은 위 실행기가 한다. 연결 규칙 개정본을 승인하면 커밋 뒤에도 하나 넣는다(366, `SyncCheckOnRuleApproval`). 승인 반영은 미연결을 기록하지 않으므로 새 개정본의 판정이 다음 주기를 기다리지 않게 한다. `AXCORE_CHECK_SYNC_ENABLED=false`면 둘 다 넣지 않는다.

- **범위:** 반영 중인 규칙 전부(`scope.kind = ALL`). 반영 중인 규칙이 없으면 넣지 않는다. 요청자와 `sync_run_id`는 없다(V12).
- **데이터 기준:** 060과 같다(원천 테이블별 마지막 성공 수집). CDC 반영기도 원천 트랜잭션마다 성공 수집(`sync_run`·`sync_run_resource` INCREMENTAL)을 남기므로, 데이터가 바뀌면 기준도 바뀐다. 그래서 데이터 기준에 원천 트랜잭션 번호를 따로 더하지 않았다(191 설계안 Q1-3).
- **같은 검사 판단:** `work_key = SYNC:-:SHA-256(범위 + 데이터 기준 + 연결 상태)`(연결 상태는 위 "같은 검사 판단", 367).
  - 같은 기준으로 성공한 SYNC가 있으면 넣지 않는다(A4).
  - 같은 기준의 SYNC가 대기·실행 중이면 합류한다.
- **확인 대기가 있을 때:** 데이터가 그대로여도 시간이 지나면 승격돼야 한다. 그래서 WAITING이 하나라도 있으면 `work_key` 끝에 주기 구간(`:t` + 현재 시각 ÷ 주기)을 붙여 주기마다 새 SYNC를 넣는다.
- **스레드:** batch의 주기 작업은 스레드 하나를 같이 쓴다(작업 표 확인, 다시 읽기 감시 등). 검사 실행이 길면 SYNC 넣기도 그만큼 늦어진다. 넣기 자체는 짧아서 그대로 두었다(218 스케줄러 분리와 별개).
- **시험:** `src/test/resources/config/application-batch.yml`에서 주기 작업을 끈다. 시험이 넣은 검사 사이에 SYNC가 끼어들지 않게 하려는 것이고, 요청 로직은 `SyncCheckRequester`를 직접 불러 시험한다.

## 규칙 → 판정 엔진 입력 (190, `LinkMappingRuleLoader`)

실행기(191-2)와 060 접수가 함께 쓴다. API는 없다.

- `load(linkMappingId, 실행 데이터 기준)` → `LinkMappingRule`
  - `input`(`CheckRuleInput`): 대상은 규칙 개정본 ID, 관계 수는 `link_type.cardinality`, 비교 조건은 `link_mapping_condition`의 순서대로.
  - 조건의 속성 이름은 `객체유형코드.속성코드`(예: `WORKER_x.id`)다. 판정 엔진은 원천 행 값(`CheckRow.values`)에서 이 이름으로 값을 찾으므로, 실행기는 행을 읽을 때 같은 이름으로 값을 넣어야 한다.
  - 변환은 저장 형식 `[{"op": 함수, "arg": 인자}]`(037~039와 같음)을 `TransformStep`으로 바꾼다.
  - 데이터 기준은 실행 전체 기준에서 이 규칙이 읽는 자원만 남긴다.
  - `sourceType`·`targetType`: 관계의 출발·도착 객체 유형(ID·코드). 실행기가 이 유형의 객체를 읽는다.
  - `sourceResourceIds`·`targetResourceIds`: 출발·도착 유형에 활성(`is_active`) 매핑된 원천 자원.
  - `sourceFilter`·`targetFilter`: `link_mapping`의 행 조건. 판정 엔진은 행 조건을 모르므로 행을 읽는 쪽이 적용한다.
- 오류
  - `IllegalArgumentException`: 규칙이 없음, 비교 조건이 없음, 실행 기준에 규칙이 읽는 자원이 빠짐.
  - `IllegalStateException`: 저장된 변환·행 조건을 읽을 수 없음.

## 검증

- `CheckRunApiIntegrationTests` 36개: 접수(ALL·LINK_MAPPINGS·ISSUES, 규칙 개정 뒤 문제를 현재 개정본으로 재검증), 데이터 기준(마지막 성공 수집, 수집 전 null), 합류(QUEUED·RUNNING), 새 수집 뒤에는 실행 중인 재검증에 합류하지 않고 새로 넣기, 성공 재사용, 새 수집·실패 뒤 새 검사, MANUAL·REVERIFY 분리, 422 각 경우, 400 12가지, 목록 필터·정렬·페이지, 상태 조회·저장 기준 이름 변환·404, 401·403(역할·CSRF).
- `LinkMappingRuleLoaderTests` 7개: 변환 결과(객체 유형, 조건 이름·변환·관계 수·자원·행 조건·기준 걸러내기), 바꾼 입력으로 판정 엔진이 실제로 판정함, 비활성 매핑 제외, 기준 누락·조건 없음·없는 규칙·읽을 수 없는 변환 거절.
- `CheckRunJobTests` 17개: 작업 표에서 가져가 규칙별 결과 저장(건수·표본·원천 키), 지운·플랫폼·비활성 매핑 객체 제외, 행 조건 적용, 같은 규칙 직전 결과 연결, 판정할 수 없는 규칙 건너뛰기와 감사 결과, 모두 건너뛰면 FAILED, 후보 시험은 가져가지 않음, 문제별 재검증 판정(옛 개정본 문제를 현재 개정본으로 PASSED·FAILED·NOT_EVALUATED)과 확장 지점 호출, 규칙 범위 재검증은 확장 지점을 부르지 않음, 활성 수동 연결이 있는 미연결 출발은 정상·연결을 끄면 다시 미연결(366), 수동 연결로 통과한 재검증 근거에 수동 연결 대상(367).
- `CheckIssueJudgmentTests` 9개: 같은 원천은 모든 문제를 issue로 한 번만, 원천 사이 미연결은 확인 대기·중복은 issue, 연결·출발 삭제로 닫기, 대기 시간 뒤 승격, FULL_COMPARE 수집이 처음 본 뒤 없거나 CDC 원천이 비정상이면 승격하지 않음, 도착 키 실패로 미연결 보류, 적재 전·복구 중 건너뛰기(SYNC는 결과 없는 성공, 이유는 감사 결과), 폐기한 규칙의 대기 닫기, 재검증은 issue 없음.
- `SyncCheckRequesterTests` 6개: 반영 중인 규칙 전부와 마지막 성공 수집 기준으로 넣기, 대기 중 SYNC에 합류, 데이터가 그대로면 성공한 SYNC 재사용·바뀌면 새로 넣기, 확인 대기가 있으면 주기 구간마다 새로 넣기, 반영 중인 규칙이 없으면 넣지 않기, 시험에서 주기 작업이 꺼져 있음.
- `CheckResultCreateTests` 3개: 판정 결과로 만든 검사 결과를 JPA로 저장(규칙·후보 대상, 건수, null 값 표본, 직전 결과), 잘못된 ID 거절.

DB 스키마·의존성 변경은 없다(확인 대기 표는 V12에 있다. Entity `LinkCheckPending`을 새로 둔다). 공용 코드 변경: 작업 표 처리기 `claimCondition`(기본은 모든 행이라 기존 처리기는 그대로), 정유림님 `CheckResult` 엔티티에 생성 메서드 `of`, `CheckResultRepository`에 직전 결과 조회.

## 프론트 수동 검사 연동 (113)

- ADMIN 전용 `/admin/checks`에서 059의 검사 실행 목록을 조회한다. 종류·상태·접수 시각 필터, 서버 페이지 이동과 목록 새로고침을 제공한다. 상단 정합성 검사 메뉴와 연결 규칙 화면에서 진입한다.
- 060에 `triggerKind=MANUAL`과 `scope.kind=ALL` 또는 `LINK_MAPPINGS`를 보낸다. 규칙 목록 API가 아직 없어, 선택 검사는 규칙 코드로 046의 `meta.hasNext`를 따라 모든 페이지를 조회해 ACTIVE 개정본을 최대 100개 추가한다. 중간 조회 실패나 잘못된 페이지 응답은 규칙 없음으로 처리하지 않고 오류로 표시한다. 승인 전 후보·폐기된 개정본을 검사 대상으로 대신 보내지 않는다. 접수 시 규칙이 바뀌거나 검사할 규칙이 없으면 서버의 422를 표시한다.
- 202 신규·합류와 200 완료 결과 재사용을 구분하고 실행 ID를 URL `run`에 보관한다. 요청 중 중복 클릭과 POST 자동 재시도를 막는다. 통신 실패·서버 오류·잘못된 응답으로 접수 여부를 모르면 목록을 확인하도록 안내한다.
- 선택한 실행은 061로 조회하고, 대기·실행 중이면 2초마다 상태를 읽는다. 종료·조회 실패 때 주기 조회를 멈추며 재시도는 GET만 한다. 진행 중 실행이 보이는 목록도 주기적으로 갱신한다.
- SUCCEEDED일 때만 062를 제안·규칙 필터 없이 조회한다. 규칙별 전체·정상·미연결·중복·위반 집계와 매칭률·판정 표본을 표시하고 서버 페이지를 이동한다. 실패·진행 중인 실행의 부분 결과는 조회하지 않으며 검사 완료를 개별 행의 정상이나 문제 해결로 해석하지 않는다.
- 기존 후보 결과 화면의 비활성 수동 재검사 버튼은 별도 수동 검사 화면으로 가는 링크와 후보 자동 시험 안내로 바꾼다. 060·062·046 계약과 백엔드·DB는 변경하지 않는다.
- 검증 범위는 MSW 기반 화면·API·권한 테스트 및 로컬 브라우저 데모다. 실제 서버의 수동 검사 접수, 배치 실행, 문제 등록과 결과 저장은 별도로 확인해야 한다.

## 프론트 재검증 연동 (122)

- 적용 후 문제 상세에서 `FIXED_PENDING_REVERIFY` 상태의 반영 규칙 문제를 060으로 접수한다. `triggerKind=REVERIFY`, `scope.kind=ISSUES`, 선택한 문제 ID 하나와 발견 당시 `detectedCheckRunId`를 `comparedToRunId`로 보낸다. 원천 수집을 자동 실행하거나 문제 상태를 자동 변경하지 않는다.
- 202 신규·합류(`joined`)와 200 완료 결과 재사용(`reused`)을 구분한다. 진행 중에는 중복 클릭을 막고, 061을 2초 간격으로 조회한다. 종료·조회 실패 시 주기 조회를 멈추며 조회 재시도는 GET만 수행한다. 통신 실패로 접수 여부를 모르면 실행 이력 확인을 안내하고 POST를 자동 재전송하지 않는다.
- 선택한 문제와 실행 ID는 URL의 `reverifyIssue`·`reverifyRun`에 함께 보관해 새로고침 후 상태 조회를 이어간다. 응답의 실행 종류·대상 문제·비교 기준을 검사해 다른 실행을 표시하지 않는다. 종료 후 목록·상세·상태 이력을 다시 조회한다.
- 발견 당시 결과는 기존 규칙 ID로, 이번 성공 재검증의 062 결과는 061 `scope.linkMappingIds`의 실제 검사 개정본 ID로 조회한다. 060 접수 기준과 061 완료 후 실제 사용한 `dataBasis`가 다를 수 있어 완료 조회 값을 표시한다. 검사 성공과 규칙 단위 건수만으로 개별 문제의 통과·해결을 판정하지 않는다.
- **135 연동:** 비교 패널에서 070 문제별 재검증 이력과 실행 간 비교를 조회한다. 재검증 완료 시 상태 변경 이력(069)과 재검증 이력(070)을 다시 읽는다. `NOT_EVALUATED`는 검사 감사 집계에 남기고 070에는 `PASSED`·`FAILED`만 제공한다. [Notion 070 초안](https://app.notion.com/p/405bdf14d2d881609c88ddd0dfcabd27)의 `checkedAt`·페이지 응답 보강과 비교 API 명세 등록이 필요하다.
- 검증은 MSW 기반 화면·계약 테스트다. 운영 Swagger는 인증으로 열지 못했으며, 운영 재검증 실행·저장·배치·서버 권한·DB 동시성은 검증하지 않았다. 운영 기록 변경 보류 요청에 따라 실제 POST는 수행하지 않는다.

## 기준

- Notion API 059·060·061. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed_266/059.md`~`061.md`(V5 반영본).
- 스키마: `V2__create_operations.sql`의 `check_run`, `V1__create_ontology_core.sql`의 `link_mapping`·`link_mapping_condition`.

## 122 재검증 비교·해결 근거 보완

- 비교 기준은 최초 발견 검사(`detectedCheckRunId`)를 유지한다. 과거 재검증의 비교 보기 버튼은 해당 실행에 저장된 비교를 여는 기능이며 비교 기준을 변경하지 않는다.
- 신규 문제는 같은 항목의 이전 정상 판정이 확인된 경우만 표시한다. 부분 비교에서는 이전 판정 기록이 없는 항목의 신규 여부를 알 수 없다고 안내한다. 일부 정상 근거가 있어도 다른 항목까지 정상으로 간주하지 않는다.
- 상세를 다시 열면 070의 문제별 판정 이력을 페이지 순서대로 읽고 `resolvedCheckRunId`와 일치하는 성공 재검증의 PASSED 기록을 찾는다. 그 기록의 `detail.linkMappingId`로 062 결과를 조회한다. 발견 당시 결과는 기존 규칙 ID로 별도 조회한다.
- 해결 기록·규칙 정보가 없으면 확인 불가를 표시하고 발견 당시나 최신 규칙을 대신 사용하지 않는다. 조회 오류는 실패 상태로 표시하며 읽기 요청 재시도만 제공한다.
- 기존 API·DB 계약은 변경하지 않는다. 사용자 전달 사항, Jira 122·135, `IssueVerificationRecorder`의 기록 필드와 서버 DTO를 대조했다. Notion 원문은 이번 연결에서도 접근을 확인하지 못했으며 실서버 재검증·상태 변경은 수행하지 않는다.
