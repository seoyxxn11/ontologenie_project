import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.analysis_runs import router as analysis_runs_router
from app.api.errors import ApiError, api_error, error_response
from app.api.health import router as health_router
from app.config import get_settings


async def validation_error(
    request: Request, error: RequestValidationError
) -> JSONResponse:
    """요청 형식 오류도 팀 공통 오류 형식으로 돌려준다 (개발 가이드 8.3). 입력값은 되돌려주지 않는다."""
    fields = [".".join(str(p) for p in e["loc"][1:]) for e in error.errors()][:5]
    return error_response(
        400, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", {"fields": fields}
    )


def create_app() -> FastAPI:
    settings = get_settings()
    # app.* 로거의 실행 기록(runId·상태·소요 시간·오류 코드)이 uvicorn 로그에 함께 나오게 한다
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    app = FastAPI(title="AI Ontology Proposer", version=settings.version)
    if not settings.internal_token:
        logging.getLogger(__name__).warning(
            "AI_ONTOLOGY_INTERNAL_TOKEN이 비어 있어 분석 시작 요청을 모두 거절합니다."
        )
    app.include_router(health_router)
    app.include_router(analysis_runs_router)
    app.add_exception_handler(RequestValidationError, validation_error)
    app.add_exception_handler(ApiError, api_error)
    return app


app = create_app()
