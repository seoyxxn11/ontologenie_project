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
