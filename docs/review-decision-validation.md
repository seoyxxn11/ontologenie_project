# 후보 결정 조건 검증 (S15P31C106-129)

API 054의 승인·거절·보류 요청을 검증하는 모듈이다. 현재 구현은 HTTP API나 실제 결정 처리를 제공하지 않는다.

## 호출 계약

- `ProposalDecisionRequest`: `revision`(1 이상), `decision`(`APPROVE`·`REJECT`·`HOLD`), 선택적인 `selectedOptionId`(1 이상), `reason`(공백 불가·최대 1000자).
- Controller에서 경로 ID의 최소값과 요청의 `@Valid` 검증을 수행한다. 검사 ID는 요청으로 받지 않는다.
- `ProposalDecisionValidator.validate(id, request, account)`는 호출자가 시작한 트랜잭션이 반드시 필요하다(`MANDATORY`).
- 관리자 권한 확인 후 `ProposalItemRepository.findForUpdate`로 후보를 잠근다. 반환한 후보는 같은 트랜잭션의 관리 대상 Entity다.
- 호출자는 이 트랜잭션이 끝나기 전에 130번 결정 이력 저장, 131번 반영·감사를 연결한다. 검증 후 트랜잭션을 끝내고 별도로 상태를 갱신하지 않는다.

## 검증

- 이미 `APPROVED`·`REJECTED`인 후보는 `409 PROPOSAL_ALREADY_DECIDED`.
- 요청 revision과 현재 revision이 다르면 `409 REVISION_CONFLICT`.
- 선택지가 있으면 같은 후보·revision에 속해야 한다. 아니면 `400 VALIDATION_FAILED` (`selectedOptionId`).
- `APPROVE`만 같은 후보·revision의 성공한 `CANDIDATE` 검사 실행과 실제 `check_result`를 요구한다. 없으면 `422 REVIEW_VALIDATION_FAILED`.
- 오류 details에는 현재 revision을 넣고, 같은 revision의 최근 검사가 있으면 ID와 상태도 넣는다.
- `REJECT`·`HOLD`에는 승인용 성공 검사 조건을 적용하지 않는다.
- 검증은 후보 상태·결정 이력·연결·감사 데이터를 변경하거나 재검사를 접수하지 않는다.

## 검사 조회의 범위

화면용 `ProposalLatestCheckRepository`는 가장 최근 실행의 상태를 보여준다. 승인용 `ProposalDecisionCheckRepository`는 명세대로 같은 revision의 성공 검사 존재 여부를 확인한다. 더 최근의 실패·진행 중 실행만으로 같은 revision의 이전 성공 결과를 배제하지 않는다.

검사 실행의 `SUCCEEDED`와 모든 데이터 행의 정상 매칭은 다르다. 이 모듈은 미연결·중복·조건 위반 건수나 매칭률로 임의의 승인 임계값을 만들지 않는다. 원천 데이터 기준 변경에 따른 별도 검사 무효화 정책도 추가하지 않는다.

## 남은 연동

`POST /api/v1/proposals/{id}/decisions`는 130·131번의 저장·반영·감사와 연결한 뒤 연다. 검증만 성공한 상태를 승인 완료로 응답하지 않는다. 실제 API의 400·401·403·404·409·422 응답, 중복 승인 차단 및 반영 실패 롤백은 그 연동 단계에서 통합 검증해야 한다. 후보 행 잠금 유지와 외부 트랜잭션 필수 조건은 현재 모듈의 DB 통합 테스트로 검증한다.

DB 스키마·API 계약 변경은 없다. 기준: Notion API 054 (`405bdf14d2d8813dbde3d11097ddd5df`).
