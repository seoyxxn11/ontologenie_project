"""백엔드 105(POST /api/v1/internal/analyses/{id}/results)로 실행 상태와 결과를 보낸다."""

import json
import urllib.error
import urllib.request
from collections.abc import Callable

from app.config import Settings

TIMEOUT_SEC = 30

Report = Callable[[int, dict], None]


class BackendError(Exception):
    """105 호출 실패. 원인은 HTTP 상태나 오류 종류만 담는다."""


def results_reporter(settings: Settings) -> Report:
    base = settings.backend_url.rstrip("/")
    token = settings.internal_token or ""

    def report(run_id: int, body: dict) -> None:
        request = urllib.request.Request(
            f"{base}/api/v1/internal/analyses/{run_id}/results",
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": token},
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as response:
                response.read()
        except urllib.error.HTTPError as error:
            raise BackendError(f"HTTP {error.code}") from None
        except OSError as error:  # 접속 실패·시간 초과 (URLError도 OSError)
            raise BackendError(type(error).__name__) from None

    return report
