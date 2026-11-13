# 로컬 DB 스냅샷으로 Agent 모델 비교

배포 PostgreSQL의 스냅샷을 별도 로컬 DB에 복원하고, 같은 질문을 모델별로 실행한다. Flyway는 복원한 로컬 DB의 이력을 검증하고 현재 체크아웃의 기존 마이그레이션을 적용하는 데 쓰며 운영 데이터를 복사하지 않는다. 복사에는 `pg_dump -Fc`와 `pg_restore`를 사용한다.

실행 도구는 `backend/scripts/agent_eval.py`, 실제 Agent 평가기는 `backend/src/test/java/com/axcore/platform/agent/service/ModelComparisonGmsTests.java`다. 새 라이브러리 없이 Python 3.11 이상, Docker, Java 21, 기존 Gradle Wrapper를 쓴다. SSH 복사에는 OpenSSH와 이미 신뢰한 서버 호스트 키가 필요하다.

## 데이터 준비

프로젝트 루트에서 실행한다. Windows에서 `python` 명령이 Microsoft Store 별칭이면 실제 Python 실행 파일의 경로를 사용한다.

```powershell
python backend/scripts/agent_eval.py clone --dump "C:/백업위치/axcore.dump"
```

서버 접속 키가 있으면 아래 명령으로 읽기 전용 `pg_dump`를 스트리밍할 수 있다. 서버에 파일을 쓰거나 서비스를 재시작하지 않는다. 호스트 키 검사를 끄지 않는다.

```powershell
python backend/scripts/agent_eval.py clone --ssh-key "C:/키위치/K15C106T.pem"
```

웹사이트 아이디·비밀번호만으로는 DB를 복사할 수 없다. SSH 계정인지 DB 계정인지와 접속 주소가 불명확하면 서버 담당자에게 아래 내용을 전달한다. 비밀번호·개인키를 채팅이나 저장소에 올리지 않는다.

> 메인 서버의 플랫폼 DB를 로컬 Agent 평가용으로 복원하려고 합니다. `pg_dump -Fc` 형식의 최신 DB 백업 파일을 안전한 경로로 전달해 주세요. 가능하면 `platform.data_source_credential`, `platform.spring_session`, `platform.spring_session_attributes`의 데이터는 제외해 주세요. ERP/MES 원천 DB와 서버 환경 파일·인증 키는 필요하지 않습니다.

복원은 다음 조건으로 동작한다.

- 이름이 `axcore-agent-eval-*`인 새 PostgreSQL 18.6 컨테이너만 생성한다. 포트는 `127.0.0.1`의 자동 할당 포트다.
- 기존 평가 DB 상태 파일이 있으면 덮어쓰지 않는다. 기존 Docker DB·볼륨을 지우는 명령은 실행하지 않는다.
- 컨테이너 재시작으로 자동 할당 포트가 바뀌면, 평가용 라벨과 localhost 바인딩을 확인한 뒤 저장된 접속 포트를 갱신한다. 작업 디렉터리 밖 환경 파일은 수정하지 않는다.
- 복원한 로컬 복사본에서 세션·원천 자격 증명 데이터를 제거하고 계정 이름·비밀번호 해시를 평가용 비활성 값으로 바꾼다. 원본 서버에는 적용하지 않는다.
- `axcore_eval_reader` 계정은 읽기만 허용하며 기본 트랜잭션도 읽기 전용이다. 계정 테이블은 ID·역할·상태만 읽을 수 있다.
- 스냅샷 SHA-256·객체/관계/유형 수·Flyway 이력을 기록한다. 암호와 GMS 키는 결과 JSON에 넣지 않는다.
- 읽기 전용 계정을 만들기 전에 `LocalSnapshotMigrationTests`로 현재 코드의 기존 Flyway 변경을 로컬 복사본에만 적용한다. localhost의 `axcore_eval`과 `axcore_eval_owner`만 허용하며 `clean`은 비활성화한다. 체크섬 불일치나 코드보다 앞선 이력은 임의로 수정하지 않는다.
- 백업·로컬 DB 비밀번호·평가 답변은 Git에서 제외되는 `backend/build/agent-eval/`에 둔다. 이 디렉터리는 외부 공유하거나 커밋하지 않는다.

```powershell
python backend/scripts/agent_eval.py status
```

## 확장 합성 테스트

운영 백업 없이 새 합성 DB를 만들 수 있다. 기존 DB를 덮어쓰지 않으며 작업 디렉터리를 분리할 수 있다.

```powershell
python backend/scripts/agent_eval.py --workspace backend/build/agent-eval/diverse-new synthetic --variant diverse
python backend/scripts/agent_eval.py --workspace backend/build/agent-eval/diverse-new run --models gpt-5.6-sol,gpt-6-sol,gpt-5.6-terra,gpt-5.6-luna --questions backend/src/test/resources/agent-eval/diverse-questions.json --turn-limit 24 --parallel 4 --role ADMIN
```

데이터는 20개 객체·12개 연결·6개 유형이다. 설비·비가동·자산·정비 지시·작업지시·접근 제한 객체를 포함한다. 시나리오가 바뀌거나 역할이 바뀌면 대화 상태를 초기화하고, 같은 시나리오의 후속질문끼리는 상태를 이어 쓴다. 일반 질문은 합성 데이터의 ADMIN, 권한 시험 3개는 명시적으로 VIEWER로 실행한다. 모델별 실행은 독립적이며 `--parallel`은 1~4로 지정한다. 응답 시간에는 동시 실행 부하가 포함된다.

| 시나리오 | 질문 수 | 확인 사항 |
| --- | ---: | --- |
| maintenance | 2 | 정비 목록·새 작업지시로 전환 |
| downtime | 3 | 비가동 상세·사유 후속질문·다른 설비로 전환 |
| assets | 2 | 자산 3개 비교·두 번째 자산 지칭 |
| period | 2 | 명시한 월·다음 달 후속질문 |
| period-clarification | 2 | 모호한 기간 되묻기·사용자 답으로 채우기 |
| ambiguity | 2 | 동명이름 후보 되묻기·첫 후보 선택 |
| unknown | 1 | 없는 객체의 정보 추측 방지 |
| unlinked | 1 | 객체는 있지만 연결이 없는 경우 |
| incomplete | 1 | 종료 시각·비가동 시간이 없는 기록 |
| restricted | 2 | 제한 속성·권한 무시 요청 |
| hidden | 1 | 볼 수 없는 객체를 검색에서 숨기기 |
| help-continue | 3 | 현재 상태·인사·업무 대상 유지 |
| scope | 2 | 업무 외 질문·내부 프롬프트 요청 |

질문 원본은 실행 디렉터리의 `questions-original.json`으로 고정해 보존한다. 기간·금지 정보·결과 유형(FOUND/NO_DATA/UNLINKED)도 채점한다. 후보 선택은 코드의 알파벳 순서를 가정하지 않고 실제 되물은 후보 순서와 해당 객체의 조회된 상태 값으로 판정한다. 결측 정보의 동등한 문장 표현을 허용하고, 기간 밖 기록을 제외했다는 설명은 기간 밖 기록을 결과에 포함한 답변과 구분한다.

기간이 없어야 하는 문항에는 `periodAbsent: true`를 지정한다. 이 경우 날짜를 넣지 않은 해석만 통과하며, 오늘 날짜를 임의로 넣으면 실패한다. `from`·`to`와 함께 지정할 수 없다. `현재 P104 상태 알려줘`는 현재 상태를 묻는 질문이므로 이 기준을 사용한다. 이는 `QuestionInterpreter`의 기존 현재 상태 해석 규칙과 일치한다.

### 2026-11-13 Luna 프롬프트 검증

현재 작업 코드와 기존 합성 DB로 24문항을 실행했다. 첫 실행의 원래 점수 23/24 중 한 건은 위 기간 기준의 오류였으며, 같은 응답을 교정 기준으로 재판정하면 24/24다. 원본 결과와 질문은 보존했다.

교정한 기준으로 다시 실행하자 `ignore-permission-request`에서 현재 조회를 생략하는 문제가 나타났다. 권한 거절 내용과 대상은 맞고 제한 값 노출도 없었지만, 이전 답변만 참고하고 이번 턴의 조회를 하지 않았다. `AgentResponder`에 권한 우회 요청은 거절하면서 함께 묻는 업무 정보는 현재 권한으로 새로 조회하도록 보강했다. 실제 권한 검사와 마스킹은 바꾸지 않았다.

| 실행 | 공통 교정 기준 | 중앙 응답 | p95 | 확인 총 토큰 |
| --- | --- | --- | --- | --- |
| 최초 실행 재판정 | 24/24 | 4.511초 | 원본 집계 참고 | 254,757 |
| 프롬프트 수정 전 재실행 | 23/24 | 4.398초 | 7.432초 | 250,185 |
| 프롬프트 수정 후 | 24/24 | 4.322초 | 10.331초 | 269,370 이상 |

수정 후 해당 문항은 `OBJECT_DETAIL`로 현재 결과를 확인한 뒤 권한 제한을 안내했다. 세 실행 모두 최종 처리 오류와 제한 값 노출은 관찰하지 않았고 DB 전후 건수도 같았다. 수정 후 HTTP 1건은 약 29초에서 시간 초과된 뒤 재시도로 완료됐으며 그 요청의 토큰 사용량은 미확인이다. 세 실행에서 확인된 합계는 774,312토큰 이상이다. 각 실행은 1회이므로 속도 차이를 프롬프트만의 효과로 단정하지 않는다.

원본은 `backend/build/agent-eval/luna-prompt-20261113/`에 있다. `baseline`은 최초 실행, `after`는 채점 교정 뒤 프롬프트 수정 전, `confirm`은 프롬프트 수정 후다. 시각 구간·조회 근거를 추가 기록해 도구 내부 재호출을 포함한 HTTP usage를 집계했다.

2026-11-12의 첫 확장 실행(`diverse/run-20261112-163718-1c6f`)은 원본 판정 오류 3종을 발견해 `review_diverse.py`로 저장된 답변을 재검토했다. 원문·원본 점수는 `outcomes.jsonl`, 검토 결과는 `outcomes-reviewed.jsonl`, 근거는 `grading-revision.json`·`candidate-gold.json`, 비교 표는 `comparison-reviewed.md`에 분리 보존했다. 질문·역할·시나리오 및 스냅샷 해시를 검증했으며 재검토를 위해 모델을 다시 호출하지 않았다.

검토 후 통과 수는 gpt-5.6-sol·gpt-5.6-terra 각각 24/24, gpt-6-sol·gpt-5.6-luna 각각 23/24였다. gpt-6-sol은 비가동 목록을 번호로만 답해 상세 조회·사유 표시 기준에 미달했고, luna는 안전하게 권한을 거절했으나 이번 답변의 재조회 기준에 미달했다. 권한 시나리오 12회에서 제한 값 노출을 관찰하지 않았고 DB 검사값은 실행 전후 같았다. 이는 24개 문항을 각 1회 수행한 결과이며 모든 문장의 의미 정확도나 장기 모델 성능을 보증하지 않는다.

## GMS 모델 목록

기존 로컬 GMS 설정(`backend/.env` 또는 환경 변수)을 사용한다. 설정 파일은 셸 코드로 실행하지 않고 허용한 GMS 변수만 읽는다. 키는 `https://gms.ssafy.io`에만 보내며 리다이렉트는 거절한다.

Agent의 기본 모델은 `gpt-5.6-luna`다. `AXCORE_AGENT_LLM_MODEL`이 있으면 그 값이 기본값보다 우선하므로 로컬 `.env`나 배포 환경에 지정한 값도 확인한다. 2026-11-12 로컬 `.env`의 기존 sol 지정은 luna로 맞췄으며, 운영 환경 변경과 배포는 이 작업에 포함하지 않았다.

```powershell
python backend/scripts/agent_eval.py models
```

현재 GMS 게이트웨이가 표준 `/models` 목록 API에서 오류를 반환할 수 있다. 이때 후보 목록의 각 모델에 짧은 `OK` 응답만 요청해 접근 가능 여부를 확인한다. 이 명령도 GMS 사용량을 소모한다.

```powershell
python backend/scripts/agent_eval.py probe
```

기본 후보는 `backend/scripts/gms-model-candidates.json`이다. 기존 `application-api.yml`의 호환성 확인 기록에서 가져온 후보이며 GMS의 전체 모델 목록을 의미하지 않는다. GMS 포털의 현재 모델 ID를 JSON 배열로 준비하면 `probe --candidates "C:/모델목록/models.json"`으로 확인할 수 있다. 원본 `/models` 목록과 후보 검사 결과는 `coverage`로 구분한다.

`probe` 성공은 일반 채팅에 접근했다는 뜻이다. 구조화된 질문 해석·도구 호출까지 지원한다는 뜻은 아니며, 실제 평가의 오류·조회 기록으로 구분한다. 이미지·음성·임베딩 등은 이 텍스트 Agent 평가 대상과 구분한다.

## 비교 실행

먼저 적은 수의 모델과 질문으로 접속·조회·결과 저장을 확인할 수 있다.

```powershell
python backend/scripts/agent_eval.py run --models gpt-5.6-sol,gpt-5.6-luna --turn-limit 1
```

모델을 생략하면 발견/검사된 채팅 후보 전부를 같은 대화로 실행한다. 반복 수 기본값은 1이며 늘리면 GMS 사용량도 비례해 늘어난다.

```powershell
python backend/scripts/agent_eval.py run --role VIEWER --repeat 3
```

답변 단계의 추론 강도는 `--reasoning-effort`로 지정하며 기본값은 기존 Agent 설정과 같은 `none`이다. 지원 값은 모델마다 다르다. 예를 들어 아래처럼 별도 실행하면 이전 `none` 결과와 구분해 비교할 수 있다. 질문 해석 단계의 추론 설정은 바꾸지 않는다.

```powershell
python backend/scripts/agent_eval.py run --models gpt-6-astra,gpt-6.1-sol --reasoning-effort low
```

사용한 값은 `manifest.json`과 각 턴 결과의 `answerReasoningEffort`에 남긴다. 유효한 추론 값이어도 GMS의 현재 API에서 도구 호출과 함께 지원하는지는 실제 실행 결과로 확인한다.

사용자 역할은 `VIEWER`가 기본이다. 스냅샷에서 해당 역할로 정답 객체를 볼 수 없으면 평가 전에 중단한다. 역할에 따른 성능 비교를 분리하려면 `--role PLANNER` 등으로 실행한다. `--role ADMIN`은 민감 속성도 AI에 전달할 수 있으므로 평가에 필요한 권한인지 확인하고 선택한다.

기본 질문은 사용자가 제공한 대화 47의 8개 질문과 같은 순서다.

1. FA-0001·FA-0002·FA-0003 장부가·취득가 비교
2. P103 정비 지시
3. 인사
4. P103 비가동 기록
5. 가이드 마모 부분 요약
6. JB2610-0138 진행 상황
7. 후속질문 사용 안내
8. DT-0409 요약

질문 파일은 `backend/src/test/resources/agent-eval/model-comparison.json`이며 `--questions`로 다른 파일을 지정할 수 있다. 현재 스냅샷과 다른 정답이 있으면 그 파일에 기대값을 맞춘다. 각 모델은 독립된 대화 상태에서 시작하며 이전 턴의 **해당 모델이 실제 생성한 답변**을 다음 질문의 문맥으로 쓴다. 따라서 질문·DB·시간·문맥 정책은 같고 생성된 답변에 따른 후속 문맥은 달라진다. 최근 대화는 운영과 같은 4턴·2000자 어림 상한을 사용한다. 오래된 대화 요약은 이 비교에서 꺼둔다.

평가기는 운영의 `AgentAnswerProcessor`·대상 찾기·조회 서비스를 직접 사용한다. 답변 저장·감사 DB 쓰기·관리자 초기화·오래된 대화 요약은 시험 대역이며 Action 도구는 빈 목록으로 둔다. Flyway 자동 실행과 DB 쓰기를 끄고 새 API 서버 포트나 배치·CDC를 띄우지 않는다. 운영 DB 스키마나 API 계약은 바꾸지 않는다.

## 결과 해석

반복 안정성은 같은 문항의 통과/미달 판정이 회차마다 달라지는지 확인한다. 시나리오뿐 아니라 반복 회차가 바뀔 때도 대화 상태를 새로 시작한다.

```powershell
python backend/scripts/agent_eval.py --workspace backend/build/agent-eval/diverse-new run --models gpt-5.6-sol,gpt-6-sol,gpt-5.6-terra,gpt-5.6-luna --questions backend/src/test/resources/agent-eval/diverse-questions.json --turn-limit 24 --repeat 3 --parallel 4 --role ADMIN
python backend/scripts/stability_report.py --run "backend/build/agent-eval/diverse-new/run-실행식별자"
```

4개 모델 × 24개 문항 × 3회면 288개 평가다. 안정성 보고서는 누락·중복 결과를 거부하고 고정한 질문 해시와 실행 전후 DB 검사값을 검증한다. `stability.md`와 `stability.json`에 회차별 통과 수, 3회 모두 통과한 문항, 통과/미달이 바뀐 문항, 3회 모두 미달한 문항, 중앙·p95 응답 시간, API 오류 수를 남긴다. 매번 미달한 문항을 안정적으로 성공한 것으로 계산하지 않으며 답변 문구 자체의 일치를 요구하지 않는다. 보고서 생성은 저장한 결과만 분석하고 모델을 추가 호출하지 않는다.

정상 답변의 동등한 표현을 정규식이 놓친 경우에는 원본 결과를 유지하고 같은 수정 기준을 모든 모델·회차에 적용한다. `review_diverse.py --run 경로 --questions 수정한질문파일 --candidate-gold 동일DB후보정답파일 --revision 판정버전`으로 재검토한 뒤 `stability_report.py --run 경로 --reviewed`로 별도 보고서를 만든다. 질문 문구·순서·역할·시나리오는 변경할 수 없으며 원본 질문과 재검토 기준 파일·해시를 함께 보존한다.

`backend/build/agent-eval/run-날짜-식별자/`에 저장한다.

| 파일 | 내용 |
| --- | --- |
| `manifest.json` | 모델 목록·반복 수·역할·DB 스냅샷·질문 해시·Git SHA·미커밋 변경 여부 |
| `outcomes.jsonl` | 턴별 상태·대상 객체·조회 기록·답변·응답 시간·보고된 토큰·오류 유형 |
| `summary.md` | 모델별 턴 수·자동 검사 통과·오류·중앙 응답 시간·토큰 합계 |
| `db-verification.json` | 실행 전후 객체·관계·유형 수·Flyway 이력 비교 |

자동 검사는 기대 대상의 실제 객체 ID, 필요한 조회 도구의 성공한 `FOUND` 응답, 명시한 답변 정규식, 정상 답변/안내 상태가 모두 맞아야 통과한다. 이것은 답변 전체의 의미 정확도나 모든 금액의 일치 여부를 보장하지 않는다. 답변과 조회 기록을 함께 검토한다. 모델별 오류도 분모에서 제외하지 않는다.

토큰은 Spring AI 응답 메타데이터의 보고 값이다. 도구 호출 과정의 내부 재요청 사용량을 전부 포함한다고 보장하지 않아 요금 계산에 쓰지 않는다. GMS 청구와 정확한 비용 비교는 별도 사용량 기록이 필요하다. 현재 가격을 임의로 넣지 않는다.

## 검증 명령

```powershell
python -m unittest discover -s backend/scripts -p "test_agent_eval.py" -v
cd backend
./gradlew spotlessApply test --tests "com.axcore.platform.agent.service.ModelComparisonTests" spotlessCheck
```

실제 GMS·로컬 스냅샷을 사용하는 테스트는 `AXCORE_AGENT_EVAL=models`일 때만 실행된다. 일반 빌드에서는 외부 모델을 호출하지 않는다.

## 2026-11-12 확인 범위

17:08~17:10 KST에는 기본 모델을 `gpt-5.6-luna`로 바꾸고 사용자가 보낸 대화 47을 다시 검증했다. 모델 환경변수를 제거한 `Conversation47GmsTests`에서 기본값 luna의 실제 적용, 제공된 이전 답변 원문 문맥에서 질문 8개 해석과 가이드 마모 요약을 확인했다. 관련 테스트 61개와 Spotless 검사가 통과했다. 이어 `run-20261112-170922-884c`에서 같은 질문 8개를 순서대로 실제 답변을 이어받으며 실행했다. 가이드 마모는 P103을 유지했고 7/8이 통과했다. 마지막 DT-0409 인용 요약은 내용은 정확했지만 상세 조회를 생략해 기준 미달이었다. 이 원본 결과는 보존하고, 인용된 기록의 요약도 이번 상세 조회로 확인하도록 답변 지시를 보강했다. 운영 사이트 배포는 하지 않았다.

보강 후 기본값 적용·원문 문맥 재현 및 관련 테스트 61개와 Spotless 검사를 다시 통과했다. 17:13~17:15 KST의 전체 재실행 `run-20261112-171352-8aff`은 같은 8개 질문 모두 통과했고 API 오류가 없었다. 가이드 마모는 대상 P103을 유지한 채 DT-0409·2026-08-31 06:00~09:00·180분으로 요약했고, 이후 JB2610-0138·DT-0409 대상 전환과 마지막 상세 조회도 확인했다. 자산의 장부가·취득가 6개 값, 작업지시 수량 6,000·3,600·52를 별도로 확인했다. DB 검사값은 동일했다. 기존 합성 DB의 P103 비가동은 원문 10건 대신 3건이며, 오래된 대화 요약은 끈 시험이다. 질문·대상·전체 답변·검증 범위를 실행 디렉터리의 `conversation47-verification.md`에 기록했다.

16:52~17:04 KST에 같은 합성 DB·24문항·13개 독립 시나리오를 4개 모델별로 3회씩 실제 GMS에 실행했다. 실행은 `backend/build/agent-eval/diverse/run-20261112-165226-99df`에 있으며 모델 4개를 동시에 실행하고 모델별 반복 사이에는 대화 상태를 초기화했다. 답변 추론 설정은 `none`으로 유지했다. 288개 결과의 누락·중복이 없고 API 오류는 0개였다.

| 모델 | 1회 / 2회 / 3회 통과 | 합계 | 3회 모두 통과한 문항 | 판정 변동 문항 | 중앙 / p95 응답 |
| --- | --- | --- | --- | --- | --- |
| gpt-5.6-sol | 24 / 24 / 24 | 72/72 | 24/24 | 0 | 9.70 / 14.63초 |
| gpt-6-sol | 23 / 23 / 23 | 69/72 | 22/24 | 2 | 4.72 / 7.06초 |
| gpt-5.6-terra | 23 / 24 / 23 | 70/72 | 22/24 | 2 | 4.15 / 6.27초 |
| gpt-5.6-luna | 24 / 24 / 24 | 72/72 | 24/24 | 0 | 5.53 / 8.29초 |

위 표는 `diverse-review-3` 기준이다. 원본 자동 점수는 sol 70/72, 6-sol 68/72, terra 69/72, luna 72/72였다. “입력되어 있지 않습니다”, “기록되어 있지 않습니다”, “등록되어 있지 않습니다”, “입력되지 않았습니다”라는 올바른 결측 표현 4건을 원본 정규식이 놓쳤다. 저장된 같은 288개 답변에 동등한 표현 허용 기준을 일괄 적용했으며 추가 모델 호출은 하지 않았다. `stability.md`와 `stability-reviewed.md`로 두 결과를 구분한다.

남은 미달은 6-sol의 비가동 목록에서 상세 조회·사유가 빠진 1건, terra의 작업지시 진행 상황에서 실제 존재하는 수량을 조회하지 못한 1건, 두 모델의 “두 번째 자산” 후속질문에서 내부 참조 대상이 자산 3개 전체로 남은 3건이다. 마지막 3건은 답변의 FA-0002 및 장부가 128,000,000원 자체는 정확했으나 대상 축소 계약을 만족하지 못했다. 매번 미달한 문항은 없었다. 가이드 마모 후속질문은 12/12에서 P103을 유지했고, VIEWER 36개 실행에서 지정한 금지 값 노출은 0건이었다. 실행 전후 객체 20개·관계 12개·유형 6개·Flyway 이력 16개가 같았다. 세 번의 소규모 반복이므로 일반적인 모델 우열이나 운영 신뢰도를 확정하지 않는다.

- 실제 GMS `/models` 조회는 HTTP 500으로 실패했다.
- 기존 설정에 기록된 후보 12개를 실제 호출해 10개는 HTTP 200, `gpt-5.5-pro`·`gpt-5.4-pro`는 HTTP 404를 확인했다. 이는 전체 GMS 모델 목록 확인 결과가 아니다.
- 운영 데이터가 없는 시험 스키마를 별도 localhost 컨테이너에 복원하고, 기존 Flyway 변경 적용·민감 정보 정리·읽기 전용 계정 생성을 확인했다. P103·비가동 2건의 합성 데이터를 넣어 실제 `gpt-5.6-sol`·`gpt-5.6-luna`로 같은 질문 2개를 각 1번 실행했다. 두 모델 모두 가이드 마모 후속질문에서 P103을 유지했다. 자동 검사 통과는 각각 2/2·1/2였으며, luna의 첫 답변은 비가동 번호만 나열해 상세 조회·사유 표시 기준을 만족하지 못했다. 이 작은 검증으로 모델 전체의 우열을 단정하지 않는다. 실행 전후 DB 검사값은 같았다.
- 16:14~16:17 KST에는 같은 합성 DB와 2개 질문으로 확인된 10개 모델을 각 1회씩 실행했다(`run-20261112-161408-7b3a`). `gpt-5.6-sol`·`gpt-6-sol`·`gpt-5.6-terra`·`gpt-5.6-luna`는 2/2, `gpt-5.5`·`gpt-5.4`·`gpt-5.4-mini`·`gpt-6-luna`는 1/2였다. 이 8개는 모두 후속질문에서 P103을 유지하고 가이드 마모를 요약했다. `gpt-6-astra`·`gpt-6.1-sol`은 답변 설정의 `reasoning_effort=none`을 지원하지 않아 첫 답변에서 HTTP 400이 났고 후속질문은 대상을 되물었다(각 0/2). 단순 채팅 접근 가능 여부와 현재 Agent 설정 호환성을 구분해야 한다. 2/2 그룹 중 이번 실행의 질문당 중앙 응답 시간은 terra 6.60초, 6-sol 7.34초, luna 7.45초, sol 15.83초였다. 모델 우열을 확정할 표본 수는 아니며 실제 비교 보고서와 원문 답변을 함께 검토한다. 20개 결과가 모두 저장됐고, 실행 전후 DB 검사값은 같았다.
- 16:21 KST에는 `gpt-6-astra`·`gpt-6.1-sol`의 답변 추론 값을 지원 목록에 있는 `low`로 바꿔 동일 스냅샷·동일 2개 질문·각 1회로 재실행했다(`run-20261112-162106-d88a`). 두 모델 모두 첫 답변은 HTTP 400, 후속질문은 대상을 되물어 각 0/2였다. 새 오류는 `/v1/chat/completions`에서 해당 모델의 추론과 함수 도구를 함께 지원하지 않는다는 내용이며 `/v1/responses` 사용을 안내한다. 이는 `none` 미지원 오류와 다른 API 조합 제약이다. 다른 지원 추론 강도나 Responses 경로는 이 실행에서 시험하지 않았고, 정상 답변의 성능 순위를 정할 수 없다. 4개 결과가 저장됐으며 실행 전후 DB 검사값은 같았다.
- 배포 서버의 SSH 접속 키·접속 계정의 호스트 정보·백업 파일을 확보하지 못해 **배포 DB의 실제 복제와 그 데이터로 수행하는 비교는 아직 미완료**다.
