# SonarQube 잔여 이슈 처리 기록

- 확인일: 2026-11-17 (Asia/Seoul)
- 대상: https://sonar.ontologenie.com/issues?issueStatuses=OPEN%2CCONFIRMED
- Chrome 확장 연결과 로그인된 SonarQube 화면을 직접 확인했다.
- 시작 상태: 실제 이슈 51건. 품질 영향 합계 63건(Security 16, Reliability 31, Maintainability 16)은 한 이슈가 여러 품질에 집계되므로 중복된다.
- 기준: `origin/develop`의 `265094aa`, 작업 브랜치 `fix/sonarqube-remaining`.
- 대응하는 진행 중 Jira 이슈는 검색에서 찾지 못했다. 기존 SonarQube 이슈 249·332·343·344는 완료 상태여서 이번 작업에 재사용하지 않았다.
- 최초 로컬 검증 완료 시점에는 Commit·Push·MR·배포·SonarQube 재분석을 실행하지 않았다. API 계약과 DB 스키마 변경은 없다. 이후 원격 반영 준비는 아래 후속 기록을 따른다.

## 코드 수정 대상 30건

| 영역 | 위치 | 건수 | 수정 |
| --- | --- | ---: | --- |
| AI | `app/extractors/equipment.py` | 3 | 숫자 중간에서 탐색을 재시작하지 않고 소유 수량자로 역추적을 방지한다. |
| AI | `app/extractors/notes.py` | 1 | 시각 접두어가 있을 때만 그 뒤 공백을 읽는다. 긴 공백에서 중복 탐색을 없애고 오후·새벽과 기간 구분을 보존한다. |
| AI | `app/llm/client.py` | 1 | JSON 코드 펜스를 문자열 경계 검사로 벗긴다. 닫히지 않은 펜스와 일반 문자열은 유지한다. |
| BE | `PasswordPolicy.java` | 2 | 영문·숫자·구두점 포함 여부를 단일 문자 검색으로 확인한다. 기존 줄바꿈 거절과 길이 제한을 유지한다. |
| BE | `ActionNotifyProperties.java` | 1 | URL 끝 슬래시를 뒤에서 한 번만 순회하여 제거한다. |
| BE | `AgentTargetResolver.java` | 2 | 유형명 뒤 번호는 뒤에서 분리하고 번호가 먼저인 두 형식은 소유 수량자로 검사한다. |
| BE | `EquipmentCapabilityComparer.java`, `ObjectSearchFilterValidator.java` | 2 | null이 아닌 경로를 명시하고 비교 자료형은 한 번 계산한다. |
| BE | `QueuedJobRunner.java` | 1 | 트랜잭션 결과인 Optional의 non-null 계약을 명시한다. |
| BE | `EncryptedSecret.java` | 1 | 배열 내용 기준 equals·hashCode를 구현하고 문자열에서는 암호문·nonce를 가린다. |
| BE | `PropertyConstraints.java`, `SourceEndpoint.java` | 4 | 레코드 필드와 대소문자만 다른 상수에 KEY·PATTERN 접미사를 붙인다. |
| BE | `AdminAccountApiTests.java`, `ActionDefinitionApiTests.java` | 2 | 응답 ID를 JSON 파서로 읽는다. |
| BE | `AgentScenarioGmsTests.java` | 2 | 건수 정규식이 긴 숫자의 중간부터 반복 탐색하지 않게 한다. |
| FE | `features/explorer/format.ts` | 2 | 순서 없는 값 묶음 비교를 문자열별 개수 비교로 바꾼다. 중복 개수와 유니코드 표기 차이도 구분한다. |
| FE | `ConversationList.tsx`, `test/reactFlow.ts` | 4 | `Number.parseFloat`를 사용한다. |
| FE | `RuleSelect.tsx` | 1 | aria-activedescendant로 다루는 option에 tabIndex=-1을 명시한다. 포커스는 combobox에 유지한다. |
| FE | `mocks/handlers.ts` | 1 | reduce 초기값을 명시하고 개정 목록이 비면 요약에서 제외한다. |

## 오탐 처리 21건 — 사용자 승인 후 적용

다음 항목은 코드와 사용 맥락을 검토한 결과다. 사용자가 `21건 오탐 처리 승인`으로 명시적으로 승인한 뒤 공유 SonarQube에 `False Positive` 상태와 아래 근거를 11·3·2·5건으로 나누어 기록했다. 알림 전송 옵션은 선택하지 않았다. 검사 규칙 비활성화나 코드의 NOSONAR·SuppressWarnings 추가로 우회하지 않았다.

최종 새로고침 결과 미해결 28건을 확인했다. 시작 51건에서 이번 오탐 처리 21건 이외에도 2건이 목록에서 제외되는 변화를 관측했으며, 그 2건의 변경 원인은 미확인이다. 로컬 코드 수정 30건이 서버에서 해소됐다는 의미는 아니다. 화면 증빙: [remaining-issues.jpg](remaining-issues.jpg).

### 평가 데이터 난수 11건

`app/dataset/notes.py`와 `app/dataset/stress.py`는 가상 ERP/MES 평가 데이터 생성기다. 고정 seed의 `random.Random`으로 문장·표본·잡음을 재현하며 인증 토큰·비밀번호·키를 생성하지 않는다. 암호학적 난수로 바꾸면 평가 재현성이 사라진다.

| 파일 | 이슈 ID |
| --- | --- |
| dataset/notes.py:478 | d6716d90-2b05-42cb-83be-018f58da6009 |
| dataset/notes.py:479 | 488237e0-f7b7-4b33-98a9-b60cb780e0f0 |
| dataset/notes.py:482 | b6b455f4-5017-4fbd-ae93-a036e0f5d628 |
| dataset/notes.py:483 | 6d985221-e87a-4cd6-9487-a2d60e281d66 |
| dataset/notes.py:486 | dfa661cd-d6dc-4b01-8b36-e7e4160cfae5 |
| dataset/notes.py:489 | 9d7c1366-e6e0-4945-939a-ca1509db65ad |
| dataset/notes.py:500 | ec350d8c-8ab3-4d85-9a37-8f32a679876e |
| dataset/stress.py:112 | 0494cff7-df81-4c31-8420-668999809883 |
| dataset/stress.py:121 | 8507ec37-40b9-481a-aa9e-b64b08a39807 |
| dataset/stress.py:130 | e2c4819c-66f4-4135-a015-cb87ec0ea072 |
| dataset/stress.py:137 | 86a7a36a-86d8-4ea4-aaf2-d3df8ff2cf93 |

### 오류 안내문 3건

세 상수 값은 로그인 실패·내부 서비스 인증 실패·비밀번호 변경 필요를 알리는 한국어 안내문이다. 실제 인증 비밀값이 없다. 로그인은 PasswordEncoder로, 내부 토큰은 설정에서 읽어 MessageDigest.isEqual로 검사한다. 인증 로직 변경은 필요하지 않다.

| 파일 | 이슈 ID |
| --- | --- |
| AuthService.java:18 | 96475b05-3e3a-40bd-951c-71b1ff8c710f |
| InternalApiTokenFilter.java:28 | 20ac8dfa-0e22-4365-940b-973a7928dcf7 |
| SecurityErrorResponder.java:28 | 66729a56-49d7-46c3-af3b-b5ec7f3aaa2f |

### SQL 식별자 2건

`SourceTableReader.distinctValues`에서 표 이름은 바인딩한 카탈로그 키로 PostgreSQL이 조회한 `quote_ident(schema) || '.' || quote_ident(table)`이다. 컬럼은 읽기 권한 목록을 확인한 뒤 큰따옴표를 이중 처리하는 `quote`를 거친다. 행 수 제한은 바인딩한다. 요청 값이 SQL 구문으로 들어가는 경로가 아니다.

| 파일 | 이슈 ID |
| --- | --- |
| SourceTableReader.java:192 | ee03b5af-1cfc-4b41-a8b3-e218bc10ee06 |
| SourceTableReader.java:203 | 14aaf69b-b6ad-4460-8ddb-00f40a07f410 |

### 키보드 접근성 5건

앞 네 영역의 tabIndex=0은 긴 표·대화·코드·검사 상세를 키보드로 스크롤하기 위한 진입점이다. 각각 region 또는 log 역할과 접근 가능한 이름이 있다. 이를 없애면 키보드 사용성이 저하된다. 모달 배경 클릭은 보조 닫기 수단이며 모달에는 닫기 버튼·Escape 처리·포커스 순환·호출자 복귀가 별도로 구현되어 있다. CommonUi 테스트에서도 Escape와 배경 닫기를 확인한다.

| 파일 | 이슈 ID |
| --- | --- |
| DataTable.tsx:14 | 9f503cdb-826c-42e8-9012-fb11c7e5fee4 |
| ConversationPanel.tsx:205 | 7ce0ea9a-2831-4639-9dd0-38f88b778f3b |
| MessageItem.tsx:63 | 781ce7bf-c73b-4360-b7e8-885bdd110560 |
| CheckRunsPage.tsx:300 | df19caee-1bf5-42e9-a61f-2ec1d159ce76 |
| Dialog.tsx:93 | 7f8809f6-84ce-4f62-b07a-9c99db792007 |

## 검증

- 프론트엔드 관련 테스트: 5파일 53건 통과.
- 프론트엔드 전체: `npm run lint`, `npm run build`, `npx vitest run` 통과. 90파일 1,272건 통과.
- 변경 프론트엔드 7파일: `npx prettier --check ...` 통과.
- AI: Python 3.11 일회용 컨테이너에서 저장소의 requirements-dev.txt 설치 후 `ruff check .`, `ruff format --check app tests main.py`, `pytest -q` 통과. 507건 통과, 기존 조건부 건너뜀 2건. FastAPI의 httpx 관련 폐기 예정 경고 1건.
- AI 첫 검사에서는 Windows bind mount가 모든 파일을 실행 가능으로 보여 EXE002가 발생했다. 규칙을 끄지 않고 컨테이너 안 임시 복사본의 파일 권한을 0644로 맞춰 검사했다. 임시 복사에서 .env·가상환경·캐시는 제외했다.
- AI 첫 전체 테스트에서 오후 접두어 회귀 1건을 확인해 수정했으며 위 최종 전체 결과는 수정 후 실행이다.
- 백엔드 관련 테스트: `gradlew.bat spotlessApply test --tests ...` 성공(120건, 실패·오류·건너뜀 0건).
- 최종 백엔드 전체 검증: `backend/`에서 `gradlew.bat spotlessApply build` 성공(2026-11-17 21:31 KST, 6분 12초). JUnit XML 합계 2,090건 중 2,052건 통과, 실패·오류 0건, 기존 조건부 건너뜀 38건. 마지막 문자 분류 보조 함수 정리까지 포함한 결과다. 앞선 전체 검증 2회도 성공했으나 최종 상태의 결과는 이 실행을 기준으로 한다.
- 유형명·번호 파서의 보조 호환성 확인: 기존 정규식과 새 알고리즘의 Python 모사 구현을 고정 seed의 문자열 조합 100,000건으로 비교하여 차이 0건. 실제 Java 회귀 테스트를 별도로 추가했다.
- `git diff --check` 통과.

## 미실행·보류

- SonarQube 재분석과 Quality Gate 해소 여부는 미확인이다. 현재 수정은 로컬에 있으며 원격 분석이 적용된 상태가 아니다.
- 난수 11건의 최초 오탐 상태·근거 기록 시도는 자동 승인 검토가 보안 지적 억제 위험 및 구체적 승인 부족을 이유로 거절했다. 일괄 변경 창을 취소한 후 구체적인 21건의 대상·영향·근거를 제시했다. 사용자의 명시적 승인 후 21건을 적용했다.
- 오탐 21건의 상태 반영은 완료했다. 코드 수정 30건은 재분석에서 실제 해소되는지 확인해야 한다.
- 실제 LLM을 호출하는 조건부 테스트는 실행하지 않았다. 실제 비밀값을 외부로 전송하지 않았다.
- 백엔드 조건부 건너뜀 38건: 실제 GMS 연동 35건, 로컬 스냅샷 마이그레이션 1건, 브라우저 E2E 1건, CDC 처리량 측정 1건이다. 건너뜀 조건을 변경하지 않았다.
- 코드 수정과 로컬 검증, 승인된 오탐 상태 반영은 완료했다. 이후 원격 코드 반영과 SonarQube 재분석은 별도 작업이다. 사람의 코드 검토와 MR 승인을 대신하지 않는다.

## 후속: 최신 develop 반영 및 MR 준비

- 사용자 후속 요청으로 Commit·Push·develop 대상 MR 생성 범위가 승인됐다. 수정 커밋은 `0fff20b0`이다.
- 원격 develop을 다시 가져와 `1fb79dbe448d91ef5d1353dac978d2497fb27e87`을 작업 브랜치에 병합했다. 기존 이력은 다시 쓰지 않았다.
- develop의 `f91e2b38`에 AI 정규식 5건 수정이 이미 반영되어 있었다. AI 구현은 develop 버전을 유지하고 양쪽 회귀 테스트를 보존해 4파일의 충돌을 해결했다. 최종 MR diff의 AI 변경은 추가 회귀 테스트다.
- 병합 후 AI 전체 검증: Ruff 검사·포맷 검사 통과(107파일), pytest 528건 통과·기존 조건부 건너뜀 2건(23.11초). 첫 포맷 검사에서 발견한 병합 테스트의 빈 줄 1개를 정리한 후의 결과다.
- 병합 후 프론트엔드 전체 검증: lint·build 성공, Vitest 90파일 1,272건 통과(61.65초).
- 백엔드는 검증된 수정 커밋과 파일 내용이 동일함을 확인했다. 동일 코드의 전체 build 결과(2,052건 통과·38건 조건부 건너뜀)를 재사용하며 테스트를 중복 실행하지 않았다.
- 별도 서브에이전트가 origin/develop 대비 최종 작업 트리 26파일과 관련 호출부·테스트를 읽기 전용으로 리뷰했다. 최종 Critical 0 / Major 0 / Minor 0. 최초 Minor 1건(병합 테스트의 빈 줄)은 수정 후 재확인됐다. 리뷰어는 별도 테스트나 외부 호출을 실행하지 않았다. 사람의 리뷰·승인을 대신하지 않는다.
- 병합 완료 전 최신 fetch에서 target이 같은 `1fb79dbe`임을 재확인했으며 충돌 마커·미해결 파일 없이 diff 검사를 통과했다. Push·MR 생성 결과는 최종 답변과 MR 본문에서 보고한다. SonarQube 재분석과 원격 CI 결과는 아직 확인하지 않았다.

## 후속: 백엔드 건수 추출 정규식 2건

- 최신 `origin/develop`의 `f30bb768`에서 `fix/backend-sonar-counts` 브랜치를 만들었다.
- SonarQube 새 코드 미해결 목록에서 `AgentScenarioGmsTests.java`의 `java:S8786` 2건을 확인했다. 이슈 ID는 `87c28de5-5f1b-4453-a1bf-0de6d3718f4e`(178행), `d0fcf3e7-644b-4dcb-ab48-7b7fcccbaf64`(180행)다. 앞선 소유 수량자 수정 뒤에도 남아 있는 지적이다.
- 건수 추출 정규식 2개를 제거하고 테스트 보조 클래스 `AgentScenarioAnswerCounts`에서 ASCII 숫자 묶음을 한 번씩 순회한다. `N건 중 M건`의 M을 우선하고, 없으면 처음 나온 `N건`을 선택한다. 기존의 한 칸 공백 허용 범위와 실제 나열된 비가동 번호 수 비교를 유지했다.
- 관련 검증: `backend/`에서 `gradlew.bat spotlessApply test --tests 'com.axcore.platform.agent.service.AgentScenarioAnswerCountsTests' --tests 'com.axcore.platform.agent.service.AgentScenarioGmsTests'` 성공. 건수 추출 회귀 24건 통과(각 100,000자리 숫자 묶음을 포함한 긴 입력 포함), 실제 GMS 시나리오 20건은 기존 환경 변수 조건에 따라 건너뛰었다.
- develop `f30bb768` 기준 전체 검증: `backend/`에서 `gradlew.bat build` 성공(2026-11-17 22:50 KST, 5분 11초). JUnit XML 225파일 합계 2,114건 중 2,076건 통과, 실패·오류 0건, 기존 조건부 건너뜀 38건. 포맷 검사도 통과했다. 실제 GMS 35건, 로컬 스냅샷 마이그레이션·브라우저 E2E·CDC 처리량 측정 각 1건의 실행 조건을 유지했다.
- SonarQube 재분석은 실행하지 않았으며 서버의 이슈 해소 여부는 코드 반영 후 확인해야 한다. 이슈 상태 변경이나 경고 억제는 하지 않았다.
- 사용자 후속 요청으로 Commit·Push·develop 대상 MR 생성 범위가 승인됐다. 최신 `origin/develop=82e826e052ec66001e6b477ee7cb44663ffc93a1`을 fast-forward로 반영했으며 Git 충돌은 없었다.
- 최신 develop 반영 후 `gradlew.bat spotlessApply build`는 컴파일·포맷 검사를 통과했으나 전체 테스트에서 실패했다(2026-11-17 23:01 KST, 3분 35초). JUnit XML 합계 2,120건 중 810건 통과, 1,272건 실패, 오류 0건, 조건부 건너뜀 38건이다. 이번 건수 추출 회귀 24건은 모두 통과했다.
- 실패의 최초 원인은 `Found more than one migration with version 28`이다. Target develop 자체에 `V28__allow_admin_unclassified_types.sql`과 `V28__remove_approved_reassignment_test_plans.sql`이 함께 존재해 Flyway 초기화와 다수의 DB 테스트가 실패한다. `git ls-tree origin/develop`으로 Target에도 두 파일이 있음을 확인했다. 이번 수정에는 migration 변경이 없으며, 이 별도 문제를 임의로 수정하거나 테스트를 건너뛰지 않았다. 앞선 전체 성공 결과는 최신 develop 반영 후 성공 근거가 아니다.
- 별도 서브에이전트가 Target 대비 변경 4파일과 관련 호출부·테스트를 읽기 전용으로 리뷰했다. Critical 0 / Major 0 / Minor 0. 중복 V28은 Target의 별도 빌드 차단 문제로 구분했다. 실제 GMS·SonarQube 재분석을 확인하지 않았으며 사람의 리뷰·승인을 대신하지 않는다.
- 재시도: 최신 `origin/develop=bf1cd022`의 V29 번호 수정(`b77297c8`)을 병합 커밋 `2c4b16cb`로 반영했다. Git 충돌은 없었으며 V28·V29가 각각 하나씩 존재한다.
- 재시도 최종 검증: `backend/`에서 `gradlew.bat clean build` 성공(2026-11-17 23:57 KST, 6분 57초). JUnit XML 225파일 합계 2,120건 중 2,082건 통과, 실패·오류 0건, 기존 조건부 건너뜀 38건. 건수 추출 회귀 24건과 컴파일·포맷 검사도 통과했다. 이전 산출물을 제거한 상태에서 검증했다.
- 새 Target `bf1cd022` 대비 4파일을 별도 서브에이전트가 재리뷰했으며 Critical 0 / Major 0 / Minor 0이다. 리뷰어는 테스트를 재실행하지 않았다. 기존 Jenkins #699도 810건 통과·1,272건 실패·38건 건너뜀과 Flyway 초기화 오류를 보여 이전 실패와 일치했다.
