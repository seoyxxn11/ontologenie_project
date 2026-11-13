# Agent 업무 외 질문 처리 확인 (S15P31C106-361)

기능과 결정된 정책은 이미 구현되어 있어 중복 구현하지 않는다. 동작의 기준은 [Agent 응답 정책 3.8](agent-response-policy.md#38-업무-외-질문-가드레일-361-2026-11-11-결정)이다.

## 동작 및 API 084 응답

- `QuestionInterpreter`가 같은 해석 호출에서 `BUSINESS`, `HELP`, `OUT_OF_SCOPE`, `PERSON_EVALUATION`을 분류한다. 알 수 없는 값과 누락은 업무로 취급한다. `PERSON_EVALUATION`(사람 단위 평가·감시, 393)의 기준은 [응답 정책 3.8 개인 평가·감시](agent-response-policy.md#개인-평가감시-393-2026-11-13)다.
- 사용 안내, 업무 외 질문, 개인 평가·감시 질문은 대상 찾기·조회·답변 생성 AI를 호출하지 않고 서버의 고정 안내로 끝낸다.
- 084 메시지 응답의 기존 `answerMeta`에 `scope`를 넣는다. `conditions`는 없고, `results`·`actions`는 빈 배열이다. 상태는 `SUCCEEDED`다.
- 업무가 섞인 질문은 업무로 처리하며, 답변 생성 지시가 관계없는 부분의 거절과 내부 규칙 비공개를 요구한다. 다만 개인 평가·감시 요청이 섞이면 `PERSON_EVALUATION`으로 막는다.
- 다음 업무 질문은 앞 업무의 대상·기간을 이어 쓴다. 사용 안내·업무 외 답변의 예시 문장은 업무 조건으로 넘기지 않는다.
- 되묻기를 이어 가는 범위는 이번 질문 저장 전 최근 메시지 10건이다. 안내를 4번 끼우면 이어지고 5번 끼우면 원래 되묻기가 범위를 벗어난다. 실패 답변 뒤에는 되묻기를 이어 가지 않는다.

```json
{
  "status": "SUCCEEDED",
  "answerMeta": {
    "syncStatus": "NONE",
    "scope": "OUT_OF_SCOPE",
    "results": [],
    "actions": []
  }
}
```

이 예시는 084 메시지 한 건 중 변경 관련 필드만 발췌했다. Notion API 084 원문은 연결 계정에서 접근되지 않아 수정하지 않았다. 원본 명세의 `answerMeta.scope` 반영 여부는 담당자가 확인해야 한다.

## 회귀 검증

- `AgentMessageApiTests.clarificationContinuesAfterOutOfScopeQuestion`을 HELP·OUT_OF_SCOPE 두 경우로 검증한다. 되묻기 → 다른 질문 → 안내 → 대상 답변 흐름에서 원래 목적과 대상을 유지해야 한다.
- 같은 테스트 클래스에서 업무 조건 유지, 최근 메시지 경계, 실패 답변 이후 처리와 다른 사용자 대화 접근 제한을 확인한다.
- `AgentAnswerProcessorTests`, `AgentAnswerServiceTests`, `QuestionInterpreterTests`, `RecentTurnsTests`, `ConversationSummariesTests`에서 조회 생략, 저장 메타데이터, 해석 실패·범위 누락, 안내 문장 제외와 요약 처리를 확인한다.
- 개인 평가·감시(393): `PersonEvaluationExamples`의 막을 질문 7건·통과 질문 6건으로 `AgentMessageApiTests.personEvaluationQuestionsGetFixedSentenceWhileRecordLevelQuestionsAreAnswered`가 막은 질문은 정해진 문장·`scope=PERSON_EVALUATION`·결과 없음·답변 AI 미호출, 통과 질문은 평소 답변인지 본다. `clarificationContinuesAfterOutOfScopeQuestion`도 `PERSON_EVALUATION`을 끼운 경우를 본다. 실제 분류는 같은 질문으로 `PersonEvaluationScopeGmsTests`가 보며 `AXCORE_AGENT_LLM=openai`일 때만 돈다(아직 실행 기록 없음).
- 실제 GMS 분류의 기존 확인 기록은 응답 정책 3.8에 있다. 이번 작업의 자동 검증은 모의 모델을 사용하며 실제 GMS 재실행·배포 검증을 의미하지 않는다.

## 남은 완료 조건

팀원 리뷰, API 084 원본 확인, 배포에서 실제 모델을 사용한 회귀 확인 및 Jira 완료 판단은 별도다. 이 작업만으로 운영 배포나 Jira 상태를 변경하지 않는다.
