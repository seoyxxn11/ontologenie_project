# 후보 결정·반영 연동 계약 (S15P31C106-131)

> 현재 구현과 검증 범위는 [결정 실행 구현](review-decision-execution.md)을 따른다. 이 문서는 반영 서비스와 승인 흐름 사이의 연동 계약을 설명한다.
>
> 2026-11-13(377): 개별 보류 건(`held_item`)은 366에서 만들지 않게 되었고 377에서 표·API·`heldItemIds`를 지웠다. 아래 보류 건 언급은 그 전 계약이며, 규칙이 잇지 못한 출발은 SYNC 검사가 문제로 기록한다.

기준: 2026-11-05 사용자가 전달한 주헌님의 정정 메시지와 서연님의 원천 식별 형식 확정 답변. 관계 재계산과 개별 보류 건 생성은 승인 트랜잭션 안에서 동기 실행한다. 이 문서는 종전의 승인 후 비동기 재계산·작업 예약 계약을 대체한다.

## 역할과 제공 순서

| 담당 | 책임 |
| --- | --- |
| 유림 / 131 | API 요청, 129·130 재사용, 최종 payload 선택, 상위 트랜잭션, 반영 호출, 후보 상태·감사·응답 |
| 주헌 | 코드→DB ID 조회, 정의·매핑·규칙·관계 저장, 동기 재조정·보류 건 생성, 매핑의 다시 읽기 표시, 결과·변경 정보 반환 |
| 서연 + 주헌 | 확정한 sourceId + resourceKey 형식의 코드 제공 및 저장·검사 조회 연동 확인 |
| 산이 | 공통 Action 정의·권한·감사 기반과 연결 방식 확인 |

제공 순서는 유지한다: 1단계 `OBJECT_TYPE`·`PROPERTY`·`LINK_TYPE`·`OBJECT_MAPPING`·`PROPERTY_MAPPING`, 2단계 `LINK_RULE`, 3단계 `DOCUMENT_LINK`. 실제 지원 여부는 제공 코드에서 확인한다. 없는 공용 타입을 복제하거나 프로덕션 성공 대역으로 승인 완료를 만들지 않는다.

## 원천 테이블 식별 계약

2026-11-05 서연님 답변으로 형식·예시·기존 후보 처리 방침을 확정했다. 아래 형식을 131·반영·검사 경로에 공통으로 사용한다.

| 항목 | 사용할 값 |
| --- | --- |
| 원천 시스템 | `sourceId` = `data_source.code`, 예: `ERP_MAIN` |
| 원천 표 | `resourceKey` = 해당 `source_resource.resource_key` 그대로, 형식은 `스키마.표`, 예: `erp.attendance` |
| 객체 매핑 | `payload.sourceId` + `payload.resourceKey` |
| 속성 매핑 | `payload.sourceColumn.sourceId` + `payload.sourceColumn.resourceKey`, 컬럼은 `payload.sourceColumn.column` |
| 속성 매핑의 `table` | 예전 후보와의 호환을 위해 남긴 값. 표 조회 키로 사용하지 않음 |

원천 조회는 `data_source.code`로 시스템을 찾고 그 시스템의 `source_resource.resource_key`를 정확히 비교한다. 컬럼은 찾은 자원의 `source_column.column_name`으로 조회한다. `sourceId + '.' + table`로 키를 추측해 만들지 않는다. `ERP_MAIN.attendance`는 AI가 보내는 표 식별 형식이 아니다.

서연님이 전달한 실제 저장 예시(원문 필드 유지):

```json
{
  "sourceId": "ERP_MAIN",
  "resourceKey": "erp.attendance",
  "objectTypeCode": "ATTENDANCE",
  "keyColumns": ["emp_no", "work_date"]
}
```

```json
{
  "objectTypeCode": "ATTENDANCE",
  "propertyCode": "emp_no",
  "sourceColumn": {
    "sourceId": "ERP_MAIN",
    "resourceKey": "erp.attendance",
    "table": "attendance",
    "column": "emp_no"
  },
  "transformSteps": []
}
```

- 예전 객체 매핑 후보는 그대로 사용한다. 예전 속성 매핑 후보는 `sourceColumn.resourceKey`가 없으므로 분석을 다시 실행해 새 후보를 생성하는 방침이다. 131이 과거의 불변 revision/선택지에 resourceKey를 끼워 넣거나 table을 바탕으로 자동 보정하지 않는다.
- 서연님 보고: 로컬 develop 백엔드에 저장된 속성 매핑 206건 모두 resourceKey로 원천 자원·컬럼 조회를 확인했다. 배포 서버에는 아직 분석 기록이 없어 삭제할 예전 후보가 없다고 했다. 이는 전달받은 결과이며 이번 작업에서 재현하거나 서버를 직접 조회한 사실이 아니다. 데이터 삭제 작업도 수행하지 않는다.
- `CandidateCheckRepository.loadBasis`는 속성 매핑의 `sourceColumn.sourceId`와 `sourceColumn.resourceKey`를 직접 조회한다. revision·선택지·같은 분석의 매핑 후보에 대한 회귀 테스트가 있다. AI의 `ColumnRef` 변경은 131 worktree에 가져오지 않았으므로 실제 분석 생성부터의 연동은 별도 검증이 필요하다.
- 기존 `ProposalApplyPayloadResolver`는 Map 내용을 그대로 전달하므로 필드 추가만으로 변경할 필요는 없다. 주헌님의 코드→ID 조회가 새 필드를 사용하는지 실제 제공 코드와 통합 검증한다.

## 수정된 반영 호출 계약

호출: `ProposalApplyService.apply(ApplyCommand)`.

```text
ApplyCommand(proposalItemId, itemKind, origin, payload, decidedBy)
ApplyResult(targetTable, targetId, reloadResourceIds, summary, changes)
```

실제 패키지·Java 타입·빈 값 표현은 팀원이 제공하는 인터페이스와 record를 따른다. 131은 기존 합의대로 반영 서비스가 호출자의 트랜잭션에 참여하는지(MANDATORY) 확인한다.

### 입력

| 필드 | 131이 전달할 값 |
| --- | --- |
| `proposalItemId` | 검증·잠금한 후보 ID |
| `itemKind` | 서버가 읽은 후보 종류 |
| `origin` | 서버가 읽은 후보의 `AI` 또는 `MANUAL`. 승인 데이터의 출처 값 변환은 반영 서비스 책임 |
| `payload` | 131이 고른 불변 revision 또는 같은 후보·revision의 선택지 내용 그대로 |
| `decidedBy` | 세션의 `LoginAccount.id()`. 공개 요청 본문에서 받지 않음 |

이전 입력의 `revision`·`analysisRunId`·`accountId`는 사용하지 않는다. 단, 요청 revision 검증·선택지 소속 검사·결정 이력·감사의 revision 추적은 그대로 필요하다. 후보의 `analysisRunId` 필드를 삭제한다는 뜻도 아니다.

### 결과

| 필드 | 의미와 사용처 |
| --- | --- |
| `targetTable`, `targetId` | 실제 반영 대상. API 054의 `appliedEntityIds`는 현행 명세 형식에 맞춰 이 값에서 구성 |
| `reloadResourceIds` | 매핑 반영으로 다시 읽도록 표시한 원천 자원. 객체 생성·갱신은 이후 배치 수집이 수행 |
| `summary` | 연결 유지·비활성·신규 수 등의 감사 요약. `AuditEntry.result`에 반영 |
| `changes` | 감사용 `List<ChangeEntry>`. 변형·중복 기록 없이 131의 이력 INSERT·후보 UPDATE 변경 목록과 함께 `AuditEntry.changes`에 포함 |

기존 결과의 `itemKind`·`followUps`는 사용하지 않는다. 후보 종류는 잠긴 후보에서 읽는다. 새 내부 결과 필드를 공개 API에 임의로 추가하지 않는다.

## 승인 트랜잭션

```text
129 검증·후보 잠금 + 130 결정 이력 저장
→ 최종 payload 조회
→ apply: 정의·매핑·규칙 저장
   · LINK_RULE: 관계 생성·재조정 + 개별 보류 건 생성까지 동기 완료
   · 매핑: 원천 자원 다시 읽기 표시까지 저장
→ approve(Instant.now(clock))
→ 성공 감사 + 필요한 flush
→ 커밋 후 HTTP 200
```

- 130의 `ProposalDecisionHistoryService.recordValidated`는 내부에서 129를 호출하고 `RecordedDecision(decisionId, basisCheckRunId)`를 반환한다. 승인 완료 결과가 아니며 검증기를 다시 호출할 필요도 없다. 139는 검증에서 선택한 검사 번호를 감사에 그대로 전달한다([검사 근거 연결](review-audit-link.md)).
- 같은 트랜잭션의 관리 대상 후보를 사용하고 후보 잠금은 커밋까지 유지한다. `ProposalApplyPayloadResolver`와 반영 서비스는 호출자 트랜잭션을 벗어나지 않는다.
- DB 설계 U6 및 `object_link.status` 주석의 동기 재조정 기준을 따른다. 관계 재계산 작업 표·큐·커밋 후 작업 예약을 131에서 새로 만들지 않는다.
- 관계 규칙의 `APPLIED`는 규칙 저장·관계 재조정·해당 보류 건 생성까지 성공한 상태다.
- 매핑의 `APPLIED`는 매핑 저장·다시 읽기 표시 완료다. 배치 수집으로 실제 객체가 갱신됐다는 뜻이 아니다. 이 배치는 관계 규칙의 동기 재조정과 구분한다.
- `REJECT`·`HOLD`는 반영 서비스를 호출하지 않고 결정 이력·후보 상태·감사만 같은 트랜잭션으로 저장한다.

## 실패·감사·오류 응답

- 반영 서비스 예외로 트랜잭션이 rollback-only가 될 수 있다. 같은 트랜잭션 안에서 잡아 계속 처리하거나 성공 값을 반환하지 않는다.
- 상위 결정 서비스가 `TransactionTemplate` 등으로 실제 커밋까지 감싼다. 예외는 업무 트랜잭션 밖에서 처리하고, 롤백이 끝난 뒤 `AuditRecorder.recordSeparately`로 차단·실패 감사를 남긴다.
- 정의·매핑·규칙·관계·보류 건·다시 읽기 표시·결정 이력·후보 상태·성공 감사는 함께 롤백한다. HELD 후보는 실패 후에도 HELD다.
- 성공은 `record`, 검증 차단은 `BLOCKED`, 실제 반영 중 실패는 `FAILED` 감사로 구분한다. 업무 롤백 뒤 후보의 `apply_status=FAILED`를 별도 저장하는 정책은 추가하지 않는다.
- 감사는 공통 `AuditRecorder`만 사용한다. 기존 `DECIDE_PROPOSAL`·`PROPOSAL_DECIDE`, `ACTION`·`USER`·`WEB` 계약을 재사용하고 실제 권한 검사와 기록을 맞춘다.
- 성공 감사는 후보 ID·결정 ID·revision·결정 사유와 연결한다. 결과에 반영 대상·보류 ID·다시 읽기 자원·`summary`를 연결해 133에서 추적 가능하게 한다.
- 실패 감사에는 롤백된 변경을 커밋된 것처럼 `changes`로 남기지 않는다. 실패 감사 ID가 실제 생성됐을 때만 오류 details에 연결하며, 감사 저장 실패가 원래 오류를 가리지 않도록 한다.
- 주헌님 내부 예외 계약은 기존 `BusinessException`의 400(형식), 409(중복·충돌), 422(반영 선행 조건)다. 예외를 삼키거나 일괄 500으로 바꾸지 않는다.
- Controller는 `ApplyErrorCode` 업무 예외를 API 054의 `422 APPLY_FAILED`로 변환하고 `reason`과 실제 저장된 실패 감사 ID를 연결한다. 서비스 내부 오류 코드와 details는 보존한다. 129 검증 오류와 시스템 오류는 이 변환 대상에 포함하지 않는다.

## 이미 구현한 도메인·조회 계약

- `ProposalItem.approve(appliedAt)`는 PENDING·HELD 후보를 APPROVED/APPLIED로 바꾸며 반영 시각이 필수다. 실제 반영 성공 뒤에만 호출한다.
- `reject()`·`hold()`는 REJECTED·HELD 및 NOT_APPLIED로 바꾸고 반영 시각을 지운다. 반복 HOLD와 HELD 이후 최종 결정은 허용한다.
- 세 결정 모두 이전 `applyError`를 지운다. APPROVED·REJECTED 이후 재결정은 `PROPOSAL_ALREADY_DECIDED`로 거절한다. revision·payload·evidence·선택지를 변경하지 않는다.
- `ProposalApplyPayloadResolver.resolve(lockedItem, selectedOptionId)`는 MANDATORY다. 선택지가 없으면 현재 `proposal_revision.payload`, 있으면 같은 후보·revision의 `proposal_option.payload`를 읽는다.
- 원천 식별값·JSON null을 그대로 보존하며 원천 코드→ID 조회는 주헌님 서비스가 맡는다. 잘못된 선택지는 `VALIDATION_FAILED`와 `field=selectedOptionId`, 불변 revision 누락은 저장 데이터 불일치 예외로 처리한다.
- 129는 관리자 권한, 후보 존재·최종 상태·revision·선택지를 검사한다. APPROVE만 같은 revision의 성공한 CANDIDATE 검사와 실제 결과를 요구한다(후보 검사 대상인 LINK_RULE만, 338 후속). REJECT·HOLD에는 그 조건을 적용하지 않는다.
- 최근 실패 검사가 있다는 이유로 같은 revision의 기존 성공 검사를 임의로 무효화하거나 AI 신뢰도·매칭률 승인 임계값을 추가하지 않는다.

## API 054와 133 조회 주의사항

- `POST /api/v1/proposals/{id}/decisions`의 기존 세션·CSRF·임시 비밀번호 제한을 유지한다. `ProposalDecisionRequest`의 revision·decision·selectedOptionId·reason과 기존 입력 제한을 재사용한다.
- API 055 이력 응답과 별도의 실행 결과 DTO를 둔다. 성공은 `ApiResponse.data`, 오류는 공통 오류 형식을 따른다.
- 기존 성공 필드 `decisionId`, `decision`, `reviewStatus`, `applyStatus`, `appliedEntityIds`, `actionExecutionId`를 명세와 대조한다.
- 보류 건·정의·객체 매핑·규칙은 `proposal_item_id`로 추적할 수 있다. `property_source_mapping`에는 이 컬럼이 없어 속성 매핑 결과는 감사 기록으로 추적한다.
- 133에서 직접 조회용 컬럼이 꼭 필요하면 주헌님과 별도 협의한다. 이번 131 문서 갱신으로 컬럼·Migration을 추가하지 않는다. 131의 감사 연결을 139가 중복 저장하지 않도록 책임을 구분한다.

## 남은 통합 검증

- 주헌님의 종류별 반영기가 제공되면 실제 정의·매핑·규칙·관계 저장과 동기 재조정·보류 생성·다시 읽기 표시의 성공 및 전체 롤백을 검증한다. 현재 운영 반영기 구현체는 없다.
- AI가 생성한 `sourceColumn.resourceKey`가 실제 저장·검사·반영 경로에서 사용되는지 분석 생성부터 확인한다. 이전 속성 매핑은 재분석 방침을 따른다.
- 팀원 리뷰와 병합 후 동작 확인은 별도 완료 조건이다.
- 구현 위치·실행 명령·검증 결과는 [결정 실행 구현](review-decision-execution.md)에 기록한다.
