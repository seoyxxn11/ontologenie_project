import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parents[1] / ".env"

# provider: (기본 주소, 기본 모델, 키 필수). 회사 LLM으로 바꿀 때는 .env의 LLM 항목만 바꾼다.
LLM_DEFAULTS: dict[str, tuple[str | None, str | None, bool]] = {
    "gms": ("https://gms.ssafy.io/gmsapi/api.openai.com/v1", "gpt-6-luna", True),
    "openai": ("https://api.openai.com/v1", None, True),
    # 사내 LLM 서버는 키 없이 내부망으로만 여는 경우가 있어 키를 강제하지 않는다
    "openai-chat": (None, None, False),
}


@dataclass(frozen=True)
class Settings:
    service_name: str = "ai-ontology"
    version: str = "0.1.0"
    # 기본은 실제 GMS다. mock(가짜 응답)은 자동 테스트에서만, none은 LLM 없이 코드 분석만 할 때 쓴다.
    llm_provider: str = "gms"
    llm_api_key: str | None = field(default=None, repr=False)
    base_url: str | None = None  # 비우면 provider 기본 주소
    model: str | None = None  # 비우면 provider 기본 모델
    llm_timeout_sec: float = 60.0
    # 결과를 보낼 백엔드 주소와 내부 API 공유 비밀(X-Internal-Token)
    backend_url: str = "http://localhost:8080"
    internal_token: str | None = field(default=None, repr=False)

    @property
    def llm_base_url(self) -> str | None:
        return self.base_url or LLM_DEFAULTS.get(self.llm_provider, (None,))[0]

    @property
    def llm_model(self) -> str | None:
        return self.model or LLM_DEFAULTS.get(self.llm_provider, (None, None))[1]

    @property
    def llm_missing(self) -> str | None:
        """LLM을 부르는 데 빠진 설정. 없으면 None."""
        if self.llm_provider == "mock":
            return None
        if self.llm_provider not in LLM_DEFAULTS:
            return "AI_ONTOLOGY_LLM_PROVIDER"
        if LLM_DEFAULTS[self.llm_provider][2] and not self.llm_api_key:
            return "AI_ONTOLOGY_LLM_API_KEY"
        if not self.llm_base_url:
            return "AI_ONTOLOGY_LLM_BASE_URL"
        if not self.llm_model:
            return "AI_ONTOLOGY_LLM_MODEL"
        return None

    @property
    def llm_ready(self) -> bool:
        return self.llm_missing is None


def load_env_file(path: Path = ENV_FILE) -> None:
    """`.env`의 KEY=VALUE 줄로 아직 없는 환경변수만 채운다. 서버에서 실제로 넣은 환경변수가 우선이다."""
    if os.getenv("AI_ONTOLOGY_SKIP_ENV_FILE") == "1" or not path.exists():
        return
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _env(name: str) -> str | None:
    """AI_ONTOLOGY_LLM_* 값. 예전 이름(AI_ONTOLOGY_GMS_*)으로 넣은 .env도 그대로 읽는다."""
    return (
        os.getenv(f"AI_ONTOLOGY_LLM_{name}")
        or os.getenv(f"AI_ONTOLOGY_GMS_{name}")
        or None
    )


@lru_cache
def get_settings() -> Settings:
    load_env_file()
    return Settings(
        llm_provider=os.getenv("AI_ONTOLOGY_LLM_PROVIDER") or "gms",
        llm_api_key=_env("API_KEY"),
        base_url=_env("BASE_URL"),
        model=_env("MODEL"),
        llm_timeout_sec=float(os.getenv("AI_ONTOLOGY_LLM_TIMEOUT_SEC") or 60),
        backend_url=os.getenv("AI_ONTOLOGY_BACKEND_URL") or "http://localhost:8080",
        internal_token=os.getenv("AI_ONTOLOGY_INTERNAL_TOKEN") or None,
    )
