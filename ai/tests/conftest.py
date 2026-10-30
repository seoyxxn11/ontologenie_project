import os
from pathlib import Path

import pytest

# 테스트는 개인 .env를 읽지 않고 실제 LLM도 호출하지 않는다.
os.environ["AI_ONTOLOGY_SKIP_ENV_FILE"] = "1"
os.environ["AI_ONTOLOGY_LLM_PROVIDER"] = "mock"
for name in ("API_KEY", "BASE_URL", "MODEL"):
    os.environ.pop(f"AI_ONTOLOGY_LLM_{name}", None)
    os.environ.pop(f"AI_ONTOLOGY_GMS_{name}", None)

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def dev_factory() -> Path:
    return FIXTURES / "dev_factory"
