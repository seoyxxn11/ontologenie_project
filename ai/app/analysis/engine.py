"""분석 엔진 자리. 구조·분포 분석과 후보 생성(#165·168·169·170)이 여기에 들어온다.

runner가 원천 접속과 고른 표 읽기 권한을 확인한 뒤 analyze()를 부른다. 결과는 105 SUCCEEDED 본문의
resultSummary·proposals가 된다. 실패 사유는 AnalysisFailure로 던지면 105 FAILED의 errorMessage로 간다.
"""

from dataclasses import dataclass, field
from pathlib import Path

from app.models.analysis import AnalysisStartRequest
from app.sources.postgres_reader import PostgresSourceReader

NOT_READY = "AI 분석 기능이 아직 준비되지 않았습니다"


class AnalysisFailure(Exception):
    """019에 그대로 보이는 실패 사유. 접속 정보를 넣지 않는다."""


@dataclass
class AnalysisResult:
    summary: dict = field(default_factory=dict)
    proposals: list[dict] = field(default_factory=list)


# 원천 코드 → 열린 PostgreSQL 읽기 창구 또는 CSV 폴더
Sources = dict[str, PostgresSourceReader | Path]


def analyze(request: AnalysisStartRequest, sources: Sources) -> AnalysisResult:
    raise AnalysisFailure(NOT_READY)
