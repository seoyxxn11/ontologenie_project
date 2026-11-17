# AI SonarQube 이슈 점검 — 2026-11-17

대상은 사용자 요청의 [AXCore AI 열린 이슈](https://sonar.ontologenie.com/project/issues?issueStatuses=OPEN%2CCONFIRMED&id=axcore-ai&s=IMPACT_RANK)다. 최신 develop `265094aa`에서 작업했으며 Jira 이슈는 만들지 않는다.

SonarQube 26.10.0.133078의 develop 분석에서 OPEN 16건, CONFIRMED 0건을 읽기 전용으로 확인했다. API 조회는 401이고 브라우저 실행 도구가 실패해, 기존 서버 접속으로 Sonar DB의 해당 프로젝트·브랜치 이슈만 조회했다. 비밀값·인증 파일은 읽지 않았으며 DB와 이슈 상태는 변경하지 않았다.

## 정규식 수정 5건 — python:S8786

| 원본 위치 | 이슈 키 | 조치 |
| --- | --- | --- |
| app/extractors/equipment.py:11 | defe6a9d-4255-4380-90e5-7b616f0a627b | 숫자열 중간 재시작을 막고 숫자·공백의 역추적 제거 |
| app/extractors/equipment.py:12 | ed7ab780-a9f2-451b-9d42-42c8974558d3 | 동일 |
| app/extractors/equipment.py:13 | c05c735b-5215-4ee4-8a25-82bcba41ef80 | 동일 |
| app/extractors/notes.py:69 | 5de6d754-2c6b-495a-b74a-52cd51040dc4 | 수식어 없는 경우의 선행 공백 반복 제거, 공백 역추적 제거 |
| app/llm/client.py:193 | a39fa057-a3d1-434b-9b12-aed094eabe4b | 정규식 대신 문자열 경계 확인과 접두사·공백 제거 |

설비·시각 정규식의 소유 수량자는 프로젝트 실행 기준인 Python 3.11 이상에서 지원한다. 숫자 표기, 유니코드 숫자, 공백, 오전·오후, 시간과 지속시간 구분, 기존 코드 펜스의 해석을 회귀 테스트로 확인한다. `test_regex_runtime.py`는 10만 글자 실패 입력을 별도 프로세스의 10초 제한 안에서 처리하는지 확인한다. 수정 전에는 제한 시간을 초과해 실패했으며 수정 후에는 통과했다.

## 합성 데이터 난수 검토 11건 — python:S2245

| 원본 위치 | 이슈 키 |
| --- | --- |
| app/dataset/notes.py:478 | d6716d90-2b05-42cb-83be-018f58da6009 |
| app/dataset/notes.py:479 | 488237e0-f7b7-4b33-98a9-b60cb780e0f0 |
| app/dataset/notes.py:482 | b6b455f4-5017-4fbd-ae93-a036e0f5d628 |
| app/dataset/notes.py:483 | 6d985221-e87a-4cd6-9487-a2d60e281d66 |
| app/dataset/notes.py:486 | dfa661cd-d6dc-4b01-8b36-e7e4160cfae5 |
| app/dataset/notes.py:489 | 9d7c1366-e6e0-4945-939a-ca1509db65ad |
| app/dataset/notes.py:500 | ec350d8c-8ab3-4d85-9a37-8f32a679876e |
| app/dataset/stress.py:112 | 0494cff7-df81-4c31-8420-668999809883 |
| app/dataset/stress.py:121 | 8507ec37-40b9-481a-aa9e-b64b08a39807 |
| app/dataset/stress.py:130 | e2c4819c-66f4-4135-a015-cb87ec0ea072 |
| app/dataset/stress.py:137 | 86a7a36a-86d8-4ea4-aaf2-d3df8ff2cf93 |

검토 결과 이 11건은 보안 문맥의 난수가 아니다. `notes.build_notes`는 `dataset.build.generate`가 만든 합성 행에서 메모의 날짜·패턴·작성자를 고른다. `stress.build`는 평가용 CSV에 표기 잡음과 미끼 컬럼 값을 넣는다. `badge_no`도 합성 근태 행의 미끼 값이며 실제 출입 인증에 사용하지 않는다. 두 생성기는 API 요청 처리나 인증·키·토큰 생성 경로에서 호출되지 않는다.

고정된 `random.Random(seed)`는 [데이터셋의 재현성 계약](dataset.md)을 구현한다. 이를 `secrets`나 `SystemRandom`으로 바꾸면 같은 시드의 원천·정답·평가 데이터가 달라진다. 난수 코드를 숨기는 래퍼나 `NOSONAR`, 규칙 제외 설정을 추가하지 않는다. 이 11건에는 위 근거를 남기고 Sonar에서 False Positive로 분류하는 것이 적절하다. 실제 분류에는 해당 프로젝트의 이슈 관리 권한이 필요하다.

## 검증과 완료 구분

- 관련 회귀 테스트: 정규식 처리·설비 연결·시각 해석·LLM 응답 처리
- 전체 검증: Ruff 검사, Ruff 포맷 검사, pytest 및 커버리지 XML 생성
- 데이터 재현성: 기존 `test_same_seed_same_files` 유지
- 실제 외부 LLM 호출·원천 운영 DB 쓰기·운영 배포는 수행하지 않음

**소스 수정과 Sonar 서버의 이슈 종료는 별개다.** 정규식 5건은 수정 코드의 재분석으로 종료 여부를 확인해야 하며, 난수 11건은 인증된 Sonar 이슈 관리 작업이 필요하다. 이 문서 작성 시점에는 이슈 상태 변경과 수정 커밋의 Sonar 재분석을 수행하지 않았다.
