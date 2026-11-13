# 원천 매핑 저장 서비스 — S15P31C106-182·183

## 범위와 권한

객체 원천 매핑(어느 원천 테이블의 행이 어떤 객체가 되는지)과 속성 원천 매핑(어느 컬럼 값이 어떤 속성이 되는지)을 관리자가 직접 조회·등록·수정한다. 모든 API는 세션 인증과 ADMIN 권한이 필요하다.

| API | 요청 | 서비스 메서드 |
| --- | --- | --- |
| 034 | `GET /api/v1/ontology/object-mappings` | `ObjectMappingService.search` |
| 035 | `POST /api/v1/ontology/object-mappings` | `ObjectMappingService.create` |
| 036 | `PATCH /api/v1/ontology/object-mappings/{id}` | `ObjectMappingService.update` |
| 037 | `GET /api/v1/ontology/property-mappings` | `PropertyMappingService.search` |
| 038 | `POST /api/v1/ontology/property-mappings` | `PropertyMappingService.create` |
| 039 | `PATCH /api/v1/ontology/property-mappings/{id}` | `PropertyMappingService.update` |
| 101 | `DELETE /api/v1/ontology/property-mappings/{id}` | `PropertyMappingService.delete` |

기준 명세는 Notion API 034~039·101이다. 노션 반영 전 완성본은 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed_182`·`api_fixed_183`에 있다.

## 쓰기 규칙

- 등록·수정·삭제는 대상 `source_resource` 행을 비관적 잠금으로 잡는다. 원천 자원이 바뀌는 수정은 전·후 두 자원을 id 순서로 잠근다.
- 수정은 요청 `rowVersion`이 현재 값과 다르면 `409 VERSION_CONFLICT`다.
- 객체 매핑의 `rowFilter`, 속성 매핑의 `sourcePath`는 아직 받지 않는다(보내면 400). 형식이 정해지면 연다.
- 객체가 만들어진 객체 매핑은 원천 자원·객체 유형·키 컬럼을 바꿀 수 없다(`409 MAPPING_IDENTITY_LOCKED`).
- 같은 원천 자원·객체 유형에 켜진 객체 매핑은 하나다. 한 객체 매핑 안에서 같은 속성은 한 번만 매핑한다(둘 다 409).
- 속성 매핑은 같은 원천 자원에 지금 있는 컬럼(`is_present=true`)만, 소속 객체 매핑의 객체 유형 속성에만 연결한다.
- 변환 단계(`transformSteps`)는 041 함수 목록으로 검사한다. 함수·인자 오류는 400, 앞 단계 결과를 다음 단계가 받을 수 없거나 마지막 결과를 속성 자료형에 넣을 수 없으면 422다. 문자열 결과는 어느 속성에나, 정수 결과는 소수 속성에도 넣을 수 있다.
- 정의 변경 로그(`action_execution`·`entity_change`)는 아직 남기지 않는다. Action 실행 엔진이 생긴 뒤 붙인다.

## 다시 적재와 `reloadReason`

매핑이 바뀌면 같은 트랜잭션에서 그 원천 자원의 `loaded_lsn`을 비워 다시 읽기를 요청한다(`SourceCatalogService.requireFullReload`). 다음 수집이 테이블을 처음부터 읽어 저장된 객체와 비교한다. 그동안에도 CDC 이벤트는 계속 반영하고, 겹치는 객체는 전체 읽기의 스냅샷으로 판단한다(V11, [개발 가이드](development-guide.md) 원천 연동). 소속 객체 매핑이 멈춰 있으면 비우지 않는다. 다시 켤 때 비운다.

매핑을 끄면(036 `isActive=false`) 그 매핑의 객체는 ACTIVE로 남지만 연결 대상에서 빠진다(387, `LinkableObjects`). 같은 트랜잭션에서 그 객체 유형의 관계 유형 연결을 다시 계산해, 그 객체에 걸린 규칙·수동 연결을 끈다([연결 재계산](link-recompute.md)). 다시 켜면 다시 읽기가 끝난 뒤 수집의 재계산이 연결을 되살린다.

036·038·039·101 응답의 `reloadRequired`가 true면 `reloadReason`에 아래 값 중 하나가 온다. false면 `null`이다. Java 타입은 `mapping.domain.ReloadReason` enum이고, JSON에는 이름 문자열로 나간다. 035 응답에는 두 필드가 없다(켜진 매핑 등록은 항상 다시 읽는다).

| 값 | API | 경우 |
| --- | --- | --- |
| `IDENTITY_CHANGED` | 036 | 객체가 없는 매핑의 원천 자원·유형·키를 바꿈 |
| `REACTIVATED` | 036 | 멈춘 매핑을 다시 켬 |
| `PROPERTY_MAPPING_ADDED` | 038 | 켜진 객체 매핑에 속성 매핑을 더함 |
| `PROPERTY_MAPPING_CHANGED` | 039 | 속성 매핑을 바꿈. 다른 객체 매핑으로 옮기면 전·후 중 켜진 쪽 테이블을 모두 다시 읽음 |
| `PROPERTY_MAPPING_DELETED` | 101 | 속성 매핑을 지움 |

039에서 보낸 값이 지금과 같으면 아무것도 바꾸지 않는다(`rowVersion` 그대로, `reloadRequired=false`).

## 다른 작업에서 맞출 것

### 매핑 화면 (228)

- `reloadReason`은 문장이 아니라 위 표의 코드다. 화면 문구는 FE가 코드별로 정한다. Swagger 응답 스키마에 허용 값이 나온다.

### 승인 반영 (131)

AI 후보는 대상을 코드로 가리키고, 이 서비스는 ID를 받는다. 반영할 때 코드를 ID로 바꿔 이 서비스를 부르면 검증·잠금·다시 적재가 한 곳에 모인다. 131이 부르는 반영 서비스 계약은 [승인 후보 반영 서비스](proposal-apply.md)에 있다.

| 후보 값 | 찾는 곳 | 요청 필드 |
| --- | --- | --- |
| `sourceId` | `data_source.code` | — |
| `resourceKey`(객체 매핑), `sourceColumn.resourceKey`(속성 매핑) | 같은 `data_source`의 `source_resource.resource_key` | `sourceResourceId` |
| `sourceColumn.column` | `source_column.column_name` | `sourceColumnId` |
| `objectTypeCode` | `object_type.code` | `objectTypeId` |
| `propertyCode` | 그 유형의 `property_definition.code` | `propertyDefinitionId` |
| 원천 자원 + 객체 유형 | `object_source_mapping` | `sourceMappingId` |

- 순서: 객체 유형 → 속성 → 객체 매핑 → 속성 매핑. `sourceMappingId`는 객체 매핑을 반영해야 생긴다.
- 지금 생성 메서드는 관리자 직접 등록(`origin=MANUAL`)뿐이다. AI 출처(`origin=AI_PROPOSAL`·`proposal_item_id`)를 남기는 객체 매핑 생성 메서드는 131과 함께 더한다. `property_source_mapping`에는 출처 칸이 없다.
- **식별 형식 협의 완료(2026-11-05, 서연님 답변):** 원천 표는 `sourceId=data_source.code`와 `resourceKey=source_resource.resource_key` 그대로 찾는다. 예: `ERP_MAIN` + `erp.attendance`. 속성 매핑은 `sourceColumn.resourceKey`를 사용하며 `sourceColumn.table`은 이전 형식 호환용으로만 남긴다. `ERP_MAIN.attendance`를 조합해 찾지 않는다.
- 예전 객체 매핑은 그대로 사용하고, resourceKey가 없는 예전 속성 매핑은 재분석해 새 후보를 만든다. 불변 후보 payload를 보정하거나 기존 후보를 임의 삭제하지 않는다.
- **코드 연동 대기:** 서연님 변경은 답변 시점 develop 미반영이다. 현재 로컬 `CandidateCheckRepository.loadBasis`의 table 기반 조회도 새 resourceKey 직접 조회로 맞춰졌는지 수령 후 확인한다. 세부 JSON 예시와 팀원 검증/미검증 범위는 [131 연동 계약](review-decision-flow.md)의 원천 식별 계약을 따른다.

### 수집 (197)

- 다시 읽을 대상은 켜진 객체 매핑이 있고 `loaded_lsn`이 null인 CDC 자원이다.
- 한 트랜잭션(`REPEATABLE READ`)에서 `pg_current_snapshot()`과 SELECT를 같이 한다. 객체는 `source_txid`가 그 스냅샷에서 보일 때만, 그리고 `loaded_gen`이 이번 세대 이하일 때만 덮어쓴다. 값이 같아도 `loaded_snapshot`·`loaded_gen`은 갱신한다.
- 객체 쓰기는 [원천 객체 저장](object-sync.md)(180)의 `applyLoaded`·`finishLoad`를 부른다.
- 새 객체를 만들기 전에 `source_delete_mark`를 본다. 흔적 번호가 스냅샷에서 안 보이면 읽은 뒤 지워진 행이라 만들지 않는다. 끝나면 스냅샷에서 보이는 흔적을 지우고 `source_resource.full_load_done_gen`을 올린다. 그보다 옛 세대 적재는 쓰지 않는다.
- 속성 매핑이 지워지거나 다른 객체 매핑으로 옮겨진 뒤 다시 읽을 때, 객체의 속성을 매핑 기준으로 통째로 다시 만들어야 지운 속성 값이 남지 않는다.

DB 스키마 변경은 없다(V1의 `object_source_mapping`·`property_source_mapping` 그대로).
