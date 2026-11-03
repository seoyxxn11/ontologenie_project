"""분석 실행 하나를 백그라운드에서 끝까지 돌린다(API 018 하단 시작 알림 → 105).

순서: 105 RUNNING → 원천 접속·고른 표 읽기 확인 → 엔진 → 105 SUCCEEDED 또는 FAILED.
실행 상태는 백엔드 analysis_run이 가진다. 여기서는 같은 실행이 겹쳐 돌지 않게 진행 중인 runId만 메모리에 둔다.
접속 정보와 요청 본문은 로그에 남기지 않는다. 실패 사유 문구는 docs/api-internal.md 4절을 따른다.
"""

import logging
import threading
import time
from collections.abc import Callable
from contextlib import ExitStack
from pathlib import Path
from typing import Any

from app.analysis import engine
from app.analysis.backend import BackendError, Report
from app.analysis.engine import AnalysisFailure, AnalysisResult, Sources
from app.models.analysis import AnalysisSource, AnalysisStartRequest
from app.sources.postgres_reader import (
    PostgresSourceReader,
    SourceError,
    psycopg_connector,
)

log = logging.getLogger(__name__)

UNEXPECTED = "분석 중 오류가 났습니다"

Connector = Callable[[dict], Callable[[], Any]]
Analyze = Callable[[AnalysisStartRequest, Sources], AnalysisResult]


class AnalysisRunner:
    def __init__(
        self,
        report: Report,
        connector: Connector = psycopg_connector,
        analyze: Analyze = engine.analyze,
    ):
        self._report = report
        self._connector = connector
        self._analyze = analyze
        self._active: set[int] = set()
        self._lock = threading.Lock()

    def claim(self, run_id: int) -> bool:
        """처음 받은 실행이면 True. 같은 실행이 아직 돌고 있으면 False(accepted=false)."""
        with self._lock:
            if run_id in self._active:
                return False
            self._active.add(run_id)
            return True

    def run(self, request: AnalysisStartRequest) -> None:
        run_id = request.run_id
        started = time.monotonic()
        try:
            try:
                self._report(run_id, {"status": "RUNNING"})
            except BackendError as error:
                # 백엔드가 이미 끝낸 실행(409)이거나 닿지 않으면 분석하지 않는다
                log.warning(
                    "분석 실행 %s RUNNING 알림 실패(%s), 분석하지 않음", run_id, error
                )
                return
            body = self._analyze_to_body(request)
            try:
                self._report(run_id, body)
            except BackendError as error:
                log.warning("분석 실행 %s 결과 알림 실패(%s)", run_id, error)
                return
            log.info(
                "분석 실행 %s %s (%.1fs)",
                run_id,
                body["status"],
                time.monotonic() - started,
            )
        finally:
            with self._lock:
                self._active.discard(run_id)

    def _analyze_to_body(self, request: AnalysisStartRequest) -> dict:
        try:
            with ExitStack() as stack:
                sources = {
                    source.data_source_code: self._open(request, source, stack)
                    for source in request.sources
                }
                result = self._analyze(request, sources)
            return {
                "status": "SUCCEEDED",
                "resultSummary": result.summary,
                "proposals": result.proposals,
            }
        except AnalysisFailure as failure:
            return {"status": "FAILED", "errorMessage": str(failure)}
        except Exception as error:  # noqa: BLE001 - 원인은 종류만 남겨 접속 정보가 로그에 새지 않게 한다
            log.error(
                "분석 실행 %s 처리 중 예상하지 못한 오류: %s",
                request.run_id,
                type(error).__name__,
            )
            return {"status": "FAILED", "errorMessage": UNEXPECTED}

    def _open(
        self, request: AnalysisStartRequest, source: AnalysisSource, stack: ExitStack
    ) -> PostgresSourceReader | Path:
        code = source.data_source_code
        targets = request.targets_of(code)
        if source.source_kind == "CSV":
            folder = Path(source.path) if source.path else None
            if folder is None or not folder.is_dir():
                raise AnalysisFailure(f"CSV 원천 폴더를 읽을 수 없습니다 ({code})")
            for target in targets:
                if not (folder / f"{target.table}.csv").is_file():
                    raise AnalysisFailure(
                        f"CSV 원천에서 이 테이블을 찾을 수 없습니다: {target.resource_key}"
                    )
            return folder
        try:
            reader = stack.enter_context(
                PostgresSourceReader(
                    code,
                    self._connector(source.connection),
                    schema=source.db_schema or "public",
                )
            )
            readable = set(reader.list_tables())
        except SourceError as error:
            raise AnalysisFailure(f"{error} ({code})") from None
        for target in targets:
            if target.table not in readable:
                raise AnalysisFailure(
                    f"AI 계정이 이 테이블을 읽을 수 없습니다: {target.resource_key}"
                )
        return reader
