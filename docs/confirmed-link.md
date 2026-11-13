# 확정 연결 저장 (Jira 329, FN-ONT-05)

관리자가 확정한 연결을 `object_link`에 저장하는 내부 서비스(`ConfirmedLinkService`)다. 화면·외부 API는 없다. 규칙 연결(origin=RULE)은 연결 재계산(`docs/link-recompute.md`)이 맡고, 여기서는 나머지 두 출처를 맡는다.

| 메서드 | origin | 부르는 곳 |
|---|---|---|
| `saveManual(ConfirmedLinkRequest)` | MANUAL | 관리자가 문제(issue)에서 정한 수동 연결(API 058, 238·366) |
| `saveDocument(ConfirmedLinkRequest)` | DOCUMENT | DOCUMENT_LINK 후보 반영기(`docs/proposal-apply.md`) |

- 부르는 쪽 트랜잭션 안에서만 돈다(`MANDATORY`). 감사 기록은 부르는 쪽이 같은 트랜잭션에서 한다.
- 요청: 관계 유형·출발·도착 객체 ID, 승인자(`confirmed_by`), 근거 후보(`proposal_item_id`, 없으면 null), 근거(`evidence`).
- 결과 `SavedLink`: 연결 ID, `outcome`(CREATED·REACTIVATED·ALREADY_LINKED), 되살린 행의 이전 출처, 감사 `changes`에 넣을 전후 내용(`change`, ALREADY_LINKED면 null).

## 순서

1. 같은 객체끼리면 거절한다.
2. 양 끝 객체를 ID 순서로 **공유 잠금**(`for share`)하며 읽고 존재·유형·연결 대상인지(387, `LinkableObjects`: ACTIVE이고 꺼진 매핑에서 오지 않음, 검사·재계산과 같은 정의)를 본다.
3. 규칙 재계산과 **같은 관계 유형 잠금**(`pg_advisory_xact_lock(hashtext('link:type:{id}'))`, `RuleLinkRepository.lockLinkType`)을 잡는다. 잡은 뒤 양 끝을 다시 읽는다. 매핑 끄기(036)는 매핑을 바꾼 뒤 같은 잠금을 잡고 재계산하므로, 기다리는 사이 끝난 매핑 끄기를 보고 거절한다. 이쪽이 먼저 잠그면 매핑 끄기의 재계산이 이 연결을 보고 끈다.
4. 쌍(관계 유형·출발·도착)의 기존 행을 본다.
5. MANUAL이면 규칙이 이미 그 출발을 이었는지 보고(이었으면 거절), 저장 당시 출발 비교 속성 값을 근거에 더한다(366, 아래 "수동 연결").
6. 관계 수 제약을 검사한다.
7. 새로 넣거나 비활성 행을 되살린다.

잠금 순서(객체 → 관계 유형)는 수집과 맞춘 것이다. 수집(197·198)은 객체를 고친 뒤 마지막에 관계 유형을 잠근다. 반대로 잡으면 "수집은 객체를 잡고 관계 유형을 기다리고, 저장은 관계 유형을 잡고 객체를 기다리는" 교착이 난다. 객체 공유 잠금 덕분에 수집이 그 객체를 원천 삭제로 바꾸는 중이면 그 커밋을 기다린 뒤 바뀐 상태를 보고 거절한다.

## 쌍의 기존 행

`uq_object_link` 때문에 쌍마다 행이 하나다.

| 기존 행 | MANUAL 저장 | DOCUMENT 저장 |
|---|---|---|
| 없음 | 새로 넣음(CREATED) | 새로 넣음(CREATED) |
| 비활성(RULE·MANUAL·DOCUMENT) | 그 행을 MANUAL로 되살림(REACTIVATED). 규칙 연결이었으면 근거 규칙을 뗀다 | 그 행을 DOCUMENT로 되살림 |
| 활성 DOCUMENT | `LINK_ALREADY_EXISTS`(409) | 그대로 두고 성공(ALREADY_LINKED) |
| 활성 RULE·MANUAL | `LINK_ALREADY_EXISTS`(409) | `LINK_ALREADY_EXISTS`(409) |

- 비활성 규칙 연결을 확정 연결로 바꾸는 것은 관리자 확정이 우선이기 때문이다(2026-11-10 결정). DOCUMENT로 바꾼 행은 규칙 재계산이 건드리지 않는다(결정 U6). MANUAL로 바꾼 행은 아래 "수동 연결" 조건으로 재계산이 끄거나 규칙 연결로 되돌린다(366).
- 같은 메모가 같은 설비를 두 번 언급한 문서 연결은 실패로 보지 않는다(2026-11-10 결정). 근거는 첫 언급만 남는다.
- 409의 `details`에는 기존 연결 ID(`linkId`)와 출처(`origin`)가 있다. 238은 이걸로 "이미 연결됨"을 안내할 수 있다.

## 검증과 오류

| 경우 | 코드 | 상태 |
|---|---|---|
| 없는 관계 유형·객체 | `RESOURCE_NOT_FOUND`(`resource`=link_type·object_instance) | 404 |
| 같은 객체끼리 | `LINK_SELF_REFERENCE` | 422 |
| 객체 유형이 관계 유형의 출발·도착 유형과 다름 | `LINK_END_TYPE_MISMATCH`(`end`, 기대·실제 유형 ID) | 422 |
| 원천에서 삭제됐거나 꺼진 매핑에서 온 객체 | `LINK_END_NOT_ACTIVE`(`end`) | 422 |
| 관계 수 초과 | `LINK_CARDINALITY_EXCEEDED`(`cardinality`, `end`) | 422 |
| 문서 근거의 `expression_kind`가 OBSERVED·ESTIMATED가 아님 | `LINK_EVIDENCE_INVALID` | 400 |
| 같은 쌍의 활성 연결 | `LINK_ALREADY_EXISTS`(`linkId`, `origin`) | 409 |
| (MANUAL) 규칙이 이미 그 출발을 이음(도착이 활성인 규칙 연결) | `LINK_SOURCE_LINKED_BY_RULE`(`sourceObjectId`, `linkTypeId`) | 409 |

관계 수 제약은 **모든 출처의 활성 연결**로 센다. 반대쪽 끝이 원천에서 삭제된 연결은 세지 않는다.

- 도착이 ONE(ONE_TO_ONE·MANY_TO_ONE): 출발에서 이미 나가는 활성 연결이 있으면 거절
- 출발이 ONE(ONE_TO_ONE·ONE_TO_MANY): 도착으로 이미 들어오는 활성 연결이 있으면 거절

## 수동 연결 (366)

수동 연결은 규칙이 잇지 못하는 출발에 관리자가 정한 예외다. 승인 당시의 값에 대해서만 믿는다.

- **규칙이 이미 이은 출발은 받지 않는다**(`LINK_SOURCE_LINKED_BY_RULE`). 받아도 재계산이 곧 끈다.
- **저장 당시 값 기록:** 근거에 `sourceValues` {속성 코드: 값}을 더한다(`LinkRecomputeService.SOURCE_VALUES`). 속성은 그 관계 유형의 반영 중인 규칙들이 출발 쪽에서 비교하는 속성이다. 반영 중인 규칙이나 비교 속성이 없으면 더하지 않는다.
- **재계산이 끄는 경우**(`docs/link-recompute.md`): 저장 당시 값이 바뀜, 양 끝 중 하나가 원천에서 삭제되거나 매핑이 꺼짐, 규칙이 그 출발을 다른 도착과 이음. 규칙이 같은 쌍을 이으면 규칙 연결로 바뀐다.
- **검사:** 출발에 활성 수동 연결이 있으면 SYNC·재검증이 그 행을 정상으로 본다(`docs/check-run.md` "수동 연결"). 그래서 그 행의 문제는 재검증 PASSED로 해결할 수 있다.

## 동시 실행

규칙 재계산(181)과 같은 관계 유형 잠금을 잡으므로, 같은 쌍을 동시에 넣으려 해도 한쪽이 기다렸다가 먼저 커밋된 행을 보고 판단한다. `uq_object_link` 위반으로 끝나지 않는다(`ConfirmedLinkServiceTests`의 동시 실행 테스트).

## 저장 방식

`object_instance`·`object_link`는 아직 Entity가 없는 테이블이라 JdbcClient(`ConfirmedLinkRepository`)로 다룬다. 규칙 연결(`RuleLinkRepository`)과 같은 방식이다(`.ai/rules/database.md`). 관계 유형은 JPA Repository로 읽는다. `object_link` Entity를 둘지는 담당 도메인과 정한다.

## 한계

- 문서(DOCUMENT) 연결의 비활성화(예: 끝 객체가 원천에서 삭제됨)는 아직 없다. 조회 쪽이 끝 객체 상태를 함께 봐야 한다. 재계산은 관계 수를 셀 때 문서 연결을 보지 않는다.
- 객체 공유 잠금과 수집의 객체 수정이 서로 다른 순서로 두 객체를 잡으면 PostgreSQL이 교착을 감지해 한쪽을 실패시킬 수 있다. 저장 쪽이 실패하면 다시 시도하면 된다.
