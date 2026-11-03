# 223 분석 결과 내부 저장 설계

사용자가 채팅에서 승인한 API 105 구현 방식을 기록한다.

- 기준: [105 분석 결과 내부 저장](https://app.notion.com/p/406bdf14d2d88179ad90d2d68a386f75), 2026-11-03 브라우저에서 확인.
- 경로: `POST /api/v1/internal/analyses/{id}/results`, 기존 내부 서비스 토큰 인증 사용.
- 허용 전이: QUEUED → RUNNING, QUEUED → FAILED, RUNNING → SUCCEEDED 또는 FAILED. 나머지는 409.
- 실행 행을 `FOR UPDATE`로 잠그고 검증·변경한다. 없는 실행은 404.
- RUNNING은 시작 시각만, FAILED는 종료 시각·실패 사유를 저장한다.
- SUCCEEDED는 종료 시각·요약, AI 후보·최초 개정본·선택지, 자동 후보 검사 접수, 결과 감사 기록을 같은 트랜잭션에서 저장한다.
- 후보는 AI / PENDING / NOT_APPLIED, revision=1이며 사용자 생성·수정자는 NULL이다.
- 입력은 DDL 수준으로 검사한다. 아직 승인되지 않은 코드 참조를 수동 후보의 업무 검증으로 거절하지 않는다.
- 전체 후보를 저장한 다음 검사를 접수해 같은 분석의 매핑 후보도 검사 기준에 포함한다.
- 자동 검사의 requested_by는 NULL이다. 기존 DDL이 AI 후보 시험의 NULL 요청자를 허용한다.
- 완료 감사 기록은 SYSTEM / INTERNAL / ANALYSIS_RUN으로 남긴다. 후보·근거·실패 사유 원문을 감사 결과에 복제하지 않고 건수만 남긴다.
- FAILED 알림 수신도 상태 변경이 성공한 업무이므로 상태 변경과 감사 기록을 함께 커밋한다. 감사 저장 실패 시 상태 변경도 롤백한다.

## 구현 방식

Analysis Controller/Service/Repository가 실행 상태와 트랜잭션을 맡고, Review Service/Repository가 AI 후보를 저장한다. 기존 CandidateCheckService와 AuditRecorder를 사용한다. 기존 수동 후보 저장은 신뢰도를 NULL로 쓰므로 AI 전용 저장 경로를 추가한다.

## 범위와 검증

018 실행 접수, 017·019·020 조회, 271 시작 알림, 273 시간 초과 정리는 별도 후속 작업이다. 스키마·인증 설정·의존성은 바꾸지 않는다. API 통합 테스트로 상태·입력·인증·후보 저장·중복·동시 요청·롤백을 검증한다.
