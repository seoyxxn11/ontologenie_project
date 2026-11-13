# 연결 규칙 목록·개정 이력·개정본 상세 — S15P31C106-186

FN-RUL-01. 승인된 연결 규칙(`link_mapping`)이 시간에 따라 어떻게 바뀌었는지 보여 준다. 읽기만 한다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 규칙 목록(375, 명세 번호 미정) | `GET /api/v1/ontology/rules` | 규칙 코드마다 최신 개정본 요약 배열(코드 순, `meta` 없음) |
| 046 | `GET /api/v1/ontology/rules/{code}/revisions` | 그 코드의 개정본 배열(개정 번호 오름차순)과 `meta` |
| 047 | `GET /api/v1/ontology/rules/{code}/revisions/{revision}` | 개정본 하나 |

모두 ADMIN만 쓸 수 있다(`RuleRevisionController`).

## 개정본을 누가 만드나

- 개정본은 만든 뒤 내용을 바꾸지 않는다(DB 트리거). 비교 항목(`link_mapping_condition`)도 개정본을 만든 트랜잭션에서만 넣는다.
- 규칙 후보를 승인하면 규칙 반영(189 `LinkRuleApplier`, [proposal-apply.md](proposal-apply.md))이 같은 코드의 ACTIVE 개정본을 RETIRED로 바꾸고(`retired_at`) 다음 번호의 새 개정본을 ACTIVE로 만든다. 코드마다 ACTIVE는 하나다(`uq_link_mapping_active`).
- 그래서 이력은 같은 코드의 개정본을 개정 번호 순으로 보여 주면 된다. 이전 개정본은 지우지 않고 RETIRED로 남는다.

## 응답 (RuleRevision)

`id`, `code`, `revision`, `linkTypeId`, `linkTypeName`, `status`(ACTIVE·RETIRED), `conditions[]`, `sourceFilter`, `targetFilter`, `retiredAt`, `description`, `createdAt`, `createdBy`, `origin`(AI_PROPOSAL·MANUAL·SEED), `proposalItemId`.

- `conditions[]`: `sequenceNo` 순. `sourcePropertyId`, `sourcePropertyName`, `targetPropertyId`, `targetPropertyName`, `operator`(EQ), `sourceTransforms`·`targetTransforms`(041 형식, 없으면 빈 배열). 항목끼리는 AND.
- `linkTypeName`·`sourcePropertyName`·`targetPropertyName`은 개정 당시가 아니라 지금 이름이다(386). 화면이 번호 대신 이름을 보여 주는 데 쓴다. 개정본이 가리키는 ID는 그대로라 이력은 바뀌지 않는다. 명세 046·047에는 아직 없는 칸이다.
- 비어 있을 수 있는 칸(`retiredAt`, `description`, `createdBy`, `proposalItemId`)도 `null`로 내보낸다(명세 예시와 같음).

## 규칙 목록

정의·규칙 이력 화면(`frontend/src/features/history`)이 규칙을 고를 때 쓴다. 규칙 수는 관계 유형 수 정도라 페이지를 나누지 않는다.

- 한 줄: `code`, `revision`(최신 개정 번호), `linkTypeId`, `linkTypeName`(지금 이름), `status`(최신 개정본의 상태), `description`.
- 조건: `linkTypeId`(1 이상), `status`(ACTIVE·RETIRED). 비우면 거르지 않는다. RETIRED는 최신 개정본이 폐기된 규칙, 곧 대체 없이 폐기된 규칙만 나온다.
- 관계 유형마다 반영 중인 규칙은 하나라(367, V17) 같은 관계 유형에 ACTIVE는 하나, 나머지는 RETIRED다.
- JPA 쿼리(`LinkMappingRepository.findLatestRevisions`, 코드별 최대 개정 번호)와 관계 유형 이름 한 번 조회로 만든다.

## 조건과 오류

- 046은 명세에 조회 파라미터가 없지만 응답에 `meta`가 있어 다른 목록처럼 `page`(기본 0)·`size`(기본 20, 최대 100)를 받는다.
- 그 코드의 개정본이 하나도 없으면 046도 404다(`RESOURCE_NOT_FOUND`, `resource=link_mapping`, `code`). 047은 없는 번호면 404(`revision` 포함).
- `revision`은 1 이상(아니면 400). 개정 번호 칸이 integer라 그보다 큰 번호는 404다.
- 401(미인증), 403(ADMIN 아님).

## 조회 방식

`LinkMapping`·`LinkMappingCondition` Entity와 Spring Data JPA로 읽는다(`RuleRevisionService`). 046은 쪽의 개정본들의 비교 항목을 한 번에 읽어 개정본마다 나눈다. 관계 유형·속성 이름도 쪽마다 한 번씩 모아 읽는다(`findAllById`).

## 시험

`RuleRevisionApiTests`(실제 보안 설정과 PostgreSQL):

- 개정본 두 개(1판 RETIRED, 2판 ACTIVE) → 번호 순, 상태·퇴역 시각·출처·필터·설명·생성자, 비교 항목 순서와 변환 단계, 관계 유형·속성 이름, 쪽 나누기
- 047: 1판·2판 각각, 비교 항목 수, 이름을 바꾼 뒤 지금 이름이 나옴
- 없는 코드·없는 번호·integer보다 큰 번호 404, `revision` 0·`size` 101·`page` -1은 400
- 401, 403
- 규칙 목록: 같은 관계 유형에 "개정한 규칙(1판 RETIRED·2판 ACTIVE)"과 "대체 없이 폐기된 규칙" → 코드 순 두 줄, 최신 개정·상태·설명·관계 유형 이름, `status` 조건, `linkTypeId` 0·잘못된 `status` 400, 401, 403

## 기준

- Notion API 046·047. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed/046.md`, `047.md`.
- 스키마: `V1__create_ontology_core.sql`의 `link_mapping`, `link_mapping_condition`.
