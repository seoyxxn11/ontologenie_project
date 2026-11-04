# 개별 보류 건 조회 API — S15P31C106-132

## 범위와 권한

FN-REV-05·07의 개별 보류 건 목록·상세 조회를 제공한다. 두 API 모두 세션 인증과 ADMIN 권한이 필요하며, 임시 비밀번호 제한은 기존 보안 체인을 따른다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 056 | `GET /api/v1/held-items` | `data` 배열과 `meta.page/size/totalElements/hasNext` |
| 057 | `GET /api/v1/held-items/{id}` | `data` 객체. `id`는 1 이상 |

저장된 `held_item`을 읽기 전용으로 조회한다. 원천 DB 접속, 규칙 재판정, 자동 해소, 수동 승인·종결, 정합성 문제(issue)의 상태 변경은 수행하지 않는다. 후보의 검토 상태와 개별 보류 건 상태는 별개다.

## 목록 조건과 기본값

| 조건 | 검증 | 생략 시 |
| --- | --- | --- |
| `status` | `HELD`, `APPROVED`, `AUTO_RESOLVED`, `DISMISSED` 중 하나 | 모든 상태 포함 |
| `linkTypeId` | 1 이상 | 관계 유형 제한 없음 |
| `page` | 0 이상 | 0 |
| `size` | 1 이상 | 20 |

정렬은 ID 내림차순이다. 페이지 0·크기 20·ID 내림차순은 기존 제안 조회에 맞춘 **구현 기본값**이며 Notion 056에 명시된 계약은 아니다. FE 담당자와 공유하거나 명세에 반영할 필요가 있다. 현재 명세에 size 상한은 없어 별도 상한을 추가하지 않았다.

## 공통 응답 데이터

목록과 상세는 같은 `HeldItemResponse`를 사용한다. ID는 JSON 숫자, 시각은 UTC ISO 8601 문자열이다. nullable 필드는 명시적으로 `null`을 반환한다.

| 정보 | 필드 |
| --- | --- |
| 식별·근거 참조 | `id`, `proposalItemId`, `linkMappingId`, `sourceObjectId`, `linkTypeId` |
| 보류 근거 | `rawValue`, `transformedValue`, `holdReason` |
| 상태·처리 결과 | `status`, `resolvedLinkId`, `resolvedSyncRunId`, `resolvedBy`, `resolvedAt`, `resolutionNote` |
| 생성 시각 | `createdAt` |

후보 ID와 규칙 ID는 적어도 하나가 있으며, 둘 다 있는 경우도 그대로 반환한다. `HELD`는 종료 시각이 없고, 수동 `APPROVED`·`DISMISSED`에는 처리자와 종료 시각이 있다. `AUTO_RESOLVED`에는 확정 연결·수집 ID·종료 시각이 있고 처리자는 `null`이다.

## 오류와 검증

입력 오류는 400, 미인증은 401, 권한 부족은 403, 없는 상세는 404(`RESOURCE_NOT_FOUND`, `resource=held_item`)다. 검색 결과가 없으면 200과 빈 배열을 반환한다.

`HeldItemControllerTests`는 인증·권한·입력·페이지 요청을 검증한다. `HeldItemApiIntegrationTests`는 PostgreSQL에서 필터·페이지·네 가지 상태·nullable 응답·조회 무변경을 검증한다. 건수 검증은 테스트 전용 관계 유형으로 범위를 제한하고, 다른 관계 유형의 데이터도 함께 넣어 격리를 확인한다. 테스트 데이터는 Testcontainers에서 생성하고 롤백한다.

DB 스키마·의존성·공통 인증 구조 변경은 없다. 실제 수집·승인 데이터 및 FE 화면 연동, 130·131번 저장·반영 흐름은 별도 검증 대상이다.

## 기준

- [API 056](https://app.notion.com/p/405bdf14d2d881409c14f81a69deddfa)
- [API 057](https://app.notion.com/p/405bdf14d2d881b69e32f435ed529bfb)
- 로컬 스키마: `V2__create_operations.sql`의 `held_item`.
