import logging
import uuid

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.health import router as health_router
from app.config import get_settings


async def validation_error(
    request: Request, error: RequestValidationError
) -> JSONResponse:
    """요청 형식 오류도 팀 공통 오류 형식으로 돌려준다 (개발 가이드 8.3)."""
    fields = [".".join(str(p) for p in e["loc"][1:]) for e in error.errors()][:5]
    return JSONResponse(
        status_code=400,
        content={
            "error": {
                "code": "VALIDATION_FAILED",
                "message": "요청 필드를 확인해 주세요.",
                "details": {"fields": fields},
                "requestId": str(uuid.uuid4()),
            }
        },
    )


def create_app() -> FastAPI:
    settings = get_settings()
    # app.* 로거의 실행 기록(runId·상태·소요 시간·오류 코드)이 uvicorn 로그에 함께 나오게 한다
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    app = FastAPI(title="AI Ontology Proposer", version=settings.version)
    app.include_router(health_router)
    app.add_exception_handler(RequestValidationError, validation_error)
    return app


app = create_app()
