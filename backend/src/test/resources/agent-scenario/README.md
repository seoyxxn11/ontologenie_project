# Agent 시나리오 시험 데이터 (S15P31C106-341)

`press-v1.0.json.gz`는 가상 프레스 데이터(`ai/data/data-v1.0`)와 정답 온톨로지(`ai/data/gold-v1.0`)로 만든 Agent 시험용 객체·연결 데이터다. 백엔드 시험 DB(Testcontainers)에만 넣는다. 정답셋을 이 용도로 쓰는 것은 데이터 담당(김서연)에게 확인했다(2026-11-11).

## 다시 만들기

`ai/`에서 실행한다. 같은 입력이면 같은 파일이 나온다(압축 시각 고정).

```bash
python -m app.dataset.agent_export
```

`ai/tests/test_agent_export.py`가 저장소 전체가 있는 환경에서 이 파일이 최신인지 확인한다. CI는 `ai/`만 연결해 그 시험을 건너뛴다.

## 내용

gzip으로 압축한 JSON 하나다.

| 키 | 내용 |
| --- | --- |
| `manifest` | 건수, 뺀 표, 입력 파일 sha256 |
| `dataSources` | ERP·MES와 표 목록 |
| `objectTypes` | 유형 코드·이름·접근 그룹(V13)·원천 표·키 열·대표 속성 |
| `properties` | 속성 코드·이름·자료형·민감도·원천 열. 원천 정책의 민감 속성 3개(거래처 담당자 이름, 작업자 숙련도, 정비 담당)는 `RESTRICTED` |
| `linkTypes` | 관계 코드·한국어 이름(`출발 → 도착`)·양 끝 유형·개수·연결 방식 `linkOrigin`(`RULE`·`MANUAL`, `object_link.origin`에 넣는 값. `link_type.origin`과 다르다) |
| `rules` | 연결 규칙(`link_mapping`) 개정본 1과 비교 조건. 변환은 `{"op","arg"}` 배열 |
| `objects` | 유형 코드, 원천 키, 표시 이름, 속성 값. 백엔드 원천 적재와 같게 만든다: 속성 값은 `SourceRowConverter` 표기, 원천 키는 `SourceKeyText` 표기(timestamp는 `2026-10-28T09:00:00`), 표시 이름은 대표 속성 값이고 없으면 원천 키를 ` / `로 이은 것 |
| `links` | 관계 코드, `origin`, 양 끝 객체(유형 코드·원천 키), 근거. `RULE` 근거는 `LinkRecomputeService`와 같은 모양(`ruleCode`·`revision`·`matchedValues`·`rawValues`·`targetRawValues`). `MANUAL` 연결은 `ck_object_link_confirm` 때문에 `confirmed_by` 계정이 있어야 들어간다 |
| `expectedUnlinked` | 일부러 넣은 미연결 값과 이유(`id_links.json`, 관계의 `known_unmatched`). "연결 대상이 없다"고 답하는지 보는 시나리오에 쓴다. `value`는 원천 값이라 민감 속성(예: 정비 기술자) 값일 수 있다 |

- 회계 표 4개(매출·매입 전표, 회계 계정, 분개)는 11-04 원천 권한에서 빠져 넣지 않았다.
- 원천 정책(`axcore.source-data-policy`)의 금지 표(근태·고정자산)와 금지 컬럼(직원 이름·직급·입사일·고용 형태·재직 상태, 작업자 이름)은 넣지 않았다(S15P31C106-406). 그래서 FINANCE 그룹 유형이 없다. 유형은 25개다.
- 메모 글에서 뽑는 관계 4개는 아직 넣지 않았다.
- 작업지시를 거쳐 라우팅을 가리키는 관계 3개는 비교 조건으로 나타낼 수 없어 `MANUAL`로 둔다.
- 객체·관계 코드는 배포 서버에서 AI 제안을 승인해 만든 코드와 다를 수 있다. 시나리오 기대 결과는 이름이나 원천 키(예: `P103`, `JB2610-0141`)로 확인한다.
