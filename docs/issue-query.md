# 정합성 문제 조회·등록·상태 변경 — S15P31C106-194·196

## 범위와 권한

FN-ISS-01~03의 정합성 문제 목록·근거 조회, 처리 상태 변경 API와, 검사가 문제를 등록할 때 쓰는 중복 방지 등록 서비스를 제공한다. 모든 API는 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 065 | `GET /api/v1/issues` | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 066 | `GET /api/v1/issues/{id}` | `data` 객체. `id`는 1 이상 |
| 067 | `PATCH /api/v1/issues/{id}/status` | 바뀐 `data` 객체(066과 같은 형식) |

065·066은 `issue`를 읽기만 한다. 원천 전달 기록(068)과 상태 이력 조회(069)는 195에서 만든다.

## 목록 조건과 기본값

| 조건 | 검증 | 생략 시 |
| --- | --- | --- |
| `status` | `NEW`, `CHECKING`, `FIXED_PENDING_REVERIFY`, `RESOLVED` 중 하나 | 모든 상태 |
| `issueKind` | `UNMATCHED`, `DUPLICATE`, `VIOLATION` 중 하나 | 모든 종류 |
| `linkMappingId` | 1 이상 | 규칙 제한 없음 |
| `from`, `to` | 시간대가 붙은 ISO 8601. `from`이 `to`보다 늦으면 400 | 기간 제한 없음 |
| `page` | 0 이상 | 0 |
| `size` | 1 이상 | 20 |

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

| 경우 | 메서드 | 예 |
| --- | --- | --- |
| 반영된 규칙이 객체 하나에서 낸 문제 | `forRule(kind, linkMappingCode, objectId)` | `UNMATCHED:rule:W2E:object:501` |
| 후보 시험이 원천 행 하나에서 낸 문제 | `forProposal(kind, proposalItemId, rowKey)` | `UNMATCHED:proposal:7:row:W23` |

최대 300자다. Notion 065 예시(`UNMATCHED:link_mapping:12:object:501`)와 ERD 예시(`UNMATCHED:W2E:W23`)가 서로 달라 위 형식으로 정했다. 명세 예시를 맞춰야 한다.

## 상태 변경 (067)

요청: `status`(CHECKING·FIXED_PENDING_REVERIFY·RESOLVED), `reason`(1~1000자, 공백만은 안 됨), `resolvedCheckRunId`(RESOLVED일 때만), `rowVersion`.

### 허용 전환

| 지금 | 바꿀 수 있는 상태 |
| --- | --- |
| NEW | CHECKING |
| CHECKING | FIXED_PENDING_REVERIFY |
| FIXED_PENDING_REVERIFY | CHECKING(재검증 실패 등으로 다시 확인), RESOLVED |
| RESOLVED | 없음. 같은 문제가 다시 나오면 새 문제로 등록된다 |

- 표에 없는 전환과 같은 상태로의 변경은 409 `INVALID_STATUS_TRANSITION`이다. `details`에 `currentStatus`, `requestedStatus`, `allowedStatuses`를 준다.
- **정책 확인 필요(246):** 한 칸씩만 앞으로 가고, 되돌림은 FIXED_PENDING_REVERIFY → CHECKING 하나만 열었다. NEW·CHECKING에서 바로 RESOLVED로 가는 길은 막았다. 관리자가 "원천을 고쳤다"고 표시한 시점이 있어야 아래 재검증 시점 검사를 할 수 있어서다. 표는 `IssueStatus.nextStatuses()` 한 곳에 있다.

### 해결(RESOLVED) 조건

`resolvedCheckRunId`가 아래를 모두 만족하지 않으면 422 `REVERIFICATION_REQUIRED`(`details.field=resolvedCheckRunId`)다.

1. `trigger_kind=REVERIFY`, `status=SUCCEEDED`인 검사다.
2. 그 검사의 `issue_verification`에 이 문제가 `PASSED`로 있다.
3. 그 검사가 이 문제를 마지막으로 FIXED_PENDING_REVERIFY로 바꾼 뒤에 시작했다(`started_at` ≥ 그 `issue_history.changed_at`). 원천을 고친 뒤의 자료로 다시 검사했다는 뜻이다.
4. 그보다 뒤에 시작한 검사에서 이 문제가 다시 `FAILED`가 나오지 않았다.

명세는 "최신 수정 자료(원천 동기화 이후)를 대상으로 했는지"를 서버가 검사하라고 한다. 수집 방식(CDC)이 정해지는 중이라 수집 실행 기준 비교는 넣지 않고, 3번의 "조치완료 표시 뒤에 시작한 재검증"으로 대신했다. 재검증(134)이 시작할 때 최신 수집 자료를 읽는지는 134 쪽에서 보장해야 한다.

### 처리 순서

1. 형식 검사(400). RESOLVED인데 `resolvedCheckRunId`가 없거나, 다른 상태인데 보내면 400 `VALIDATION_FAILED`(`details.field=resolvedCheckRunId`).
2. 문제 행을 `for update`로 잠그고, 없으면 404.
3. `rowVersion`이 다르면 409 `VERSION_CONFLICT`(`expected`, `actual`).
4. 전환 검사(409), 해결 조건 검사(422).
5. 같은 트랜잭션에서 `issue`(status, `row_version + 1`, `updated_at`, RESOLVED면 `resolved_check_run_id`·`resolved_outcome=PASSED`)를 바꾸고 `issue_history`에 한 행(from, to, `note=reason`, RESOLVED면 `check_run_id`, `changed_by`=요청 관리자)을 넣는다.

거절된 요청은 상태·버전·이력을 바꾸지 않는다.

## 오류와 검증

입력 오류는 400, 미인증은 401, 권한 부족은 403, 없는 상세는 404(`RESOURCE_NOT_FOUND`, `resource=issue`)다. 결과가 없으면 200과 빈 배열이다.

- `IssueApiIntegrationTests`: PostgreSQL에서 필터·기간 양 끝·페이지·정렬·`causeConfirmed` 기본값·해결/전달 필드·400/401/403/404를 확인한다.
- `IssueRegistrationServiceTests`: 등록 이력, 열린 문제 재사용(처리 중 상태 포함), 해결 뒤 재발 등록, 객체·종류별 분리를 확인한다.
- `NewIssueTests`: 키 형식과 입력 검사.
- `IssueStatusApiIntegrationTests`: 전환 흐름(앞으로·되돌림), 해결 성공, 해결 조건 위반(시점·뒤 실패·FAILED·다른 문제·REVERIFY 아님·미완료·없는 검사), 흐름 밖 전환, 해결 뒤 재변경, 버전 충돌, 400·401·403(CSRF 포함)·404.
- `IssueStatusTests`: 전환 표.

동시 등록은 테스트하지 않았다. PostgreSQL `on conflict`와 부분 유일 인덱스에 맡긴다. DB 스키마·의존성 변경은 없다.

## 기준

- Notion API 065·066·067. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed/065.md`, `066.md`, `067.md`.
- 스키마: `V2__create_operations.sql`의 `issue`, `issue_verification`, `issue_history`.
