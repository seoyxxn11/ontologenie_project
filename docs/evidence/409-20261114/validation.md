# 409 데이터 연결 제공 범위 정합 검증

- Jira: S15P31C106-409, 담당·보고자 손주연, 5포인트.
- 작업 브랜치: `fix/409-source-capabilities`.
- 사용자 지정 순서에 따라 `6b4b184` 기준으로 먼저 구현한 뒤, 마지막 통합 단계에서 원격 develop을 가져왔다.
- 최신 develop `5cce1ab`에 408이 병합되어 있었으며, 409 변경과 겹치는 파일이 없어 미커밋 변경을 보존한 채 fast-forward했다. 이후 최종 검사를 실행했다.

## 확인한 계약과 구현 범위

### 원천 표본

391은 Jira상 완료이며 `SourceSampleJob`이 `SourceColumnPolicy`로 금지 표를 거절하고 금지·민감 컬럼을 읽기 전에 제외하는 것을 확인했다. 이 정책·백엔드 코드는 수정하지 않았다.

프론트 `sampleApi.ts`를 기존 `SourceSampleAccess`로 주입하여 016 `POST /sources/{id}/resources/{resourceId}/samples`와 102 `GET /ontology/source-previews/{previewId}`를 연결했다. 경로의 공통 접두사는 `/api/v1`이다. 표본 접수는 명시적인 버튼 동작으로만 수행한다.

미리보기 ID·SAMPLE 종류·선택한 자원 ID·상태·만료·컬럼/행 구조를 확인한다. 서버가 반환한 컬럼 순서·값·기존 가림을 유지하고, 제외 컬럼이나 원문을 추가 조회하지 않는다. 표본은 기존 화면 상태에서만 유지하며 쿼리 캐시·브라우저 저장소에 저장하지 않는다. 오류 원문의 SQL·행 값은 화면에 전달하지 않는다.

### CSV·문서 등록

서버가 CSV·DOCUMENT의 메타정보를 저장하는 경로는 있지만 파일 업로드·카탈로그·수집 구현은 없다. 연결 검사는 미지원으로 실패하며 `SyncRunJob`도 PostgreSQL만 읽는다.

신규 등록의 유형 선택에는 PostgreSQL만 제공하고 CSV·문서 선택지와 미지원 기능 안내문을 노출하지 않는다. 조작된 미지원 유형 변경도 무시한다. 기존 메타정보의 이름 수정은 유지한다. CSV·문서 연결 재검사와 표본 조회도 실행 가능한 것처럼 표시하지 않는다. 파일 업로드 API를 새로 만들지 않았다.

### 수집 주기

기존 071 `GET /sources/{id}/sync-schedule`와 072 `PUT /sources/{id}/sync-schedule`를 연결했다. PostgreSQL 원천에 FULL_COMPARE 자원이 있을 때만 폼을 표시하고, 세 필드(`syncEnabled`, `syncIntervalMinutes`, `rowVersion`)만 다룬다. 공통 요청 클라이언트에 기존 세션·CSRF·오류 처리를 재사용하는 PUT 함수를 추가했다.

저장 후 설정·목록·상세를 갱신하고, 중복 제출·자동 재시도를 막는다. 409·권한·통신 오류·빈 응답에서는 재전송을 막으며 명시적인 최신 설정 재조회 후 서버 값과 버전을 다시 확인한다. 400·422는 입력을 유지한다. FULL_COMPARE에만 적용되고 CDC 간격 설정이 아님을 안내하며 스케줄러·배치·CDC 코드를 변경하지 않았다.

## 실행한 검사

- 프론트 최종 전체: `vitest run --maxWorkers=4 --reporter=dot`, **73개 파일·1,042개 테스트 통과**.
- `npm run lint`, `npm run build`, `npm run format:check` 통과. 중복 key·500kB 초과 경고 없음.
- 기존 백엔드 관련 테스트: `./gradlew.bat test --tests '*SourceSampleJobTests' --tests '*SourceSampleApiTests' --tests '*SyncScheduleApiTests' --offline`, **BUILD SUCCESSFUL**.
- JUnit XML: SourceSampleJobTests 22개, SourceSampleApiTests 4개, SyncScheduleApiTests 9개, 합계 **35개 통과·실패/오류/건너뜀 0개**. 백엔드 전체 build는 이번 프론트 변경에서 실행하지 않았다.
- 초기 구현의 로컬 Chrome·MSW 합성 데이터: 원천 테이블 선택→표본 접수→결과 표 표시, CSV·문서 선택→미지원 안내·등록 버튼 차단 확인. 이후 사용자 피드백으로 신규 등록의 CSV·문서 선택지와 긴 안내문을 제거했고 이 최종 UI는 자동 테스트로 확인했다. Chrome 연결이 응답하지 않아 변경 후 브라우저 재확인은 미실행이다. 주기 저장·재조회·충돌·권한·응답 유실은 자동 화면/API 어댑터 테스트로 검증했다.
- `git diff --check` 통과. 검사한 비밀값 패턴 검출 없음.

## 작업 경계와 남은 확인

백엔드·DB·391 분류 정책·384 팀원 담당 코드·인프라·운영 데이터는 변경하지 않았다. API·ERD 계약 변경도 없다. 프론트의 기존 요청 경로에 연결한 작업이다.

Jira 완료 상태 변경과 MR 병합은 수행 범위에 포함하지 않는다. 운영 원천으로 표본을 접수하거나 운영 수집 주기를 저장하지 않았고 운영 배포 후 검증도 미실행이다. 자동 테스트·로컬 합성 화면 검증과 운영 검증을 구분한다.

## 독립 리뷰 후 보완

초기 독립 리뷰는 Critical 0건, Major 1건, Minor 2건이었다. 저장 요청 상태가 화면 이동 때 소실되는 Major를 원천별 공유 mutation 상태·진행/결과 경로·오류 후 재조회 잠금으로 보완했다. Minor인 미저장 입력 이탈 확인과 NULL 문서 설명도 수정했다. 등록 창을 열 때도 미저장 주기 입력을 확인한 뒤 이동한다. 재리뷰에서 추가로 발견한 Minor 1건(015와 15의 문자열 비교로 저장 성공 뒤에도 이탈 경고가 남는 문제)도 실제 전송 숫자/null 비교와 회귀 테스트로 수정했다. 마지막 독립 재리뷰 결과 Critical·Major·Minor 미해결은 0건이다. 뒤로 가기 자동 테스트의 React act 경고도 해결했다. user-event가 입력 blur/change를 Dialog 포커스 이동 중에 재전송하던 테스트 순서를, 입력 처리를 마친 뒤 이동하도록 보완했다. 경고를 숨기거나 동작·입력 보존 단언을 제거하지 않았다. 최종 검사 결과는 MR 본문에도 기록한다.
