import uuid

from fastapi import Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    """팀 공통 오류 형식(개발 가이드 8.3)으로 돌려줄 오류."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def error_response(status: int, code: str, message: str, details: dict) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "error": {
                "code": code,
                "message": message,
                "details": details,
                "requestId": str(uuid.uuid4()),
            }
        },
    )


async def api_error(request: Request, error: ApiError) -> JSONResponse:
    return error_response(error.status, error.code, error.message, {})
