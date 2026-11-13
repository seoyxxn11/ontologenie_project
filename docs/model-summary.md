# AI·Agent용 모델 요약 (API 043, Jira 188)

AI 분석(FastAPI)과 Agent가 같은 구조로 온톨로지 정의를 받는다. 형식은 노션 API 명세 043과 같다.

| 쓰는 곳 | 부르는 방법 | 조회 범위 |
|---|---|---|
| AI 분석(FastAPI) | `GET /api/v1/internal/ontology/model-summary`, `X-Internal-Token` | 전체 정의(`DataAccessPolicy.forDefinitions()`, 정의 조회 용도라 객체 값은 읽지 못함, `docs/data-access.md`). 분석은 ADMIN만 요청할 수 있다 |
| Agent(같은 백엔드) | `ModelSummaryService.summarize(dataAccessPolicy.of(질문한 사용자))` | 질문한 사용자의 범위 |

## 응답

| 묶음 | 필드 | 들어가는 것 |
|---|---|---|
| `objectTypes` | id, code, name, description | 볼 수 있는 객체 유형 |
| `properties` | id, objectTypeId, code, name, dataType, description, sensitivity, temporalRole | 볼 수 있는 유형의 속성. 민감(RESTRICTED) 속성은 그 유형의 민감 열람 권한이 있을 때만 |
| `linkTypes` | id, code, name, sourceTypeId, targetTypeId, cardinality, description | 양 끝 유형을 모두 볼 수 있는 관계 유형. `name`은 관계 유형 이름이다(S15P31C106-345, Agent 모델 요약 도구가 사용자 말과 관계를 맞추는 데 쓴다) |
| `activeRules` | id, code, revision, linkTypeId, sourceFilter, targetFilter, conditions | ACTIVE 개정본만. 조건 형식은 API 047과 같다 |

- 목록은 ID 순서다. 속성은 유형 ID, 속성 ID 순서다.
- 조회 범위 기준은 객체 조회(076~078)와 같은 `DataAccess`다(S15P31C106-205).

## 규칙을 빼는 경우

아래 중 하나라도 해당하면 그 규칙은 **통째로** 뺀다. 일부 조건만 빼면 규칙의 뜻이 바뀌기 때문이다.

- 관계 유형이 빠졌다(양 끝 유형 중 하나를 볼 수 없다).
- 비교 조건이 빠진 속성(볼 수 없는 유형, 숨긴 민감 속성)을 쓴다.
- 출발·도착 조건(`sourceFilter`·`targetFilter`)이 숨긴 민감 속성을 가리킨다.

조건 형식은 아직 정하지 않았다(AI 후보는 `{"property": "유형코드.속성코드", "equals": "값"}`). 그래서 조건 JSON 안의 문자열(키와 값)이 숨긴 속성 코드와 같거나 `.코드`로 끝나면 가리킨다고 본다. 넓게 잡아 빼는 쪽으로 틀린다. 형식을 정하면 이 검사도 그 형식에 맞춘다.

## 엔티티

`link_mapping`·`link_mapping_condition`의 JPA 엔티티(`LinkMapping`, `LinkMappingCondition`)를 이번에 만들었다. 지금은 조회만 한다. 개정본 저장·폐기는 186·189에서 더한다. 개정본 내용은 DB 트리거가 바꾸지 못하게 막고, 비교 항목은 규칙을 만든 트랜잭션 안에서만 넣을 수 있다.
