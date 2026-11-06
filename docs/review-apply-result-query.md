# 승인 반영 결과 조회 — S15P31C106-133

FN-REV-06·07의 결과는 기존 후보 상세(049), 결정 이력(055), 실행 이력(096·097)으로 조회한다. 프론트, 공개 API 계약, DB 스키마와 131의 실행·롤백 코드는 변경하지 않는다.

## 조회 경로

| API | 요청 | 제공하는 정보 |
| --- | --- | --- |
| 049 | `GET /api/v1/proposals/{id}` | 현재 `revision`, `reviewStatus`, `applyStatus`, `applyError`, `appliedAt` |
| 055 | `GET /api/v1/proposals/{id}/decisions` | 커밋된 결정 ID·대상 revision·선택지·결정자·시각·사유 |
| 096 | `GET /api/v1/audit/actions` | 성공·실패·차단된 실행, 대상 후보, 요청 `input`, 처리 `result`, 변경 내역 |
| 097 | `GET /api/v1/audit/actions/{id}` | 실행 하나의 입력·결과·변경 내역 |
| 056·057 | `GET /api/v1/held-items`, `GET /api/v1/held-items/{id}` | 승인 과정에서 생성된 개별 보류 건의 현재 상태와 근거 |

후보 검토 화면(049·055)은 ADMIN이다. 감사 목록(096)은 ADMIN에게 전체, 그 밖의 역할에게 본인 실행만 제공한다. 감사 상세(097)는 ADMIN이다. 기존 인증·임시 비밀번호 제한을 그대로 따른다.

## 기존 프론트에서 확인하는 방법

- 제안 상세와 규칙 이력의 **승인 상태**는 049의 현재 상태를 표시한다.
- 규칙 이력의 **활동 기록**은 055의 커밋된 승인·거절·보류 결정을 표시한다.
- **실행 이력(`/audit`)**은 096의 `SUCCEEDED`·`FAILED`·`BLOCKED`를 성공·실패·차단으로 표시한다. 대상은 `proposal_item #후보ID`, 동작은 기존 Action 표시를 사용한다. **내용 보기**의 입력값과 처리 결과에서 revision·요청 결정·사유·반영 대상·보류 건 ID를 확인한다.
- 현재 실행 이력 화면이 보내는 기간·상태 필터와 후보 필터 없는 기본 목록을 그대로 쓴다. 후보별 바로가기나 새 결과 화면은 추가하지 않는다. 기본 목록은 API 기본 페이지 범위이고, 전체 이력 탐색 UI를 새로 만드는 작업은 포함하지 않는다.
- 제안 상세의 `applyError` 조건부 표시도 유지한다. 131 실패는 이 필드를 갱신하지 않으므로 해당 표시 대신 실행 이력에서 확인한다. `applyStatus=FAILED` 후보 목록 필터로 131의 실패 시도를 찾을 수는 없다.
- 054 결정 버튼 연동과 실제 종류별 반영기 제공은 별도 작업이다. 이번 검증은 현재 사이트에서 승인 버튼까지 동작한다는 의미가 아니다.

## 결과 연결

성공한 054 응답의 `actionExecutionId`는 097의 ID다. 감사 `result.decisionId`는 055의 결정 ID다. 성공 감사의 `result`에는 다음 정보가 저장된다.

- `decision`, `revision`, `reviewStatus`, `applyStatus`
- `appliedEntityIds`, `heldItemIds`
- 실제 반영 결과가 있을 때 `targetTable`, `targetId`, `reloadResourceIds`, `summary`

`SUCCEEDED`는 결정 처리 성공이다. HOLD·REJECT도 성공 실행이며, 이 경우 반영 상태는 `NOT_APPLIED`, 반영 대상·보류 건 ID 배열은 비어 있다. APPROVE 성공일 때만 `APPLIED`다. `APPLIED`와 개별 `held_item.status=HELD`는 함께 있을 수 있다. 후보 자체의 HOLD와 개별 보류 건은 서로 다른 상태다. 매핑 APPLIED를 후속 객체 수집 완료로 해석하지 않는다.

## 실패·차단과 롤백

131은 승인 결정·반영 행·후보 갱신·성공 감사를 같은 트랜잭션에서 커밋한다. 반영 업무 오류나 커밋 실패가 나면 모두 롤백한 뒤, 별도 트랜잭션으로 실패 감사를 기록한다. 실패 전 후보가 HELD였다면 그대로 HELD이고, 기존 HOLD 결정도 남는다. 이번 실패를 위한 APPROVE 결정은 남지 않는다.

| 결과 | 049·055 | 실행 이력 |
| --- | --- | --- |
| 승인·반영 성공 | `APPROVED / APPLIED`, 승인 결정과 반영 시각 | `SUCCEEDED`, 결정 ID·반영 결과 |
| 거절·보류 성공 | `REJECTED` 또는 `HELD` / `NOT_APPLIED`, 해당 결정 | `SUCCEEDED`, 반영 ID 배열은 비어 있음 |
| 반영·저장·커밋 실패 | 실행 전 상태와 기존 결정 유지 | `FAILED`, 요청 `input`, `result.errorCode`·`reason`, 변경 내역 없음 |
| revision 충돌·이미 최종 결정·검사 조건 미충족 등 | 기존 상태 유지 | 서비스에서 기록한 `BLOCKED`, `blockedReason`, 오류 코드 |

실패 HTTP 응답의 `error.details.actionExecutionId`가 있으면 097로 직접 조회한다. 반영 업무 오류의 공개 응답 코드는 054 계약의 `APPLY_FAILED`이고, 감사에는 내부 반영 오류 코드가 보존된다. 시스템 오류는 `INTERNAL_ERROR`다. 실패 감사에는 유효한 `decisionId`를 만들어 넣지 않는다. 시각으로 결정 이력과 실패 시도를 추측해 연결하지 않는다.

실패 후 재시도가 성공하면 049는 현재 APPLIED를, 055는 커밋된 승인만, 096은 과거 실패와 새 성공을 각각 보여 준다. 과거 실패의 `input.revision`을 현재 049의 revision으로 바꾸지 않는다. 최신 감사가 중복 요청의 BLOCKED라고 해서 이미 반영된 현재 상태를 실패로 해석하지 않는다.

감사 저장 자체가 실패하면 원래 업무 오류를 유지하고 존재하지 않는 `actionExecutionId`를 반환하지 않는다. 이 경우 저장된 실행 이력은 없을 수 있다. 인증·CSRF·입력 검증처럼 서비스 진입 전 거절된 모든 요청에 결정 감사가 생기는 것도 아니다.

## 후보별 API 조회 예시

API 사용자는 기존 필터를 조합할 수 있다. 현재 프론트가 이 후보 필터를 보내도록 변경하지는 않는다.

```http
GET /api/v1/audit/actions?actionCode=DECIDE_PROPOSAL&targetType=proposal_item&targetId=123&page=0&size=20
GET /api/v1/audit/actions?actionCode=DECIDE_PROPOSAL&targetType=proposal_item&targetId=123&status=FAILED
GET /api/v1/audit/actions/456
```

096은 `requestedAt DESC, id DESC`, 055는 `decidedAt DESC, id DESC` 순이다. `targetId`는 `targetType`과 함께 보낸다. 실패만 조회하면 BLOCKED는 포함되지 않는다.

## 검증 범위

`ProposalApplyResultQueryApiTests`는 실제 PostgreSQL과 MockMvc로 054 요청이 종료된 후 별도 GET 요청을 보낸다. 테스트 전체를 자동 롤백 트랜잭션으로 감싸지 않는다. 사용자 요청에 따라 신규 검증은 핵심 3건만 둔다.

- 정상 승인 후 후보 상세·결정 이력·감사 목록/상세·실제 보류 건의 ID 연결.
- 기존 HOLD 이후 반영 업무 오류가 났을 때 이전 상태·결정 유지와 실패 감사 조회.
- 기존 HOLD 이후 실제 DB 커밋이 실패했을 때 업무 행·성공 감사 롤백과 실패 감사 조회.

현재 프론트와 같은 후보 필터 없는 기본 목록 및 기간·상태 목록도 위 시나리오 안에서 확인한다. 권한·페이지·중복·revision 등의 기존 검증은 `ProposalDecisionApiTests`, `ProposalDecisionServiceTests`, `ProposalDecisionHistoryApiTests`, `AuditExecutionApiTests`를 재사용한다. 새로 중복 작성하지 않는다.

테스트 전용 `QueryProbeApplier`는 규칙과 보류 행을 실제 DB에 생성한다. 이 값들이 커밋 또는 롤백된 후 기존 API에서 정확히 조회되는지를 검증하며, 실제 관계 계산·보류 분류 알고리즘 구현을 의미하지 않는다. 운영 반영기 미제공 상태를 성공 대역으로 바꾸지 않는다. 실제 종류별 반영기 수령 후 성공 시나리오와 관계 재조정·보류 생성은 별도 연동 검증이 필요하다.

검증 명령과 실제 결과는 이 문서의 후속 검증 기록에 남긴다. 팀원 리뷰·MR 병합·병합 후 배포 동작 확인은 별도 완료 조건이다.

## 실행 검증 — 2026-11-05

- 변경 전 `./backend/gradlew.bat -p backend build --console=plain`: 종료 코드 0, BUILD SUCCESSFUL. XML 집계 93개 suite·911건, 실패·오류·Skip 0.
- 최종 `./backend/gradlew.bat -p backend spotlessApply test --tests '*ProposalApplyResultQueryApiTests' --console=plain`: 종료 코드 0, BUILD SUCCESSFUL(43초). 신규 핵심 3건만 실행했다. JDK 21·로컬 Docker/Testcontainers PostgreSQL을 사용했다.
- 사용자 최소 검증 요청에 따라 기존 테스트와 중복되는 신규 검증을 제거하고, 전체 build는 반복하지 않았다. 최종 전체 테스트 집계로 914건 통과를 주장하지 않는다.
- 조회 API·131 실행/롤백·프론트·Migration의 변경은 없다. 독립 에이전트 코드 리뷰에서 수정이 필요한 결함은 발견되지 않았으며, 팀원 승인이나 병합 후 확인을 대신하지 않는다.
- 실제 반영기 연동, 배포 서버 UI 동작은 이번에 실행 검증하지 않았다. 최초 작업에서는 Commit·Push·원격 이슈/명세 변경을 수행하지 않았다.

## 후속 정리 — 생성자 주입

테스트의 MockMvc·JdbcClient·JsonMapper·QueryProbeApplier는 `final` 필드와 `@Autowired` 생성자로 주입한다. 테스트 시나리오와 운영 코드는 그대로 유지한다.

- 커밋 전 `spotlessApply spotlessCheck compileTestJava`: 종료 코드 0, BUILD SUCCESSFUL.
- 133 변경은 `9e3eec0`(`test: 승인 반영 결과 조회 검증 추가`)으로 커밋했다.
- 가져온 최신 `origin/develop`은 `8381018`이다. 작업 브랜치에 일반 merge로 반영했으며 충돌은 없었다.
- 병합 상태에서 `./backend/gradlew.bat -p backend spotlessCheck test --tests '*ProposalApplyResultQueryApiTests' --console=plain`: 종료 코드 0, BUILD SUCCESSFUL(1분 25초). XML 기준 3건, 실패·오류·Skip 0. 전체 build는 반복하지 않았다.
- 최신 develop과 비교한 133 변경은 테스트·문서 5개다. 운영 조회 메서드·프론트·Migration은 추가 수정하지 않았다. Push·MR 생성은 수행하지 않았다.
- 현재 JPA 우선 규칙을 확인했다. 신규 운영 Repository·SQL은 없고, 테스트 데이터 생성·롤백 유도·DB 상태 검증에만 JdbcClient를 쓴다. 규칙에는 테스트 fixture 적용 범위가 별도로 명시돼 있지 않다.
