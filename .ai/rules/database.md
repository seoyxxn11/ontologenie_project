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

## 조회·저장 방식

자세한 기준과 예는 [C106 개발 가이드](../../docs/development-guide.md) 9.3 "조회·저장 방식 고르기"다.

- 새로 쓰거나 고치는 코드(메서드 단위)에 적용한다. 기존 코드는 요청 없이 고치지 않는다. 기준과 다른 기존 코드는 [기준 문서](../references.md)의 S15P31C106-294 분류표를 보고 도메인별 이슈로 고친다.
- 기본은 Spring Data JPA다. 파생 쿼리 → Specification → JPQL `@Query` DTO projection → native `@Query`·`@Modifying` 순서로 고른다. 앞의 방식으로 쓸 수 있으면 뒤로 넘어가지 않는다. 같은 일을 하는 JPA 메서드가 있으면 새로 만들지 않는다.
- JdbcClient는 대량 upsert·조건부 insert, Entity를 두지 않는 테이블의 조회·저장·잠금·갱신, 조건에 따라 SQL 모양이 바뀌어 JPA로 쓰기 어려운 조회, 다른 도메인 테이블을 한 번의 SQL로 join·검사하는 조회, 시퀀스로 ID를 먼저 받는 저장에만 쓴다. 클래스 주석(경우가 메서드마다 다르면 메서드 주석)에 어느 경우인지 적는다.
- Entity를 두지 않는 테이블은 작업 테이블(`sync_run`, `check_run`, `source_connection_check`, `source_preview`, `sync_run`의 하위 `sync_run_resource`, `analysis_run`과 하위 `analysis_run_target`)과 비밀값 테이블(`data_source_credential`)이다. 그 밖에 아직 Entity가 없는 테이블은 기존 Repository 방식을 따르고, 새 Entity를 만들려면 담당 도메인과 정한다.
- 다른 도메인 소유 테이블은 그 도메인의 Repository나 Service로 읽는다. 한 번의 SQL로 끝내야 하는 join·존재 검사만 SQL로 직접 읽을 수 있다. 이때도 읽기만 한다.
- JdbcClient 코드는 `repository` 패키지에 둔다. 요청 값은 반드시 바인딩한다. SQL에 이어 붙여도 되는 것은 코드가 정한 값(클래스 상수, enum, 허용 목록에서 고른 컬럼 이름, 코드가 만든 파라미터 이름과 조건 조각)뿐이다.
- SQL과 행 변환을 한 클래스에 두고, 통합 테스트로 SQL을 실행해 확인한다.
- 같은 트랜잭션에서 Entity가 있는 테이블을 JdbcClient로 바꾸지 않는다. 꼭 섞어야 하면 SQL 전에 flush하고, 뒤에 같은 Entity를 읽으면 영속성 컨텍스트를 비우거나(`clear`) `refresh`한다. 비우지 않고 다시 조회하면 바뀌기 전 값이 나온다. native `@Modifying`도 같다.

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
