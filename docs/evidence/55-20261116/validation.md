# S15P31C106-55: 타임아웃 측정

## 준비 확인 (2026-11-16 KST)

- 기존 `axcore` SSH 프로필로 메인 서버의 호스트 이름 조회 성공. 개인 키·실제 환경 파일·비밀값은 출력하지 않았다.
- `https://ontologenie.com/api/v1/auth/me` 비인증 요청은 HTTP 401, 외부 왕복 0.105645초. 이는 연결 확인용 수치이며 인증된 업무 API 성능 수치가 아니다.
- 메인 nginx, backend, AI, PostgreSQL 실행 확인. backend·AI·PostgreSQL healthy.
- 기존 로그인 폼 입력으로 앱 로그인 성공, 관리자 화면 접근 확인.
- 읽기 전용 DB 조회: 연결된 원천 2개, 원천 리소스 29개, 객체 유형 25개, 객체 3,397개.
- ERP·MES의 수집 접속 확인 상태 및 ERP 전체 읽기 완료 이력을 앱에서 확인했다.
- Agent LLM provider `openai`, 분석 LLM provider `gms`; 두 API 키는 존재 여부만 확인했다. 실제 9회 실행은 성공했으며 잔여 크레딧은 조회하지 않았다.
- 서버에서 읽은 nginx: `/api/`에 `proxy_buffering off`, `proxy_read_timeout 300s`, `proxy_send_timeout 60s`. 설정 변경·reload는 하지 않았다.

## 현재 구현과 이슈 작성 당시 전제의 차이

- `AnalysisController.create`는 POST `/api/v1/analyses`를 202로 접수한다.
- `AgentMessageController.ask`는 POST `/api/v1/agent/conversations/{id}/messages`를 202로 접수한다. `ConversationPanel`은 RUNNING 답변을 주기적으로 다시 조회한다. 현재 경로는 SSE 응답을 오래 유지하는 방식이 아니다.
- 최근 기존 분석 22번은 접수부터 완료까지 348.921951초, 12번은 392.372362초였다. 기존 데이터의 참고 기록이며 이번 3회 반복 측정 결과로 세지 않는다.
- 작업 완료까지 걸린 시간을 nginx의 개별 HTTP 읽기 대기 시간과 같은 값으로 취급하지 않는다. `proxy_read_timeout`은 전체 작업 시간 제한이 아니다.
- 저장소 `application-api.yml`의 Agent LLM 설정은 30초다. 로컬 Spring AI 2.0.2 설정 메타데이터에서 `spring.ai.openai.chat.timeout` 지원을 확인했다. 실행 중인 서버의 최종 바인딩 값은 별도 확인 대상이다.
- 분석 LLM의 `AI_ONTOLOGY_LLM_TIMEOUT_SEC`는 기본값·서버의 비밀값 제외 설정 조회 모두 60초였다. 이는 개별 LLM 호출 제한이며 전체 분석 작업 제한과 다르다.

코드 근거: [분석 접수](../../../backend/src/main/java/com/axcore/platform/analysis/controller/AnalysisController.java), [Agent 접수](../../../backend/src/main/java/com/axcore/platform/agent/controller/AgentMessageController.java), [Agent 폴링](../../../frontend/src/features/agent/ConversationPanel.tsx), [Agent 설정](../../../backend/src/main/resources/application-api.yml), [분석 LLM 설정](../../../ai/app/config.py).

## 측정 방법

사용자가 승인한 기존 시연 데이터 범위에서 AI 분석 3회, 조회용 Agent 질문 3회, 완료 시간 계산 질문 3회를 순차 실행했다. 로그인한 앱에서 실행하고 측정기는 SSH로 서버 상태만 읽었다. 새 분석·대화 기록과 LLM 사용량이 발생했다. 데이터 변경 Action·승인·원천 변경·nginx 배포는 수행하지 않았다.

1. 요청 전 `snapshot`으로 backend HTTP POST 카운터·누적 처리 시간과 마지막 작업 ID를 기록한다.
2. 앱에서 한 번 실행한다. `watch`로 해당 작업의 상태·시간만 관측한다. 질문·답변 본문, 쿠키·토큰·접속 비밀번호는 수집하지 않는다.
3. 요청 후 `snapshot`을 기록하고 `compare`로 접수 처리 시간을 계산한다. backend 재시작이나 예상과 다른 POST 개수는 측정 오류로 거절한다.
4. 분석 완료 시간은 DB의 `finished_at - requested_at`을 사용한다. Agent는 완료 시각 컬럼이 없어 AGENT 메시지 `created_at`부터 마지막 RUNNING 관측과 최초 종료 관측까지를 기록한다. 2초 폴링 및 DB 조회 시간이 포함되므로 최초 종료 관측은 실제 완료 시간의 상한이다. 마지막 RUNNING 시각도 쿼리 반환 후 기록되므로 엄밀한 하한으로 사용하지 않는다. 사용자 전송부터 화면 표시까지의 정확한 시간은 아니다.

```bash
python infra/scripts/measure_ai_timeout.py snapshot --output docs/evidence/55-20261116/analysis-1-before.json
python infra/scripts/measure_ai_timeout.py watch --kind analysis --after-id 22 --output docs/evidence/55-20261116/analysis-1-observer.jsonl
python infra/scripts/measure_ai_timeout.py snapshot --output docs/evidence/55-20261116/analysis-1-after.json
python infra/scripts/measure_ai_timeout.py compare --kind analysis --before docs/evidence/55-20261116/analysis-1-before.json --after docs/evidence/55-20261116/analysis-1-after.json
```

`--after-id 22`는 최초 기준값이다. 반복 측정마다 직전 snapshot의 ID를 사용하고, 앱에서 표시된 실행 ID와 일치하는지 확인한다. Agent는 `--kind agent --conversation-id <측정 대화 ID>`도 지정한다.

HTTP 처리 시간은 backend Micrometer 지표 차이이며 nginx·네트워크·클라이언트 시간을 포함하지 않는다. 현재 nginx의 backend 라우팅 로그에는 요청별 처리 시간이 없어 이를 nginx 왕복 시간으로 표현하지 않는다.

## 반복 측정 결과

측정일: 2026-11-16, KST. 모든 요청이 HTTP 202로 접수되었고, 9건 모두 최종 상태 `SUCCEEDED`였다. 원시 표본과 요약은 [results.json](results.json)에 있다.

| 종류 | 회차 | 분석/답변 ID | backend 접수 처리(초) | 작업 완료/종료 관측(초) |
| --- | ---: | ---: | ---: | ---: |
| AI 분석 | 1 | 23 | 0.175213 | 22.490388 |
| AI 분석 | 2 | 24 | 0.036165 | 23.813261 |
| AI 분석 | 3 | 25 | 0.032637 | 22.850346 |
| Agent 조회 | 1 | 600 | 0.068386 | ≤14.513341 |
| Agent 조회 | 2 | 602 | 0.020176 | ≤13.413537 |
| Agent 조회 | 3 | 604 | 0.019330 | ≤12.516611 |
| 완료 시간 계산 | 1 | 606 | 0.018732 | ≤17.543371 |
| 완료 시간 계산 | 2 | 608 | 0.018078 | ≤13.112495 |
| 완료 시간 계산 | 3 | 610 | 0.018611 | ≤15.086185 |

AI 분석 평균 완료 시간은 **23.051332초**, Agent 조회 최초 종료 관측 평균은 **13.481163초**, 완료 시간 계산 최초 종료 관측 평균은 **15.247350초**다. Agent 값은 관측 상한의 평균이며 실제 완료 시간의 평균으로 단정하지 않는다. backend 접수 처리 시간은 각각 평균 **0.081339초 / 0.035964초 / 0.018473초**였다.

### 실행 범위와 입력

- AI 분석: MES `mes.equipment`(8개 컬럼), `mes.work_order`(14개 컬럼), 총 **2개 리소스·22개 컬럼**을 세 번 동일하게 선택했다. 분석 ID 23~25의 리소스 수를 DB에서도 확인했다. 기존과 같은 범위여서 새 후보는 없었으며 분석 자체는 성공했다. 전체 29개 리소스의 최장 분석을 측정한 것은 아니다.
- Agent 조회: 설비 P103(객체 3)을 참조한 새 대화 141~143에서 동일 질문을 실행했다.
  > P103에 배정된 진행 중인 작업지시 목록을 알려줘. 데이터 조회만 하고 변경 작업은 하지 마.
- 완료 시간 계산: 작업지시 JB2610-0141(객체 731)을 참조한 새 대화 144~146에서 동일 질문을 실행했다.
  > 작업지시 JB2610-0141의 남은 공정을 설비 P103에서 2026-11-16 20:00(KST)부터 처리하면 예상 완료 시각이 언제인지 계산해줘. 조회와 예상 완료 시간 계산만 하고 변경 작업은 하지 마.

완료 시간 계산의 `ESTIMATE_COMPLETION` 감사 기록은 세 번 모두 `SUCCEEDED / FOUND`이며 도구 자체 처리 시간은 **25ms / 20ms / 15ms**였다. 전체 답변 시간에는 검색·LLM 호출 등이 포함된다. 답변에 표시된 예상 완료 시각은 세 번 모두 2026-11-17 07:23:20 KST였다. 이번 검증은 처리 시간과 종료 상태를 대상으로 하며 계산 결과·자연어 설명의 업무 정확성 검증을 대신하지 않는다.

별도 확인할 사항: 완료 화면의 자연어 설명은 공정별 6시간·5시간 24분의 합계를 총 11시간 24분으로 적으면서 준비 시간 70분도 포함했다고 설명한다. 준비 시간이 공정 시간에 이미 포함되는지와 도구 원본 결과를 대조하지 않았으므로 설명의 정합성은 미확인이다. 타임아웃 측정 범위에서 계산 로직·답변 생성 코드를 수정하지 않았다.

### 504·실패·데이터 변경 확인

[server-evidence.json](server-evidence.json)의 nginx 집계 범위는 KST **19:30:28~19:43:25**다. 해당 시간대의 `backend_routing` 기록 **242건 중 504는 0건**이었다. GET 200은 227건, 대화 생성 POST 201은 6건, 작업 접수 POST 202는 9건이었다. 이 집계는 측정 시간대의 전체 backend 라우팅 기록이며 요청별 URI를 수집하지 않았으므로 각 실행의 nginx 추적 로그라고 표현하지 않는다.

Agent 조회 2회차에서 `OBJECT_SET_FIND`가 한 번 실패(41ms)한 뒤 재시도(97ms)하여 최종 답변은 성공했다. 따라서 최종 실패·504는 없었지만 내부 도구 오류까지 0건인 것은 아니다. 여섯 대화의 감사 기록에서 데이터 변경 Action은 **0건**이었다. 감사 기록은 USER 질문 ID로 연결했으며 AGENT 답변 ID와 구분했다.

### 화면 증빙

현행 설정에서 세 종류의 완료 화면을 저장했다. nginx 설정을 바꾸지 않았으므로 아래 화면을 설정 조정의 전후 비교로 표시하지 않는다.

- [AI 분석 완료](analysis-completed.jpg)
- [Agent 조회 완료](agent-query-completed.jpg)
- [완료 시간 계산 완료](completion-estimate-completed.jpg)

## 설정 판단

이번 표본에서는 backend 접수가 모두 0.18초 안에 끝났고 504가 없었다. 현재의 **202 접수·폴링 구조**에서는 분석 완료까지의 수십~수백 초를 nginx의 한 HTTP 요청 대기 시간으로 볼 수 없다. 따라서 **현재 측정 범위를 근거로 nginx 타임아웃을 더 늘릴 필요는 확인되지 않았다**. `proxy_read_timeout 300s`, `proxy_send_timeout 60s`, `proxy_buffering off`를 유지했다.

Agent 개별 LLM 30초·분석 개별 LLM 60초는 설정 기준으로 nginx 300초보다 짧다. 다만 비동기 작업 전체 제한과 개별 호출·HTTP 제한은 역할이 달라 무조건 같은 부등식으로 정렬하지 않는다. 서버의 최종 Agent 클라이언트 바인딩 값과 장애 시 종료·재시도 동작은 아직 검증하지 않았다. 운영 설정을 낮추거나 LLM 장애를 주입해 504를 강제로 만들지 않았다.

표본은 각 3회 순차 실행이다. 전체 리소스 분석, 동시 사용자·부하, 긴 대화·복잡한 도구 조합, 장애·재시도 한계, p95/p99는 검증하지 않았다. S15P31C106-24의 모든 성능 목표 충족이나 후속 71·72번 작업 완료를 이 결과로 주장하지 않는다.

## 검증 및 남은 작업

- `python -m unittest discover -s infra/tests -p test_measure_ai_timeout.py -v`: 측정값 혼입·재시작·카운터 초기화·불완전 지표 방지 등 8개 통과.
- `python -m py_compile infra/scripts/measure_ai_timeout.py`: 통과.
- Ruff는 로컬 환경에 없어 실행하지 못했다. 의존성은 추가하지 않았다.
- 첫 기준 snapshot은 `baseline.json`, 각 실행의 전후 지표는 `*-before.json`·`*-after.json`, backend 접수 시간은 `*-http.json`, 상태 관측은 `*-observer.jsonl`이다. 9개 표본의 전후 지표를 다시 계산해 요약과 일치하는지 확인했다.
- 원본 관측 파일의 `completion_lower_seconds`는 당시 수집기의 필드 이름이다. 실제 의미는 마지막 RUNNING **관측**까지의 시간이며 엄밀한 하한이 아니다. 요약 `results.json`과 현재 수집기는 `last_running_observed_seconds`로 명확히 구분한다. 원본 파일은 수정하지 않았다.
- 서버 설정·스키마·배포 변경은 없다. 분석 3회·대화 6개·질문/답변 6쌍은 승인된 실행 기록으로 남았다.

| Jira 55 완료 기준 | 현재 결과 |
| --- | --- |
| 스트리밍 여부 기록 | 코드상 202·폴링 확인. 담당자 장민화의 확인은 남음 |
| 세 종류 각 3회 측정표 | 완료, 표본 9개와 원시 기록 저장 |
| 조정 전 504 발생 또는 미발생 증빙 | 현행 설정에서 504 0건 집계와 완료 화면 저장 |
| 조정 후 정상 응답 캡처 | 설정 조정 필요성을 확인하지 못해 조정하지 않음. 현행 정상 완료 캡처로 대체하는 기준 합의 필요 |
| backend·AI 타임아웃 정합성 | 저장소·일부 비밀값 제외 서버 설정 확인. 최종 Agent 바인딩과 장애 경로는 미확인 |
| 검토자 승인 코멘트 | 장민화의 검토·승인 필요. AI가 대신 승인하지 않음 |
| Notion Infra 게시 및 Jira 산출물 링크 | [Jira 측정 결과 댓글](https://ssafy.atlassian.net/browse/S15P31C106-55?focusedCommentId=336028)에 측정표·판단·남은 항목 게시. [증빙 ZIP](https://ssafy.atlassian.net/secure/attachment/31586/S15P31C106-55-20261116-evidence.zip) 첨부 완료. Notion Infra 게시는 남음 |

이슈의 SSE·장시간 HTTP 전제와 현행 구현의 차이는 사용자가 승인한 현행 구조 기준으로 측정했다. Jira 상태·담당자·보고자·스토리포인트는 유지했다. 측정 산출물과 수집기·테스트는 사용자 요청에 따라 Commit 대상으로 준비했으며 Push·MR 변경은 수행하지 않았다.
