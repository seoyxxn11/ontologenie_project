# S15P31C106-422 검증 기록

- 기준: 최신 `origin/develop` 828a091. 작업 후 재-fetch에서도 동일함(2026-11-14).
- 브랜치: `fix/422-rule-editor-selection`.
- 범위: FN-REV-02 후보 편집 동선, 목록 상태 복원, 기존 관계·양쪽 속성의 이름/코드 검색 선택.
- BE·DB·CDC·배치·Agent·인증/인가 구조·384/390/391 정책·인프라는 변경하지 않음.

## 구현과 계약

- PENDING/HELD LINK_RULE의 수정 링크를 상세 헤더에 통합. 기존 탭 내부 중복 링크 제거.
- 검색·필터·후보·페이지·분석 범위를 URL 쿼리로 편집/복귀/저장 상세에 전달. 현재 후보 ID도 명시.
- GET `/api/v1/ontology/link-types?page=0&size=100` 이후 `meta.hasNext`에 따라 모든 페이지 조회.
- 선택 관계의 `sourceTypeId`/`targetTypeId`로 GET `/api/v1/ontology/object-types/{id}` 및 `/{id}/properties` 조회.
- 이름과 코드를 함께 표시하고 선택 값은 기존 저장 계약의 관계 ID 및 속성 코드(유형 코드 접두어 포함)로 전달.
- 관계 변경 시 속성 선택만 비우고 변환 단계는 유지. 민감 속성은 선택지에서 제외하며 최종 검증은 서버 담당.
- 같은 분석의 미확정 관계 코드는 별도 입력 방식으로 보존. 기존 미확정 속성·묶음 정의·복수 후보·변환 순서도 보존.
- POST `/api/v1/proposals/manual`, POST `/api/v1/proposals/{id}/revisions` 계약 유지. baseRevision 및 복수 후보 revision 검증.
- 409/권한 오류·응답 유실/불명확한 성공 응답/서버 오류 후 같은 화면의 재전송 차단. 400/422는 입력을 유지하여 수정 가능.
- 저장 후 자동 검사 요청과 새 개정 재검사 필요 안내. 이전 검사로 프론트가 승인 상태를 만들지 않음.

## 자동 검사

`frontend/`에서 실행:

- 관련 회귀 검사: `npm test -- --run src/features/review/RuleEditorPage.test.tsx src/features/review/ProposalsPage.test.tsx --reporter=dot` 40개 통과(최종 조건 삭제 회귀 검사 추가 전).
- 최종 전체 검사: `npm test -- --run --reporter=dot` **81개 파일, 1,135개 테스트 통과**.
- `npm run lint`: 통과.
- `npm run format:check`: 통과.
- `npm run build`: TypeScript 및 Vite 빌드 통과, 번들 크기 경고 없음.
- `git -c core.autocrlf=false diff --check`: 통과.
- BE 변경이 없으므로 BE 테스트는 실행하지 않음.

## Chrome 모의 환경 E2E

개발 서버 `http://127.0.0.1:5177`, MSW의 가상 관리자/조회 계정과 가상 후보만 사용.

1. 검색어 `설비`, 후보 #302 선택 후 수정 진입. 편집/복귀 URL의 `q`/`candidate` 유지.
2. 관계를 반대 방향으로 변경하면 양쪽 속성이 초기화되고 재선택 안내 표시.
3. 관계·속성을 이름으로 검색/선택하고 가상 후보 저장. 개정 2 및 자동 검사 #1304, 재검사 필요 안내 확인.
4. 목록 복귀 후 새로고침에서도 검색어와 선택 후보 #302 유지.
5. 조회 계정으로 편집 URL 접근 시 접근 권한 없음 화면. 저장 UI 미노출.

MSW 데이터는 페이지 새로고침 시 초기화되는 가상 데이터다. 이 결과를 실제 서버 저장 성공으로 해석하지 않는다.

- [모의 저장](mock-save.png)
- [목록 상태 복원](mock-restored.png)
- [조회 역할 접근 차단](mock-viewer-denied.png)

## 운영 API 읽기 전용 대조

기존 Chrome 관리자 세션에서 GET만 실행. 객체 값/원문/비밀정보는 수집하지 않고 정의 메타데이터 필드만 확인.

- `/api/v1/ontology/link-types?page=0&size=100`: 39건, `meta.hasNext=false`, 관계 ID/이름/코드/양쪽 유형 ID/rowVersion 일치.
- 관계 #29는 작업지시 → 품목, sourceTypeId=4, targetTypeId=11.
- `/api/v1/ontology/object-types/4`: code=WORK_ORDER, name=작업지시.
- `/api/v1/ontology/object-types/4/properties`: 14건, 모두 objectTypeId=4. product_code(생산 제품 코드), sensitivity=NORMAL 확인.
- `/api/v1/ontology/object-types/11`: code=ITEM, name=품목.
- `/api/v1/ontology/object-types/11/properties`: 9건, 모두 objectTypeId=11. item_code(품번), sensitivity=NORMAL 확인.
- 서버 LinkTypeResponse, PropertyResponse, ProposalPayloadValidator와 요청/응답 사용 필드를 대조함.

## 별도 컨텍스트 코드 리뷰

프로젝트 code-review/merge-request Workflow에 따라 별도 서브에이전트가 전체 변경과 관련 계약을 읽기 전용으로 검토.

- 최초: Critical 0 / Major 1 / Minor 0.
- Major: 앞 조건 삭제 후 뒤 조건의 미확정 속성을 배열 인덱스로 잘못 비교하여 저장 차단.
- 수정: 조건 자체의 원본 필드와 비교. 선행 조건 삭제 후 미확정 속성·변환 보존 회귀 검사 추가.
- 재리뷰: 추가 Critical/Major/Minor 0, 기존 Major 해결 확인.
- AI 리뷰는 팀원 리뷰·사람의 승인을 대신하지 않음.

## 미검증·남은 확인

- 실제 BE 저장/개정/자동 검사 완료 E2E: 합의된 쓰기 환경·데이터가 없어 미실행. 운영 데이터 쓰기 없음.
- 실제 조회 역할 서버 정책 전체, 재시도 횟수/장시간 반복 안정성/민감값 노출 전수 검증: 미검증.
- 팀원 MR 리뷰·병합 및 병합 후 운영 UI 확인: 사람의 후속 작업.
