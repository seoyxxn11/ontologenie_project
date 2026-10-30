from fastapi import APIRouter

from app.config import get_settings

router = APIRouter()


@router.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "service": settings.service_name,
        "version": settings.version,
        "llm_provider": settings.llm_provider,
        "llm_ready": settings.llm_ready,  # 키 값은 내보내지 않고 설정 여부만 알려 준다.
        "llm_missing": settings.llm_missing,  # 빠진 환경변수 이름. 없으면 코드 분석만 한다
    }
