# 포팅 매뉴얼 (exec)

OntoloGenie(에이엑스코어 온톨로지 플랫폼, SSAFY 15기 자율 프로젝트 C106)의 빌드·배포·시연에 필요한 자료입니다. GitLab 저장소를 클론한 뒤 아래 순서로 읽습니다. 환경별 접속 키와 서비스 비밀값은 운영 담당자가 별도로 준비합니다.

| 번호 | 문서 | 내용 |
| --- | --- | --- |
| 1 | [1-build-deploy.md](1-build-deploy.md) | 사용 도구와 버전(JVM·웹서버·WAS 설정 값, IDE 포함), 빌드 방법, 환경 변수, 서버 배포 절차, 배포 특이사항, DB 접속 정보, 주요 계정과 설정 파일 목록 |
| 2 | [2-external-services.md](2-external-services.md) | 외부 서비스(LLM, 알림, 인증서, 도메인 등)의 용도와 설정 방법 |
| 3 | [3-db-dump/](3-db-dump/README.md) | 최신 V29 스키마·초기 데이터와 원천 ERP·MES 합성 데이터. 운영 시연 상태를 복제한 덤프는 아님 |
| 4 | [4-demo-scenario.md](4-demo-scenario.md) | 시연 순서에 따른 화면별·동작별 시나리오(메뉴·버튼 위치와 클릭 순서 포함) |

- 설정 대조 기준: `develop`의 `f10dbd37cb668a242887969424c57266f8d33c9f` (2026-11-18). 문서의 기준이며 브랜치나 서버를 변경하는 명령이 아닙니다.
- 새 환경: 3장의 덤프 범위를 먼저 확인 → 1장의 최초 설치(덤프 사용 시 앱 기동 전에 3장의 복원 수행) → 4장의 `빈 플랫폼에서 시연 상태 준비` → 발표 시연 순서입니다. 이미 시연 데이터가 있는 환경은 준비 확인 후 4.2부터 진행합니다.
- 명령 실행 위치는 각 코드 블록의 안내를 따릅니다. 서버 설치 명령은 Ubuntu의 Bash 기준이고, Windows 로컬 개발에서 Gradle은 `gradlew.bat`를 사용합니다.
- 비밀값(비밀번호, API 키, 토큰, 암호화 키, SSH 키)은 이 폴더와 저장소에 넣지 않았습니다. 어디에 넣는지만 적었습니다.
- 더 자세한 운영 문서: [README.md](../README.md), [infra/README.md](../infra/README.md), [infra/cdc/README.md](../infra/cdc/README.md), [infra/source-db/README.md](../infra/source-db/README.md), [docs/development-guide.md](../docs/development-guide.md)

검증 범위(2026-11-18): Dockerfile·Compose·환경 변수 예시·애플리케이션 설정·프론트 lock 파일과 대조했습니다. 문서 내 상대 링크·절 링크, Bash 예제 구문, 환경 변수 목록을 검사하고 가짜 비밀값으로 Compose 모델 3개를 해석했습니다. 수동 backend·batch 배포 예제는 가짜 Docker로 정상 배포·재생성 실패·상태 검사 실패·기존 이미지 불일치·후속 롤백과, 새 Bash 세션에서 롤백 태그 미입력·이미지 누락·백업 실패 시 중단까지 8개 경로를 확인했습니다. 덤프 3개는 네트워크와 영속 볼륨이 없는 로컬 PostgreSQL 18.6에 복원해 플랫폼 54표·V29, ERP 18표·2,356행, MES 15표·2,875행을 확인했습니다. 실제 비밀값을 사용하는 신규 서버 전체 설치, 외부 서비스 가입·인증서 발급, AI 분석과 화면 시연은 이번 문서 검증에서 실행하지 않았습니다.
