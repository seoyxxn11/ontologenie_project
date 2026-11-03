# AI Ontology 데이터셋·정답셋

00프레스(가상) ERP·MES 원천 데이터와 정답셋, 그리고 이를 만드는 생성기와 점검 도구입니다.

- 설명: [데이터셋·정답셋](docs/dataset.md) · [분석 연동 초안](docs/api-internal.md)
- 담당: 김서연 (DAT-01~05)

## 폴더 구조

```text
ai/
├── data/
│   ├── data-v1.0/       원천 데이터 (ERP 18·MES 15개 표, 표 정의, 적재 스크립트)
│   ├── gold-v1.0/       정답셋 (원천 DB·AI 입력에 넣지 않음)
│   └── scenario-v1.0/   시나리오 5 시연용 원천 수정분
├── results/
│   └── proposals-v1.0.api.json   분석 결과 후보 샘플 (API 105 형식)
├── app/
│   ├── dataset/         데이터·정답 생성기
│   ├── validators/      정답 점검 (gold_check, gold_source_check)
│   └── rules/           연결 규칙 변환 단계 (transforms.py)
└── docs/
```

## 처음 설정 (Windows PowerShell)

Python 3.11에서 확인했습니다.

```powershell
cd C:\S15P31C106\ai
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## FastAPI Docker 이미지 (5-1b)

저장소 루트에서 실행합니다. Python 3.11 slim 이미지에 운영 의존성과 `main.py` · `app/`만 복사하고 UID 10001인 `app` 사용자로 실행합니다. 소스 복사 전에 의존성을 설치해 해당 레이어를 재사용합니다. 데이터셋·정답셋·결과 샘플·테스트·가상환경은 이미지에 포함하지 않습니다.

```powershell
docker build -t axcore-ai:test ai/
docker run --rm --name axcore-ai-local -p 127.0.0.1:18000:8000 -e AI_ONTOLOGY_BACKEND_URL=http://backend:8080 axcore-ai:test
```

다른 터미널에서 확인합니다. Docker 상태는 첫 헬스체크가 실행된 뒤 `healthy`로 바뀝니다.

```powershell
curl.exe -fsS http://127.0.0.1:18000/health
docker inspect --format '{{.State.Health.Status}}' axcore-ai-local
```

- 기본 GMS 설정에서 키 없이도 기동합니다. `/health`는 `status: "ok"`, `llm_ready: false`, `llm_missing: "AI_ONTOLOGY_LLM_API_KEY"`를 반환합니다.
- 헬스체크는 프로세스의 HTTP 응답을 확인합니다. LLM 호출·backend 연동 성공을 의미하지 않습니다.
- `.env`는 빌드 컨텍스트에서 제외하고 `AI_ONTOLOGY_SKIP_ENV_FILE=1`로 파일 로딩을 끕니다. 필요한 값은 실행 환경변수로 주입하며 키·토큰을 Dockerfile에 넣지 않습니다.
- 위 단독 실행에는 backend가 없습니다. `http://backend:8080`은 이후 같은 Docker 네트워크에서 사용할 compose 서비스 주소이며 실제 통신은 compose 단계에서 검증합니다.
- 현재 등록된 API는 `/health`와 분석 시작 알림 수신(`POST /internal/v1/analysis-runs`, [분석 연동](docs/api-internal.md) 3절)입니다. 원천 확인 뒤 분석 엔진이 후보를 만들어 105로 보냅니다. compose와 Jenkins 배포 절차는 아래 서버 자동 배포 안내를 참고합니다.
- 검증 후 실행한 터미널에서 Ctrl+C로 종료합니다. 실제 배포 이미지 태그는 `test` 대신 커밋 SHA를 사용합니다.

## 서버 자동 배포

develop에 머지되면 Jenkins `axcore-deploy`가 서버의 `AI_TAG`와 현재 develop 사이의 `ai/` 변경을 비교합니다. README를 포함해 해당 폴더에 변경이 있으면 AI 이미지 빌드·배포를 실행합니다.

- 이미지: `axcore-ai:<커밋 SHA>`
- 처리: 서버 `AI_TAG` 갱신 → compose 기동 → 최대 90초 healthy 대기
- 실패 시 이전 태그가 있으면 롤백 시도
- backend·frontend도 각각 변경이 있을 때만 배포 (`FORCE_DEPLOY`는 세 서비스 모두 배포)
- AI에는 backend에 대한 `depends_on`이 없어 단독 기동할 수 있습니다. backend 주소와 내부 네트워크 설정은 유지하며, 실제 backend 호출 시에는 해당 서비스가 응답할 수 있어야 합니다.
- AI 이미지는 최근 3개 태그 보관 대상
- 결과는 GitLab 커밋 상태와 Mattermost DM으로 확인

헬스체크 성공은 실제 LLM 호출·분석 연동 성공을 의미하지 않습니다. 롤백 후 정상 복구 여부도 별도 확인이 필요합니다.

상세 절차: [인프라 자동 배포 안내](../infra/README.md#자동-배포-develop-머지--jenkins-axcore-deploy)

## 데이터 다시 만들기

```powershell
.\.venv\Scripts\python.exe -m app.dataset.build --version v1.0 --force
```

시드가 고정되어 있어 같은 명령이면 같은 파일이 나옵니다(`CHECKSUMS.sha256` 동일). 값을 바꿔야 할 때는 CSV를 직접 고치지 않고 생성기를 고쳐 다시 만듭니다. 표와 컬럼(이름, 자료형)은 바꾸지 않습니다.

## 정답 점검

```powershell
.\.venv\Scripts\python.exe -m app.validators.gold_check data/gold-v1.0
.\.venv\Scripts\python.exe -m app.validators.gold_source_check data/data-v1.0 data/gold-v1.0
```

- `gold_check`: 정답 파일 형식 점검
- `gold_source_check`: 정답 관계·규칙을 원천 값에 적용해 보는 점검. 오류 0, 설명 안 된 미연결 0이 나와야 합니다.
