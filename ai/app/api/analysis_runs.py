"""분석 시작 알림 수신(API 018 하단, POST /internal/v1/analysis-runs).

백엔드가 018로 실행을 만든 직후 부른다. 바로 202 {runId, accepted}로 답하고 분석은 백그라운드에서 돌린다.
내부망에서만 받으며 X-Internal-Token이 AI_ONTOLOGY_INTERNAL_TOKEN과 같아야 한다. 설정값이 비어 있으면 모두 거절한다.
"""

import hmac
from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, Header

from app.analysis.backend import results_reporter
from app.analysis.runner import AnalysisRunner
from app.api.errors import ApiError
from app.config import get_settings
from app.models.analysis import AnalysisStartRequest

router = APIRouter()


def require_internal_token(
    x_internal_token: str | None = Header(default=None),
) -> None:
    expected = get_settings().internal_token
    if (
        not expected
        or not x_internal_token
        or not hmac.compare_digest(x_internal_token.encode(), expected.encode())
    ):
        raise ApiError(401, "UNAUTHENTICATED", "인증 정보가 없거나 유효하지 않습니다.")


@lru_cache
def get_runner() -> AnalysisRunner:
    return AnalysisRunner(results_reporter(get_settings()))


@router.post(
    "/internal/v1/analysis-runs",
    status_code=202,
    dependencies=[Depends(require_internal_token)],
)
def start_analysis(
    request: AnalysisStartRequest,
    background: BackgroundTasks,
    runner: Annotated[AnalysisRunner, Depends(get_runner)],
) -> dict:
    accepted = runner.claim(request.run_id)
    if accepted:
        background.add_task(runner.run, request)
    return {"runId": request.run_id, "accepted": accepted}
