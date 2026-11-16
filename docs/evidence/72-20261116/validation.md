# S15P31C106-72: 객체 검색의 원천 키 가림 조회 최적화

## 대상과 준비 확인

- [Jira 72](https://ssafy.atlassian.net/browse/S15P31C106-72)의 대상은 71번에서 가장 느렸던 `GET /api/v1/objects?q=P103&page=0&size=20`이다.
- [71번 측정](https://ssafy.atlassian.net/browse/S15P31C106-71?focusedCommentId=336100)의 API 왕복 p95는 단일 약 1.57초, 동시 5개 약 4.12초였다. 이 값과 아래 SQL 실행 시간을 혼합해 개선율을 계산하지 않는다.
- 기존 `axcore` SSH 접근 및 backend health HTTP 200을 확인했다. backend·PostgreSQL·AI healthy, nginx running이었다.
- 객체 3,397개·유형 25개·속성 185개·연결 원천 2개와 시연 객체 3이 존재한다. 준비 확인의 시각·시작 상태·이미지·건수 요약은 [readiness.json](readiness.json)에 있다.
- 운영 backend·batch 이미지는 `axcore-backend:3b70c945`였다. 해당 Git 리비전과 비교 기준 `e9a3e080`의 `ObjectDisplaySql.java` 내용은 같았다. 현재 코드에는 검색 시 트랜잭션 범위에서 JIT를 끄는 처리가 이미 있어 같은 조건으로 비교했다.

## 원인

기존 원천 키 가림 SQL은 객체의 원천 키마다 `property_source_mapping`·`source_column`·`property_definition`을 join하고 모든 매핑 속성이 NORMAL인지 다시 집계했다. 같은 매핑을 공유하는 객체가 많아도 반복했다.

읽기 전용 실행 계획에서 페이지 조회 하나에 `property_source_mapping` 순차 스캔 **15,452회**, `source_column` 순차 스캔 **5,396회**가 나타났다. 개선 후 두 스캔은 각각 **1회**였다. [sql-comparison.json](sql-comparison.json)의 실행 계획 노드에 시간·행 수·반복 수·버퍼 통계를 저장했다. 상위·하위 노드 시간은 포함 관계이므로 합산해 병목 비율을 주장하지 않는다.

## 조치

`ObjectDisplaySql.SOURCE_SCOPE_JOINS` 한 곳에서 **매핑 ID·객체 유형 ID·원천 컬럼별 공개 여부를 먼저 집계**하고 객체 조회가 그 결과를 join하도록 바꿨다.

- 같은 원천 컬럼에 연결된 속성이 전부 NORMAL일 때만 공개한다.
- RESTRICTED가 하나라도 있거나 매핑이 없으면 기존처럼 가린다.
- 민감 값 열람 권한이 있으면 기존처럼 원천 키를 공개한다.
- 역할·유형 범위, 표시 이름 복구, 검색 조건·정렬·페이지·전체 건수, API 응답 형식은 유지한다.
- 인덱스·스키마·연결 풀·캐시·nginx·컨테이너 자원은 바꾸지 않았다. 운영 파일 수정·수동 배포도 하지 않았다.

공통 SQL 조각이므로 객체 상세·관계·공통 읽기·Agent 조회에도 사용된다. 관련 통합 테스트로 범위·가림 동작의 영향을 확인했다. 신규 회귀 테스트는 서로 다른 매핑·유형, 한 컬럼의 NORMAL/RESTRICTED 혼합 매핑, 속성 민감도 변경 직후의 가림을 검사한다.

## 같은 데이터의 SQL 전후 비교

KST 2026-11-16 **21:02:39~21:03:30**, 같은 운영 DB에서 기존 SQL과 작업 트리 SQL을 비교했다. 비교 기준은 `e9a3e080`, 검색어 P103·ADMIN의 실제 유형/가림 범위·ACTIVE·첫 페이지 20개다. `jit=off`는 현재 API 검색의 설정과 같다.

각 조건·버전별 20회이며, 하나의 표본은 페이지 조회와 전체 건수 조회의 `EXPLAIN ANALYZE` Execution Time 합이다. p95는 nearest-rank이다. **네트워크·인증·서비스 로직·계획 수립·psql 연결 시간을 포함하지 않는 SQL 실행 시간**이다.

| 조건 | 기존 평균(ms) | 개선 평균(ms) | 기존 p95(ms) | 개선 p95(ms) |
| --- | ---: | ---: | ---: | ---: |
| 단일 | 910.6 | 146.3 | 1036.4 | 186.1 |
| 동시 5개 | 2286.1 | 303.6 | 2395.6 | 342.4 |

페이지의 반환 행·순서와 전체 건수는 DB 안에서 기존·개선 결과를 JSON 집계로 비교해 모두 같음을 확인했다. 객체 응답 원문을 외부로 내보내거나 저장하지 않았다. [sql-summary.json](sql-summary.json)은 위 표의 재계산 요약이다.

실행 예:

```powershell
python infra/scripts/profile_object_search.py --baseline-ref e9a3e080 --samples 20 --output docs/evidence/72-20261116/sql-comparison.json
```

도구는 저장소의 실제 Java SQL 상수를 조합한다. 원격 Python은 SSH stdin으로 전달하며 서버 파일로 저장하지 않는다. DB 요청은 READ ONLY 트랜잭션·statement timeout 5초·lock timeout 1초를 적용하고 종료 시 rollback한다. `SET LOCAL`만 사용해 운영의 전역 설정을 바꾸지 않는다. baseline-plan은 변경 전 두 번의 진단, optimized-plan은 첫 후보 진단이며 반복 비교 표본과 구분한다.

## 검증

- `backend/`에서 `gradlew.bat spotlessApply test --tests '*ObjectSearchApiTests' --tests '*ObjectQueryModelPurposeTests' --tests '*ScopedObjectReaderTests' --tests '*ObjectSearchAroundIntegrationTests'`: **56개 통과**, 실패·오류·skip 0.
- `python -m unittest discover -s infra/tests -p test_profile_object_search.py -v`: **4개 통과**.
- `python -m py_compile infra/scripts/profile_object_search.py`: 통과.
- backend 전체 `gradlew.bat build`: **BUILD SUCCESSFUL**. 테스트 2,027개, 실패·오류 0, skip 38. 완료된 결과를 재사용한 최종 build 확인도 성공했다.
- Python Ruff는 로컬 환경에 없어 실행하지 않았다. 의존성을 추가하지 않았다.
- API·스키마 변경이 없으므로 API 명세·ERD 수정은 필요하지 않다.

## 남은 확인

- 개선 코드의 MR 리뷰·병합·Jenkins 배포 및 실제 API 왕복 전후 비교는 아직 실행 전이다. 위 SQL 결과를 배포 후 HTTP 결과로 표현하지 않는다.
- 비기능 응답 수치 목표는 기획서에서 미정이므로 목표 달성·미달은 판정하지 않는다.
- 독립 서브에이전트 리뷰: Critical 0 / Major 0 / Minor 0. Java·Python·증거 파일과 호출부를 읽고 통계 재계산을 확인했다. 운영 측정을 독립 재현한 것은 아니며 사람의 검토·승인을 대신하지 않는다.
- MR 생성 전 최신 `origin/develop`을 fetch해 기준 `e9a3e08093e533fee7d8a062b2640c0daae9af37`과 동일함을 확인했다. 이후 71번 MR이 병합되어 `03227ecf`로 전진한 develop을 다시 fetch·merge했고 충돌이 없었다. 추가된 내용은 71번 측정 도구·테스트·증거이며 백엔드 코드는 변경되지 않았다. SQL 측정 비교 기준은 계속 `e9a3e080`이다.
- [MR !529](https://lab.ssafy.com/s15-final/S15P31C106/-/merge_requests/529)를 생성했다. 담당 손주연·검토 장민화, Squash 및 사용자 요청 자동 병합 대상이다. CI와 사람의 검토 결과는 MR에서 확인한다.
- [Jira 결과 코멘트](https://ssafy.atlassian.net/browse/S15P31C106-72?focusedCommentId=336139)에 SQL 전후 표와 증거 ZIP을 등록했고, [Notion Infra 보고서](https://app.notion.com/p/41abdf14d2d880998cd7f9717fa8f29d)를 저장 후 재조회했다.
- 장민화의 검토 승인·병합 후 HTTP 왕복 검증은 남아 있다. Jira 상태는 자동 변경하지 않았다.
- 사용자가 최신 develop 반영·검증·Commit·Push·MR 생성(담당 손주연)·자동 병합 등록을 요청했다. 각 단계의 실제 결과를 확인하며 진행한다.
