# 396 경로별 노출 검증 — 2026-11-14

384 데이터 접근 통제(390~395, 398~406)가 develop(`d458a420`)에 모두 들어간 뒤, 같은 시험 값이 허용된 곳에만 나오는지 경로 × 역할 × 등급으로 확인했다. 운영 원천·운영 플랫폼에는 아무것도 쓰지 않았다. 운영 원천에는 아직 허용 목록이 적용되지 않았다(397).

## 결론

| 완료 기준 | 결과 |
|---|---|
| 금지 값은 어디에도 없음 | 원천에서 세 계정 모두 못 읽고, CDC 변경 흐름에 실리지 않는다. 플랫폼은 매핑·적재 계획·분석 접수·후보 시험·표본에서 거절한다. **운영에는 397 전까지 예전 사본(객체·Kafka 토픽·백업)이 남아 있다** |
| 제한 값은 PLANNER 화면에서만 보임, 모델 입력에는 없음 | 확인. 기본 역할 범위(V21) 그대로 PLANNER만 화면에서 보고, ADMIN·VIEWER는 못 본다. PLANNER가 물어도 Agent 도구 결과에는 없고, 민감 속성으로 거르는 것도 거절된다 |
| 일반 값은 통합 연결대로 보임 | 확인. 세 역할 화면과 모델 입력에 모두 나온다. 그룹 없는 유형은 아무에게도 안 보인다 |
| 검증 기록 문서 | 이 문서 |

발견 사항: 자유 글 속 사람 이름 가림(AI 쪽 정규식)이 직함 없는 이름과 네 글자 이름을 놓친다(아래 "발견 사항" 1). 금지·민감 분류 밖의 문장 속 값이라 완료 기준과는 별개이고, 받아들인 위험으로 정했다.

## 실행한 확인

| 무엇 | 명령 | 결과 |
|---|---|---|
| 원천 SQL·CDC | `bash infra/tests/source_exposure_check.sh` (일회용 `postgres:18.6`, `data-v1.0`, `setup_roles.sql`) | 31건 모두 기대대로, 종료 코드 0 |
| 화면·모델 입력 교차(새 시험) | `./gradlew test --tests 'com.axcore.platform.auth.ExposureMatrixTests'` | 3개 통과 |
| AI 이름 가림 | `app.llm.privacy.mask_text`에 시험 문장 11개 | 아래 "발견 사항" 1 |
| 기존 자동 시험 | 경로별 표의 시험들. develop 전체 백엔드 1,912개·프론트 1,062개·AI 498개가 384 MR 6개를 합친 상태에서 통과(2026-11-14 새벽) | 통과 |

## 1. 원천 (허용 목록 적용 뒤)

시험 값은 행 하나를 바꿔 넣고 pgoutput 슬롯(`publication axcore_cdc`)에서 꺼낸 변경에 그 글자가 있는지 봤다. Debezium이 받는 흐름과 같고, Kafka 토픽은 이것을 옮긴 것이다(Debezium 자체는 실행하지 않았다).

| 등급 | 컬럼 | platform_ro | platform_ai | platform_cdc | CDC 변경에 실림 |
|---|---|---|---|---|---|
| 금지 | `erp.attendance`(표 전체) | 못 읽음 | 못 읽음 | 못 읽음 | 안 실림(`work_type`) |
| 금지 | `erp.fixed_asset`(표 전체) | 못 읽음 | 못 읽음 | 못 읽음 | — |
| 금지 | `erp.change_log`·`mes.change_log`(표 전체) | 못 읽음 | 못 읽음 | 못 읽음 | 안 실림(`erp.change_log.row_key`) |
| 금지 | `erp.employee`의 `emp_name`·`position`·`hire_date`·`employment_type`·`status` | 못 읽음 | 못 읽음 | 못 읽음 | 안 실림(`emp_name`·`position`) |
| 금지 | `mes.worker.name` | 못 읽음 | 못 읽음 | 못 읽음 | 안 실림 |
| 제한 | `mes.maintenance_order.technician`, `erp.vendor.contact_name`, `mes.worker.skill_level` | 읽음 | 못 읽음 | 읽음 | 실림(`technician`·`contact_name`) |
| 제외 | 회계 표(`sales_invoice`·`journal_entry`) | 못 읽음 | 못 읽음 | 못 읽음 | — |
| 일반 | `employee.emp_no`·`dept_code`, `worker.id`, `vendor.vendor_name`, `maintenance_order.mo_no`·`note`, `item.item_name` | 읽음 | 읽음 | 읽음 | 실림 |

publication 컬럼 목록: `erp.employee (emp_no, dept_code)`, `mes.worker (id, shift, line, skill_level, active_yn)`. 금지 표는 publication에 없다.

## 2. 플랫폼 (경로 × 역할 × 등급)

역할 범위는 V21 기본값: PRODUCTION·SALES_SUPPLY를 세 역할이 보고, 민감 값은 PLANNER만 본다. "모델"은 Agent가 외부 모델에 넘기는 도구 결과(MODEL 용도)다. 금지 등급은 플랫폼에 들어오기 전에 막히므로 "막는 곳"으로 적었다.

| 경로 | ADMIN | PLANNER | VIEWER | 모델 | 금지 등급을 막는 곳 | 근거 시험 |
|---|---|---|---|---|---|---|
| 원천 카탈로그·표본(016)·매핑 미리보기(040·102) | 일반만 | (ADMIN 전용) | (ADMIN 전용) | — | 금지 표 실패, 금지·민감 컬럼은 빼고 읽거나 422 | `SourceSamplePostgresTests`, `SourceSampleJobTests`, `MappingPreviewApiTests` |
| 분석 프롬프트(018 시작 알림) | — | — | — | 금지·민감 컬럼 빼고 보냄 | 금지 표 분석은 422, 대기 중 실행도 시작 때 다시 거름 | `AnalysisStartApiTests.sendsOnlyNormalColumnsOfPolicyTablesToAi`·`failsQueuedRunThatTargetsForbiddenTableWithoutCallingAi`, `AnalysisColumnScopeTests` |
| 제안 수신(105)·제안 근거(049·선택지·개정) | 일반만, 민감·그룹 없는 값 가림 | (ADMIN 전용) | 403 | — | 금지 위치 근거는 값을 비움, 민감 제안은 접수 거절 | `AiProposalSourcePolicyTests`, `ProposalWriteApiTests`, `StoredCopyScopeApiTests` |
| 객체 목록(076)·상세(077) | 일반 | 일반·**민감** | 일반 | 일반 | 적재 계획 거절(정책 위반 매핑) | **`ExposureMatrixTests`**, `ObjectSearchApiTests`, `FullLoadPlannerPolicyTests` |
| 그룹 없는 유형 | 안 보임(404) | 안 보임 | 안 보임 | 안 보임 | — | **`ExposureMatrixTests.ungroupedTypeIsVisibleToNoRole`** |
| 값으로 찾기·ID 읽기·건수 | 일반 | 일반·민감 | 일반 | 민감 가림 | — | `ObjectSearchApiTests`, `ObjectQueryModelPurposeTests` |
| 관계·관계 근거·묶음 건수 | 민감 근거 가림 | 민감 근거 보임 | 민감 근거 가림 | 민감 근거 가림 | — | `ObjectSearchApiTests.documentEvidence…`·`multiConditionRuleEvidence…`, `ObjectQueryModelPurposeTests.modelGetsLinkEvidenceRestricted` |
| Agent 도구(목록·상세·관계·검색·값 표본·Object Set) | — | — | — | 일반만. PLANNER가 물어도 민감 없음, 민감 속성 조건은 DENIED | — | **`ExposureMatrixTests.modelInputHasNoRestrictedValueEvenWhenPlannerAsks`**, `AgentObjectListToolsIntegrationTests`, `AgentObjectSetToolsIntegrationTests` |
| Action 결과(091·092)·재배정 계획(093~095)·차단 문구 | 민감 가림 | 민감 보임(기록 남음) | 민감 가림 | 민감 가림 | — | `ActionExecutionApiTests`, `ReassignmentPlanQueryApiTests`, `AuditValueMaskerTests` |
| 감사(097·098) | 허용 목록 밖 값 가림 | (ADMIN 전용) | (ADMIN 전용) | — | — | `AuditExecutionApiTests`, `AuditValueMaskerTests` |
| 저장된 사본: 문제(065~070)·검사 결과(062)·재검증 비교·후보 시험 표본 | 일반만, 민감·그룹 없는 값 가림 | (ADMIN 전용) | 403 | — | 후보 시험은 금지 표·컬럼이면 원천을 읽지 않음, 민감 값은 건수·분류만 저장 | `StoredCopyScopeApiTests`, `CandidateCheckJobTests` |
| 민감 값 열람 기록 | — | 보인 경로마다 한 행(값 원문 없음) | — | — | — | `RestrictedViewLogApiTests` |

"(ADMIN 전용)"은 그 API를 ADMIN만 부를 수 있다는 뜻이다(PLANNER·VIEWER는 403). 민감 값을 여는 역할이 그 API를 쓰지 않으므로 그 화면에서 민감 값은 누구에게도 보이지 않는다.

## 3. 발견 사항

1. **자유 글 속 이름 가림이 놓치는 경우가 있다(AI, 외부 모델 경로) — 받아들인 위험으로 결정.** `app/llm/privacy.py`는 성씨 목록 + 직함이 붙은 이름과 전화번호를 가린다. 문서 원문·메모를 LLM에 보낼 때와 문서 연결 근거 `originalText`에 쓴다.

   | 문장 | 결과 |
   |---|---|
   | 김민수 대리가 금형을 교체함 | 가림 |
   | 황보현 반장 인계 / 박 반장에게 인계 | 가림 |
   | 연락처 010-1234-5678 | 가림 |
   | 이 설비는 김포 공장 소속 | 안 가림(맞음, 지명) |
   | 김민수가 교체함 / 담당 김민수 / 홍길동 작성 | **안 가림**(직함 없음) |
   | 남궁민수 대리 확인 | **안 가림**(두 글자 성 + 두 글자 이름, 정규식이 성 뒤 0~2글자만 봄) |
   | 독고영재 확인 / Kim 대리 확인 | **안 가림** |

   인계 메모처럼 사람 이름이 자주 나오는 칸에서는 직함 없는 이름이 외부 모델과 저장된 근거로 나갈 수 있다. 그래도 받아들이기로 했다(`docs/data-access.md` "자유 글 속 사람 이름").

   - 메모 칸은 일반 속성이라 화면에서는 원래 이름이 그대로 보인다. 외부 모델로 갈 때만 가리는 것이라 완벽하게 해도 노출이 크게 줄지 않는다.
   - 사람을 특정하는 정형 값(직원 이름·직급 등)은 컬럼 단위로 금지·민감이고, 사람 단위로 모아 보는 질문은 393 가드레일이 거절한다.
   - 직함 없는 이름까지 잡으려면 개체명 인식이나 넓은 패턴이 필요해 오탐("김포 공장")과 인계 메모 내용 손상이 크다.
   - 지금 데이터는 가상 기업 데이터다. 실제 사람 데이터를 다루게 되면 다시 정한다. 네 글자 이름("남궁민수 대리")은 정규식 수정으로 고칠 수 있지만 이 결정과 어긋나지 않아 지금은 두었다.
2. **Debezium은 직접 실행하지 않았다.** pgoutput 변경 흐름에서 금지 값이 빠지는 것은 확인했지만, 커넥터 설정(`column.exclude.list` 등)과 실제 토픽 메시지는 397에서 커넥터를 다시 등록한 뒤 확인한다.
3. **운영은 아직 이 상태가 아니다.** 운영 원천은 예전 권한이고, 플랫폼 DB·Kafka 토픽·DB 백업에 금지 값 사본이 남아 있다(근태 1,660건·고정자산 8건 객체 등, 그룹이 없어 아무도 못 봄). 기동 때 `SourcePolicyCatalogCheck`가 "금지 표·컬럼이 카탈로그에 보인다"고 경고한다. 397에서 정리한다.
4. **REPLICATION 권한의 한계는 그대로다.** `platform_cdc`가 다른 출력 플러그인으로 슬롯을 만들면 publication과 관계없이 변경을 읽는다(`infra/source-db/README.md` "한계").

## 재현

```bash
bash infra/tests/source_exposure_check.sh
```

```bash
cd backend && ./gradlew test --tests 'com.axcore.platform.auth.ExposureMatrixTests'
```

시험 값: 원천 `FORBID396*`·`FBD396ATT`(금지), `RESTRICT396*`(제한), `NORMAL396*`(일반). 플랫폼 `N396-REF`(일반), `R396-OWNER`(민감), `U396-HIDDEN`(그룹 없는 유형).
