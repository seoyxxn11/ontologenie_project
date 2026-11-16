# 포팅 매뉴얼 (exec)

OntoloGenie(에이엑스코어 온톨로지 플랫폼, SSAFY 15기 자율 프로젝트 C106)의 빌드·배포·시연에 필요한 자료입니다. GitLab 저장소를 클론한 뒤 이 폴더만 보고 같은 환경을 다시 만들 수 있게 정리했습니다.

| 번호 | 문서 | 내용 |
| --- | --- | --- |
| 1 | [1-build-deploy.md](1-build-deploy.md) | 사용 도구와 버전(JVM·웹서버·WAS 설정 값, IDE 포함), 빌드 방법, 환경 변수, 서버 배포 절차, 배포 특이사항, DB 접속 정보, 주요 계정과 설정 파일 목록 |
| 2 | [2-external-services.md](2-external-services.md) | 외부 서비스(LLM, 알림, 인증서, 도메인 등)의 용도와 설정 방법 |
| 3 | [3-db-dump/](3-db-dump/README.md) | DB 덤프 최신본(플랫폼 DB 스키마·초기 데이터, 원천 ERP·MES 합성 데이터)과 복구 방법 |
| 4 | [4-demo-scenario.md](4-demo-scenario.md) | 시연 순서에 따른 화면별·동작별 시나리오(메뉴·버튼 위치와 클릭 순서 포함) |

- 기준 커밋: `develop` 2026-11-16
- 비밀값(비밀번호, API 키, 토큰, 암호화 키, SSH 키)은 이 폴더와 저장소에 넣지 않았습니다. 어디에 넣는지만 적었습니다.
- 더 자세한 운영 문서: [README.md](../README.md), [infra/README.md](../infra/README.md), [infra/cdc/README.md](../infra/cdc/README.md), [infra/source-db/README.md](../infra/source-db/README.md), [docs/development-guide.md](../docs/development-guide.md)
