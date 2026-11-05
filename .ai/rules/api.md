# API 규칙

팀 가이드 원본은 [C106 개발 가이드](../../docs/development-guide.md) 8절이다. 계약의 기준 문서는 C106 OpenAPI 3.1 명세서다. 명세서의 경로와 형식이 이 문서보다 우선한다. 명세서 위치는 [기준 문서](../references.md)에 있다.

## 경로와 Method

- 기본 경로는 `/api/v1`이다.
- 경로는 소문자와 하이픈을 쓴다. 자원 이름은 명사로 쓰고, 목록은 복수형을 쓴다.
- GET은 조회, POST는 생성 또는 명시적인 실행 요청, PATCH는 일부 수정에 쓴다.
- DELETE는 명세된 삭제 작업에만 쓴다.
- 구현 편의만을 이유로 명세된 경로를 바꾸지 않는다.
- Swagger UI(`/swagger-ui.html`)는 `api` 프로필에서만 열린다. 확인용이며 계약의 원본은 API 명세서다.

## 응답 형식

- 성공 응답은 `data`로 감싼다. 목록 조회는 `meta`에 `page`, `size`, `totalElements`, `hasNext`를 넣는다.
- 오류 응답은 모든 API가 `error` 객체에 `code`, `message`, `details`, `requestId`를 담는다.
- 성공 응답은 `ApiResponse`로 감싸고, 오류는 `BusinessException`을 던져 `GlobalExceptionHandler`가 응답을 만들게 한다. 공통 오류 코드 표와 도메인 오류 코드 작성법은 [C106 개발 가이드](../../docs/development-guide.md) 8.3을 따른다.
- 도메인 전용 오류 코드는 그 도메인 패키지에 `ErrorCode`를 구현한 enum으로 만든다. `CommonErrorCode`에 넣지 않는다. 공통 코드(`IN_USE`, `BUSINESS_RULE_VIOLATION` 등)로 표현되면 새 코드를 만들지 않는다.
- 오류 메시지는 API 명세서에 그 API 전용 문구가 있으면 그 문구를 쓴다. 없으면 오류 코드의 기본 문구를 쓴다.
- 오류 응답의 `requestId`는 서버가 만든 추적 ID다. 응답 헤더 `X-Request-Id`와 같은 값이다.
- Entity를 응답으로 직접 반환하지 않는다. 화면에 값이 필요하다는 이유로 Entity 전체를 반환하지 않는다.

| 상태 코드 | 사용 기준 |
| --- | --- |
| 200 | 정상 조회·수정·실행 완료 |
| 201 | 자원 생성 완료 |
| 202 | 비동기 작업 접수 |
| 204 | 응답 본문 없이 처리 완료 |
| 400 | 잘못된 요청 |
| 401 | 인증 필요·인증 실패 |
| 403 | 권한 부족 |
| 404 | 대상 없음 |
| 405 | 지원하지 않는 HTTP Method |
| 409 | 중복·상태 충돌·버전 충돌 |
| 422 | 형식은 맞지만 업무 조건 위반 |
| 500 | 서버 내부 오류 |

API별 실제 상태 코드는 OpenAPI 명세에 기록한다.

## 필드와 입력값

- Java 필드와 JSON 필드는 camelCase를 쓴다.
- ID는 DB bigint에 대응하며, 현재 OpenAPI는 JSON 숫자로 정의한다.
- 시각은 시간대 정보가 있는 ISO 8601 문자열을 쓴다. DTO는 `Instant`를 쓰고, 응답은 UTC(`Z`)로 나간다. 시간대가 없는 입력은 400으로 거절된다.
- 현재 시각은 주입받은 `Clock`으로 구한다(`Instant.now(clock)`).
- 필수 입력과 선택 입력을 명확히 구분한다.
- 목록 조회는 `page`, `size` 등 명세된 파라미터를 쓴다.
- 목록 정렬은 API 명세에 적힌 고정 정렬을 쓴다. `Pageable`의 sort를 받으려면 명세에 먼저 적는다.
- 입력값 검사는 Controller 진입 시점부터 한다.

## 인증·권한

- 인증은 서버 세션으로 한다. 인증이 필요한 API는 세션 쿠키로 사용자를 확인한다. Bearer 토큰을 쓰지 않는다.
- 예외로 내부 API(`/api/v1/internal/**`)는 세션·CSRF 대신 `X-Internal-Token` 서비스 토큰으로 인증한다. 자세한 내용은 [C106 개발 가이드](../../docs/development-guide.md) 11.1 "내부 API"다.
- 상태를 바꾸는 요청(POST·PATCH·DELETE)은 CSRF 토큰을 검사한다. 서버는 `XSRF-TOKEN` 쿠키로 토큰을 주고, 화면은 `X-XSRF-TOKEN` 헤더로 보낸다.
- 세션 만료, 로그아웃, 세션을 끊는 경우는 [C106 개발 가이드](../../docs/development-guide.md) 11.1을 따른다.
- 인증 실패(401)와 권한 부족(403)을 구분한다. 보안 필터에서 막힌 요청도 공통 오류 형식으로 응답한다(`global.security.SecurityErrorResponder`).
- 로그인 사용자는 `@AuthenticationPrincipal LoginAccount`로 받는다. 공개 경로를 늘리려면 `SecurityConfig`를 고치고 리뷰를 받는다.
- 임시 비밀번호 상태에서는 002·003·099만 쓸 수 있다. 계정의 세션을 끊을 때는 `AccountSessionService`를 쓴다. 자세한 내용은 [C106 개발 가이드](../../docs/development-guide.md) 11.1이다.
- 모든 권한은 서버에서 검사한다. 세부 권한은 최종 권한 매트릭스를 따른다.

## 계약 변경

요청 필드, 응답 필드, 상태 코드, 경로가 바뀌면 기존 API 호환성을 깨는 변경일 수 있다. 실행 권한은 [AI 협업 규칙](ai-collaboration.md)을 따른다.

- 영향을 받는 담당자와 협의가 필요하다고 보고한다.
- OpenAPI 명세 수정을 같은 MR에 포함하거나 수정이 필요하다고 보고한다.
- 계약 테스트를 실행하거나 실행하지 못한 이유를 보고한다.

## Team Decision Needed

- ID의 JSON 표현 (숫자 안전 범위 검토 후 확정)
