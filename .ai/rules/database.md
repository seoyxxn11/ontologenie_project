# DB 규칙

팀 가이드 원본은 [C106 개발 가이드](../../docs/development-guide.md) 9절이다. DB Schema 변경과 데이터 삭제·마이그레이션의 실행 권한은 [AI 협업 규칙](ai-collaboration.md)을 따른다. ERD와 DDL 위치는 [기준 문서](../references.md)에 있다.

## 기본 원칙

- PostgreSQL을 쓴다. 컬럼 이름은 snake_case를 쓴다.
- ERD와 실제 스키마가 일치해야 한다. 스키마를 바꾸면 ERD 수정도 함께 보고한다.
- 운영 DB를 직접 수정하지 않는다.
- 기존 데이터가 있는 컬럼을 삭제하거나 바꿀 때 영향 범위를 확인한다.
- DB 자격 증명을 코드에 넣지 않는다.

## Migration

- Flyway를 쓴다. 파일 위치는 `src/main/resources/db/migration/`, 이름은 `V{번호}__{설명}.sql`이다.
- 이미 실행한 Migration 파일은 수정하지 않는다. 새 버전 파일을 추가한다.
- 다른 영역의 테이블을 참조하는 외래 키는 생성 순서를 검증한다.
- 로컬·테스트·배포 DB에 같은 Migration을 적용한다.
- 스키마 변경 MR에는 적용과 복구 영향을 적는다.

## Entity·Repository

- Entity와 API DTO를 분리한다.
- 연관 관계는 필요한 방향만 정의한다. 모든 연관 관계를 양방향으로 만들지 않는다.
- 조회에 필요한 값만 가져온다.
- N+1이 생길 수 있는 조회는 테스트로 확인한다. Fetch Join, DTO 조회 등은 실제 조회 요구에 따라 고른다.
- `created_at`·`updated_at`이 있는 테이블은 `BaseTimeEntity`를, `created_by`·`updated_by`까지 있으면 `BaseAuditEntity`를 상속한다. 맞지 않는 테이블은 필드를 직접 선언한다. 자세한 내용은 [C106 개발 가이드](../../docs/development-guide.md) 9.3이다.
- `timestamptz` 컬럼은 `Instant`로 매핑한다. `row_version`은 각 Entity가 `@Version`으로 선언한다.

## 동시성과 제약 조건

- `rowVersion`이 있는 데이터는 수정 요청의 버전과 저장된 버전을 비교한다. 다르면 변경을 거절한다(409).
- 고유성과 중복 금지는 애플리케이션 검사와 DB 제약 조건 둘 다로 보장한다.

## C106 데이터 규칙

- 원천 객체는 같은 원천 키를 기준으로 갱신한다.
- 원천에서 삭제된 객체는 `DELETED_AT_SOURCE`로 표시한다.
- Explorer와 Agent의 일반 조회는 활성 객체와 활성 관계만 쓴다.
- 승인 전 규칙은 제안 데이터에 보관한다. `link_mapping`에는 승인된 규칙 개정본을 저장한다.
- 규칙을 개정하면 기존 관계를 재계산하고, 성립하지 않는 관계는 비활성화한다.
- 정합성 문제는 재검증 통과 기록 없이 해결 상태로 바꾸지 않는다.
- 재배정 계획은 전용 테이블에 저장한다. 계획 생성은 원천 MES의 실제 배정을 변경하지 않는다.

## 감사 기록

- 업무 변경 이력은 일반 로그와 별도로 `action_execution`, `entity_change` 등 ERD의 테이블에 저장한다.
- 실행자, Action, 검사한 권한, 변경 데이터와 전후 내용, 성공 여부, 실패·차단 사유를 남긴다.
- Agent의 조회 Tool 호출은 `action_execution`(`event_kind=AGENT_QUERY`)에 남긴다.
- 기록은 `audit.service.AuditRecorder`로만 남긴다. 성공은 `record`(업무와 같은 트랜잭션), 차단·실패는 `recordSeparately`(별도 트랜잭션)를 쓴다. 사용법은 [C106 개발 가이드](../../docs/development-guide.md) 14.2이다.
