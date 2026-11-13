# 정합성 문제 조회·등록·상태 변경·전달 기록 — S15P31C106-194·195·196

## 범위와 권한

FN-ISS-01~04의 정합성 문제 목록·근거 조회, 처리 상태 변경, 원천 전달 기록, 상태 이력 API와, 검사가 문제를 등록할 때 쓰는 중복 방지 등록 서비스를 제공한다. 모든 API는 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청                               | 결과                                                 |
| --- | ---------------------------------- | ---------------------------------------------------- |
| 065 | `GET /api/v1/issues`               | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 066 | `GET /api/v1/issues/{id}`          | `data` 객체. `id`는 1 이상                           |
| 067 | `PATCH /api/v1/issues/{id}/status` | 바뀐 `data` 객체(066과 같은 형식)                    |
| 068 | `POST /api/v1/issues/{id}/forward` | 바뀐 `data` 객체(066과 같은 형식)                    |
| 069 | `GET /api/v1/issues/{id}/history`  | `data` 배열과 `meta`                                 |

065·066·069는 읽기만 한다.

## 목록 조건과 기본값

| 조건            | 검증                                                            | 생략 시        |
| --------------- | --------------------------------------------------------------- | -------------- |
| `status`        | `NEW`, `CHECKING`, `FIXED_PENDING_REVERIFY`, `RESOLVED` 중 하나 | 모든 상태      |
| `issueKind`     | `UNMATCHED`, `DUPLICATE`, `VIOLATION` 중 하나                   | 모든 종류      |
| `linkMappingId` | 1 이상                                                          | 규칙 제한 없음 |
| `from`, `to`    | 시간대가 붙은 ISO 8601. `from`이 `to`보다 늦으면 400            | 기간 제한 없음 |
| `page`          | 0 이상                                                          | 0              |
| `size`          | 1 이상                                                          | 20             |

- `from`·`to`는 문제 등록 시각(`createdAt`) 기준이고 양 끝을 포함한다.
- 쿼리 문자열의 `+09:00`은 `%2B09:00`으로 보내야 한다. 그대로 보내면 `+`가 공백으로 바뀌어 400이 된다.
- 정렬은 등록 시각 내림차순, 같으면 ID 내림차순이다. 페이지 기본값과 정렬은 **구현 기본값**이며 Notion 065에 적힌 계약은 아니다.

## 응답

목록과 상세는 같은 `IssueResponse`를 쓴다. nullable 필드는 `null`로 내보낸다.

- `evidence`는 저장된 근거를 그대로 주되 `causeConfirmed`는 항상 넣는다. 저장된 근거에 boolean으로 없으면 `false`다. 화면은 false일 때 "원인 미확정"을 표시한다(FN-ISS-02).
- 원인을 확인한 검사는 근거에 `"causeConfirmed": true`를 넣어 등록한다.

## 문제 등록 (`IssueRegistrationService.register`)

검사(191 수동 검사·재검증, 수집 중 분류, 후보 시험)가 문제를 찾으면 이 서비스를 부른다. API는 없다.

- 같은 `dedupeKey`의 열린 문제(`status <> RESOLVED`)가 있으면 새로 만들지 않고 그 ID를 `created=false`로 돌려준다. 기존 문제의 값과 처음 등록한 검사는 바꾸지 않는다.
- 해결된 뒤 같은 문제가 다시 나오면 새 문제로 등록한다(재발).
- 새로 만들면 `issue_history`에 `from_status=null`, `to_status=NEW`, `check_run_id`=등록한 검사, `changed_by=null`인 행을 함께 넣는다(069 계약).
- 중복은 DB의 `uq_issue_open` 부분 유일 인덱스로 막는다(`insert ... on conflict do nothing`). 동시에 같은 문제를 넣어도 하나만 남는다.
- 호출한 검사의 트랜잭션 안에서 실행한다. 검사 기록이 롤백되면 문제와 이력도 롤백된다.
- 문제는 반영된 규칙(`linkMappingId`)이나 승인 전 후보(`proposalItemId`) 중 정확히 하나에서 나와야 한다.

### `dedupeKey` 형식

`IssueDedupeKey`로 만든다. 규칙은 개정본 ID가 아니라 **규칙 코드**로 가리킨다. 규칙을 개정해도 같은 행의 같은 문제는 열린 문제 하나로 남는다.

| 경우                                 | 메서드                                      | 예                              |
| ------------------------------------ | ------------------------------------------- | ------------------------------- |
| 반영된 규칙이 객체 하나에서 낸 문제  | `forRule(kind, linkMappingCode, objectId)`  | `UNMATCHED:rule:W2E:object:501` |
| 후보 시험이 원천 행 하나에서 낸 문제 | `forProposal(kind, proposalItemId, rowKey)` | `UNMATCHED:proposal:7:row:W23`  |

최대 300자다. Notion 065 예시(`UNMATCHED:link_mapping:12:object:501`)와 ERD 예시(`UNMATCHED:W2E:W23`)가 서로 달라 위 형식으로 정했다. 명세 예시를 맞춰야 한다.

## 수동 연결 (058)

`POST /api/v1/issues/{issueId}/manual-links`는 관리자가 확인중인 미연결 문제에 도착 객체를 연결한다.

- 요청은 `targetObjectId`(1 이상의 정수), `reason`(1~1000자, 공백만은 안 됨), `rowVersion`(066에서 읽은 0 이상의 정수)이다.
- 서버는 문제에 기록된 출발 객체와 발견 당시 규칙의 관계 유형을 사용한다. 해당 문제는 `UNMATCHED`이고 `CHECKING`이어야 한다.
- 성공은 HTTP 200이며 `data`에 `issueId`, `linkId`, `outcome`(`CREATED` 또는 `REACTIVATED`), `actionExecutionId`를 담는다. 연결과 성공 감사 기록을 같은 트랜잭션에 저장한다.
- 형식 오류는 400, 인증·권한 오류는 401·403, 없는 문제·규칙·연결 대상은 404다. 버전이나 상태가 다르거나 이미 연결돼 있으면 409, 문제 유형이나 연결 조건이 맞지 않으면 422다. 연결 조건별 오류 코드는 [확정 연결 저장](confirmed-link.md)을 따른다.
- 저장 후 문제는 확인중 상태다. 관리자가 067로 조치완료로 바꾼 뒤 재검증하고, 통과 기록으로 해결 처리한다.
- 이전 보류 건은 056·057에서 기존 보류 이력으로 조회한다. 현재 문제 상태는 066, 문제별 재검증 결과는 070에서 조회한다.

## 상태 변경 (067)

요청: `status`(CHECKING·FIXED_PENDING_REVERIFY·RESOLVED), `reason`(1~1000자, 공백만은 안 됨), `resolvedCheckRunId`(RESOLVED일 때만), `rowVersion`.

### 허용 전환

| 지금                   | 바꿀 수 있는 상태                                |
| ---------------------- | ------------------------------------------------ |
| NEW                    | CHECKING                                         |
| CHECKING               | FIXED_PENDING_REVERIFY                           |
| FIXED_PENDING_REVERIFY | CHECKING(재검증 실패 등으로 다시 확인), RESOLVED |
| RESOLVED               | 없음. 같은 문제가 다시 나오면 새 문제로 등록된다 |

- 표에 없는 전환과 같은 상태로의 변경은 409 `INVALID_STATUS_TRANSITION`이다. `details`에 `currentStatus`, `requestedStatus`, `allowedStatuses`를 준다.
- 표는 `IssueStatus.nextStatuses()` 한 곳에 있다.

### 상태 전환 정책 (246 확정)

| 전환                              | 누가                                 | 조건                                                                                       |
| --------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------ |
| (등록) → NEW                      | 검사(SYNC·MANUAL)                    | 같은 `dedupeKey`의 열린 문제가 없을 때                                                     |
| NEW → CHECKING                    | 관리자(067)                          | 없음                                                                                       |
| CHECKING → FIXED_PENDING_REVERIFY | 관리자(067)                          | 없음. 이 시각이 "원천을 고쳤다"는 기준이 된다                                              |
| FIXED_PENDING_REVERIFY → CHECKING | 관리자(067) 또는 재검증 실패 시 검사 | 검사는 조치완료 표시 뒤의 자료를 읽은 재검증에서 FAILED일 때만(376)                                  |
| FIXED_PENDING_REVERIFY → RESOLVED | **관리자만**(067)                    | 아래 해결 조건. 재검증이 통과해도 자동으로 해결하지 않는다(2026-10-27 결정, 시나리오 S5-6) |
| RESOLVED → (없음)                 | —                                    | 끝 상태. 같은 문제가 다시 나오면 새 문제로 등록된다                                        |

- **한 칸씩만 앞으로 간다.** NEW·CHECKING에서 바로 RESOLVED로 가는 길은 없다. 관리자가 "원천을 고쳤다"고 표시한 시점이 있어야 해결 조건 3(조치완료 뒤에 시작한 재검증)을 검사할 수 있기 때문이다.
- **되돌림은 FIXED_PENDING_REVERIFY → CHECKING 하나다.** 재검증이 실패했거나 관리자가 다시 확인해야 한다고 판단한 경우다.
- **되돌린 뒤 다시 조치완료로 바꾸면 재검증도 다시 돌아야 한다.** 그 전 재검증은 새 조치완료보다 먼저 시작해 해결 근거가 안 된다. 그래서 060은 데이터가 그대로여도 끝난 재검증을 다시 쓰지 않고 새로 돈다(389, `docs/check-run.md` "같은 검사 판단").
- **수집 중 분류(SYNC)는 상태를 바꾸지 않는다.** 같은 문제를 다시 찾아도, 사라져도 그대로 둔다. 수집이 늦게 따라와 고치기 전 자료로 판단할 수 있어서다. 해결은 재검증 기록을 근거로 사람이 한다.
- **규칙을 개정한 뒤의 재검증:** 문제는 규칙 코드로 묶여 처음 발견한 개정본을 계속 가리킨다. 재검증(060 ISSUES)은 그 개정본이 아니라 같은 규칙 코드의 지금 반영 중인 개정본으로 검사한다(`docs/check-run.md`). 그래서 규칙이 틀려 생긴 문제(오탐)는 규칙을 고친 새 개정본을 승인하고, 조치완료로 바꾼 뒤 재검증이 통과하면 해결로 닫는다. 별도의 "종결" 상태는 두지 않는다.
- **재검증 기록 조회(070):** 관리자가 `GET /api/v1/issues/{id}/verifications`로 문제별 판정과 실행 상태를 조회한다. 해결 근거로 고를 검사는 `triggerKind=REVERIFY`, `status=SUCCEEDED`, `outcome=PASSED`인 기록이다. 실제 해결 처리에는 아래 067 조건이 적용된다.

### 재검증 기록 API (070)

- `page`는 0부터 시작하고 기본값은 0, `size` 기본값은 20이다. `checkedAt` 내림차순, 같은 시각이면 `checkRunId` 내림차순으로 반환한다.
- 각 항목은 `issueId`, `checkRunId`, `triggerKind`, 실행 `status`, 판정 `outcome`, `detail`, `checkedAt`을 담는다. `detail`에는 원본 값, 변환 값, 대상 존재 여부, 판정 분류, 판정 사유(`reason`), 검사에 사용한 규칙 개정본 ID(`linkMappingId`)가 들어간다.
- 실제로 판정한 PASSED와 FAILED만 저장하며, 같은 문제·검사 실행의 기록은 하나만 남긴다. 판정할 수 없었던 항목은 실행 감사 기록의 집계에서 확인할 수 있다.
- 문제가 없으면 404, 문제가 있지만 기록이 없으면 200과 빈 목록을 반환한다. 잘못된 ID·페이지 조건은 400, 미인증은 401, 관리자 권한이 없으면 403이다.

#### 알려진 한계

- 규칙 코드를 대체 없이 폐기하면 그 규칙의 열린 문제는 재검증을 접수할 수 없어(422) 해결로 갈 수 없다. 지금은 승인 반영(189)에 폐기만 하는 경로가 없어 생기지 않는다. 그 경로를 만들 때 함께 정한다.
- 기록 기능이 도입되기 전에 완료된 재검증은 문제별 기록이 없을 수 있다. 067은 실제로 저장된 통과 기록을 요구한다.

### 검사가 바꾸는 상태 (재검증 실패)

검사가 바꾸는 상태는 하나다. 재검증(135)이 문제의 `issue_verification`에 FAILED를 넣은 같은 트랜잭션에서 `IssueStatusService.reopenAfterFailedReverification(issueId, checkRunId)`를 부른다.

- 문제가 FIXED_PENDING_REVERIFY이고, 그 재검증이 마지막 조치완료 표시 뒤의 자료를 읽었으면(검사가 표시 뒤에 시작했고, 데이터 기준의 FULL_COMPARE 표마다 기준 수집이 표시 뒤에 시작함, 376) CHECKING으로 되돌리고 `true`를 준다. `row_version`을 올리고 이력에 `changed_by=null`, `check_run_id`=그 재검증, `note=null`인 행을 남긴다(069의 "검사가 바꾼 행").
- 그 밖에는 바꾸지 않고 `false`를 준다. NEW·CHECKING(관리자가 아직 고쳤다고 하지 않음), RESOLVED(해결된 문제는 열지 않음), 조치완료 표시 전에 시작한 재검증(고치기 전 자료).
- 이 재검증에 이 문제의 FAILED 기록이 없거나 REVERIFY 검사가 아니면 `IllegalStateException`이다(호출 순서 오류).
- 재검증이 PASSED면 아무것도 바꾸지 않는다. 해결은 관리자가 067로 한다. 수집 중 분류(SYNC)가 같은 문제를 다시 찾아도 상태를 바꾸지 않는다. 수집이 늦게 따라와 고치기 전 자료로 판단할 수 있어서다.
- 되돌려진 문제는 관리자가 원천을 다시 고친 뒤 FIXED_PENDING_REVERIFY로 바꾸면 그 시각이 새 기준이 된다.
- DB 변경은 없다. `issue_history`의 `ck_issue_history_who`(처리자나 검사 중 하나)와 `ck_issue_history_resolver`(해결은 사람만)가 이 경우를 이미 허용·제한한다.

### 해결(RESOLVED) 조건

`resolvedCheckRunId`가 아래를 모두 만족하지 않으면 422 `REVERIFICATION_REQUIRED`(`details.field=resolvedCheckRunId`)다.

1. `trigger_kind=REVERIFY`, `status=SUCCEEDED`인 검사다.
2. 그 검사의 `issue_verification`에 이 문제가 `PASSED`로 있다.
3. 그 검사가 이 문제를 마지막으로 FIXED_PENDING_REVERIFY로 바꾼 뒤에 시작했다(`started_at` ≥ 그 `issue_history.changed_at`). 원천을 고친 뒤의 자료로 다시 검사했다는 뜻이다.
4. 그보다 뒤에 시작한 검사에서 이 문제가 다시 `FAILED`가 나오지 않았다.

재검증 배치는 실행을 시작할 때 최신 수집 기준을 읽는다(134). API 067은 3번의 조치완료 시각과 재검증 시작 시각을 비교한다.

### 처리 순서

1. 형식 검사(400). RESOLVED인데 `resolvedCheckRunId`가 없거나, 다른 상태인데 보내면 400 `VALIDATION_FAILED`(`details.field=resolvedCheckRunId`).
2. 문제 행을 `for update`로 잠그고, 없으면 404.
3. `rowVersion`이 다르면 409 `VERSION_CONFLICT`(`expected`, `actual`).
4. 전환 검사(409), 해결 조건 검사(422).
5. 같은 트랜잭션에서 `issue`(status, `row_version + 1`, `updated_at`, RESOLVED면 `resolved_check_run_id`·`resolved_outcome=PASSED`)를 바꾸고 `issue_history`에 한 행(from, to, `note=reason`, RESOLVED면 `check_run_id`, `changed_by`=요청 관리자)을 넣는다.

거절된 요청은 상태·버전·이력을 바꾸지 않는다.

## 원천 담당자 전달 기록 (068)

요청: `rowVersion`(필수, 0 이상).

- 플랫폼은 메시지를 보내지 않는다. 관리자가 플랫폼 밖에서 원천 담당자에게 알린 뒤 "전달함"을 기록한다(FN-ISS-04). 받는 사람 이름은 남기지 않는다.
- `forwarded_at`=요청 시각, `forwarded_by`=요청 관리자로 함께 기록하고 `row_version`을 올린다. 상태는 바꾸지 않고 `issue_history`도 남기지 않는다. 원천 수정이 끝났다는 뜻이 아니다. 수정 완료 알림을 받으면 067로 FIXED_PENDING_REVERIFY로 바꾼다.
- 다시 전달하면 마지막 전달 시각·관리자로 덮어쓴다. 전달 이력을 따로 쌓지 않는다(DB 칸이 하나뿐이다).
- 해결된 문제는 409 `ISSUE_ALREADY_RESOLVED`다(새 코드, 명세에 없음). 다시 생기면 새 문제로 등록되므로 해결된 문제에 전달할 일이 없다고 봤다.
- 버전이 다르면 409 `VERSION_CONFLICT`, 없는 문제는 404.

## 상태 이력 (069)

- `issue_history`를 `changed_at` 오름차순, 같으면 ID 오름차순으로 준다. 응답 필드는 `id`, `issueId`, `beforeStatus`, `afterStatus`, `changedAt`, `changedBy`, `checkRunId`, `note`.
- 검사가 처음 등록한 행은 `beforeStatus=null`, `afterStatus=NEW`, `changedBy=null`, `checkRunId`=등록한 검사, `note=null`이다(`IssueRegistrationService`가 남김). 사람이 바꾼 행은 `changedBy`와 `note`가 있고, RESOLVED 행은 `checkRunId`에 근거 재검증이 있다(067이 남김).
- 명세에 조회 파라미터가 없지만 응답에 `meta`가 있어 다른 목록처럼 `page`(기본 0)·`size`(기본 20)를 받는다.
- 없는 문제는 404. 이력이 없는 문제(등록 서비스를 거치지 않고 넣은 행)는 200과 빈 배열이다.

## 오류와 검증

입력 오류는 400, 미인증은 401, 권한 부족은 403, 없는 상세는 404(`RESOURCE_NOT_FOUND`, `resource=issue`)다. 결과가 없으면 200과 빈 배열이다.

- `IssueApiIntegrationTests`: PostgreSQL에서 필터·기간 양 끝·페이지·정렬·`causeConfirmed` 기본값·해결/전달 필드·400/401/403/404를 확인한다.
- `IssueRegistrationServiceTests`: 등록 이력, 열린 문제 재사용(처리 중 상태 포함), 해결 뒤 재발 등록, 객체·종류별 분리를 확인한다.
- `NewIssueTests`: 키 형식과 입력 검사.
- `IssueStatusApiIntegrationTests`: 전환 흐름(앞으로·되돌림), 해결 성공, 해결 조건 위반(시점·뒤 실패·FAILED·다른 문제·REVERIFY 아님·미완료·없는 검사), 흐름 밖 전환, 해결 뒤 재변경, 버전 충돌, 400·401·403(CSRF 포함)·404.
- `IssueStatusTests`: 전환 표.
- `IssueReopenServiceTests`: 재검증 실패 때 되돌림과 이력, 조치완료 전 재검증 무시, NEW·CHECKING·RESOLVED 무변경, FAILED 기록 없는 호출 거절, 되돌린 뒤 다시 조치완료·해결.
- `IssueForwardHistoryApiIntegrationTests`: 전달 기록(상태·이력 무변경), 재전달 덮어쓰기, 버전 충돌, 해결된 문제 거절, 등록→확인→조치완료→해결 이력 순서와 필드, 이력 페이지·다른 문제 제외, 빈 이력, 400·401·403(CSRF 포함)·404.
- 테스트의 CSRF는 `IssueTestFixtures.csrfToken()`으로 쿠키·헤더를 직접 보낸다. spring-security-test `csrf()`는 같은 컨텍스트를 쓰는 다른 테스트의 실제 로그인 요청을 깨뜨린다.

동시 등록은 테스트하지 않았다. PostgreSQL `on conflict`와 부분 유일 인덱스에 맡긴다. DB 스키마·의존성 변경은 없다.

## 프론트 전달 기록·상태 이력 연동 (S15P31C106-118)

2026-11-12 원격 develop `b4976d4`에서 시작했다. Jira의 기존 완료 상태는 변경하지 않았다. Chrome에서 Notion 068·069의 전달 사실 기록·버전 충돌·이력 순서와 시스템/관리자 필드를 확인하고, 최신 Controller·DTO·서비스·기존 API 테스트와 대조했다.

- 문제 상세의 전달 기록은 068에 현재 `rowVersion`을 보낸다. 성공 응답으로 상세 캐시를 갱신하고 목록·상세·이력을 다시 조회한다. 늦은 저장 응답이 더 높은 버전의 상세를 덮어쓰지 않게 한다.
- 068은 실제 메시지 전송·상태 변경이 아니다. 다시 전달했다고 기록하면 마지막 전달 시각·처리자를 갱신한다. `RESOLVED` 문제는 전달 기록 버튼을 제공하지 않는다.
- 연속 클릭과 자동 재전송을 막는다. 409 버전·상태 충돌은 서버 오류를 표시하고 최신 기록을 조회한다. 네트워크·서버·저장 응답 오류는 성공으로 표시하지 않으며, 다시 기록하기 전에 조회로 확인하도록 안내한다.
- 069는 `page`·`size=20`으로 조회한다. `beforeStatus/afterStatus/changedBy/checkRunId/note`를 화면에 표시하고 서버의 시간순 정렬을 유지한다. 처리자가 null인 행은 시스템으로, 근거 검사가 있으면 검사 ID도 표시한다.
- 기존 067 상태 변경 성공 뒤에도 069를 갱신한다. 이력의 로딩·빈 결과·실패·재조회·페이지 이동을 구분하며 다른 문제 ID나 잘못된 페이지 응답을 정상 이력으로 표시하지 않는다.
- 권한은 기존 ADMIN 관리 라우트와 서버 인가를 사용한다. API·DB·인증 구조·인프라는 변경하지 않았다.

자동 검증은 모의 HTTP 응답으로 정상·재전달·중복 클릭·권한·충돌·응답 지연·페이지·조회 실패와 067 후 갱신을 확인한다. 실제 운영 068 저장·069 조회 및 배포 후 확인은 별도이며, 최종 테스트·빌드·정적 검사·리뷰 결과는 MR에 기록한다.

## 기준

- Notion API 065·066·067·070. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed/065.md`, `066.md`, `067.md`, `070.md`.
- 스키마: `V2__create_operations.sql`의 `issue`, `issue_verification`, `issue_history`.
