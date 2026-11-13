# S15P31C106-239 문제 상세 수동 연결

366 이후 미연결은 문제(issue)로 기록한다. 377에서 개별 보류 건의 표·API·화면을 제거했으므로 예전 `held-items` 경로나 보류 건 화면을 복구하지 않는다. 제안 전체의 검토 보류(HOLD)와 승인 전 CANDIDATE의 예상 미연결 표시는 별개이며 유지한다.

## 화면 흐름

1. 관리자 → 연결 규칙 → 적용 후 문제에서 `UNMATCHED`·`CHECKING` 문제를 연다. 출발 객체와 연결 규칙이 있어야 한다.
2. 도착 객체 유형을 고르고 이름·코드로 검색한다. 유형 선택지는 `GET /api/v1/objects/types`, 검색은 076을 사용한다. 서버의 조회 범위와 가림을 그대로 사용하며 객체 속성 원문을 추가로 표시하지 않는다.
3. 페이지별 검색 결과에서 도착 객체를 고르고 1~1000자의 사유를 입력한다. 출발 객체 자신과 원천 삭제 객체는 선택할 수 없다. 검색 조건·페이지를 바꾸면 선택을 해제한다. 관계 유형·연결 수·사용 가능한 매핑 등 최종 조건은 서버가 검증한다.
4. 출발 객체·도착 객체·사유를 확인하고 `수동 연결 저장`을 누른다. 검색이나 입력 중 Enter로 저장하지 않는다. 저장 중에는 중복 클릭·입력 수정·다른 처리·상세 창 닫기를 막는다.
5. 저장 결과와 연결·감사 실행 ID를 확인한다. 문제 목록·상세·상태 및 재검증 이력, 업무 데이터·감사 캐시를 무효화하여 서버에서 다시 조회한다. 저장 직후 문제는 확인중이다.
6. 기존 상태 변경 화면에서 조치완료로 바꾼 뒤 재검증한다. 통과한 재검증을 근거로 관리자가 해결 처리한다. 수동 연결 성공이나 재검증 통과만으로 해결 상태를 만들지 않는다.

## 기존 API 연동 및 실패 처리

- 058은 `POST /api/v1/issues/{issueId}/manual-links`다. `targetObjectId`, 공백을 정리한 `reason`, 확인 시점의 `rowVersion`을 전송한다. [문제 API 계약](issue-query.md#수동-연결-058)
- 성공 응답의 문제 ID, 양의 정수 연결·감사 실행 ID, `CREATED`·`REACTIVATED` 결과를 확인한 뒤 성공으로 표시한다. 빈 응답이나 다른 문제의 응답은 결과 미확인이다.
- 400·422는 대상과 사유를 다시 확인하여 수정한다. 유형 불일치·연결 수 초과·삭제된 객체 등은 안내하고 도착 객체 선택을 해제한다.
- 401·403·404·409, 네트워크·서버 오류 및 응답 유실·잘못된 응답에서는 재전송을 막는다. 최신 문제를 다시 조회해도 자동으로 풀지 않는다. 사용자가 업무 데이터의 기존 연결·실행 이력을 확인한 뒤 상세를 다시 열어 판단한다. 서버가 멱등 키나 저장 결과 조회 API를 제공하는 것은 아니므로 자동 복구로 표현하지 않는다.
- 상세 재조회 중·실패 시 저장을 막고, 확인 중 문제 버전이 바뀌면 확인 내용을 다시 작성해야 한다. POST 자동 재시도는 하지 않는다.

## 작업 경계와 검증 한계

- 보류 건 제거(377), 그룹·민감도 UI(392), 접근 정책·서버·원천 설정은 변경하지 않는다. 새 API·DB·인증 계약이나 의존성을 추가하지 않는다.
- 프론트 요청·표시·실패 경로는 합성 데이터·MSW로, 로컬 API는 실제 PostgreSQL·HTTPS로 검증했다. Chrome에서 MSW가 없는 production 프론트와 실제 API를 연결한 브라우저 E2E도 아래 기록처럼 통과했다. 운영 배포 검증은 별개다.
- 팀원 리뷰와 병합 후 확인이 남아 있다. 로컬 검증 통과만으로 239 전체 완료나 Jira 완료를 의미하지 않는다.

## 2026-11-14 로컬 검증

기준: develop `3a1e381`, 작업 브랜치 `feature/239-issue-manual-link`.

| 검사 | 명령 (`frontend/`) | 결과 |
| --- | --- | --- |
| 관련 화면 테스트 | `npx vitest run src/features/issues/IssuesPanel.test.tsx src/features/issues/IssueManualLinkForm.test.tsx` | 2개 파일·41개 통과 |
| 전체 프론트 테스트 | `npx vitest run --maxWorkers=4 --reporter=dot` | 65개 파일·967개 통과 |
| 린트 | `npm run lint` | 통과 |
| 프로덕션 빌드·타입 검사 | `npm run build` | 통과 |
| 포맷 | `npm run format:check` | 통과 |

Vitest 명령은 설치된 로컬 실행 파일(`node_modules/.bin/vitest.cmd`)로 실행했다. 테스트에서 재검증 목록의 중복 React key(`20`, `42`) 경고가 나왔고, 빌드에서는 500 kB를 넘는 번들 경고가 남았다. 이 변경에서 해당 목록·번들 구조는 수정하지 않았다. 이 절은 프론트 검증 시점의 기록이며, 이후 백엔드·DB 검증은 아래에 기록한다.

## 2026-11-14 실제 API 통합 검증

추가한 `backend/src/test/java/com/axcore/platform/issue/IssueManualLinkHttpIntegrationTests.java`는 임의 포트의 루프백 HTTPS 서버와 일회용 PostgreSQL 18.6에서 합성 데이터만 사용한다. 실제 Flyway·Controller·Service·Repository·로그인 세션·CSRF와 검사 실행기를 사용한다. `QueuedJobPoller`와 `CdcReloadJob`의 자동 스케줄링만 테스트 대역으로 막고, 실제 `QueuedJobRunner`가 검사를 처리한다. 외부 원천 수집·LLM·운영 서버에는 요청하지 않는다.

세션 쿠키의 Secure·HttpOnly·SameSite 정책은 변경하지 않았다. JDK keytool로 테스트 전용 인증서를 만들고 클라이언트는 해당 인증서만 신뢰하며 호스트 이름 검증을 유지한다. 인증서 파일은 테스트 후 삭제한다. ADMIN 표시 이름 가림도 유지하며, 새 합성 유형의 공개 대표 속성 값으로 검색한다.

| 단계 | 확인 결과 |
| --- | --- |
| 001·002 로그인·내 계정 | 실제 세션 쿠키로 로그인·내 계정 조회 성공 |
| 076 및 객체 유형 선택지 | 허용 유형·검색어·페이지 조건으로 도착 객체 조회, 서버가 허용한 표시 이름 사용 |
| 058 수동 연결 | 연결·감사 실행 ID 반환, DB에 MANUAL 연결과 object_link 변경 감사 기록 저장 |
| 저장 후 상태 | CHECKING 유지. 자동 조치완료·자동 해결 없음 |
| 거절 경로 | 미로그인 401, VIEWER 실행·CSRF 누락 403, 필수 사유 누락 400, 버전 충돌·같은 쌍 재저장 409, 유형 불일치·삭제 객체 422, 없는 대상 404 |
| 067 조치완료 | FIXED_PENDING_REVERIFY로 전환. 통과 기록 없는 해결 요청 422 |
| 060·061 실제 검사 | API로 재검증 접수 후 실제 배치 실행, SUCCEEDED 확인 |
| 062 집계·표본 | 매칭 1→2, 미연결 1→0. 미연결 표본은 빈 배열 |
| 070 판정 | PASSED, matchedBy=MANUAL_LINK. 판정 후에도 조치완료 상태 유지 |
| 067·069 해결·이력 | 통과 실행 ID로 RESOLVED 전환, 조치완료·해결 이력 조회 성공 |

먼저 기존 6개 테스트 클래스의 91개 검사를 통과했다: `IssueManualLinkApiIntegrationTests`(8), `IssueManualLinkRouteTests`(9), `IssueStatusApiIntegrationTests`(35), `IssueVerificationApiIntegrationTests`(5), `CheckRunJobTests`(19), `CheckResultApiIntegrationTests`(15). 여기에는 감사 저장 실패 시 연결·성공 감사 롤백 확인도 포함된다. 추가한 실제 HTTPS 시나리오 1개도 통과했다.

최종 `backend/`의 `.\gradlew.bat spotlessApply build --offline`도 **BUILD SUCCESSFUL**이었다(5분 45초). 실제 JUnit XML 기준 200개 테스트 클래스·총 1,818개 중 **1,795개 통과, 23개 조건부 건너뜀, 실패 0·오류 0**이다. 건너뛴 항목은 GMS 외부 모델·로컬 스냅샷 준비용 테스트의 기존 환경 조건이 충족되지 않은 경우이며 테스트를 임의로 제외하거나 조건을 바꾸지 않았다. 새 HTTPS 테스트도 이 전체 실행에서 통과했고 `spotlessCheck`를 포함한 빌드 검사를 마쳤다.

운영 배포·운영 데이터에 대한 저장은 실행하지 않았다. 브라우저 E2E는 아래 후속 기록을 참조한다. 팀원 리뷰·MR·병합 후 검증은 남아 있으며 Jira 상태를 완료로 바꾸지 않았다.

## 2026-11-14 Chrome 브라우저 E2E

`IssueManualLinkBrowserE2ETests`가 컨텍스트 전용 PostgreSQL과 루프백 API, 합성 관리자·VIEWER·객체·규칙·확인중 문제를 준비한다. 업무 서비스는 실제 구현을 사용하고 자동 스케줄 가져오기만 막은 뒤 실제 검사 실행기로 큐를 처리한다. `frontend/scripts/issue239-browser-e2e.mjs`는 HEAD 프론트에 239 파일만 덧대어 production 빌드하고, 정적 파일과 실제 API 프록시를 localhost에서 제공한다. 같은 체크아웃에 있던 다른 작업의 관계도·목업·문서 변경은 이 빌드와 커밋 범위에 포함하지 않는다.

Chrome에서 실제 로그인·입력·버튼 조작으로 다음을 확인했다.

- 미선택·빈 사유에서는 확인 버튼 비활성화, 없는 검색어에는 빈 결과 표시.
- 출발 객체 자신은 선택 불가. 잘못된 유형을 고른 저장은 실제 422와 안내를 표시하고 선택을 해제한다.
- 정상 대상은 확인 화면에서 출발·도착·사유를 확인한 뒤 저장한다. 실제 200과 연결·감사 실행 ID 표시 후에도 확인중 상태를 유지한다.
- 같은 대상을 다시 저장하면 실제 409 안내와 재전송 차단을 표시하며, 최신 문제 조회 후에도 자동 해제하지 않는다.
- 조치완료 → 실제 재검증 접수 202 → 배치 완료 → PASSED·집계 비교를 확인했다. 이전 정상 1/미연결 1에서 정상 2/미연결 0으로 바뀌고 표본은 빈 배열이다.
- 통과 실행 ID로 해결 처리했고 해결 근거·상태 변경 이력이 표시된다. 새로고침 후에도 해결 상태가 유지된다.
- 로그아웃 후 VIEWER로 로그인하면 관리자 문제 화면 접근이 차단된다.

후속 DB 검증도 RESOLVED·활성 MANUAL 연결 정확히 1개·매칭 2/미연결 0·빈 표본을 확인하여 브라우저 전용 테스트가 성공했다. 브라우저 요청의 경로·메서드·상태만 [browser-requests.json](evidence/239-20261114/browser-requests.json)에 보관했다. 요청 본문·쿠키·토큰·자격 증명은 기록하지 않는다. 합성 계정의 임시 ready.json은 종료 시 삭제했다.

재실행 순서:

1. `backend/`에서 `AXCORE_ISSUE_BROWSER_E2E=true`를 해당 프로세스에만 설정하고 `./gradlew test --tests '*IssueManualLinkBrowserE2ETests' --offline`을 실행한다. 이 전용 테스트는 기본 자동 검사에서는 실행 조건에 따라 건너뛴다.
2. 루트에서 `node frontend/scripts/issue239-browser-e2e.mjs`를 실행하고 출력된 localhost 주소를 Chrome에서 연다. `backend/build/issue239-e2e/ready.json`의 임시 합성 계정·문제 ID로 검증한다. 이 파일은 저장소에 추가하지 않는다.
3. 해결을 확인한 뒤 `backend/build/issue239-e2e/done` 파일을 만들면 최종 DB 검증·종료·자격 증명 파일 정리를 수행한다. 중단하려면 같은 폴더의 `abort` 파일을 사용한다. 프론트 프록시는 Ctrl+C로 종료한다.

Chrome localhost의 Secure 쿠키 처리를 사용하며 제품의 Secure·HttpOnly·SameSite·CSRF 설정은 변경하지 않았다. 실제 운영 도메인·TLS 프록시·운영 데이터는 이 검증 범위에 포함하지 않는다.

### 브라우저 검증 후 최종 검사

- 분리된 프론트 트리(`backend/build/issue239-e2e/frontend`)에서 lint → Vitest 전체 65개 파일·967개 통과 → build → format:check를 모두 통과했다. 새 E2E 프록시 스크립트의 ESLint·Prettier 검사도 통과했다.
- 백엔드 `spotlessApply build --offline` 성공(5분 46초). 201개 클래스·총 1,819개 중 1,795개 통과, 실패·오류 0, 기존 환경 조건 테스트 23개와 브라우저 수동 실행 전용 테스트 1개는 기본 전체 검사에서 조건부 건너뜀이다. 브라우저 전용 테스트는 별도 활성 실행에서 실제 화면 조작·최종 DB 검증으로 통과했다.
- 별도 컨텍스트의 읽기 전용 AI 리뷰: Critical 0, Major 0. 최초 Minor 1건(문서의 변경 범위 표현)은 최종 MR 포함 범위를 명시한 뒤 재확인하여 해결했다. 사람의 리뷰·MR 파이프라인·Merge 승인을 대신하지 않는다.

재실행: `backend/`에서 `./gradlew test --tests '*IssueManualLinkHttpIntegrationTests' --offline`. 기존 6개 클래스도 `--tests '*클래스명'`으로 함께 지정할 수 있다. Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용한다. 테스트용 Docker 엔진과 JDK 21(keytool 포함)이 필요하며, 운영 비밀값 파일을 읽지 않는다.

이 MR의 범위는 239 프론트의 대상 검색·선택·확인 및 실패 처리, 관련 테스트, 실제 API·브라우저 검증용 테스트/도구, 검증 문서·요청 상태 기록이다. 기존 백엔드 업무 코드·권한 정책·DB 마이그레이션·원천/인프라 설정 및 다른 작업의 관계도 변경은 포함하지 않는다. MR 생성·Merge·운영 배포·Jira 상태 변경은 별도 사람의 판단으로 남긴다.

11월 7일의 [이전 058 초안](https://app.notion.com/p/405bdf14d2d881dea3d4dda5b646c3f6)은 보류 건 승인·종결을 전제로 한 과거 기록이다. 현재 연동은 238에서 확정한 문제 중심의 058 계약을 따른다. 이 문서를 이유로 과거 보류 건 승인·종결을 다시 추가하지 않는다.
